package de.florianheger.elemntarysync.uploader;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Uploads converted .fit files to Garmin Connect. See requirements/features/garmin-upload.csv.
 *
 * <p>Implementations throw when the upload finally fails, so the Dropbox watcher moves the ride to
 * {@code Failed}.
 */
public interface GarminUploader {

    void uploadGarminFitFile(Path fitFile) throws IOException;
}
