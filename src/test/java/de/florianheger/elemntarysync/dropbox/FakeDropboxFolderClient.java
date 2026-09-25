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
 * In-memory Dropbox. Files are keyed by path. Each longpoll applies the next queued change and reports
 * it, together with the removals caused by moves, in the following continue call. Once the queue is
 * empty, longpoll throws {@link StopException} to end {@code sync()}.
 */
public class FakeDropboxFolderClient implements DropboxFolderClient {

    public static class StopException extends IOException {
        StopException() {
            super("no more changes");
        }
    }

    public final Map<String, String> files = new LinkedHashMap<>();
    public final List<Path> downloadedTo = new ArrayList<>();
    /** Runs at the start of every longpoll, e.g. to advance a test clock. */
    public Runnable onLongpoll = () -> {
    };
    private final Deque<Runnable> changes = new ArrayDeque<>();
    private final List<Entry> added = new ArrayList<>();
    private final List<Entry> deleted = new ArrayList<>();

    public FakeDropboxFolderClient(Map<String, String> initialFiles) {
        files.putAll(initialFiles);
    }

    /** Queues files that appear after the initial listing. */
    public void addLater(Map<String, String> newFiles) {
        changes.add(() -> newFiles.forEach((path, content) -> {
            files.put(path, content);
            added.add(entry(path));
        }));
    }

    /** Queues a deletion made by someone else. */
    public void deleteLater(String path) {
        changes.add(() -> {
            files.remove(path);
            deleted.add(entry(path));
        });
    }

    /** Queues a longpoll that reports changes without any new ones, e.g. to deliver pending removals. */
    public void pollLater() {
        changes.add(() -> {
        });
    }

    @Override
    public Listing listFolder(String folder) {
        deleted.clear();
        List<Entry> entries = new ArrayList<>();
        files.keySet().forEach(path -> {
            if (path.substring(0, path.lastIndexOf('/')).equalsIgnoreCase(folder)) {
                entries.add(entry(path));
            }
        });
        return new Listing(entries, List.of(), "cursor");
    }

    @Override
    public Listing listFolderContinue(String cursor) {
        Listing listing = new Listing(List.copyOf(added), List.copyOf(deleted), cursor);
        added.clear();
        deleted.clear();
        return listing;
    }

    @Override
    public Changes longpoll(String cursor) throws IOException {
        onLongpoll.run();
        if (changes.isEmpty()) {
            throw new StopException();
        }
        changes.poll().run();
        return new Changes(true, 0);
    }

    private static Entry entry(String path) {
        return new Entry(path.substring(path.lastIndexOf('/') + 1), path);
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
        deleted.add(entry(from));
    }

    @Override
    public void createFolderIfMissing(String path) {
    }
}
