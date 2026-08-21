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

## Completed (Oura milestone)
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

## Next Milestone

The path towards an AI coach, in the order the pieces earn their keep. Decided 2026-08-15; the
reasoning for watch-telemetry-first is that running is the most taxing session type and the one
with real numbers behind it (pace, distance, time, calories, training load), while a strength
session's lone heart-rate line says little — an advisor analysing only what Oura holds would have
almost nothing to say about the sessions that matter most.

1. ~~**Watch telemetry, via intervals.icu**~~ — built. `data/intervals` reads
   `/api/v1/athlete/0/activities` between two dates and stores `intervals_activities` (schema v7).
   Runs match to planned sessions through the same use case Oura's workouts go through, and a
   matched session shows a pace line Oura cannot supply, plus intervals.icu's own training load.

   **This was Strava for about a day.** Strava paywalled its API in June 2026 — developer access
   now needs an active subscription — so that integration was removed entirely and replaced. Suunto's
   own API was ruled out first: its FAQ states access is for "companies/organizations" and that "we
   do not provide this for personal use". intervals.icu already receives the watch's activities, its
   API is free for personal use, and it authenticates with a personal API key over HTTP Basic — no
   OAuth, no callback activity, no refresh token, and one fewer exported component than before.

   **Connected to a real account and fetching**, confirmed 2026-08-15. What remains unchecked is
   whether every displayed number matches intervals.icu's own interface for the same activity, and
   two undocumented fields are read on a stated assumption — see
   [API_INTEGRATIONS.md](API_INTEGRATIONS.md#two-fields-the-specification-does-not-describe). The
   setup steps are in [INTERVALS_SETUP.md](INTERVALS_SETUP.md).
2. ~~**Readiness rule, deterministic (Phase A)**~~ — built, and no AI anywhere in it.
   `ReadinessAdviceUseCase` is a pure function of the stored Oura days and the plan: a session left
   open on a day whose readiness was below 70 raises a card the next morning offering to shift the
   programme forward or start today lighter, and a poor reading on a day with a session offers
   lightening alone. Both buttons call operations that already existed
   (`handleMissedSessions`, `applyLighterVersion`), so nothing new can happen to the plan and every
   change carries an author in the event log. A day the ring was not worn produces nothing — see
   [TRAINING_ENGINE.md](TRAINING_ENGINE.md#readiness-advice--asking-never-acting). This is the
   first point where the readiness number reaches the plan at all.
3. ~~**Easy-run drift, deterministic (Phase A′)**~~ — designed 2026-08-16, built 2026-08-21. No AI,
   and unusually for a rule here, **no engine operation either**: it changes nothing about the plan
   and so needed none. `EasyRunDriftUseCase` is a pure function of the stored sessions and what the
   watch measured for the completed ones, and `EasyRunDriftCard` is the only card in the app with
   no action button under it.

   *What it detects.* Not one hard easy run — that is a Tuesday, a hill, a headwind. Three in a row
   is a finding: base training stops being base training, and the next hard session arrives on
   tired legs.

   *The measure is `icu_intensity`, not `icu_training_load`.* Load grows with duration, so a long
   calm run scores high without being hard — it answers "what did this cost". Intensity is a
   percentage of threshold and therefore comparable across runs of different lengths; it answers
   "was this hard", which is the question.

   *The baseline is the athlete's own history, not a fixed band.* Writing "easy means under 75 % of
   threshold" would put invented physiology in the code, which this project has avoided everywhere
   else — `DailyRecovery.readinessLabel` shares Oura's own bands rather than inventing a second
   opinion. The comparison is instead against the median of the athlete's own comparable runs,
   which is self-calibrating, needs no invented number, and states a claim the person can check for
   themselves.

   *The rule, in one sentence:* the three most recent completed sessions of the same `WorkoutType`
   **and** the same planned `intensity` were each above the median intensity of all comparable
   stored runs. Fewer than six comparable runs — three judged plus three of baseline — produces
   silence, on the same discipline as the readiness rule: no measurement, no advice.

   *What it does.* A card on the morning a matching easy session is scheduled, saying that the last
   three were run harder than usual and that this one is meant to be easy. It offers no plan
   button, because there is nothing to change: the sessions in question are already done, and
   lightening a session that is supposed to be easy is incoherent. The one control puts the card
   away for the day. The useful thing is the reminder arriving before the run rather than a plan
   edit after it.

   *What it needed that already existed:* the plan's `intensity` (Plan Schema v1) and
   `CompletedRunMetrics.intensityPercent` (stored since schema v9). **Nothing new to fetch, no new
   column, no migration** — the first feature in this milestone that cost the database nothing.
   Activities synced before v9 carry no intensity and are excluded from the comparison rather than
   read as 0 %: missing is not zero, and it is not easy either.

   *Two decisions the design did not settle, taken while building it.* The comparison population is
   what the app can **classify** — completed sessions of the active plan that a stored activity was
   matched to — because a planned intensity is what makes a run comparable and only a session
   carries one. And the median is taken over that population **including the three under
   judgement**, which is the conservative direction: three drifting runs pull the median towards
   themselves and make the rule harder to satisfy, never easier.

   *Deliberately out of scope:* `icu_atl` and `icu_ctl` — acute and chronic load — answer a
   different question ("has total load outrun the plan?") and deserve their own rule rather than
   being folded into this one.

4. ~~**AI coach comments, read-only (Phase B)**~~ — built. An "AI-analyysi" button under individual
   workouts, in two flavours: what a **completed** session cost against that morning's recovery, and
   how to execute an **upcoming** one against the current trend. BYOK — the user's own Anthropic key,
   typed into Settings and stored under its own Keystore alias like the other two
   ([ADR-010](DECISIONS.md#adr-010-on-demand-ai-workout-analysis-called-directly-from-the-app-with-a-user-supplied-key)).
   It changes nothing: no button on the result touches the plan, and no analysis is stored.

   **Two corrections landed after real use**, both of the same shape — the app was confidently
   telling the model something it had not checked. The prompt was built from screen-scoped
   `StateFlow`s, so tapping the button from the Today screen sent **no recovery data at all** (only
   the Week screen collects that flow); and the fatigue figures came from the newest *activity*,
   where they are frozen and never decay, so a three-day-old run reported a TSB of −5.9 when the
   true figure was −0.6. The prompt is now built from the repositories, and load comes from
   intervals.icu's daily wellness series (schema v12) with its date named in the heading.

   **Three things came with it that outlast it.** The night's own **HRV and resting heart rate** are
   now read from Oura's sleep-periods collection and stored on the daily summary (schema v11) — a
   measurement where the readiness score is an opinion of one, and available to any future rule that
   wants a trend rather than a verdict. `icu_atl`/`icu_ctl`, stored since v10 and read by nothing,
   finally have a reader. And the **model is chosen in Settings** from three options rather than
   fixed, because which tier suits this athlete's data is an empirical question.

   The request payload is shown verbatim behind "Näytä pyyntö" (the idea came from
   [INSPIRATION.md](INSPIRATION.md)). [PRIVACY.md](PRIVACY.md) was revised in two places before this
   shipped, and [SECURITY.md](SECURITY.md) records that its old promise to send "only abstracted
   metrics" is not what was built.

   **Then it grew two more providers** — ChatGPT and Gemini beside Claude
   ([ADR-011](DECISIONS.md#adr-011-three-analysis-providers-behind-one-interface)), after the same
   real prompt was pasted into all three by hand. They returned the same substantive judgement in
   different prose, which is the finding that shaped the design: the prompt is doing the work, so it
   stays shared and the provider is taste and price. The comparison also exposed the actual defect —
   every one of them wrote something too long for a phone — so the prompt now carries a hard
   "enintään 110 sanaa" rather than "2–4 kappaletta". Gemini is used on its **paid** tier, because
   the free tier permits Google to use submitted content to improve their products and this app
   submits HRV.
5. **AI plan adjustments with approval (Phase C)** — the advisor may propose changes as
   structured operations (move session X to day Y, lighten session Z) that map onto the same
   engine operations Phase A uses. Nothing is written without the user accepting, and accepted
   changes go through the append-only session-event log like every other transition. Standing
   constraints — e.g. runs belong on weekends, because that is when there is time to run — are
   user preferences carried in the prompt, and the advisor asks its clarifying questions before
   proposing.

## Later (Phase 4 & Beyond)
- Logging what was actually lifted, so a strength session can be compared with the last time it
  was done. Oura holds completed workouts but not per-set loads, so the "history lives in Oura"
  reasoning does not cover this one.

## Blocked
- None. The Oura milestone is built end to end and a real account is connected.

## Technical Debt
- Extract use cases from `TrainingRepository`: it still carries domain logic (lighter-version
  fallback, reschedule chaining).
- `versionCode` has never been raised from `1`, so builds cannot be told apart on a device.
- The ICS parsers in `tools/` guess plan structure from Finnish prose with regular expressions.
  They are a legacy path; write plans against [PLAN_SCHEMA.md](PLAN_SCHEMA.md) instead.
