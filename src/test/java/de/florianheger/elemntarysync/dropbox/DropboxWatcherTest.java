package de.florianheger.elemntarysync.dropbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import de.florianheger.elemntarysync.converter.FitConverter;

class DropboxWatcherTest {

    private static final String WATCH = "/Apps/WahooFitness";

    @TempDir
    Path workDir;

    private final List<String> processed = new ArrayList<>();

    /** Records processed file names and fails for names containing "broken". */
    private final FitConverter converter = new FitConverter(null, Path.of("FitCSVTool.jar"), Path.of("unused")) {
        @Override
        public void processFitFile(Path fitFile) {
            processed.add(fitFile.getFileName().toString());
            if (fitFile.getFileName().toString().contains("broken")) {
                throw new IllegalStateException("conversion failed");
            }
        }
    };

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger watcherLogger = (Logger) LoggerFactory.getLogger(DropboxWatcher.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        watcherLogger.addAppender(logs);
    }

    @AfterEach
    void stopCapturingLogs() {
        watcherLogger.detachAppender(logs);
    }

    private void sync(FakeDropboxFolderClient client) {
        DropboxWatcher watcher = new DropboxWatcher(client, converter, WATCH, workDir, () -> now);
        assertThrows(FakeDropboxFolderClient.StopException.class, watcher::sync);
    }

    private List<String> logMessages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private boolean logged(String fragment) {
        return logMessages().stream().anyMatch(message -> message.contains(fragment));
    }

    @Test
    void existingFitFilesAreProcessedAndMovedToProcessed() {
        FakeDropboxFolderClient client = new FakeDropboxFolderClient(Map.of(
                WATCH + "/ride1.fit", "a",
                WATCH + "/RIDE2.FIT", "b"));

        sync(client);

        assertEquals(Set.of("ride1.fit", "RIDE2.FIT"), Set.copyOf(processed));
        assertEquals(Set.of(WATCH + "/Processed/ride1.fit", WATCH + "/Processed/RIDE2.FIT"), client.files.keySet());
    }

    @Test
    void failedFileIsMovedToFailedAndOthersContinue() {
        FakeDropboxFolderClient client = new FakeDropboxFolderClient(Map.of(
                WATCH + "/broken.fit", "x",
                WATCH + "/good.fit", "y"));

        sync(client);

        assertEquals(Set.of("broken.fit", "good.fit"), Set.copyOf(processed));
        assertEquals(Set.of(WATCH + "/Failed/broken.fit", WATCH + "/Processed/good.fit"), client.files.keySet());
    }

    @Test
    void nonFitFilesAndSubfoldersAreIgnored() {
        FakeDropboxFolderClient client = new FakeDropboxFolderClient(Map.of(
                WATCH + "/notes.txt", "t",
                WATCH + "/Processed/old.fit", "o"));

        sync(client);

        assertEquals(List.of(), processed);
        assertEquals(Set.of(WATCH + "/notes.txt", WATCH + "/Processed/old.fit"), client.files.keySet());
    }

    @Test
    void filesArrivingLaterAreProcessed() {
        FakeDropboxFolderClient client = new FakeDropboxFolderClient(Map.of());
        client.addLater(Map.of(WATCH + "/new.fit", "n"));

        sync(client);

        assertEquals(List.of("new.fit"), processed);
        assertEquals(Set.of(WATCH + "/Processed/new.fit"), client.files.keySet());
    }

    @Test
    void localCopyIsDeletedAfterProcessing() {
        FakeDropboxFolderClient client = new FakeDropboxFolderClient(Map.of(
                WATCH + "/ride.fit", "a",
                WATCH + "/broken.fit", "b"));

        sync(client);

        assertEquals(2, client.downloadedTo.size());
        client.downloadedTo.forEach(path -> assertFalse(Files.exists(path), path + " still exists"));
    }

    @Test
    void deletionByUserIsLogged() {
        FakeDropboxFolderClient client = new FakeDropboxFolderClient(Map.of(WATCH + "/notes.txt", "t"));
        client.deleteLater(WATCH + "/ride.fit");
        client.deleteLater(WATCH + "/notes.txt");

        sync(client);

        assertTrue(logged("Removed " + WATCH + "/ride.fit"), logMessages().toString());
        assertFalse(logged("notes.txt"), logMessages().toString());
    }

    @Test
    void ownMovesAreNotLoggedAsRemovals() {
        FakeDropboxFolderClient client = new FakeDropboxFolderClient(Map.of(WATCH + "/ride.fit", "a"));
        client.pollLater();

        sync(client);

        assertTrue(logged("Moved ride.fit"), logMessages().toString());
        assertFalse(logged("Removed"), logMessages().toString());
    }

    @Test
    void heartbeatIsLoggedAfterAnIdleHour() {
        FakeDropboxFolderClient client = new FakeDropboxFolderClient(Map.of());
        client.pollLater();
        client.pollLater();
        client.pollLater();
        client.onLongpoll = () -> now = now.plus(Duration.ofMinutes(40));

        sync(client);

        // 40 min: nothing yet; 80 min: heartbeat; 120 min (only 40 min after it): nothing.
        assertEquals(1, logMessages().stream().filter(message -> message.startsWith("Still watching")).count(),
                logMessages().toString());
    }

    @Test
    void startupLogsHowManyFitFilesWereFound() {
        FakeDropboxFolderClient client = new FakeDropboxFolderClient(Map.of(
                WATCH + "/ride.fit", "a",
                WATCH + "/notes.txt", "t"));

        sync(client);

        assertTrue(logged("Found 1 .fit file(s) in " + WATCH), logMessages().toString());
    }
}
