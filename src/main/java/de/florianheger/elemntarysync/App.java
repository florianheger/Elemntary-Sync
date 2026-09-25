package de.florianheger.elemntarysync;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.florianheger.elemntarysync.converter.FitConverter;
import de.florianheger.elemntarysync.dropbox.DropboxWatcher;
import de.florianheger.elemntarysync.uploader.GarminUploader;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws InterruptedException {
        Path fitCsvToolJar = Path.of(env("FIT_CSV_TOOL_PATH", "FitCSVTool.jar"));
        String watchFolder = env("DROPBOX_WATCH_FOLDER", "/Apps/WahooFitness");

        GarminUploader uploader = new GarminUploader();
        FitConverter converter = new FitConverter(uploader, fitCsvToolJar);
        DropboxWatcher watcher = new DropboxWatcher(converter, watchFolder);

        log.info("Starting Elemntary Sync");
        watcher.start();

        new CountDownLatch(1).await();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
