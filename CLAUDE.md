# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

The repo holds requirements only. No source code, build files or tests exist yet. The specs live in `requirements/`: `overview.md` plus one file per feature in `requirements/features/`. Some feature files use a `.csv` extension but contain Markdown. Read the relevant spec before implementing a feature, and update this file once a build system (Maven or Gradle) and a module layout exist.

## Purpose

Garmin Connect only computes stats such as training effect and calories for `.fit` files recorded by Garmin devices. Elemntary Sync rewrites the device identifiers in `.fit` files from a Wahoo Elemnt so that Garmin Connect treats them as Garmin recordings.

## Architecture (planned pipeline)

The pipeline has three stages. Each stage hands off to the next by calling a named function:

1. **Dropbox** (`requirements/features/dropbox.md`): watch `Apps/WahooFitness` and pick up new files within one minute. Download each new file, move it to `Apps/WahooFitness/Processed`, then call `ProcessFitFile(file)`.
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
