package de.florianheger.elemntarysync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.florianheger.elemntarysync.converter.FitConverter;
import de.florianheger.elemntarysync.dropbox.DropboxWatcher;
import de.florianheger.elemntarysync.uploader.GarminUploader;

class PipelineWiringTest {

    @Test
    void newDropboxFileReachesUploader() {
        List<Path> uploaded = new ArrayList<>();
        GarminUploader uploader = new GarminUploader() {
            @Override
            public void uploadGarminFitFile(Path fitFile) {
                uploaded.add(fitFile);
            }
        };
        FitConverter converter = new FitConverter(uploader, Path.of("FitCSVTool.jar"));
        DropboxWatcher watcher = new DropboxWatcher(converter, "/Apps/WahooFitness");

        Path ride = Path.of("ride.fit");
        watcher.onNewFile(ride);

        assertEquals(List.of(ride), uploaded);
    }
}
