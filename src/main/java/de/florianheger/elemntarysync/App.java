package de.florianheger.elemntarysync;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.florianheger.elemntarysync.converter.FitConverter;
import de.florianheger.elemntarysync.dropbox.DropboxAuthCommand;
import de.florianheger.elemntarysync.dropbox.DropboxWatcher;
import de.florianheger.elemntarysync.dropbox.SdkDropboxFolderClient;
import de.florianheger.elemntarysync.uploader.GarminAuthClient;
import de.florianheger.elemntarysync.uploader.GarminAuthCommand;
import de.florianheger.elemntarysync.uploader.GarminConnectUploader;
import de.florianheger.elemntarysync.uploader.GarminEndpoints;
import de.florianheger.elemntarysync.uploader.GarminTokenStore;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        Path workDir = Path.of(env("WORK_DIR", "data"));
        GarminTokenStore garminTokens = new GarminTokenStore(workDir.resolve("garmin-tokens.properties"));
        GarminAuthClient garminAuth = new GarminAuthClient(GarminEndpoints.PRODUCTION);

        if (args.length > 0 && args[0].equals("auth-dropbox")) {
            DropboxAuthCommand.run(requiredEnv("DROPBOX_APP_KEY"));
            return;
        }
        if (args.length > 0 && args[0].equals("auth-garmin")) {
            GarminAuthCommand.run(garminAuth, garminTokens);
            return;
        }

        String dropboxAppKey = requiredEnv("DROPBOX_APP_KEY");
        String dropboxRefreshToken = requiredEnv("DROPBOX_REFRESH_TOKEN");
        String watchFolder = env("DROPBOX_WATCH_FOLDER", "/Apps/WahooFitness");
        Path fitCsvToolJar = Path.of(env("FIT_CSV_TOOL_PATH", "FitCSVTool.jar"));
        if (!garminTokens.exists()) {
            System.err.println("Garmin Connect is not connected. Run: docker compose run --rm elemntary-sync auth-garmin");
            System.exit(1);
        }

        GarminConnectUploader uploader = new GarminConnectUploader(garminTokens, garminAuth, GarminEndpoints.PRODUCTION);
        FitConverter converter = new FitConverter(uploader, fitCsvToolJar, workDir);
        DropboxWatcher watcher = new DropboxWatcher(
                new SdkDropboxFolderClient(dropboxAppKey, dropboxRefreshToken), converter, watchFolder, workDir);

        log.info("Starting Elemntary Sync");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "garmin-token-refresh");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(uploader::refreshDaily, 1, 24 * 60, TimeUnit.MINUTES);
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
