# Treenivalmentaja

## Product Summary
Treenivalmentaja is an Android application for managing a progressive training programme with
actionable notifications. Users can complete, skip, lighten, reschedule or pause sessions. When a
session is missed, the deterministic engine previews the resulting calendar change and applies it
only after approval. Oura and intervals.icu supply recovery and workout data; optional, on-demand
AI analysis comments on one workout but never changes the plan.

## Current Implementation Status
**Status:** MVP Prototype

**Implemented:**
- Jetpack Compose UI (Today, Week and Settings) with bottom-tab navigation; a Week row expands to
  show what that session is
- Room database as the local source of truth, observed by `WorkoutViewModel`, at schema version 12
  with tested migrations
- Session state machine with an append-only event history
- Training plan JSON import (file + clipboard) with validation and duplicate detection
- Deterministic training engine: user-approved missed-session proposals, plan shifting, illness
  pause and the graduated return
- AlarmManager reminders with a 7-day window, restored after reboot, reinstall and timezone change
- Exercises shown as the plan wrote them, with loads, per-set ramps and clocks for timed movements
- Exercise guides: tap a movement for an animation and instructions from ExerciseDB or wger,
  fetched on demand and never stored ([docs](docs/EXERCISE_GUIDE.md))
- Oura: connect from the phone, a readiness reading on Today, and what Oura recorded for a finished
  session — duration, distance, calories, heart rate — under what the plan asked for
  ([docs](docs/API_INTEGRATIONS.md))
- Intervals.icu: paste a personal API key and the Suunto watch's own recordings arrive — a matched
  run shows pace, time, distance, climb, heart rate, cadence, calories, training load and intensity,
  the telemetry Oura does not carry ([setup](docs/INTERVALS_SETUP.md))
- A morning question when readiness was poor and a session went undone: shift the programme, or
  start lighter. Asked from a measurement, never acted on by itself
  ([docs](docs/TRAINING_ENGINE.md#readiness-advice--asking-never-acting))
- A note on the morning of an easy session when the last three comparable easy ones were each run
  harder than this athlete's own easy sessions usually are. It has no button: the runs it reports on
  are done, and lightening a session meant to be light changes nothing
  ([docs](docs/TRAINING_ENGINE.md#easy-run-drift--a-fact-with-no-button-under-it))
- Optional read-only AI analysis from Anthropic, OpenAI or Google, requested per workout with the
  user's own API key; the exact prompt is visible and no analysis is stored
- A scrollable calendar rather than a fixed week, and a daily background sync (WorkManager)
- Rolling test APK built by GitHub Actions, with an in-app check for whether the build is current
- App icon and splash screen

**Planned / Missing:**
- AI-proposed plan changes with explicit approval. Read-only workout analysis is already built;
  autonomous plan modification is not — see [ROADMAP.md](docs/ROADMAP.md#next-milestone).
- Acting on readiness beyond the morning question below — chronic load, multi-week adjustment,
  and anything needing judgement rather than a rule.

## Main Features
Implemented today:
- View daily and weekly training plans.
- Receive actionable training notifications.
- Adapt the plan when sessions are missed, or when training pauses for illness.

Planned:
- Adapt training based on recovery data. The reading is on screen; nothing yet acts on it, and what
  a number is allowed to change is a training decision rather than a parsing one.

## Technology Stack
- **Language:** Kotlin
- **UI Toolkit:** Jetpack Compose
- **Architecture:** MVVM and Clean Architecture
- **Local Storage:** Room
- **JSON:** Moshi (plan import, JSON columns)
- **Background Work:** AlarmManager for reminders; WorkManager for the daily Oura sync
- **Network:** OkHttp for the Oura client ([ADR-007](docs/DECISIONS.md#adr-007-okhttp-not-retrofit-for-the-oura-client)); plain `HttpURLConnection` for the update check and the exercise guides
- **Screenshot tests:** Roborazzi on Robolectric (JVM, no device needed)
- **Backend:** None ([ADR-006](docs/DECISIONS.md#adr-006-no-separate-backend-in-the-mvp))

## Repository Structure
- `/app/src/main/java/fi/merilainen/treenivalmentaja/` - Source code (UI, ViewModel, domain, data)
- `/docs/` - Project documentation
- `/gradlew`, `/gradle/wrapper/` - Gradle wrapper (pinned version + checksum)
- `/build.gradle.kts` - Build configuration

## Prerequisites
- JDK 17 or newer (verified on Temurin 21)
- Android SDK Platform 36.1, Build-Tools 36.1.0
- `minSdk` 26 (Android 8.0)
- Android Studio (optional)

## Install the latest test APK

No PC, cable or ADB needed — open this on the phone and accept *Install* / *Update*:

**<https://github.com/merilainen-star/Treenivalmentaja2000/releases/download/test-build/Treenivalmentaja-test.apk>**

The link is permanent and always serves the newest successful build.
[Release page](https://github.com/merilainen-star/Treenivalmentaja2000/releases/tag/test-build) ·
[Actions](https://github.com/merilainen-star/Treenivalmentaja2000/actions) (press *Run workflow* to
build on demand, works from a phone).

GitHub Actions rebuilds on every push to `main` that touches code, and publishes only when the
build, unit tests, screenshot comparisons and lint all pass. See
[SETUP.md](docs/SETUP.md#7-installing-a-test-build-on-the-phone) for what to do if Android refuses
an update.

## Quick-Start Commands
Always use the wrapper:
- **Build app:** `./gradlew assembleDebug`
- **Run tests:** `./gradlew :app:testDebugUnitTest`
- **Screenshot tests:** `./gradlew :app:verifyRoborazziDebug`

## Configuration Overview
The app builds and runs with no configuration — plan management is entirely local, and there is no
backend to set up ([ADR-006](docs/DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)).

Connecting Oura needs an application registered in Oura's developer portal, and its Client ID and
Secret are **typed into the app's Settings screen**, not compiled in
([ADR-009](docs/DECISIONS.md#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it)).
The whole setup happens on the phone; see [SETUP.md](docs/SETUP.md#4-oura-developer-application-setup).
A local build may still supply them through a git-ignored `.env`, which is used only when nothing
has been entered in the app.

## Links to Detailed Documentation
- [Architecture](docs/ARCHITECTURE.md)
- [Product Requirements](docs/PRODUCT_REQUIREMENTS.md)
- [Data Model](docs/DATA_MODEL.md)
- [Training Plan JSON Schema v1](docs/PLAN_SCHEMA.md)
- [API Integrations](docs/API_INTEGRATIONS.md)
- [Authentication](docs/AUTHENTICATION.md)
- [Training Engine](docs/TRAINING_ENGINE.md)
- [Exercise Guide](docs/EXERCISE_GUIDE.md)
- [Notifications](docs/NOTIFICATIONS.md)
- [Security](docs/SECURITY.md)
- [Testing](docs/TESTING.md)
- [Setup](docs/SETUP.md)
- [Roadmap](docs/ROADMAP.md)
- [Decisions](docs/DECISIONS.md)
- [AI Agents Instructions](AGENTS.md)

## Known Limitations
- The readiness rule asks about one day at a time and knows nothing about chronic load. It fires on
  yesterday's missed session or this morning's low score, and offers only what the app could
  already be asked to do by hand.
- The easy-run drift note compares against the sessions of the **active plan** that a stored
  activity was matched to, because a planned intensity is what makes a session comparable and only a
  session carries one. A fresh plan therefore starts the count again, and fewer than six comparable
  sessions means silence rather than a weaker claim.
- Workouts recorded on another device and synced into Oura do not arrive through its workout
  collection, so they appear only in the day's scores. Measured, not assumed —
  [API_INTEGRATIONS.md](docs/API_INTEGRATIONS.md). This is what the intervals.icu integration exists
  to work around: a watch-tracked run reaches the app from there instead.
- Intervals.icu is connected and fetching against a real account, but **no field has been checked
  one by one** against what intervals.icu's own interface shows for the same activity. Two fields
  are documented nowhere and are read on a stated assumption — see
  [API_INTEGRATIONS.md](docs/API_INTEGRATIONS.md#two-fields-the-specification-does-not-describe).
- Oura publishes a workout to the API some time after the app shows it, so today's session may
  appear only later.
- No test taps through a screen: the captures pin what each state looks like, not what happens
  when you use it.
