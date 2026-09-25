package de.florianheger.elemntarysync.uploader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GarminTokenStoreTest {

    @TempDir
    Path dir;

    @Test
    void savesAndLoadsTokensReadableByOwnerOnly() throws IOException {
        Path file = dir.resolve("garmin-tokens.properties");
        GarminTokenStore store = new GarminTokenStore(file);
        GarminTokens tokens = new GarminTokens("access", "refresh", "CLIENT", Instant.parse("2026-01-01T10:00:00Z"));

        store.save(tokens);

        assertEquals(tokens, store.load());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void missingFileMeansNotConnected() {
        GarminTokenStore store = new GarminTokenStore(dir.resolve("missing.properties"));

        assertFalse(store.exists());
        assertThrows(GarminAuthException.class, store::load);
    }
}
