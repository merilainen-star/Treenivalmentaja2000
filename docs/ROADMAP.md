# Roadmap

## Completed (MVP Phase 3)
- Reminder system: AlarmManager notifications with a 7-day sliding window, `BOOT_COMPLETED` & timezone change restoration, and notification permission checks. (Uses inexact `setAndAllowWhileIdle` to comply with Google Play exact alarm policies).
- Room database migrations: `AppDatabase` updated to schema version 4 (`exportSchema = true`), with tested migrations (`MIGRATION_3_4`) and schema JSON exports.
- Deterministic Training Engine: `TrainingEngine` rules implemented for handling missed sessions, plan shifting, illness pause (`markSick()`), and recovery progression (`markRecovered()`).

## Completed (MVP Phase 2)
- Room database as the local source of truth (entities, DAOs, repository).
- Session state machine with a validated transition table and an append-only event log.
- Training plan JSON import (file + clipboard) against Plan Schema v1, with validation and
  duplicate detection.
- First-launch seeding through the real importer.

## Completed (MVP Phase 1)
- Initial Android project setup (Kotlin, Jetpack Compose, Gradle configurations).
- Splash screen with custom animations and logo.
- Main layout scaffold with Bottom Navigation.
- `TodayScreen` displaying current daily plan and recovery state.
- `WeekScreen` displaying the upcoming 7-day schedule.
- `SettingsScreen` for notification preferences.
- `WorkoutViewModel` utilizing static mock data.

## In Progress (MVP Phase 3)
- Oura API V2 Integration via Retrofit.

## Next Milestone (MVP Phase 3)
- In-app OAuth2 token exchange with PKCE ([ADR-006](DECISIONS.md#adr-006-no-separate-backend-in-the-mvp) — no backend).
- WorkManager integration for background biometric syncing.

## Later (Phase 4 & Beyond)
- Remote AI advisor integration (sending prompt, parsing JSON, user approval flow).
- Strava API integration for richer workout telemetry.

## Blocked
- None currently.

## Technical Debt
- Extract Use Cases from `TrainingRepository` once the engine rules land — the repository
  currently carries some domain logic (lighter-version fallback, reschedule chaining).
- Roborazzi screenshot tests were removed from the template and have not been reinstated.
