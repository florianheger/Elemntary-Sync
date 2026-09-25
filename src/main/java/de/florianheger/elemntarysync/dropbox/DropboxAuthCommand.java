package de.florianheger.elemntarysync.dropbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.dropbox.core.DbxAppInfo;
import com.dropbox.core.DbxAuthFinish;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxPKCEWebAuth;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.DbxWebAuth;
import com.dropbox.core.TokenAccessType;

/** One-time interactive login that prints a refresh token for the .env file. */
public final class DropboxAuthCommand {

    private DropboxAuthCommand() {
    }

    public static void run(String appKey) throws IOException, DbxException {
        DbxPKCEWebAuth auth = new DbxPKCEWebAuth(
                DbxRequestConfig.newBuilder(SdkDropboxFolderClient.CLIENT_ID).build(), new DbxAppInfo(appKey));
        String url = auth.authorize(DbxWebAuth.newRequestBuilder()
                .withNoRedirect()
                .withTokenAccessType(TokenAccessType.OFFLINE)
                .build());

        System.out.println("1. Open this URL and allow access:");
        System.out.println("   " + url);
        System.out.print("2. Paste the authorization code here: ");
        System.out.flush();
        String code = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (code == null || code.isBlank()) {
            throw new IOException("No authorization code entered");
        }

        DbxAuthFinish finish = auth.finishFromCode(code.trim());
        System.out.println();
        System.out.println("Add this line to your .env file:");
        System.out.println("DROPBOX_REFRESH_TOKEN=" + finish.getRefreshToken());
    }
}
