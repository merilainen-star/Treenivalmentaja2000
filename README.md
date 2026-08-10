# Treenivalmentaja

## Product Summary
Treenivalmentaja is an Android application designed to manage a progressive training programme. It aims to replace calendar events with actionable Android training notifications. Users can start, complete, skip, lighten, reschedule, or pause sessions due to illness. The app will adapt the remaining programme when sessions are missed and will integrate with the Oura API V2 to retrieve recovery, sleep, activity, and workout data. A local deterministic rule engine acts as the default scheduler, with future support for an optional remote AI service for proposed plan changes. 

## Current Implementation Status
**Status:** MVP Prototype

**Implemented:**
- Jetpack Compose UI (Today, Week and Settings) with bottom-tab navigation; a Week row expands to
  show what that session is
- Room database as the local source of truth, observed by `WorkoutViewModel`, at schema version 4
  with tested migrations
- Session state machine with an append-only event history
- Training plan JSON import (file + clipboard) with validation and duplicate detection
- Deterministic training engine: missed sessions, plan shifting, illness pause and the graduated
  return
- AlarmManager reminders with a 7-day window, restored after reboot, reinstall and timezone change
- Exercises shown as the plan wrote them, with loads, per-set ramps and clocks for timed movements
- Exercise guides: tap a movement for an animation and instructions from ExerciseDB or wger,
  fetched on demand and never stored ([docs](docs/EXERCISE_GUIDE.md))
- Rolling test APK built by GitHub Actions, with an in-app check for whether the build is current
- App icon and splash screen

**Planned / Missing:**
- Oura API V2 integration (OAuth exchange happens in-app; no backend — see ADR-006)
- WorkManager integration (background sync)
- Remote AI advisor

## Main Features
Implemented today:
- View daily and weekly training plans.
- Receive actionable training notifications.
- Adapt the plan when sessions are missed, or when training pauses for illness.

Planned:
- Adapt training based on recovery data.
- Connect to Oura to track completed workouts.

## Technology Stack
- **Language:** Kotlin
- **UI Toolkit:** Jetpack Compose
- **Architecture:** MVVM and Clean Architecture
- **Local Storage:** Room
- **JSON:** Moshi (plan import, JSON columns)
- **Background Work:** AlarmManager (implemented); WorkManager (planned)
- **Network:** Retrofit & OkHttp (planned — currently commented out in `app/build.gradle.kts`)
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
The app builds and runs with no configuration — plan management is entirely local. Connecting Oura
requires `OURA_CLIENT_ID` and `OURA_CLIENT_SECRET` in a git-ignored `.env` file; there is no
backend to set up ([ADR-006](docs/DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)).

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
- No Oura connection yet, so the app says nothing about recovery. The Today screen's card offers
  the "Sairastuin" and "Tervehdyin" buttons and no verdict.
- No background sync.
- No test taps through a screen: the captures pin what each state looks like, not what happens
  when you use it.
