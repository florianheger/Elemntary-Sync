package de.florianheger.elemntarysync.converter;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.florianheger.elemntarysync.uploader.GarminUploader;

/** Rewrites the device identifiers of a Wahoo .fit file. See requirements/features/fit-processing.csv. */
public class FitConverter {

    private static final Logger log = LoggerFactory.getLogger(FitConverter.class);

    private final GarminUploader uploader;
    private final Path fitCsvToolJar;

    public FitConverter(GarminUploader uploader, Path fitCsvToolJar) {
        this.uploader = uploader;
        this.fitCsvToolJar = fitCsvToolJar;
    }

    public void processFitFile(Path fitFile) {
        // TODO step 3: convert with FitCSVTool, edit the CSV, convert back to ride_garmin.fit
        log.info("Processing {} (conversion not implemented yet, using {})", fitFile, fitCsvToolJar);
        uploader.uploadGarminFitFile(fitFile);
    }
}
