package de.florianheger.elemntarysync.uploader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Uploads .fit files to Garmin Connect with the tokens from {@code auth-garmin}.
 *
 * <p>Temporary failures (network, HTTP 429/5xx) are retried up to {@code attempts} times,
 * {@code retryDelay} apart. A rejected file or an expired login fails at once. HTTP 409 means the
 * activity is already on Garmin Connect and counts as success.
 */
public class GarminConnectUploader implements GarminUploader {

    private static final Logger log = LoggerFactory.getLogger(GarminConnectUploader.class);
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final GarminTokenStore tokenStore;
    private final GarminAuthClient authClient;
    private final GarminEndpoints endpoints;
    private final int attempts;
    private final Duration retryDelay;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final ObjectMapper json = new ObjectMapper();

    public GarminConnectUploader(GarminTokenStore tokenStore, GarminAuthClient authClient, GarminEndpoints endpoints) {
        this(tokenStore, authClient, endpoints, 5, Duration.ofSeconds(30));
    }

    GarminConnectUploader(GarminTokenStore tokenStore, GarminAuthClient authClient, GarminEndpoints endpoints,
            int attempts, Duration retryDelay) {
        this.tokenStore = tokenStore;
        this.authClient = authClient;
        this.endpoints = endpoints;
        this.attempts = attempts;
        this.retryDelay = retryDelay;
    }

    /** Synchronized with {@link #refreshDaily()}, because every refresh replaces the refresh token. */
    @Override
    public synchronized void uploadGarminFitFile(Path fitFile) throws IOException {
        String name = fitFile.getFileName().toString();
        byte[] content = Files.readAllBytes(fitFile);

        for (int attempt = 1; ; attempt++) {
            try {
                upload(name, content);
                return;
            } catch (GarminAuthException | RejectedException e) {
                throw e;
            } catch (IOException e) {
                if (attempt >= attempts) {
                    throw new IOException("Uploading " + name + " failed after " + attempts + " attempts: "
                            + e.getMessage(), e);
                }
                log.warn("Upload attempt {}/{} for {} failed: {}, retrying in {} s", attempt, attempts, name,
                        e.getMessage(), retryDelay.toSeconds());
                sleep();
            }
        }
    }

    /** Refreshes the tokens so the rotating refresh token stays valid even when no rides come in. */
    public synchronized void refreshDaily() {
        try {
            refreshAndSave(tokenStore.load());
        } catch (GarminAuthException e) {
            log.error("Garmin Connect login is no longer valid: {}", e.getMessage());
        } catch (IOException e) {
            log.warn("Refreshing the Garmin Connect login failed, trying again later: {}", e.getMessage());
        }
    }

    private void upload(String name, byte[] content) throws IOException {
        GarminTokens tokens = tokenStore.load();
        if (tokens.expiresWithin(REFRESH_MARGIN, Instant.now())) {
            tokens = refreshAndSave(tokens);
        }

        HttpResponse<String> response = post(name, content, tokens);
        if (response.statusCode() == 401) {
            response = post(name, content, refreshAndSave(tokens));
        }

        int status = response.statusCode();
        if (status == 409) {
            log.info("{} is already on Garmin Connect", name);
        } else if (status >= 200 && status < 300) {
            checkImportResult(name, response.body());
            log.info("Uploaded {} to Garmin Connect{}", name, uploadId(response.body()));
        } else if (status == 401) {
            throw new GarminAuthException("Garmin Connect rejected the login (HTTP 401). " + GarminAuthClient.RELOGIN_HINT);
        } else if (status == 429 || status >= 500) {
            throw new IOException("HTTP " + status);
        } else {
            throw new RejectedException("Garmin Connect rejected " + name + ": HTTP " + status + " " + abbreviate(response.body()));
        }
    }

    private HttpResponse<String> post(String name, byte[] content, GarminTokens tokens) throws IOException {
        String boundary = "----ElemntarySync" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream(content.length + 512);
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name.replace("\"", "") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.connectapi() + "/upload-service/upload/.fit"))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("User-Agent", GarminAuthClient.NATIVE_USER_AGENT)
                .header("X-Garmin-User-Agent", GarminAuthClient.NATIVE_X_GARMIN_USER_AGENT)
                .header("NK", "NT")
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while uploading " + name);
        }
    }

    private GarminTokens refreshAndSave(GarminTokens tokens) throws IOException {
        GarminTokens refreshed = authClient.refresh(tokens);
        tokenStore.save(refreshed);
        return refreshed;
    }

    /** Garmin can accept the upload but still report failures for the file in the response. */
    private void checkImportResult(String name, String body) throws RejectedException {
        JsonNode failures = parse(body).path("detailedImportResult").path("failures");
        if (failures.isArray() && !failures.isEmpty()) {
            throw new RejectedException("Garmin Connect could not import " + name + ": " + abbreviate(failures.toString()));
        }
    }

    private String uploadId(String body) {
        JsonNode id = parse(body).path("detailedImportResult").path("uploadId");
        return id.isMissingNode() || id.isNull() ? "" : " (upload ID " + id.asText() + ")";
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    private void sleep() throws InterruptedIOException {
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to retry the upload");
        }
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }

    /** Garmin refused the file itself. Retrying won't help. */
    private static class RejectedException extends IOException {
        RejectedException(String message) {
            super(message);
        }
    }
}
