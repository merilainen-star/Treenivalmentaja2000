# Roadmap

Status claims here are measured, not estimated. See [PROJECT_STATUS.md](../PROJECT_STATUS.md) for
the numbers behind them.

## Completed (MVP Phase 3)
- Reminder system: AlarmManager notifications with a 7-day sliding window, `BOOT_COMPLETED`,
  `MY_PACKAGE_REPLACED` and timezone-change restoration, and notification permission checks. Uses
  inexact `setAndAllowWhileIdle` to comply with Google Play exact alarm policies.
- Room database migrations: schema version 4 with `exportSchema = true`, `MIGRATION_3_4` covered
  by an instrumented test, and no destructive fallback — a missing migration fails loudly rather
  than emptying the database.
- Deterministic Training Engine: missed-session handling, plan shifting, illness pause
  (`markSick`) and the graduated return (`markRecovered`), wired into the Today screen.

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
- `TodayScreen`, `WeekScreen` and `SettingsScreen`.

## Completed (after Phase 3)
- Test APK distribution: every push to `main` that touches code builds, verifies and republishes a
  rolling GitHub prerelease, and Settings says whether the installed build is the current one.
- Import asks where a plan lands — the file's dates, or starting today — and replaces the previous
  plan rather than leaving it behind.
- Exercises are shown as the plan wrote them: prescriptions (`3 × 12 · 17,5 kg`), per-set ramps,
  and clocks for timed movements that run once per side or per set.

## In Progress
- Nothing. The app is being used in real training between milestones.

## Next Milestone
1. **Exercise guides** — tap a movement, see an animation and instructions. Fully specified in
   [EXERCISE_GUIDE.md](EXERCISE_GUIDE.md), including the ExerciseDB terms that constrain it: free
   personal use is permitted with attribution, persistent caching is not.
2. **Screen state hoisting.** Split each screen into a stateless `…Content(state, callbacks)` and
   a thin stateful wrapper. Today the screens take a `WorkoutViewModel` directly, so nothing can
   render one in a test and `WorkoutViewModel` has no tests at all.
3. **Oura API V2 integration** via Retrofit, developed against `MockWebServer` so it needs no
   credentials until the end.
4. **In-app OAuth2 token exchange with PKCE**
   ([ADR-006](DECISIONS.md#adr-006-no-separate-backend-in-the-mvp) — no backend), tokens in
   `EncryptedSharedPreferences`.
5. **WorkManager** for background biometric syncing.
6. **Feed readiness into the recovery card**, which is a fixed placeholder today and is the first
   point at which the Oura work becomes visible to the user.

## Later (Phase 4 & Beyond)
- Logging what was actually lifted, so a strength session can be compared with the last time it
  was done. Oura holds completed workouts but not per-set loads, so the "history lives in Oura"
  reasoning does not cover this one.
- Remote AI advisor integration (sending prompt, parsing JSON, user approval flow).
- Strava API integration for richer workout telemetry.

## Blocked
- None. Registering the Oura developer application and creating a local `.env` needs the owner's
  account; everything else in the Oura milestone can be built and tested without it.

## Technical Debt
- Extract use cases from `TrainingRepository`: it still carries domain logic (lighter-version
  fallback, reschedule chaining).
- `versionCode` has never been raised from `1`, so builds cannot be told apart on a device.
- The ICS parsers in `tools/` guess plan structure from Finnish prose with regular expressions.
  They are a legacy path; write plans against [PLAN_SCHEMA.md](PLAN_SCHEMA.md) instead.
