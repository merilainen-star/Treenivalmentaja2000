# Treenivalmentaja

## Product Summary
Treenivalmentaja is an Android application designed to manage a progressive training programme. It aims to replace calendar events with actionable Android training notifications. Users can start, complete, skip, lighten, reschedule, or pause sessions due to illness. The app will adapt the remaining programme when sessions are missed and will integrate with the Oura API V2 to retrieve recovery, sleep, activity, and workout data. A local deterministic rule engine acts as the default scheduler, with future support for an optional remote AI service for proposed plan changes. 

## Current Implementation Status
**Status:** MVP Prototype

**Implemented:**
- Basic Jetpack Compose UI (Today, Week, and Settings screens)
- Navigation graph with bottom tabs
- Room database as the local source of truth, observed by `WorkoutViewModel`
- Session state machine with an append-only event history
- Training plan JSON import (file + clipboard) with validation and duplicate detection
- App icon and Splash screen

**Planned / Missing:**
- Deterministic training engine rules (missed sessions, illness return)
- Oura API V2 integration (OAuth exchange happens in-app; no backend — see ADR-006)
- AlarmManager integration (notifications)
- WorkManager integration (background sync)
- Rule-based training engine and offline support
- Remote AI advisor

## Main Features (Planned)
- View daily and weekly training plans.
- Receive actionable training notifications.
- Adapt training plans based on recovery data and missed sessions.
- Connect to Oura to track completed workouts.

## Technology Stack
- **Language:** Kotlin
- **UI Toolkit:** Jetpack Compose
- **Architecture:** MVVM and Clean Architecture
- **Local Storage:** Room
- **JSON:** Moshi (plan import, JSON columns)
- **Background Work:** WorkManager & AlarmManager (Planned)
- **Network:** Retrofit & OkHttp (Planned)
- **Backend:** None ([ADR-006](docs/DECISIONS.md#adr-006-no-separate-backend-in-the-mvp))

## Repository Structure
- `/app/src/main/java/fi/merilainen/treenivalmentaja/` - Source code (UI, ViewModel, domain, data)
- `/docs/` - Project documentation
- `/gradlew`, `/gradle/wrapper/` - Gradle wrapper (pinned version + checksum)
- `/build.gradle.kts` - Build configuration

## Prerequisites
- JDK 17 or newer (verified on Temurin 21)
- Android SDK Platform 36.1, Build-Tools 36.0.0
- Android Studio (optional)

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
- [Notifications](docs/NOTIFICATIONS.md)
- [Security](docs/SECURITY.md)
- [Testing](docs/TESTING.md)
- [Setup](docs/SETUP.md)
- [Roadmap](docs/ROADMAP.md)
- [Decisions](docs/DECISIONS.md)
- [AI Agents Instructions](AGENTS.md)

## Known Limitations
- No Oura connection yet; the recovery card on the Today screen is a placeholder.
- No notifications and no background sync.
- The deterministic engine enforces the session state machine but does not yet shift a plan when
  sessions are missed.
