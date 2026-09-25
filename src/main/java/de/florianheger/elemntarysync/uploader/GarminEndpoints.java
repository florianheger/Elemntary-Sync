package de.florianheger.elemntarysync.uploader;

/** Base URLs of the Garmin services. Tests point them at a local server. */
public record GarminEndpoints(String sso, String diauth, String connectapi) {

    public static final GarminEndpoints PRODUCTION = new GarminEndpoints(
            "https://sso.garmin.com", "https://diauth.garmin.com", "https://connectapi.garmin.com");
}
