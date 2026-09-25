package de.florianheger.elemntarysync.uploader;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Logs in to Garmin Connect through the SSO embed widget and manages the resulting DI OAuth2 tokens.
 *
 * <p>Garmin has no official API for this. The flow follows python-garminconnect
 * (github.com/cyberjunky/python-garminconnect, {@code _widget_web_login} and
 * {@code _exchange_service_ticket}); check it first if Garmin changes its login again.
 */
public class GarminAuthClient {

    static final String NATIVE_USER_AGENT = "GCM-Android-5.23";
    static final String NATIVE_X_GARMIN_USER_AGENT =
            "com.garmin.android.apps.connectmobile/5.23; ; Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0";
    static final String RELOGIN_HINT = "Run: docker compose run --rm elemntary-sync auth-garmin";

    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String SERVICE_TICKET_GRANT =
            "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket";
    private static final List<String> CLIENT_IDS = List.of(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_IOS_DI");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"(.+?)\"");
    private static final Pattern TITLE = Pattern.compile("<title>(.+?)</title>");
    private static final Pattern TICKET = Pattern.compile("\\?ticket=(ST-[^\"&\\s]+)");
    private static final Pattern MFA_VARS =
            Pattern.compile("var\\s+(customerGuid|mfaMethod|locale|clientId|codeSentTo)\\s*=\\s*\"([^\"]*)\"\\s*;");

    private final GarminEndpoints endpoints;
    private final Duration minLoginDelay;
    private final Duration maxLoginDelay;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper json = new ObjectMapper();

    public GarminAuthClient(GarminEndpoints endpoints) {
        this(endpoints, Duration.ofSeconds(3), Duration.ofSeconds(8));
    }

    /** The delay between loading the sign-in form and posting it avoids Cloudflare's bot detection. */
    GarminAuthClient(GarminEndpoints endpoints, Duration minLoginDelay, Duration maxLoginDelay) {
        this.endpoints = endpoints;
        this.minLoginDelay = minLoginDelay;
        this.maxLoginDelay = maxLoginDelay;
    }

    /** Logs in with email and password. {@code mfaCode} is only asked if Garmin requires a second factor. */
    public GarminTokens login(String email, String password, Supplier<String> mfaCode) throws IOException {
        HttpClient browser = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();
        String sso = endpoints.sso() + "/sso";
        String embed = sso + "/embed";
        Map<String, String> embedParams = ordered("id", "gauth-widget", "embedWidget", "true", "gauthHost", sso);
        Map<String, String> signinParams = ordered("id", "gauth-widget", "embedWidget", "true", "gauthHost", embed,
                "service", embed, "source", embed,
                "redirectAfterAccountLoginUrl", embed, "redirectAfterAccountCreationUrl", embed);
        String signinUrl = sso + "/signin?" + encode(signinParams);

        HttpResponse<String> response = send(browser, browserRequest(embed + "?" + encode(embedParams)).GET());
        requireOk(response, "Loading the Garmin login page");

        response = send(browser, browserRequest(signinUrl).header("Referer", embed).GET());
        requireOk(response, "Loading the Garmin sign-in form");
        String csrf = find(CSRF, response.body(), "Garmin sign-in form has no CSRF token");

        pause();
        response = send(browser, browserRequest(signinUrl)
                .header("Referer", response.uri().toString())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(form(ordered("username", email, "password", password, "embed", "true", "_csrf", csrf))));
        requireOk(response, "Signing in to Garmin");

        String title = title(response.body());
        String lower = title.toLowerCase(Locale.ROOT);
        if (List.of("locked", "invalid", "incorrect", "account error").stream().anyMatch(lower::contains)) {
            throw new GarminAuthException("Garmin rejected the login: " + title);
        }
        Map<String, String> mfaVars = mfaVars(response.body());
        if (lower.contains("mfa") || (lower.contains("authentication application") && mfaVars.containsKey("mfaMethod"))) {
            response = completeMfa(browser, response, mfaVars, sso, signinParams, mfaCode);
            title = title(response.body());
        }
        if (!title.equals("Success")) {
            throw new IOException("Unexpected Garmin login page '" + title + "' (wrong password, or Garmin blocked the login)");
        }
        return exchangeTicket(find(TICKET, response.body(), "Garmin login succeeded but returned no service ticket"));
    }

    private HttpResponse<String> completeMfa(HttpClient browser, HttpResponse<String> mfaPage,
            Map<String, String> mfaVars, String sso, Map<String, String> signinParams, Supplier<String> mfaCode)
            throws IOException {
        String method = mfaVars.getOrDefault("mfaMethod", "").toLowerCase(Locale.ROOT);
        if ((method.equals("email") || method.equals("sms")) && mfaVars.getOrDefault("codeSentTo", "").isEmpty()) {
            // The widget doesn't always send the code on its own; request it like the "new code" link does.
            String payload = json.writeValueAsString(Map.of(
                    "customerGuid", mfaVars.getOrDefault("customerGuid", ""),
                    "mfaMethod", mfaVars.getOrDefault("mfaMethod", ""),
                    "locale", mfaVars.getOrDefault("locale", "")));
            HttpResponse<String> sent = send(browser, browserRequest(
                    sso + "/verifyMFA/mfaCode?" + encode(ordered("clientId", mfaVars.getOrDefault("clientId", ""))))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", mfaPage.uri().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(payload)));
            requireOk(sent, "Requesting the Garmin MFA code");
        }

        String csrf = find(CSRF, mfaPage.body(), "Garmin MFA page has no CSRF token");
        String code = mfaCode.get();
        if (code == null || code.isBlank()) {
            throw new GarminAuthException("No MFA code entered");
        }
        HttpResponse<String> response = send(browser, browserRequest(
                sso + "/verifyMFA/loginEnterMfaCode?" + encode(signinParams))
                .header("Referer", mfaPage.uri().toString())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(form(ordered("mfa-code", code.trim(), "embed", "true", "_csrf", csrf,
                        "fromPage", "setupEnterMfaCode"))));
        requireOk(response, "Sending the Garmin MFA code");
        if (!title(response.body()).equals("Success")) {
            throw new GarminAuthException("Garmin rejected the MFA code: " + title(response.body()));
        }
        return response;
    }

    GarminTokens exchangeTicket(String ticket) throws IOException {
        String lastError = "";
        for (String clientId : CLIENT_IDS) {
            HttpResponse<String> response = send(http, tokenRequest(clientId, ordered(
                    "client_id", clientId,
                    "service_ticket", ticket,
                    "grant_type", SERVICE_TICKET_GRANT,
                    "service_url", endpoints.sso() + "/sso/embed")));
            if (response.statusCode() == 429) {
                throw new IOException("Garmin token exchange is rate limited (HTTP 429), try again later");
            }
            if (isOk(response)) {
                return parseTokens(response.body(), clientId, null);
            }
            lastError = clientId + ": HTTP " + response.statusCode();
        }
        throw new GarminAuthException("Garmin token exchange failed for all client IDs (last " + lastError + ")");
    }

    /** Gets a new access token. Throws {@link GarminAuthException} if Garmin no longer accepts the refresh token. */
    public GarminTokens refresh(GarminTokens tokens) throws IOException {
        HttpResponse<String> response = send(http, tokenRequest(tokens.clientId(), ordered(
                "grant_type", "refresh_token",
                "client_id", tokens.clientId(),
                "refresh_token", tokens.refreshToken())));
        if (response.statusCode() == 400 || response.statusCode() == 401) {
            throw new GarminAuthException("Garmin login expired or was revoked (HTTP " + response.statusCode() + "). "
                    + RELOGIN_HINT);
        }
        if (!isOk(response)) {
            throw new IOException("Refreshing the Garmin token failed: HTTP " + response.statusCode());
        }
        return parseTokens(response.body(), tokens.clientId(), tokens.refreshToken());
    }

    private HttpRequest.Builder tokenRequest(String clientId, Map<String, String> fields) {
        String basic = Base64.getEncoder().encodeToString((clientId + ":").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(endpoints.diauth() + "/di-oauth2-service/oauth/token"))
                .timeout(TIMEOUT)
                .header("User-Agent", NATIVE_USER_AGENT)
                .header("X-Garmin-User-Agent", NATIVE_X_GARMIN_USER_AGENT)
                .header("Authorization", "Basic " + basic)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cache-Control", "no-cache")
                .POST(form(fields));
    }

    private GarminTokens parseTokens(String body, String fallbackClientId, String fallbackRefreshToken)
            throws IOException {
        JsonNode node = json.readTree(body);
        String accessToken = node.path("access_token").asText("");
        if (accessToken.isEmpty()) {
            throw new IOException("Garmin token response contains no access token");
        }
        String refreshToken = node.path("refresh_token").asText(fallbackRefreshToken == null ? "" : fallbackRefreshToken);
        if (refreshToken.isEmpty()) {
            throw new IOException("Garmin token response contains no refresh token");
        }
        long expiresIn = node.path("expires_in").asLong(3600);
        String clientId = clientIdFromJwt(accessToken);
        return new GarminTokens(accessToken, refreshToken, clientId == null ? fallbackClientId : clientId,
                Instant.now().plusSeconds(expiresIn));
    }

    /** Garmin puts the client ID it actually used into the access token; refreshes must use that one. */
    private String clientIdFromJwt(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            String clientId = payload.path("client_id").asText("");
            return clientId.isEmpty() ? null : clientId;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static HttpRequest.Builder browserRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", BROWSER_USER_AGENT);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws IOException {
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while talking to Garmin");
        }
    }

    private static void requireOk(HttpResponse<String> response, String step) throws IOException {
        if (response.statusCode() == 429) {
            throw new IOException(step + " failed: Garmin rate limit (HTTP 429), try again later");
        }
        if (!isOk(response)) {
            throw new IOException(step + " failed: HTTP " + response.statusCode() + " " + title(response.body()));
        }
    }

    private static boolean isOk(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private void pause() throws IOException {
        long min = minLoginDelay.toMillis();
        long max = maxLoginDelay.toMillis();
        try {
            Thread.sleep(max > min ? ThreadLocalRandom.current().nextLong(min, max) : min);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted during Garmin login");
        }
    }

    private static String title(String html) {
        Matcher matcher = TITLE.matcher(html);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static Map<String, String> mfaVars(String html) {
        Map<String, String> vars = new HashMap<>();
        Matcher matcher = MFA_VARS.matcher(html);
        while (matcher.find()) {
            vars.put(matcher.group(1), matcher.group(2));
        }
        return vars;
    }

    private static String find(Pattern pattern, String text, String error) throws IOException {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new IOException(error);
        }
        return matcher.group(1);
    }

    private static Map<String, String> ordered(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static String encode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static HttpRequest.BodyPublisher form(Map<String, String> fields) {
        return HttpRequest.BodyPublishers.ofString(encode(fields));
    }
}
