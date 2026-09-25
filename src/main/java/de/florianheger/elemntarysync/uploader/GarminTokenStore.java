package de.florianheger.elemntarysync.uploader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Properties;

/** Persists the Garmin tokens in a properties file that only the owner can read. */
public class GarminTokenStore {

    private final Path file;

    public GarminTokenStore(Path file) {
        this.file = file;
    }

    public boolean exists() {
        return Files.exists(file);
    }

    public GarminTokens load() throws IOException {
        if (!exists()) {
            throw new GarminAuthException("Garmin Connect is not connected. Run: docker compose run --rm elemntary-sync auth-garmin");
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        try {
            return new GarminTokens(
                    required(properties, "access_token"),
                    required(properties, "refresh_token"),
                    required(properties, "client_id"),
                    Instant.parse(required(properties, "access_expires_at")));
        } catch (RuntimeException e) {
            throw new IOException("Invalid Garmin token file " + file + ": " + e.getMessage());
        }
    }

    public void save(GarminTokens tokens) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("access_token", tokens.accessToken());
        properties.setProperty("refresh_token", tokens.refreshToken());
        properties.setProperty("client_id", tokens.clientId());
        properties.setProperty("access_expires_at", tokens.accessExpiresAt().toString());

        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // Not a POSIX file system, e.g. Windows.
            }
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                properties.store(writer, "Garmin Connect tokens - created by auth-garmin, refreshed automatically");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }
}
