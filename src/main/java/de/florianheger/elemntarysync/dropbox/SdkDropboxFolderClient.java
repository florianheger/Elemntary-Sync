package de.florianheger.elemntarysync.dropbox;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.http.StandardHttpRequestor;
import com.dropbox.core.oauth.DbxCredential;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.CreateFolderErrorException;
import com.dropbox.core.v2.files.DeletedMetadata;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.ListFolderLongpollResult;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;

/** {@link DropboxFolderClient} backed by the official Dropbox SDK. */
public class SdkDropboxFolderClient implements DropboxFolderClient {

    static final String CLIENT_ID = "elemntary-sync";
    private static final long LONGPOLL_TIMEOUT_SECONDS = 120;

    private final DbxClientV2 client;
    private final DbxClientV2 longpollClient;

    public SdkDropboxFolderClient(String appKey, String refreshToken) {
        // An empty, already expired access token makes the SDK refresh it before the first call.
        DbxCredential credential = new DbxCredential("", 0L, refreshToken, appKey);
        client = new DbxClientV2(DbxRequestConfig.newBuilder(CLIENT_ID).build(), credential);

        // Longpoll requests stay open for up to the timeout, so they need a longer read timeout.
        StandardHttpRequestor.Config httpConfig = StandardHttpRequestor.Config.DEFAULT_INSTANCE.copy()
                .withReadTimeout(LONGPOLL_TIMEOUT_SECONDS + 60, TimeUnit.SECONDS)
                .build();
        DbxRequestConfig longpollConfig = DbxRequestConfig.newBuilder(CLIENT_ID)
                .withHttpRequestor(new StandardHttpRequestor(httpConfig))
                .build();
        longpollClient = new DbxClientV2(longpollConfig, credential);
    }

    @Override
    public Listing listFolder(String folder) throws IOException {
        try {
            return collect(client.files().listFolderBuilder(folder).withRecursive(false).start());
        } catch (DbxException e) {
            throw new IOException("Listing " + folder + " failed", e);
        }
    }

    @Override
    public Listing listFolderContinue(String cursor) throws IOException {
        try {
            return collect(client.files().listFolderContinue(cursor));
        } catch (DbxException e) {
            throw new IOException("Continuing folder listing failed", e);
        }
    }

    private Listing collect(ListFolderResult result) throws DbxException {
        List<Entry> files = new ArrayList<>();
        List<Entry> deleted = new ArrayList<>();
        while (true) {
            for (Metadata metadata : result.getEntries()) {
                if (metadata instanceof FileMetadata file) {
                    files.add(new Entry(file.getName(), file.getPathDisplay()));
                } else if (metadata instanceof DeletedMetadata entry) {
                    deleted.add(new Entry(entry.getName(), entry.getPathDisplay()));
                }
            }
            if (!result.getHasMore()) {
                return new Listing(files, deleted, result.getCursor());
            }
            result = client.files().listFolderContinue(result.getCursor());
        }
    }

    @Override
    public Changes longpoll(String cursor) throws IOException {
        try {
            ListFolderLongpollResult result = longpollClient.files().listFolderLongpoll(cursor, LONGPOLL_TIMEOUT_SECONDS);
            Long backoff = result.getBackoff();
            return new Changes(result.getChanges(), backoff == null ? 0 : backoff);
        } catch (DbxException e) {
            throw new IOException("Longpoll failed", e);
        }
    }

    @Override
    public void download(String path, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
            client.files().download(path).download(out);
        } catch (DbxException e) {
            throw new IOException("Downloading " + path + " failed", e);
        }
    }

    @Override
    public void move(String from, String to) throws IOException {
        try {
            client.files().moveV2Builder(from, to).withAutorename(true).start();
        } catch (DbxException e) {
            throw new IOException("Moving " + from + " to " + to + " failed", e);
        }
    }

    @Override
    public void createFolderIfMissing(String path) throws IOException {
        try {
            client.files().createFolderV2(path);
        } catch (CreateFolderErrorException e) {
            if (!(e.errorValue.isPath() && e.errorValue.getPathValue().isConflict())) {
                throw new IOException("Creating " + path + " failed", e);
            }
        } catch (DbxException e) {
            throw new IOException("Creating " + path + " failed", e);
        }
    }
}
