package de.florianheger.elemntarysync.dropbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.florianheger.elemntarysync.converter.FitConverter;

class DropboxWatcherTest {

    private static final String WATCH = "/Apps/WahooFitness";

    @TempDir
    Path workDir;

    private final List<String> processed = new ArrayList<>();

    /** Records processed file names and fails for names containing "broken". */
    private final FitConverter converter = new FitConverter(null, Path.of("FitCSVTool.jar")) {
        @Override
        public void processFitFile(Path fitFile) {
            processed.add(fitFile.getFileName().toString());
            if (fitFile.getFileName().toString().contains("broken")) {
                throw new IllegalStateException("conversion failed");
            }
        }
    };

    private void sync(FakeDropboxFolderClient client) {
        DropboxWatcher watcher = new DropboxWatcher(client, converter, WATCH, workDir);
        assertThrows(FakeDropboxFolderClient.StopException.class, watcher::sync);
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
}
