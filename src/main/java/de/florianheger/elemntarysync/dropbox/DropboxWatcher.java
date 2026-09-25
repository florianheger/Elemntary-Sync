package de.florianheger.elemntarysync.dropbox;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.florianheger.elemntarysync.converter.FitConverter;

/** Watches the Dropbox folder for new .fit files. See requirements/features/dropbox.md. */
public class DropboxWatcher {

    private static final Logger log = LoggerFactory.getLogger(DropboxWatcher.class);

    private final FitConverter converter;
    private final String watchFolder;

    public DropboxWatcher(FitConverter converter, String watchFolder) {
        this.converter = converter;
        this.watchFolder = watchFolder;
    }

    public void start() {
        // TODO step 2: poll the folder at least once a minute, download new files, move them to Processed
        log.info("Watcher started for {} (polling not implemented yet)", watchFolder);
    }

    public void onNewFile(Path file) {
        log.info("New file {}", file);
        converter.processFitFile(file);
    }
}
