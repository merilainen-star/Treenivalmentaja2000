# Privacy Policy — Treenivalmentaja

**Last updated: 15 August 2026** *(revised: the app can now also connect to **Strava** and read
your activities from it — see "What the app requests from Strava" below. Previously revised on 10
August 2026 to add the Oura `heartrate` scope.)*

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
- **Strava data, cached** — your activities for the days the app has fetched: sport, start time,
  moving and elapsed time, distance, heart rate and elevation gain. Stored so the app works
  offline.
- **Oura and Strava access and refresh tokens, and the Client ID and Secret of each application
  you registered** — encrypted with AES-256-GCM under keys held in the Android Keystore, which
  cannot be extracted from the device. Each service has its own file and its own key. All of these
  are excluded from Android cloud backup and device transfer.
- **App settings**, such as reminder times.

All of it is stored inside the app's private storage, protected by the Android application sandbox.

## What leaves your device, and where it goes

The app makes network requests to exactly five places, and to nobody else:

| Destination | What is sent | Why |
| --- | --- | --- |
| `api.ouraring.com`, `cloud.ouraring.com` | Your Oura credentials and tokens; requests for date ranges | To sign in to Oura and read your own Oura data |
| `www.strava.com` | Your Strava credentials and tokens; requests for date ranges | To sign in to Strava and read your own activities |
| `oss.exercisedb.dev` (ExerciseDB) | The name or catalogue id of an exercise in your plan | To show an animation and instructions when you tap a movement |
| `wger.de` | The same | The same, for movements ExerciseDB does not have |
| `api.github.com` / `github.com` | Nothing about you — only a request for the latest release metadata | To tell you whether your installed build is the current one |

**No health data is ever sent to the exercise-guide sources or to GitHub.** They receive only an
exercise name such as "plank". Nothing fetched from the exercise-guide sources is stored: there is
no disk cache for it, and the in-memory cache is discarded when the app closes.

## What the app requests from Oura, and what it does not

The app asks Oura for three permission scopes:

- **Daily** — readiness, sleep and activity scores.
- **Workout** — completed workouts, so they can be matched against planned sessions.
- **Heartrate** — the heart-rate time series, used **only** to compute an average and a maximum for
  a workout you actually did. Oura provides no heart rate on a workout itself, so there is no other
  way to show one. Samples are reduced to those two numbers, stored against that workout, and the
  series itself is not kept.

It does **not** request your email address, personal information (gender, age, height, weight),
tags, sessions, SpO2, ring configuration, stress, or heart-health data, even though the Oura API
offers them. Data that is not needed for scheduling training is not requested.

Adding a scope requires you to authorise it again: an existing connection keeps the permissions it
was granted with until you disconnect and reconnect.

## What the app requests from Strava, and what it does not

The app asks Strava for **one** scope:

- **`activity:read_all`** — your own activities, private ones included. `read_all` rather than
  `read` because private runs are the common case, and the narrower scope would silently return an
  empty training history.

It requests **no write scope of any kind** and never posts, edits or deletes anything on Strava. It
does not request `profile:read_all` or `profile:write`. Nothing about other athletes, clubs or
segments is read.

If you untick the activity permission on Strava's consent screen, the app refuses the resulting
connection and says so, rather than appearing to work and never showing a run.

## What the app does not do

- No analytics, telemetry, crash reporting or advertising. There are no such SDKs in the build.
- No account, no sign-up, no user profile on any server.
- No selling, sharing, renting or transferring of data to anyone.
- No use of your data for training machine-learning models.
- No location tracking.

## Deleting your data

- **Settings → Oura → "Katkaise Oura-yhteys"** deletes the stored Oura tokens and every cached Oura
  row from the device. Your training plan is untouched.
- **Settings → Strava → "Katkaise Strava-yhteys"** does the same for Strava.
- **"Vaihda tunnukset"** under either service additionally deletes that service's stored Client ID
  and Secret.
- **Uninstalling the app** removes everything it has stored.

The app cannot revoke its own access at Oura, because the Oura API publishes no revocation endpoint.
To withdraw the application's access to your Oura account, remove it in your Oura account settings.

Strava *does* publish a deauthorize endpoint, and the app deliberately does not call it: it would
revoke the whole application, and a failed network call would leave the app unsure whether it is
still authorized. Disconnecting gives up access locally; to revoke the application itself, remove
it under *My Apps* in your Strava settings.

## Data held by Oura and Strava

This policy covers only what Treenivalmentaja does. The data Oura and Strava themselves hold about
you is governed by their own privacy policies and your account settings there.

## Children

The app is not directed at children and is used only by its author.

## Changes

This file lives in the app's public repository; its history is the change log. Material changes will
be reflected in the "last updated" date above.

## Contact

merilainen@gmail.com
