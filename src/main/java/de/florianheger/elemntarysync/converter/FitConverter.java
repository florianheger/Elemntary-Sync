package de.florianheger.elemntarysync.converter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.florianheger.elemntarysync.uploader.GarminUploader;

/**
 * Rewrites the device identifiers of a Wahoo .fit file. See requirements/features/fit-processing.csv.
 *
 * <p>Round trip through FitCSVTool: .fit → CSV, edit with {@link FitCsvEditor}, CSV → {@code <name>_garmin.fit}.
 * Work files live in {@code WORK_DIR/converting/<name>/}. They are deleted after a successful upload
 * and kept in {@code WORK_DIR/failed/<name>/} on failure.
 */
public class FitConverter {

    private static final Logger log = LoggerFactory.getLogger(FitConverter.class);

    private final GarminUploader uploader;
    private final FitCsvTool tool;
    private final Path convertingDir;
    private final Path failedDir;

    public FitConverter(GarminUploader uploader, Path fitCsvToolJar, Path workDir) {
        this.uploader = uploader;
        this.tool = new FitCsvTool(fitCsvToolJar);
        this.convertingDir = workDir.resolve("converting");
        this.failedDir = workDir.resolve("failed");
    }

    public void processFitFile(Path fitFile) throws IOException {
        String name = fitFile.getFileName().toString().replaceFirst("(?i)\\.fit$", "");
        Path dir = convertingDir.resolve(name);
        deleteRecursively(dir);
        Files.createDirectories(dir);

        try {
            Path csv = dir.resolve(name + ".csv");
            tool.toCsv(fitFile, csv);
            FitCsvEditor.EditResult result = FitCsvEditor.edit(Files.readAllLines(csv, StandardCharsets.UTF_8));
            Files.write(csv, result.lines(), StandardCharsets.UTF_8);

            Path garminFit = dir.resolve(name + "_garmin.fit");
            tool.toFit(csv, garminFit);
            log.info("Converted {}: device {}, file_id {}, serial {} line(s) changed", fitFile.getFileName(),
                    result.deviceLines(), result.fileIdLines(), result.serialLines());

            uploader.uploadGarminFitFile(garminFit);
        } catch (IOException | RuntimeException e) {
            keepForDebugging(dir, name, e);
            throw e;
        }
        deleteRecursively(dir);
    }

    private void keepForDebugging(Path dir, String name, Exception failure) {
        Path target = failedDir.resolve(name);
        try {
            deleteRecursively(target);
            Files.createDirectories(failedDir);
            Files.move(dir, target);
            log.info("Kept work files of {} in {}", name, target);
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
