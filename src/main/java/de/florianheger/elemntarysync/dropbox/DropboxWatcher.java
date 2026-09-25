package de.florianheger.elemntarysync.dropbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.florianheger.elemntarysync.converter.FitConverter;

/**
 * Watches the Dropbox folder for new .fit files. See requirements/features/dropbox.md.
 *
 * <p>The watch folder itself is the queue: every .fit file in it is unprocessed. After processing, a
 * file is moved to {@code Processed} on success or {@code Failed} on error, so no state is persisted.
 */
public class DropboxWatcher {

    private static final Logger log = LoggerFactory.getLogger(DropboxWatcher.class);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final DropboxFolderClient client;
    private final FitConverter converter;
    private final String watchFolder;
    private final String processedFolder;
    private final String failedFolder;
    private final Path incomingDir;

    public DropboxWatcher(DropboxFolderClient client, FitConverter converter, String watchFolder, Path workDir) {
        this.client = client;
        this.converter = converter;
        this.watchFolder = watchFolder;
        this.processedFolder = watchFolder + "/Processed";
        this.failedFolder = watchFolder + "/Failed";
        this.incomingDir = workDir.resolve("incoming");
    }

    public void start() {
        Thread thread = new Thread(this::run, "dropbox-watcher");
        thread.setDaemon(true);
        thread.start();
        log.info("Watcher started for {}", watchFolder);
    }

    private void run() {
        while (true) {
            try {
                sync();
            } catch (IOException e) {
                log.warn("Dropbox sync failed, retrying in {} s", RETRY_DELAY.toSeconds(), e);
                try {
                    Thread.sleep(RETRY_DELAY);
                } catch (InterruptedException ie) {
                    return;
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Processes everything in the watch folder, then keeps processing new files until an error occurs. */
    public void sync() throws IOException, InterruptedException {
        client.createFolderIfMissing(processedFolder);
        client.createFolderIfMissing(failedFolder);

        DropboxFolderClient.Listing listing = client.listFolder(watchFolder);
        processAll(listing.files());
        String cursor = listing.cursor();

        while (true) {
            DropboxFolderClient.Changes changes = client.longpoll(cursor);
            if (changes.changes()) {
                DropboxFolderClient.Listing next = client.listFolderContinue(cursor);
                processAll(next.files());
                cursor = next.cursor();
            }
            if (changes.backoffSeconds() > 0) {
                Thread.sleep(Duration.ofSeconds(changes.backoffSeconds()));
            }
        }
    }

    private void processAll(List<DropboxFolderClient.Entry> files) throws IOException {
        for (DropboxFolderClient.Entry file : files) {
            if (file.name().toLowerCase(Locale.ROOT).endsWith(".fit")) {
                onNewFile(file);
            }
        }
    }

    void onNewFile(DropboxFolderClient.Entry file) throws IOException {
        log.info("New file {}", file.path());
        Path local = incomingDir.resolve(file.name());
        try {
            client.download(file.path(), local);
            String target;
            try {
                converter.processFitFile(local);
                target = processedFolder;
            } catch (Exception e) {
                log.error("Processing {} failed, moving it to {}", file.path(), failedFolder, e);
                target = failedFolder;
            }
            client.move(file.path(), target + "/" + file.name());
            log.info("Moved {} to {}", file.name(), target);
        } finally {
            Files.deleteIfExists(local);
        }
    }
}
