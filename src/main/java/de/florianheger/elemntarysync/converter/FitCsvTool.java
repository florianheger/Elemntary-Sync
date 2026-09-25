package de.florianheger.elemntarysync.converter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Runs Garmin's FitCSVTool.jar as a subprocess.
 *
 * <p>The tool always exits with code 0, even on errors, so failures are detected from its output and
 * from the target file.
 */
public class FitCsvTool {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final Path jar;
    private final String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    public FitCsvTool(Path jar) {
        this.jar = jar;
    }

    public void toCsv(Path fit, Path csv) throws IOException {
        run(csv, "-b", fit.toString(), csv.toString());
    }

    public void toFit(Path csv, Path fit) throws IOException {
        run(fit, "-c", csv.toString(), fit.toString());
    }

    private void run(Path target, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(javaBinary, "-jar", jar.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));

        String text;
        try {
            if (!process.waitFor(TIMEOUT)) {
                process.destroyForcibly();
                throw new IOException("FitCSVTool timed out after " + TIMEOUT.toSeconds() + " s: " + command);
            }
            text = output.get().strip();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while running FitCSVTool");
        } catch (ExecutionException e) {
            throw new IOException("Reading FitCSVTool output failed", e.getCause());
        }

        if (text.contains("Error") || text.contains("does not exist")
                || !Files.exists(target) || Files.size(target) == 0) {
            throw new IOException("FitCSVTool " + args[0] + " failed for " + args[1] + ":\n" + text);
        }
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
