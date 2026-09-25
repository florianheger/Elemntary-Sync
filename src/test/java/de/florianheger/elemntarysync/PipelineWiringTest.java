package de.florianheger.elemntarysync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.florianheger.elemntarysync.converter.FitConverter;
import de.florianheger.elemntarysync.dropbox.DropboxWatcher;
import de.florianheger.elemntarysync.dropbox.FakeDropboxFolderClient;
import de.florianheger.elemntarysync.uploader.GarminUploader;

class PipelineWiringTest {

    @TempDir
    Path workDir;

    @Test
    void newDropboxFileReachesUploader() {
        List<String> uploaded = new ArrayList<>();
        GarminUploader uploader = new GarminUploader() {
            @Override
            public void uploadGarminFitFile(Path fitFile) {
                uploaded.add(fitFile.getFileName().toString());
            }
        };
        FitConverter converter = new FitConverter(uploader, Path.of("FitCSVTool.jar"));
        FakeDropboxFolderClient client = new FakeDropboxFolderClient(Map.of("/Apps/WahooFitness/ride.fit", "data"));
        DropboxWatcher watcher = new DropboxWatcher(client, converter, "/Apps/WahooFitness", workDir);

        assertThrows(FakeDropboxFolderClient.StopException.class, watcher::sync);

        assertEquals(List.of("ride.fit"), uploaded);
    }
}
