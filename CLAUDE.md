# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

Garmin Connect only computes stats such as training effect and calories for `.fit` files recorded by Garmin devices. Elemntary Sync picks up the `.fit` files a Wahoo Elemnt uploads to Dropbox, rewrites their device identifiers so Garmin Connect treats them as Garmin recordings (Edge 530), and uploads them to Garmin Connect. User-facing setup lives in `README.md`.

## Commands

The project uses Maven and Java 25 (`maven.compiler.release` 25). The local machine may only have Java 21 and no `mvn`. In that case, run Maven in a container:

```
docker run --rm -v "$PWD":/build -v elemntary-m2:/root/.m2 -w /build maven:3.9-eclipse-temurin-25 mvn -B package
```

- Build fat jar + run tests: `mvn package` (produces `target/elemntary-sync.jar`)
- Tests only: `mvn test`; a single test: `mvn test -Dtest=PipelineWiringTest`. Converter tests run the real `FitCSVTool.jar` from the project root.
- Real rides can't be committed (they contain personal data). `RealSampleConversionTest` runs only when `FIT_SAMPLE_DIR` is set. With the Maven container: `docker run --rm -v "$PWD":/build -v ~/Repos:/samples:ro -e FIT_SAMPLE_DIR=/samples -v elemntary-m2:/root/.m2 -w /build maven:3.9-eclipse-temurin-25 mvn -B test -Dtest=RealSampleConversionTest`
- Run with Docker: `cp .env.example .env` (fill in credentials), then `docker compose up --build`
- One-time Dropbox login (prints `DROPBOX_REFRESH_TOKEN`): `docker compose run --rm elemntary-sync auth-dropbox`
- One-time Garmin login (stores tokens in `WORK_DIR/garmin-tokens.properties`): `docker compose run --rm elemntary-sync auth-garmin`

## Architecture

Base package: `de.florianheger.elemntarysync`. Each pipeline stage has its own package with one entry class. Calls go one way, wired with constructor injection in `App.main`:

`dropbox.DropboxWatcher.onNewFile` → `converter.FitConverter.processFitFile` → `uploader.GarminUploader.uploadGarminFitFile`

**Failure contract:** `processFitFile` and `uploadGarminFitFile` signal failure by throwing. `DropboxWatcher` catches the exception and moves the file to `Failed`, so the uploader must throw once its retries are used up. `App` reads its configuration from environment variables (see `.env.example`). `PipelineWiringTest` checks the whole call chain.

In the Docker image, `FitCSVTool.jar` sits at `/app/FitCSVTool.jar`, and `/data` (`WORK_DIR`) is a persistent volume for working files.

### 1. Dropbox (`dropbox/`)

- `DropboxWatcher` keeps no state. The watch folder is the queue, so on every (re)start it processes all `.fit` files directly in it. After that it uses Dropbox longpoll plus `list_folder/continue` for new files.
- Each file is downloaded to `WORK_DIR/incoming`, processed, then moved to `Processed` or `Failed`, and the local copy is deleted. A Dropbox error triggers a full resync after 30 s.
- Logging goes to stdout (for `docker logs`) and covers `.fit` files only: new files, moves, and removals made outside the app. Removals caused by the app's own moves are filtered through `ownMoves`. It also logs an hourly heartbeat when idle.
- The SDK is hidden behind `DropboxFolderClient` (real: `SdkDropboxFolderClient`, tests: `FakeDropboxFolderClient`).
- Auth uses a PKCE refresh token (app key only, no secret) and needs a Full Dropbox app, because Wahoo writes into its own app folder (`Apps/WahooFitness`).

### 2. FIT conversion (`converter/`)

`FitCsvTool` runs the jar as a subprocess and detects failures from its output, because **FitCSVTool always exits 0**. `FitCsvEditor` holds the pure CSV rules. `FitConverter` works in `WORK_DIR/converting/<name>/`: it deletes that directory after the upload and moves it to `WORK_DIR/failed/<name>/` on failure. The rules round-trip the file through `FitCSVTool.jar`, which is checked into the repo root:

- `java -jar FitCSVTool.jar ride.fit` produces `ride.csv`.
- Edit `ride.csv` in place. Change values on `Data` lines only; never touch `Definition` lines or the CSV structure.
  - On `device_info` lines where `device_index` is `0`: change manufacturer `32` (Wahoo) to `1` (Garmin), and product `63` to `3121`. If the field is named `garmin_product`, change that field instead of `product`.
  - On the `file_id` line: make sure manufacturer is `1` and product is `3121`.
  - On `device_info` lines: clear every non-numeric `serial_number` (e.g. `1E00FB32`, the "Puls" sensor) so the field reads `serial_number,,null`. Numeric serials like `10032` stay. FitCSVTool can't encode non-numeric serials, so an unmodified round trip fails.
- `java -jar FitCSVTool.jar -c ride.csv ride_garmin.fit` must finish without errors. Report how many lines each rule changed, and verify that no `device_index` `0` line still has manufacturer `32`.
- Then call `uploadGarminFitFile(<name>_garmin.fit)`.

### 3. Garmin upload (`uploader/`)

`GarminUploader` is an interface, so tests use anonymous classes or lambdas. The real implementation is `GarminConnectUploader`.

- **No official API.** `GarminAuthClient` copies python-garminconnect's SSO embed-widget login (github.com/cyberjunky/python-garminconnect, `_widget_web_login` / `_exchange_service_ticket`): CSRF form → wait 3–8 s → POST → `ST-` ticket → DI OAuth2 token from `diauth.garmin.com`. If Garmin changes its login, compare with that project first.
- **Tokens:** access ~24 h, and the refresh token **rotates** on every refresh. That's why tokens live in `WORK_DIR/garmin-tokens.properties` (mode 600), not in `.env`, and why the uploader methods are `synchronized`. `App` schedules `refreshDaily()`. Never log tokens or tickets.
- **Upload:** multipart POST to `connectapi.garmin.com/upload-service/upload/.fit`. `409` counts as success. `429`/`5xx`/network errors are retried: 5 attempts, 30 s apart. A rejected file (other `4xx`, or `failures` in a 2xx response) and `GarminAuthException` (expired login) fail at once.
- **Tests:** `FakeGarminServer` (JDK `HttpServer`) stands in for all three Garmin hosts.

## Technical constraints

- Language: Java.
- Deployment: the app must run in Docker and deploy with Docker Compose. A new user should only need to create a `.env` file with the Dropbox and Garmin Connect credentials.
