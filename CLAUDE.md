# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

Implementation happens in four steps: (1) project skeleton, (2) Dropbox, (3) FIT conversion, (4) Garmin upload. Steps 1 and 2 are done. `FitConverter` and `GarminUploader` are still stubs that only log and pass the file along. User-facing setup lives in `README.md`.

The specs live in `requirements/`: `overview.md` plus one file per feature in `requirements/features/`. Some feature files use a `.csv` extension but contain Markdown. Read the relevant spec before implementing a feature.

## Commands

The project uses Maven and Java 25 (`maven.compiler.release` 25). The local machine may only have Java 21 and no `mvn`. In that case, run Maven in a container:

```
docker run --rm -v "$PWD":/build -v elemntary-m2:/root/.m2 -w /build maven:3.9-eclipse-temurin-25 mvn -B package
```

- Build fat jar + run tests: `mvn package` (produces `target/elemntary-sync.jar`)
- Tests only: `mvn test`; a single test: `mvn test -Dtest=PipelineWiringTest`
- Run with Docker: `cp .env.example .env` (fill in credentials), then `docker compose up --build`
- One-time Dropbox login (prints `DROPBOX_REFRESH_TOKEN`): `docker compose run --rm elemntary-sync auth-dropbox`

## Code layout

Base package: `de.florianheger.elemntarysync`. Each feature has its own package with one entry class. Calls go one way, wired with constructor injection in `App.main`:

`dropbox.DropboxWatcher.onNewFile` → `converter.FitConverter.processFitFile` → `uploader.GarminUploader.uploadGarminFitFile`

These are the Java names for the spec's `ProcessFitFile` and `UploadGarminFitFile`. **Failure contract:** both methods signal failure by throwing. `DropboxWatcher` catches the exception and moves the file to `Failed`, so the uploader must throw once its retries are used up. `App` reads its configuration from environment variables (see `.env.example`). `PipelineWiringTest` checks the whole call chain.

**Dropbox** (`dropbox/`): `DropboxWatcher` keeps no state. The watch folder is the queue, so on every (re)start it processes all `.fit` files directly in it. After that it uses Dropbox longpoll plus `list_folder/continue` for new files. Each file is downloaded to `WORK_DIR/incoming`, processed, then moved to `Processed` or `Failed`, and the local copy is deleted. A Dropbox error triggers a full resync after 30 s. Logging goes to stdout (for `docker logs`) and covers `.fit` files only: new files, moves, and removals made outside the app. Removals caused by the app's own moves are filtered through `ownMoves`. It also logs an hourly heartbeat when idle. The SDK is hidden behind `DropboxFolderClient` (real: `SdkDropboxFolderClient`, tests: `FakeDropboxFolderClient`). Auth uses a PKCE refresh token (app key only, no secret) and needs a Full Dropbox app, because Wahoo writes into its own app folder. In the Docker image, `FitCSVTool.jar` sits at `/app/FitCSVTool.jar`, and `/data` is a persistent volume for working files.

## Purpose

Garmin Connect only computes stats such as training effect and calories for `.fit` files recorded by Garmin devices. Elemntary Sync rewrites the device identifiers in `.fit` files from a Wahoo Elemnt so that Garmin Connect treats them as Garmin recordings.

## Architecture (pipeline)

The pipeline has three stages. Each stage hands off to the next by calling a named function:

1. **Dropbox** (`requirements/features/dropbox.md`): implemented, see Code layout above.
2. **FIT processing** (`requirements/features/fit-processing.csv`): round-trip the file through `FitCSVTool.jar`, which is checked into the repo root:
   - `java -jar FitCSVTool.jar ride.fit` produces `ride.csv`.
   - Edit `ride.csv` in place. Change values on `Data` lines only; never touch `Definition` lines or the CSV structure.
     - On `device_info` lines where `device_index` is `0`: change manufacturer `32` (Wahoo) to `1` (Garmin), and product `63` to `3121`. If the field is named `garmin_product`, change that field instead of `product`.
     - On the `file_id` line: make sure manufacturer is `1` and product is `3121`.
     - On `device_info` lines with `serial_number` `1E00FB32` (the "Puls" sensor): clear that value so the field reads `serial_number,,null`. Keep the numeric serial `10032`.
   - `java -jar FitCSVTool.jar -c ride.csv ride_garmin.fit` must finish without errors. Report how many lines each rule changed, and verify that no `device_index` `0` line still has manufacturer `32`.
   - Then call `UploadGarminFitFile(ride_garmin.fit)`.
3. **Garmin upload** (`requirements/features/garmin-upload.csv`): authenticate to Garmin Connect and upload the file. If the upload fails, wait 30 seconds and retry.

## Technical constraints

- Language: Java.
- Deployment: the app must run in Docker and deploy with Docker Compose. A new user should only need to create a `.env` file with the Dropbox and Garmin Connect credentials.
