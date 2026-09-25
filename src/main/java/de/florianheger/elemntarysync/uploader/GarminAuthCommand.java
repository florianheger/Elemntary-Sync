package de.florianheger.elemntarysync.uploader;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** One-time interactive Garmin login. Stores the tokens; the password is never saved. */
public final class GarminAuthCommand {

    private GarminAuthCommand() {
    }

    public static void run(GarminAuthClient authClient, GarminTokenStore tokenStore) throws IOException {
        Console console = System.console();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        String email = prompt(console, in, "Garmin Connect email: ");
        String password = console != null
                ? new String(console.readPassword("Garmin Connect password: "))
                : prompt(null, in, "Garmin Connect password: ");
        if (email.isBlank() || password.isEmpty()) {
            throw new IOException("Email and password are required");
        }

        System.out.println("Signing in, this takes a few seconds...");
        GarminTokens tokens = authClient.login(email.trim(), password, () -> {
            try {
                return prompt(console, in, "Garmin sent you a verification code. Enter it: ");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        tokenStore.save(tokens);
        System.out.println("Garmin Connect connected. You can now start Elemntary Sync.");
    }

    private static String prompt(Console console, BufferedReader in, String text) throws IOException {
        if (console != null) {
            String line = console.readLine(text);
            return line == null ? "" : line;
        }
        System.out.print(text);
        System.out.flush();
        String line = in.readLine();
        return line == null ? "" : line;
    }
}
