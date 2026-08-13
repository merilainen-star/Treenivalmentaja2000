# Roadmap

Status claims here are measured, not estimated. See [PROJECT_STATUS.md](../PROJECT_STATUS.md) for
the numbers behind them.

## Completed (MVP Phase 3)
- Reminder system: AlarmManager notifications with a 7-day sliding window, `BOOT_COMPLETED`,
  `MY_PACKAGE_REPLACED` and timezone-change restoration, and notification permission checks. Uses
  inexact `setAndAllowWhileIdle` to comply with Google Play exact alarm policies.
- Room database migrations: `exportSchema = true`, both `MIGRATION_3_4` (hand-written) and the
  4 → 5 auto migration covered by instrumented tests, and no destructive fallback — a missing
  migration fails loudly rather than emptying the database.
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
- Screen state hoisting: every screen is a stateless `…Content` plus a thin ViewModel wrapper, so
  whole screens are renderable in a test and `WorkoutViewModel` finally has some.
- Test APK distribution: every push to `main` that touches code builds, verifies and republishes a
  rolling GitHub prerelease, and Settings says whether the installed build is the current one.
- Import asks where a plan lands — the file's dates, or starting today — and replaces the previous
  plan rather than leaving it behind.
- Exercises are shown as the plan wrote them: prescriptions (`3 × 12 · 17,5 kg`), per-set ramps,
  and clocks for timed movements that run once per side or per set.
- Exercise guides: tap a movement for an animation and instructions, fetched on demand and never
  stored, with attribution — [EXERCISE_GUIDE.md](EXERCISE_GUIDE.md). Two sources, because neither
  has everything: ExerciseDB animates all 1500 of its movements but has no plank, side plank,
  plain squat, bird dog or cat-cow, and wger has all of those under CC-BY-SA but pictures for only
  a third. There is no disk cache anywhere in the path, because ExerciseDB permits free personal
  use with attribution but not persistent caching.

## In Progress
- Nothing. The app is being used in real training between milestones.

## Next Milestone
1. ~~**Oura API V2 client**~~ — built. `data/oura` reads readiness, sleep, activity and workouts
   between two dates, follows `next_token` to the last page, maps the five documented status codes
   to typed already-Finnish failures, and turns what comes back into `oura_daily_summaries` and
   `oura_workouts` rows. Written against the vendored `docs/api/oura-openapi-1.37.json` and a local
   `com.sun.net.httpserver`, so it needed no credentials; on OkHttp rather than Retrofit
   ([ADR-007](DECISIONS.md#adr-007-okhttp-not-retrofit-for-the-oura-client)). It now runs against a
   real account. **What no one has checked is whether it parses that account correctly** — the
   fixtures behind its tests are derived from the specification rather than captured from Oura, so
   the remaining step is comparing a number the app displays with Oura's own app.
2. ~~**In-app OAuth2 token exchange with PKCE**~~ — built, and reachable from Settings. The
   authorization request, `state` validation, the code exchange, encrypted token storage, and the
   OkHttp `Authenticator` that renews on `401` without spending a rotated refresh token twice.
   Tokens are under an Android Keystore key rather than in `EncryptedSharedPreferences`, whose
   library is deprecated
   ([ADR-008](DECISIONS.md#adr-008-android-keystore-directly-rather-than-encryptedsharedpreferences)).
   The client id and secret are **typed into Settings**, not compiled in, so the whole setup happens
   on the phone — the previous design needed a local `.env` and therefore a PC, which is not how
   this app is installed
   ([ADR-009](DECISIONS.md#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it)).
   **A real login has been completed** from the phone, against a real Oura account.
3. ~~**WorkManager** for background biometric syncing.~~ — built. A daily periodic worker fetching
   the last five days (Oura revises a day after the fact, and an offline weekend would otherwise
   leave a permanent hole), plus a foreground sync when the Today screen opens. Scheduled only while
   Oura is connected.
4. ~~**Put a recovery reading back on the Today screen**~~ — done, and this is the first point where
   the Oura work is visible at all. The card tells four situations apart: Oura not connected (no
   indicator, exactly as before), nothing fetched yet, a day Oura answered about with no score, and
   a reading. The third is the one the whole design turns on — the ring was not worn, so the card
   says "ei tietoa" about a day that exists rather than drawing a zero.

## Later (Phase 4 & Beyond)
- Logging what was actually lifted, so a strength session can be compared with the last time it
  was done. Oura holds completed workouts but not per-set loads, so the "history lives in Oura"
  reasoning does not cover this one.
- Remote AI advisor integration (sending prompt, parsing JSON, user approval flow). See
  [INSPIRATION.md](INSPIRATION.md) for ideas worth a look if this is ever designed.
- Strava API integration for richer workout telemetry.

## Blocked
- None. The Oura milestone is built end to end and a real account is connected.

## Technical Debt
- Extract use cases from `TrainingRepository`: it still carries domain logic (lighter-version
  fallback, reschedule chaining).
- `versionCode` has never been raised from `1`, so builds cannot be told apart on a device.
- The ICS parsers in `tools/` guess plan structure from Finnish prose with regular expressions.
  They are a legacy path; write plans against [PLAN_SCHEMA.md](PLAN_SCHEMA.md) instead.
