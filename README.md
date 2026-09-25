# Elemntary Sync

Garmin Connect only shows stats like training effect and calories for rides recorded by a Garmin device. Elemntary Sync picks up the `.fit` files that your Wahoo Elemnt uploads to Dropbox, changes the device identifier to a Garmin device and uploads the result to Garmin Connect.

## Setup

You need Docker with Docker Compose.

1. **Create a Dropbox app** in the [Dropbox App Console](https://www.dropbox.com/developers/apps):
   - Choose **Scoped access** and **Full Dropbox**. "App folder" doesn't work, because Wahoo writes into its own app folder (`Apps/WahooFitness`).
   - Under **Permissions**, enable `files.metadata.read`, `files.content.read` and `files.content.write`, and click Submit.
   - Copy the **App key** from the Settings tab.
2. **Create your `.env`:**
   ```
   cp .env.example .env
   ```
   Set `DROPBOX_APP_KEY` to the app key.
3. **Connect Dropbox** (one time):
   ```
   docker compose run --rm elemntary-sync auth-dropbox
   ```
   Open the printed link, allow access, and paste the code back. Copy the printed `DROPBOX_REFRESH_TOKEN=...` line into `.env`.
4. **Connect Garmin Connect** (one time):
   ```
   docker compose run --rm elemntary-sync auth-garmin
   ```
   Enter your Garmin email and password, and the verification code if your account uses two-step verification. The password isn't stored. The app keeps a login token in its data volume and renews it automatically.
5. **Start:**
   ```
   docker compose up -d --build
   ```

> [!WARNING]
> On its first start, Elemntary Sync syncs **every** `.fit` file in `Apps/WahooFitness` to Garmin Connect, including all your old rides. If you don't want that, move the files you don't want synced into `Apps/WahooFitness/Processed` **before** starting it.

## How it works

- New files in `Apps/WahooFitness` are picked up within seconds.
- After a successful sync, the file is moved to `Apps/WahooFitness/Processed`.
- If a file can't be processed, it's moved to `Apps/WahooFitness/Failed` and the error shows in the logs (`docker compose logs`). To retry, move the file back to `Apps/WahooFitness`. The intermediate conversion files are kept in the `/data/failed/` volume for debugging.
- Watch what happens with `docker compose logs -f`. Every new, moved or removed `.fit` file in `Apps/WahooFitness` is logged. When nothing happens, a "Still watching" line appears once an hour.
- A ride is uploaded to Garmin Connect as recorded by a Garmin Edge 530, so Garmin calculates training effect, calories and so on. If Garmin is unreachable, the upload is retried 5 times, 30 seconds apart. A ride that is already on Garmin Connect counts as synced.

## Garmin login

Garmin has no official upload API for individuals, so Elemntary Sync uses the same unofficial login as the Garmin Connect app. It can break if Garmin changes its login. If the logs say `Garmin login expired or was revoked`, run the `auth-garmin` command again and move the rides from `Failed` back to `Apps/WahooFitness`.
