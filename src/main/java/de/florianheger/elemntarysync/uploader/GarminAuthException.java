package de.florianheger.elemntarysync.uploader;

import java.io.IOException;

/** The Garmin login was rejected or has expired. Retrying doesn't help; the user has to log in again. */
public class GarminAuthException extends IOException {

    public GarminAuthException(String message) {
        super(message);
    }
}
