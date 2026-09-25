package de.florianheger.elemntarysync.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.florianheger.elemntarysync.uploader.GarminUploader;

/** Runs the real FitCSVTool.jar from the project root. */
class FitConverterTest {

    static final Path FIT_CSV_TOOL = Path.of("FitCSVTool.jar");

    @TempDir
    Path workDir;

    /** Decodes every uploaded file to CSV lines, since the uploaded file is deleted afterwards. */
    private final List<List<String>> uploads = new ArrayList<>();
    private final List<String> uploadedNames = new ArrayList<>();
    private final GarminUploader decodingUploader = new GarminUploader() {
        @Override
        public void uploadGarminFitFile(Path fitFile) {
            uploadedNames.add(fitFile.getFileName().toString());
            try {
                Path csv = fitFile.resolveSibling("uploaded.csv");
                new FitCsvTool(FIT_CSV_TOOL).toCsv(fitFile, csv);
                uploads.add(Files.readAllLines(csv, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    };

    @Test
    void convertsSyntheticRideAndCleansUp() throws IOException {
        Path ride = workDir.resolve("incoming/ride.fit");
        Files.createDirectories(ride.getParent());
        new FitCsvTool(FIT_CSV_TOOL).toFit(Path.of("src/test/resources/wahoo-synthetic.csv"), ride);

        new FitConverter(decodingUploader, FIT_CSV_TOOL, workDir).processFitFile(ride);

        assertEquals(List.of("ride_garmin.fit"), uploadedNames);
        List<String> lines = uploads.getFirst();
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Data,0,file_id,")
                && line.contains("manufacturer,\"1\"") && line.contains("garmin_product,\"3121\"")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("device_info") && line.contains("device_index,\"0\"")
                && line.contains("manufacturer,\"1\"") && line.contains("garmin_product,\"3121\"")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"Radar\"") && line.contains("garmin_product,\"295\"")));
        assertFalse(Files.exists(workDir.resolve("converting/ride")));
        assertFalse(Files.exists(workDir.resolve("failed/ride")));
    }

    @Test
    void invalidFileFailsAndKeepsWorkFiles() throws IOException {
        Path broken = workDir.resolve("incoming/broken.fit");
        Files.createDirectories(broken.getParent());
        byte[] garbage = new byte[2048];
        new Random(42).nextBytes(garbage);
        Files.write(broken, garbage);

        FitConverter converter = new FitConverter(decodingUploader, FIT_CSV_TOOL, workDir);

        assertThrows(IOException.class, () -> converter.processFitFile(broken));
        assertEquals(List.of(), uploadedNames);
        assertTrue(Files.isDirectory(workDir.resolve("failed/broken")));
        assertFalse(Files.exists(workDir.resolve("converting/broken")));
    }
}
