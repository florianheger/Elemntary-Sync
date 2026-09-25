package de.florianheger.elemntarysync.dropbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.florianheger.elemntarysync.converter.FitConverter;

/**
 * Watches the Dropbox folder for new .fit files. See requirements/features/dropbox.md.
 *
 * <p>The watch folder itself is the queue: every .fit file in it is unprocessed. After processing, a
 * file is moved to {@code Processed} on success or {@code Failed} on error, so no state is persisted.
 *
 * <p>Logs every change to a .fit file in the watch folder, except the removals caused by its own moves,
 * and a heartbeat when nothing happened for {@link #HEARTBEAT_INTERVAL}.
 */
public class DropboxWatcher {

    private static final Logger log = LoggerFactory.getLogger(DropboxWatcher.class);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    static final Duration HEARTBEAT_INTERVAL = Duration.ofHours(1);

    private final DropboxFolderClient client;
    private final FitConverter converter;
    private final String watchFolder;
    private final String processedFolder;
    private final String failedFolder;
    private final Path incomingDir;
    private final InstantSource clock;
    /** Lower-cased paths this watcher moved away, so the resulting deletions aren't logged. */
    private final Set<String> ownMoves = new HashSet<>();
    private Instant lastActivity;

    public DropboxWatcher(DropboxFolderClient client, FitConverter converter, String watchFolder, Path workDir) {
        this(client, converter, watchFolder, workDir, InstantSource.system());
    }

    DropboxWatcher(DropboxFolderClient client, FitConverter converter, String watchFolder, Path workDir,
            InstantSource clock) {
        this.client = client;
        this.converter = converter;
        this.watchFolder = watchFolder;
        this.processedFolder = watchFolder + "/Processed";
        this.failedFolder = watchFolder + "/Failed";
        this.incomingDir = workDir.resolve("incoming");
        this.clock = clock;
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

        // A fresh cursor doesn't report deletions from before, so old entries would never be cleared.
        ownMoves.clear();
        lastActivity = clock.instant();

        DropboxFolderClient.Listing listing = client.listFolder(watchFolder);
        List<DropboxFolderClient.Entry> fitFiles = fitFiles(listing.files());
        log.info("Found {} .fit file(s) in {}", fitFiles.size(), watchFolder);
        processAll(fitFiles);
        String cursor = listing.cursor();

        while (true) {
            DropboxFolderClient.Changes changes = client.longpoll(cursor);
            if (changes.changes()) {
                DropboxFolderClient.Listing next = client.listFolderContinue(cursor);
                logDeletions(next.deleted());
                processAll(fitFiles(next.files()));
                cursor = next.cursor();
            }
            logHeartbeatIfIdle();
            if (changes.backoffSeconds() > 0) {
                Thread.sleep(Duration.ofSeconds(changes.backoffSeconds()));
            }
        }
    }

    private static List<DropboxFolderClient.Entry> fitFiles(List<DropboxFolderClient.Entry> entries) {
        return entries.stream().filter(entry -> entry.name().toLowerCase(Locale.ROOT).endsWith(".fit")).toList();
    }

    private void processAll(List<DropboxFolderClient.Entry> fitFiles) throws IOException {
        for (DropboxFolderClient.Entry file : fitFiles) {
            onNewFile(file);
        }
    }

    private void logDeletions(List<DropboxFolderClient.Entry> deleted) {
        for (DropboxFolderClient.Entry file : fitFiles(deleted)) {
            if (!ownMoves.remove(file.path().toLowerCase(Locale.ROOT))) {
                log.info("Removed {} (moved or deleted outside Elemntary Sync)", file.path());
                lastActivity = clock.instant();
            }
        }
    }

    private void logHeartbeatIfIdle() {
        Instant now = clock.instant();
        if (Duration.between(lastActivity, now).compareTo(HEARTBEAT_INTERVAL) >= 0) {
            log.info("Still watching {}, no .fit changes in the last {} min", watchFolder,
                    HEARTBEAT_INTERVAL.toMinutes());
            lastActivity = now;
        }
    }

    void onNewFile(DropboxFolderClient.Entry file) throws IOException {
        log.info("New file {}", file.path());
        lastActivity = clock.instant();
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
            ownMoves.add(file.path().toLowerCase(Locale.ROOT));
            log.info("Moved {} to {}", file.name(), target);
        } finally {
            Files.deleteIfExists(local);
        }
    }
}
