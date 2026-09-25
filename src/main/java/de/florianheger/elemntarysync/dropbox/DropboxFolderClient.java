package de.florianheger.elemntarysync.dropbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The Dropbox operations the watcher needs. Listings are not recursive and contain files only: added or
 * changed files in {@code files}, deleted ones in {@code deleted}.
 */
public interface DropboxFolderClient {

    record Entry(String name, String path) {}

    record Listing(List<Entry> files, List<Entry> deleted, String cursor) {}

    record Changes(boolean changes, long backoffSeconds) {}

    Listing listFolder(String folder) throws IOException;

    Listing listFolderContinue(String cursor) throws IOException;

    /** Blocks until the folder behind the cursor changes or the timeout passes. */
    Changes longpoll(String cursor) throws IOException;

    void download(String path, Path target) throws IOException;

    /** Moves a file, renaming it if the target already exists. */
    void move(String from, String to) throws IOException;

    void createFolderIfMissing(String path) throws IOException;
}
