# Privacy Policy — Treenivalmentaja

**Last updated: 10 August 2026**

Treenivalmentaja is a private, single-user Android training-companion app. It is built and installed
by its author for their own use and is not distributed through any app store. This policy describes
what the app does with data, and it is written to describe the actual implementation rather than to
reserve rights the app does not exercise. The source code is public at
<https://github.com/merilainen-star/Treenivalmentaja2000>, so every claim below can be checked.

## The short version

The app has **no server of its own**. Nothing you enter, and nothing it reads from Oura, is sent to
the author, to any analytics service, or to any third party. Data lives on your phone until you
delete it.

## What the app stores on your device

- **Your training plan** — sessions, dates, statuses, and a log of changes to them. Imported by you
  from a JSON file or the clipboard.
- **Oura data, cached** — daily readiness, sleep and activity scores, and completed workouts, for
  the days the app has fetched. Stored so the app works offline.
- **Oura access and refresh tokens, and your Oura application's Client ID and Secret** — encrypted
  with AES-256-GCM under a key held in the Android Keystore, which cannot be extracted from the
  device. These are excluded from Android cloud backup and device transfer.
- **App settings**, such as reminder times.

All of it is stored inside the app's private storage, protected by the Android application sandbox.

## What leaves your device, and where it goes

The app makes network requests to exactly four places, and to nobody else:

| Destination | What is sent | Why |
| --- | --- | --- |
| `api.ouraring.com`, `cloud.ouraring.com` | Your Oura credentials and tokens; requests for date ranges | To sign in to Oura and read your own Oura data |
| `oss.exercisedb.dev` (ExerciseDB) | The name or catalogue id of an exercise in your plan | To show an animation and instructions when you tap a movement |
| `wger.de` | The same | The same, for movements ExerciseDB does not have |
| `api.github.com` / `github.com` | Nothing about you — only a request for the latest release metadata | To tell you whether your installed build is the current one |

**No health data is ever sent to the exercise-guide sources or to GitHub.** They receive only an
exercise name such as "plank". Nothing fetched from the exercise-guide sources is stored: there is
no disk cache for it, and the in-memory cache is discarded when the app closes.

## What the app requests from Oura, and what it does not

The app asks Oura for two permission scopes only:

- **Daily** — readiness, sleep and activity scores.
- **Workout** — completed workouts, so they can be matched against planned sessions.

It does **not** request your email address, personal information (gender, age, height, weight),
heart-rate time series, tags, sessions, SpO2, ring configuration, stress, or heart-health data, even
though the Oura API offers them. Data that is not needed for scheduling training is not requested.

## What the app does not do

- No analytics, telemetry, crash reporting or advertising. There are no such SDKs in the build.
- No account, no sign-up, no user profile on any server.
- No selling, sharing, renting or transferring of data to anyone.
- No use of your data for training machine-learning models.
- No location tracking.

## Deleting your data

- **Settings → Oura → "Katkaise Oura-yhteys"** deletes the stored Oura tokens and every cached Oura
  row from the device. Your training plan is untouched.
- **"Vaihda tunnukset"** additionally deletes the stored Client ID and Secret.
- **Uninstalling the app** removes everything it has stored.

The app cannot revoke its own access at Oura, because the Oura API publishes no revocation endpoint.
To withdraw the application's access to your Oura account, remove it in your Oura account settings.

## Data held by Oura

This policy covers only what Treenivalmentaja does. The data Oura itself holds about you is governed
by Oura's own privacy policy and your account settings there.

## Children

The app is not directed at children and is used only by its author.

## Changes

This file lives in the app's public repository; its history is the change log. Material changes will
be reflected in the "last updated" date above.

## Contact

merilainen@gmail.com
