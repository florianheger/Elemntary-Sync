package de.florianheger.elemntarysync.uploader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GarminConnectUploaderTest {

    private static final String UPLOAD = "/upload-service/upload/.fit";
    private static final String TOKEN = "/di-oauth2-service/oauth/token";
    private static final String NEW_TOKENS =
            "{\"access_token\":\"access2\",\"refresh_token\":\"refresh2\",\"expires_in\":86400}";

    @TempDir
    Path dir;

    private FakeGarminServer garmin;
    private GarminTokenStore store;
    private GarminConnectUploader uploader;
    private Path ride;

    @BeforeEach
    void setUp() throws IOException {
        garmin = new FakeGarminServer();
        store = new GarminTokenStore(dir.resolve("garmin-tokens.properties"));
        store.save(new GarminTokens("access1", "refresh1", "CLIENT", Instant.now().plus(Duration.ofHours(10))));
        uploader = new GarminConnectUploader(store, new GarminAuthClient(garmin.endpoints(), Duration.ZERO, Duration.ZERO),
                garmin.endpoints(), 5, Duration.ZERO);
        ride = dir.resolve("ride_garmin.fit");
        Files.write(ride, new byte[] {1, 2, 3, 4});
    }

    @AfterEach
    void tearDown() {
        garmin.close();
    }

    @Test
    void uploadsFileAsMultipart() throws IOException {
        garmin.enqueue(UPLOAD, 202, "{\"detailedImportResult\":{\"uploadId\":42,\"failures\":[]}}");

        uploader.uploadGarminFitFile(ride);

        FakeGarminServer.Request request = garmin.requests(UPLOAD).getFirst();
        assertEquals("Bearer access1", request.header("Authorization"));
        assertTrue(request.header("Content-Type").startsWith("multipart/form-data; boundary="));
        assertTrue(request.bodyText().contains("name=\"file\"; filename=\"ride_garmin.fit\""), request.bodyText());
        assertTrue(new String(request.body(), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(new String(new byte[] {1, 2, 3, 4}, java.nio.charset.StandardCharsets.ISO_8859_1)));
    }

    @Test
    void duplicateCountsAsSuccess() throws IOException {
        garmin.enqueue(UPLOAD, 409, "{}");

        uploader.uploadGarminFitFile(ride);

        assertEquals(1, garmin.requests(UPLOAD).size());
    }

    @Test
    void retriesTemporaryErrors() throws IOException {
        garmin.enqueue(UPLOAD, 500, "").enqueue(UPLOAD, 503, "").enqueue(UPLOAD, 202, "{}");

        uploader.uploadGarminFitFile(ride);

        assertEquals(3, garmin.requests(UPLOAD).size());
    }

    @Test
    void givesUpAfterFiveAttempts() {
        for (int i = 0; i < 6; i++) {
            garmin.enqueue(UPLOAD, 500, "");
        }

        assertThrows(IOException.class, () -> uploader.uploadGarminFitFile(ride));
        assertEquals(5, garmin.requests(UPLOAD).size());
    }

    @Test
    void rejectedFileFailsWithoutRetry() {
        garmin.enqueue(UPLOAD, 400, "{\"message\":\"bad file\"}");

        IOException e = assertThrows(IOException.class, () -> uploader.uploadGarminFitFile(ride));

        assertEquals(1, garmin.requests(UPLOAD).size());
        assertTrue(e.getMessage().contains("rejected"), e.getMessage());
    }

    @Test
    void importFailureInAcceptedResponseFails() {
        garmin.enqueue(UPLOAD, 202, "{\"detailedImportResult\":{\"failures\":[{\"messages\":[{\"content\":\"Invalid\"}]}]}}");

        assertThrows(IOException.class, () -> uploader.uploadGarminFitFile(ride));
        assertEquals(1, garmin.requests(UPLOAD).size());
    }

    @Test
    void unauthorizedTriggersOneRefresh() throws IOException {
        garmin.enqueue(UPLOAD, 401, "").enqueue(TOKEN, 200, NEW_TOKENS).enqueue(UPLOAD, 202, "{}");

        uploader.uploadGarminFitFile(ride);

        assertEquals("Bearer access2", garmin.requests(UPLOAD).get(1).header("Authorization"));
        assertEquals("refresh2", store.load().refreshToken());
    }

    @Test
    void expiringTokenIsRefreshedBeforeUpload() throws IOException {
        store.save(new GarminTokens("access1", "refresh1", "CLIENT", Instant.now().plus(Duration.ofMinutes(1))));
        garmin.enqueue(TOKEN, 200, NEW_TOKENS).enqueue(UPLOAD, 202, "{}");

        uploader.uploadGarminFitFile(ride);

        FakeGarminServer.Request refresh = garmin.requests(TOKEN).getFirst();
        assertTrue(refresh.bodyText().contains("grant_type=refresh_token"), refresh.bodyText());
        assertTrue(refresh.bodyText().contains("refresh_token=refresh1"), refresh.bodyText());
        assertEquals("Bearer access2", garmin.requests(UPLOAD).getFirst().header("Authorization"));
        assertEquals("access2", store.load().accessToken());
    }

    @Test
    void revokedLoginFailsWithoutRetry() {
        garmin.enqueue(UPLOAD, 401, "").enqueue(TOKEN, 401, "{\"error\":\"invalid_grant\"}");

        assertThrows(GarminAuthException.class, () -> uploader.uploadGarminFitFile(ride));
        assertEquals(1, garmin.requests(UPLOAD).size());
    }

    @Test
    void dailyRefreshReplacesTokens() throws IOException {
        garmin.enqueue(TOKEN, 200, NEW_TOKENS);

        uploader.refreshDaily();

        assertEquals("refresh2", store.load().refreshToken());
    }
}
