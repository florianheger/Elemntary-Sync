package de.florianheger.elemntarysync.uploader;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Uploads converted .fit files to Garmin Connect. See requirements/features/garmin-upload.csv. */
public class GarminUploader {

    private static final Logger log = LoggerFactory.getLogger(GarminUploader.class);

    public void uploadGarminFitFile(Path fitFile) {
        // TODO step 4: authenticate and upload, retry after 30 seconds on failure
        log.info("Upload requested for {} (not implemented yet)", fitFile);
    }
}
