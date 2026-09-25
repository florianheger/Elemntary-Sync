package de.florianheger.elemntarysync.dropbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory Dropbox. Files are keyed by path. Each longpoll hands out the next queued batch of new
 * files; once the queue is empty, longpoll throws {@link StopException} to end {@code sync()}.
 */
public class FakeDropboxFolderClient implements DropboxFolderClient {

    public static class StopException extends IOException {
        StopException() {
            super("no more batches");
        }
    }

    public final Map<String, String> files = new LinkedHashMap<>();
    public final List<Path> downloadedTo = new ArrayList<>();
    private final Deque<Map<String, String>> batches = new ArrayDeque<>();
    private Map<String, String> pending = Map.of();

    public FakeDropboxFolderClient(Map<String, String> initialFiles) {
        files.putAll(initialFiles);
    }

    /** Queues files that appear after the initial listing. */
    public void addLater(Map<String, String> newFiles) {
        batches.add(newFiles);
    }

    @Override
    public Listing listFolder(String folder) {
        return new Listing(filesIn(folder, files), "cursor");
    }

    @Override
    public Listing listFolderContinue(String cursor) {
        return new Listing(filesIn(null, pending), cursor);
    }

    @Override
    public Changes longpoll(String cursor) throws IOException {
        if (batches.isEmpty()) {
            throw new StopException();
        }
        pending = batches.poll();
        files.putAll(pending);
        return new Changes(true, 0);
    }

    private static List<Entry> filesIn(String folder, Map<String, String> source) {
        List<Entry> entries = new ArrayList<>();
        source.forEach((path, content) -> {
            String parent = path.substring(0, path.lastIndexOf('/'));
            if (folder == null || parent.equalsIgnoreCase(folder)) {
                entries.add(new Entry(path.substring(path.lastIndexOf('/') + 1), path));
            }
        });
        return entries;
    }

    @Override
    public void download(String path, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, files.get(path));
        downloadedTo.add(target);
    }

    @Override
    public void move(String from, String to) {
        files.put(to, files.remove(from));
    }

    @Override
    public void createFolderIfMissing(String path) {
    }
}
