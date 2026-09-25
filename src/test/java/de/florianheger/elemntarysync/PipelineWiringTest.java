package de.florianheger.elemntarysync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.florianheger.elemntarysync.converter.FitConverter;
import de.florianheger.elemntarysync.converter.FitCsvTool;
import de.florianheger.elemntarysync.dropbox.DropboxWatcher;
import de.florianheger.elemntarysync.dropbox.FakeDropboxFolderClient;
import de.florianheger.elemntarysync.uploader.GarminUploader;

class PipelineWiringTest {

    private static final Path FIT_CSV_TOOL = Path.of("FitCSVTool.jar");

    @TempDir
    Path workDir;

    @Test
    void newDropboxFileReachesUploaderConverted() throws Exception {
        Path ride = workDir.resolve("ride.fit");
        new FitCsvTool(FIT_CSV_TOOL).toFit(Path.of("src/test/resources/wahoo-synthetic.csv"), ride);

        List<String> uploaded = new ArrayList<>();
        GarminUploader uploader = new GarminUploader() {
            @Override
            public void uploadGarminFitFile(Path fitFile) {
                uploaded.add(fitFile.getFileName().toString());
            }
        };
        FitConverter converter = new FitConverter(uploader, FIT_CSV_TOOL, workDir);
        FakeDropboxFolderClient client = new FakeDropboxFolderClient(Map.of());
        client.files.put("/Apps/WahooFitness/ride.fit", Files.readAllBytes(ride));
        DropboxWatcher watcher = new DropboxWatcher(client, converter, "/Apps/WahooFitness", workDir);

        assertThrows(FakeDropboxFolderClient.StopException.class, watcher::sync);

        assertEquals(List.of("ride_garmin.fit"), uploaded);
        assertEquals(List.of("/Apps/WahooFitness/Processed/ride.fit"), List.copyOf(client.files.keySet()));
    }
}
