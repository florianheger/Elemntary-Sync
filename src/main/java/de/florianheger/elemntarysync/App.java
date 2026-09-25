package de.florianheger.elemntarysync;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.florianheger.elemntarysync.converter.FitConverter;
import de.florianheger.elemntarysync.dropbox.DropboxAuthCommand;
import de.florianheger.elemntarysync.dropbox.DropboxWatcher;
import de.florianheger.elemntarysync.dropbox.SdkDropboxFolderClient;
import de.florianheger.elemntarysync.uploader.GarminUploader;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("auth-dropbox")) {
            DropboxAuthCommand.run(requiredEnv("DROPBOX_APP_KEY"));
            return;
        }

        String dropboxAppKey = requiredEnv("DROPBOX_APP_KEY");
        String dropboxRefreshToken = requiredEnv("DROPBOX_REFRESH_TOKEN");
        String watchFolder = env("DROPBOX_WATCH_FOLDER", "/Apps/WahooFitness");
        Path fitCsvToolJar = Path.of(env("FIT_CSV_TOOL_PATH", "FitCSVTool.jar"));
        Path workDir = Path.of(env("WORK_DIR", "data"));

        GarminUploader uploader = new GarminUploader();
        FitConverter converter = new FitConverter(uploader, fitCsvToolJar);
        DropboxWatcher watcher = new DropboxWatcher(
                new SdkDropboxFolderClient(dropboxAppKey, dropboxRefreshToken), converter, watchFolder, workDir);

        log.info("Starting Elemntary Sync");
        watcher.start();

        new CountDownLatch(1).await();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println("Missing environment variable " + name + ". See .env.example and README.md.");
            System.exit(1);
        }
        return value;
    }
}
