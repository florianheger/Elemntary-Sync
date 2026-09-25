package de.florianheger.elemntarysync.uploader;

import java.time.Duration;
import java.time.Instant;

/** Garmin DI OAuth2 tokens. The refresh token changes on every refresh. Never log these values. */
public record GarminTokens(String accessToken, String refreshToken, String clientId, Instant accessExpiresAt) {

    public boolean expiresWithin(Duration duration, Instant now) {
        return !accessExpiresAt.isAfter(now.plus(duration));
    }

    @Override
    public String toString() {
        return "GarminTokens[clientId=" + clientId + ", accessExpiresAt=" + accessExpiresAt + "]";
    }
}
