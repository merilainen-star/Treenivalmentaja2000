# Project status

## Last verified build

Every number below was measured on this commit, not carried over from a previous report.

- Date: 2026-08-08
- Git commit: `0de2d05` (repository connected to
  https://github.com/merilainen-star/Treenivalmentaja2000)
- Toolchain: Temurin JDK 21.0.12, Gradle 9.6.1 via wrapper, Android SDK Platform 36.1,
  Build-Tools 36.1.0. `minSdk` 26, `targetSdk` 36.

| Check | Command | Result |
| --- | --- | --- |
| Build | `./gradlew :app:assembleDebug` | Success — `app-debug.apk`, 19.5 MB |
| Unit tests | `./gradlew :app:testDebugUnitTest` | 72 tests, 0 failures, 0 errors |
| Screenshots | `./gradlew :app:verifyRoborazziDebug` | 10 comparisons, no diffs |
| Lint | `./gradlew :app:lintDebug` | 0 errors, 40 warnings |
| Instrumented | `./gradlew :app:connectedDebugAndroidTest` | 9 tests, 0 failures, 0 errors |

The 40 lint warnings are all non-functional: 38 are dependency-update notices, one is a
`RedundantLabel`, and one is an `ObsoleteSdkInt` that must stay — see below.

Backend deployment: N/A by design ([ADR-006](docs/DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)).
`assembleDebug` needs a local `debug.keystore` at the repository root; it is git-ignored, see
[SETUP.md](docs/SETUP.md).

## Working

- **UI shell.** Bottom navigation, splash screen, Today, Week and Settings.
- **Room persistence.** `TrainingPlan`, `WorkoutSession`, `SessionEvent`, `OuraDailySummary`,
  `OuraWorkout` entities, DAOs and `AppDatabase` at schema version 4. `WorkoutViewModel` observes
  a Room `Flow`.
- **Session state machine.** All nine statuses with a validated transition table; a forbidden
  transition is rejected and writes nothing.
- **Append-only history.** Every accepted transition writes a `SessionEvent` in the same
  transaction as the session update.
- **Rescheduling as a chain.** Moving a session closes the old row as `RESCHEDULED` and inserts a
  new one carrying `originalSessionId`; a date is never rewritten in place.
- **Plan JSON import** from a file and the clipboard, validated against
  [PLAN_SCHEMA.md](docs/PLAN_SCHEMA.md) with Finnish per-field errors and conflict detection.
- **First-launch seeding** with a starter week, routed through the real importer.
- **Deterministic training engine.** `TrainingEngine` handles missed sessions, plan shifting,
  illness pause (`markSick`) and the graduated return (`markRecovered`), wired to the Today
  screen's buttons and run at startup.
- **Reminders.** AlarmManager with a 7-day sliding window and a re-arm alarm, restored on
  `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and `TIMEZONE_CHANGED`, with a notification-permission
  check in Settings.
- **Room migrations.** `exportSchema = true`, schemas committed under `app/schemas/`,
  `MIGRATION_3_4` covered by an instrumented test. There is deliberately **no**
  `fallbackToDestructiveMigration`: a missing migration now fails loudly instead of silently
  emptying the database.
- **Expandable week rows.** Tapping a row in the Week view unrolls the session's content beneath
  it.
- **Screenshot tests.** 10 Roborazzi baselines over the Today and Week cards, the recovery card
  and every status badge. They run on the JVM, no device needed.

## Partially working

- "Kevyempi versio" applies the plan's lighter payload, or falls back to a 40% reduction. The
  wider rule engine (load balancing, stacking prevention) is not built.
- The recovery card on the Today screen is a **fixed placeholder** — it always reads
  "Kohtalainen". Nothing feeds it, and nothing will until Oura data arrives.

## Not implemented

- Oura connection and API integration. The two Oura tables exist in the database and have **zero
  callers**; there is no network layer, and `retrofit` / `okhttp` are commented out in
  `app/build.gradle.kts`.
- In-app OAuth2 token exchange with PKCE.
- WorkManager background sync.
- AI advisor. `metadata.json` declares `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API`, but there is no
  code behind it.

## Known constraints worth remembering

- `mipmap-anydpi-v26` keeps its version qualifier even though `minSdk` is 26 and lint reports
  `ObsoleteSdkInt`. Renaming it to plain `mipmap-anydpi` makes the resource merger drop the folder
  silently and the build fails with "resource mipmap/ic_launcher not found". Measured; the reason
  is a comment in both XML files.
- `versionCode` is still `1` and has never been raised, so an installed build cannot be told apart
  from another by its version.

## Current blockers

- None. Registering an Oura developer application and creating a local `.env` requires the
  owner's account and is the one step that cannot be done for them.

## Recommended next task

1. **Split each screen into a stateless `…Content(state, callbacks)` and a thin stateful wrapper.**
   `TodayScreen`, `WeekScreen` and `SettingsScreen` each take a `WorkoutViewModel`, so no test can
   render a whole screen and `WorkoutViewModel` has no tests at all. The split closes both gaps
   and makes every later UI change verifiable.
2. **Then Oura**, in this order: Retrofit client and DTOs against `MockWebServer`; OAuth2 + PKCE
   per [AUTHENTICATION.md](docs/AUTHENTICATION.md); WorkManager sync; and finally feed readiness
   into the recovery card, which is the first point where any of it becomes visible.

## Files most relevant to the next task

- `app/src/main/java/fi/merilainen/treenivalmentaja/TodayScreen.kt`
- `app/src/main/java/fi/merilainen/treenivalmentaja/WeekScreen.kt`
- `app/src/main/java/fi/merilainen/treenivalmentaja/SettingsScreen.kt`
- `app/src/main/java/fi/merilainen/treenivalmentaja/WorkoutViewModel.kt`
- `app/src/test/java/fi/merilainen/treenivalmentaja/ui/ComponentScreenshotTest.kt`
