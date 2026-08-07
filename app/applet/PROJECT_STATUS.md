# Project status

## Last verified build

- Date: 2026-08-05
- Git commit: see `git log -1` (repository connected to
  https://github.com/merilainen-star/Treenivalmentaja)
- Toolchain: Temurin JDK 21.0.12, Gradle 9.6.1 via wrapper, Android SDK Platform 36.1,
  Build-Tools 36.0.0
- Android build: Success — `./gradlew assembleDebug`
  (`app/build/outputs/apk/debug/app-debug.apk`, package `fi.merilainen.treenivalmentaja`)
- Lint: Success — `./gradlew :app:lintDebug`
- Tests: Success — `./gradlew :app:testDebugUnitTest`, 41 tests, 0 failures
- Backend deployment: N/A by design — the MVP has no backend
  ([ADR-006](docs/DECISIONS.md#adr-006-no-separate-backend-in-the-mvp))
- Note: `assembleDebug` requires a local `debug.keystore` at the repository root. It is
  git-ignored; see [SETUP.md](docs/SETUP.md) for the one-line `keytool` command.

## Working

- UI Shell (Bottom Navigation, Splash screen, Today, Week, Settings views)
- Custom adaptive icon and splash screen background
- Gradle wrapper (`./gradlew`), pinned to 9.6.1 with a distribution checksum
- Package `fi.merilainen.treenivalmentaja` (namespace + applicationId)
- **Room persistence.** `TrainingPlan`, `WorkoutSession`, `SessionEvent`, `OuraDailySummary`,
  `OuraWorkout` entities + DAOs + `AppDatabase`. `WorkoutViewModel` observes a Room `Flow`;
  `MockData` is gone.
- **Session state machine.** All nine statuses with a validated transition table. Forbidden
  transitions are rejected and write nothing.
- **Append-only session history.** Every accepted transition writes a `SessionEvent` in the same
  transaction as the session update.
- **Rescheduling as a chain.** Moving a session closes the old row (`RESCHEDULED`) and inserts a
  new one carrying `originalSessionId`; dates are never rewritten in place.
- **Plan JSON import** from a file and from the clipboard, validated against
  [PLAN_SCHEMA.md](docs/PLAN_SCHEMA.md) before anything is written, with Finnish per-field errors
  and duplicate/conflict detection.
- **First-launch seeding** with a starter week, so a fresh install does not open on a blank screen.
  The seed goes through the real importer, so it is validated against the published schema.

## Partially working

- "Kevyempi versio" applies the plan's lighter payload, or falls back to a 40% reduction. The
  wider rule engine (load balancing, stacking prevention) is not built.
- Recovery state on the Today screen is still a fixed placeholder — no Oura data feeds it.

## Not implemented

- Oura connection & API integration (in-app OAuth token exchange, syncing data)
- AlarmManager notifications
- WorkManager background sync
- Rule-based deterministic training engine (missed-session handling, plan shifting)
- Illness return week
- Room migrations (schema is at version 1; the first schema change needs a migration)
- AI advisor

## Current blockers

- None

## Recommended next task

- Implement the deterministic rule engine on top of the now-persistent state machine: missed
  session handling, plan shifting, and the illness pause / return-to-training progression
  described in [TRAINING_ENGINE.md](docs/TRAINING_ENGINE.md). The state machine, the event log,
  and the reschedule chain it needs are already in place.
- After that, AlarmManager notifications — `WorkoutSession.scheduledAtUtc` is already stored as an
  absolute instant for exactly this purpose.

## Files most relevant to the next task

- `app/src/main/java/fi/merilainen/treenivalmentaja/data/repository/TrainingRepository.kt`
  (transition, reschedule, and lighter-version entry points)
- `app/src/main/java/fi/merilainen/treenivalmentaja/domain/SessionStatus.kt` (transition table)
- `app/src/main/java/fi/merilainen/treenivalmentaja/data/local/dao/Daos.kt`
- `docs/TRAINING_ENGINE.md` (binding rules)

## Nykyinen tila
- **AlarmManager notifications.** Reminder system implemented with 7-day sliding window, `BOOT_COMPLETED` / timezone restore, and permission checks.
- **Rule-based deterministic training engine.** `TrainingEngine` handles missed sessions, plan shifting, and illness pause/recovery progression.
- **Room migrations.** Schema version 4 with `exportSchema = true` and `MIGRATION_3_4`.
