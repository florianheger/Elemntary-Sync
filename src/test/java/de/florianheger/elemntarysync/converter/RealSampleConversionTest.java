package de.florianheger.elemntarysync.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import de.florianheger.elemntarysync.uploader.GarminUploader;

/**
 * Converts real rides from a local folder. Real .fit files contain personal data and must not be
 * committed, so this only runs when FIT_SAMPLE_DIR points to a folder with .fit files.
 */
@EnabledIfEnvironmentVariable(named = "FIT_SAMPLE_DIR", matches = ".+")
class RealSampleConversionTest {

    private static final Pattern NON_NUMERIC_SERIAL = Pattern.compile("serial_number,\"[^\"]*[^0-9\"][^\"]*\"");

    @TempDir
    Path workDir;

    @Test
    void convertsAllSamples() throws IOException {
        List<Path> samples;
        try (Stream<Path> files = Files.list(Path.of(System.getenv("FIT_SAMPLE_DIR")))) {
            samples = files.filter(path -> path.toString().toLowerCase().endsWith(".fit")).sorted().toList();
        }
        assertFalse(samples.isEmpty(), "No .fit files in FIT_SAMPLE_DIR");

        FitCsvTool tool = new FitCsvTool(FitConverterTest.FIT_CSV_TOOL);
        for (Path sample : samples) {
            Path originalCsv = workDir.resolve(sample.getFileName() + ".original.csv");
            tool.toCsv(sample, originalCsv);

            List<List<String>> uploads = new ArrayList<>();
            GarminUploader uploader = new GarminUploader() {
                @Override
                public void uploadGarminFitFile(Path fitFile) {
                    try {
                        Path csv = fitFile.resolveSibling("uploaded.csv");
                        tool.toCsv(fitFile, csv);
                        uploads.add(Files.readAllLines(csv, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }
            };
            new FitConverter(uploader, FitConverterTest.FIT_CSV_TOOL, workDir).processFitFile(sample);

            List<String> converted = uploads.getFirst();
            assertEquals(count(Files.readAllLines(originalCsv, StandardCharsets.UTF_8), "record"),
                    count(converted, "record"), sample + ": record count changed");
            assertTrue(converted.stream().filter(line -> line.startsWith("Data,") && line.contains(",file_id,"))
                    .allMatch(line -> line.contains("manufacturer,\"1\"") && line.contains("product,\"3121\"")),
                    sample + ": file_id not converted");
            assertFalse(converted.stream().anyMatch(line -> line.contains(",device_info,")
                    && line.contains("device_index,\"0\"") && line.contains("manufacturer,\"32\"")),
                    sample + ": main device still Wahoo");
            assertFalse(converted.stream().anyMatch(line -> line.contains(",device_info,")
                    && NON_NUMERIC_SERIAL.matcher(line).find()), sample + ": non-numeric serial left");
        }
    }

    private static long count(List<String> lines, String message) {
        return lines.stream().filter(line -> line.startsWith("Data,") && line.split(",", 4)[2].equals(message)).count();
    }
}
