# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased] - 2026-08-05

### Added (Room persistence)
- Room database: `TrainingPlan`, `WorkoutSession`, `SessionEvent`, `OuraDailySummary` and
  `OuraWorkout` entities, their DAOs, type converters, and `AppDatabase` (schema version 1).
- `TrainingRepository` — the single entry point to training data. Enforces the session state
  machine and writes an immutable `SessionEvent` in the same transaction as every accepted
  status change.
- Rescheduling creates a new session row linked by `originalSessionId` instead of rewriting a
  date in place.
- Training plan JSON import from a file (Storage Access Framework) and from the clipboard, in the
  Settings screen. Validated against `docs/PLAN_SCHEMA.md` before anything is written, with
  per-field Finnish error messages and duplicate/conflict detection.
- First-launch seeding with a starter week, routed through the real importer.
- 41 unit tests: state transitions, event-history accumulation, JSON validation (valid, broken,
  duplicate), import conflicts, reschedule chain, and cascade delete. Room tests run in memory
  under Robolectric.
- Core library desugaring so `java.time` is usable on the declared `minSdk` 24.

### Changed (Room persistence)
- `WorkoutViewModel` observes a Room `Flow` instead of `MockData`; `MockData` removed.
- `WorkoutStatus` replaced by `domain.SessionStatus`; `WorkoutType` moved to `domain`.
- Today screen gained a "Merkitse tehdyksi" action and hides actions on closed sessions.

### Added
- Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) pinned to Gradle 9.6.1 with a
  distribution checksum. All documented commands now use `./gradlew`.
- `docs/PLAN_SCHEMA.md` — Treenivalmentaja Training Plan Schema v1 (JSON import format).
- ADR-006 "No separate backend in the MVP"; ADR-004 marked Superseded.
- `SessionEvent` entity (immutable, append-only session history) in the data model.
- `OURA_CLIENT_ID` / `OURA_CLIENT_SECRET` documented in `.env.example`.

### Changed
- Package renamed `com.example` → `fi.merilainen.treenivalmentaja`; `applicationId` changed from
  `com.aistudio.treenivalmentaja.bvcxw` to `fi.merilainen.treenivalmentaja`.
- Session state model expanded to `PLANNED`, `NOTIFIED`, `STARTED`, `COMPLETED`, `SKIPPED`,
  `RESCHEDULED`, `REPLACED_WITH_LIGHTER_VERSION`, `PAUSED_DUE_TO_ILLNESS`, `CANCELLED`
  (replacing `LIGHTER` and `MOVED`), with a normative transition table.
- Rescheduling no longer rewrites a session's date in place: the old row closes as `RESCHEDULED`
  and a new row references it via `originalSessionId`.
- `AUTHENTICATION.md`, `ARCHITECTURE.md`, `SECURITY.md`, `SETUP.md`, `README.md` updated for the
  no-backend design (in-app OAuth exchange with PKCE, secret via `BuildConfig`, tokens in
  `EncryptedSharedPreferences`).

### Added (initial scaffolding)
- Initial project scaffolding and Gradle setup.
- Basic MVVM structure with Jetpack Compose.
- Splash screen with logo and animations.
- Bottom navigation with "Tänään", "Viikko", and "Asetukset" tabs.
- `WorkoutViewModel` with mock data for workouts.
- Static UI for viewing mock training sessions.
- Icons and basic styling for different workout types (Running, Strength, Skiing).
- Comprehensive documentation skeleton in `/docs`.

### Changed
- Replaced the default app icon with a custom adaptive icon using the user-provided `Icon.png`.
- Replaced the Material Design gradient background on the Splash screen with a custom background image (`Splash_notext.png`).
- App theme and colors configured to match the requested dark blue and vibrant green aesthetic.

### Planned
- Room database integration.
- Oura API V2 data fetching.
- Notification engine via AlarmManager.
- Background sync via WorkManager.
