package de.florianheger.elemntarysync.uploader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GarminAuthClientTest {

    private static final String EMBED = "/sso/embed";
    private static final String SIGNIN = "/sso/signin";
    private static final String TOKEN = "/di-oauth2-service/oauth/token";
    private static final String FORM = "<html><title>GARMIN Authentication Application</title>"
            + "<input type=\"hidden\" name=\"_csrf\" value=\"CSRF1\"/></html>";
    private static final String SUCCESS = "<html><title>Success</title>"
            + "<script>var url = \"https://sso.garmin.com/sso/embed?ticket=ST-123-abc-sso\";</script></html>";

    private FakeGarminServer garmin;
    private GarminAuthClient client;

    @BeforeEach
    void setUp() throws IOException {
        garmin = new FakeGarminServer();
        client = new GarminAuthClient(garmin.endpoints(), Duration.ZERO, Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        garmin.close();
    }

    private static String tokens(String accessToken) {
        return "{\"access_token\":\"" + accessToken + "\",\"refresh_token\":\"refresh1\",\"expires_in\":86400}";
    }

    @Test
    void logsInThroughWidgetAndExchangesTicket() throws IOException {
        garmin.enqueue(EMBED, 200, "embed", Map.of("Set-Cookie", "GARMIN-SSO=abc; Path=/"))
                .enqueue(SIGNIN, 200, FORM)
                .enqueue(SIGNIN, 200, SUCCESS)
                .enqueue(TOKEN, 400, "{\"error\":\"unauthorized_client\"}")
                .enqueue(TOKEN, 200, tokens("access1"));

        GarminTokens tokens = client.login("me@example.com", "secret", () -> {
            throw new AssertionError("no MFA expected");
        });

        assertEquals("access1", tokens.accessToken());
        assertEquals("refresh1", tokens.refreshToken());
        assertEquals("GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4", tokens.clientId());

        List<FakeGarminServer.Request> signin = garmin.requests(SIGNIN);
        assertEquals("POST", signin.get(1).method());
        assertTrue(signin.get(1).bodyText().contains("username=me%40example.com"), signin.get(1).bodyText());
        assertTrue(signin.get(1).bodyText().contains("_csrf=CSRF1"), signin.get(1).bodyText());
        assertTrue(signin.get(1).header("Cookie").contains("GARMIN-SSO=abc"));

        FakeGarminServer.Request exchange = garmin.requests(TOKEN).get(1);
        assertTrue(exchange.bodyText().contains("service_ticket=ST-123-abc-sso"), exchange.bodyText());
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4:".getBytes(StandardCharsets.UTF_8)), exchange.header("Authorization"));
    }

    @Test
    void usesClientIdFromAccessToken() throws IOException {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"client_id\":\"FROM_JWT\"}".getBytes(StandardCharsets.UTF_8));
        garmin.enqueue(TOKEN, 200, tokens("header." + payload + ".signature"));

        assertEquals("FROM_JWT", client.exchangeTicket("ST-1").clientId());
    }

    @Test
    void lockedAccountIsAnAuthError() {
        garmin.enqueue(EMBED, 200, "embed").enqueue(SIGNIN, 200, FORM)
                .enqueue(SIGNIN, 200, "<title>Account Locked</title>");

        assertThrows(GarminAuthException.class, () -> client.login("me@example.com", "wrong", () -> ""));
    }

    @Test
    void unexpectedPageNamesTheTitle() {
        garmin.enqueue(EMBED, 200, "embed").enqueue(SIGNIN, 200, FORM)
                .enqueue(SIGNIN, 200, "<title>Just a moment...</title>");

        IOException e = assertThrows(IOException.class, () -> client.login("me@example.com", "secret", () -> ""));
        assertTrue(e.getMessage().contains("Just a moment..."), e.getMessage());
    }

    @Test
    void completesEmailMfa() throws IOException {
        String mfaPage = "<title>GARMIN Authentication Application</title>"
                + "<script>var mfaMethod = \"email\"; var customerGuid = \"guid1\"; var locale = \"de_DE\";"
                + " var clientId = \"GarminConnect\"; var codeSentTo = \"\";</script>"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"CSRF2\"/>";
        garmin.enqueue(EMBED, 200, "embed").enqueue(SIGNIN, 200, FORM).enqueue(SIGNIN, 200, mfaPage)
                .enqueue("/sso/verifyMFA/mfaCode", 200, "{}")
                .enqueue("/sso/verifyMFA/loginEnterMfaCode", 200, SUCCESS)
                .enqueue(TOKEN, 200, tokens("access1"));
        AtomicInteger prompts = new AtomicInteger();

        GarminTokens tokens = client.login("me@example.com", "secret", () -> {
            prompts.incrementAndGet();
            return "123456";
        });

        assertEquals("access1", tokens.accessToken());
        assertEquals(1, prompts.get());
        assertTrue(garmin.requests("/sso/verifyMFA/mfaCode").getFirst().bodyText().contains("\"customerGuid\":\"guid1\""));
        String codeBody = garmin.requests("/sso/verifyMFA/loginEnterMfaCode").getFirst().bodyText();
        assertTrue(codeBody.contains("mfa-code=123456") && codeBody.contains("_csrf=CSRF2"), codeBody);
    }

    @Test
    void refreshKeepsOldRefreshTokenIfNoneReturned() throws IOException {
        garmin.enqueue(TOKEN, 200, "{\"access_token\":\"access2\",\"expires_in\":86400}");

        GarminTokens refreshed = client.refresh(new GarminTokens("access1", "refresh1", "CLIENT", java.time.Instant.now()));

        assertEquals("access2", refreshed.accessToken());
        assertEquals("refresh1", refreshed.refreshToken());
        assertEquals("CLIENT", refreshed.clientId());
    }
}
