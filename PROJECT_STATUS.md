# Project status

## Last verified build

Every number below was measured on this commit, not carried over from a previous report.

- Date: 2026-08-10
- Git commit: working tree on top of `81e8263` (repository connected to
  https://github.com/merilainen-star/Treenivalmentaja2000)
- Toolchain: Temurin JDK 21.0.12, Gradle 9.6.1 via wrapper, Android SDK Platform 36.1,
  Build-Tools 36.1.0. `minSdk` 26, `targetSdk` 36.

| Check | Command | Result |
| --- | --- | --- |
| Build | `./gradlew clean :app:assembleDebug` | Success — `app-debug.apk`, 20,744,843 B (19.78 MiB) |
| Unit tests | `./gradlew :app:testDebugUnitTest` | 419 tests, 0 failures, 0 errors |
| Screenshots | `./gradlew :app:verifyRoborazziDebug` | 46 comparisons, 0 changed |
| Lint | `./gradlew :app:lintDebug` | 0 errors, 41 warnings |
| Instrumented | `./gradlew :app:connectedDebugAndroidTest` | 38 tests, 0 failures, 0 errors, on `treeni-test` (AVD, Android 16) |

Replacing the Strava client with the intervals.icu one **shrank** the APK by 16,472 B — 20,761,315 B
before, 20,744,843 B after — which is the clearest measure of how much smaller a personal API key is
than an OAuth flow: gone are the authorization-URL builder, the redirect parser, the token
exchange and refresh service, the `Authenticator`, the connection state machine's five states and
an exported callback activity, replaced by one Basic-auth header.

The Strava client and the readiness rule together had cost **98,472 B (+0.48%)** over the build
before them: 20,662,843 B to 20,761,315 B. No new dependency in either direction — OkHttp, Moshi
and Room were already in the APK and the key store uses platform crypto rather than a library, so
every figure here is this app's own code.

Before that, the whole Oura milestone cost **164,928 B (+0.80%)**: 20,497,915 B
without any of it, 20,662,843 B with all of it. The week view, the unmatched-workout list and the
resume fix added nothing measurable at all — the same 20,646,459 B as before them — and the
diagnostics screen 16,384 B on top. Its one new dependency is WorkManager, which is most
of that; the API client, the authentication and the token store added none, because OkHttp was
already inside the APK via Coil and the store uses platform crypto rather than a library.

**Measure APK size with `clean`, or do not measure it.** This entry previously blamed a 231,261 B
discrepancy on "the two machines or toolchains that produced the two numbers". That was probably
wrong. Measured here on one machine, the *same source* built incrementally and cleanly differs by
**260,082 B** — an incremental build produced 20,890,157 B where a clean one produced 20,630,075 B.
Incremental dexing is the likelier explanation for the old gap too. Every figure in this section now
comes from `./gradlew clean :app:assembleDebug`, and the last stage's own cost, measured that way on
both sides, is 16,384 B rather than the 267,138 B an incremental comparison first suggested.

`clean` needs the daemon stopped first (`./gradlew --stop`): lint holds jars open under
`app/build/intermediates/lint-cache` and the delete fails otherwise.

Instrumented tests ran on the `treeni-test` AVD (Android 16), and had to: the token store encrypts
with an Android Keystore key, and there is no Keystore on the JVM. Seventeen of the thirty-six
cover it — that a round trip returns what went in, that what lands in `SharedPreferences` is not the
token, that the same token encrypts differently each time (a reused GCM nonce would break it
completely), that a tampered ciphertext fails to decrypt rather than decrypting to something else,
and that an unreadable store reads as "not connected" instead of crashing. CI still has no device
and its workflow says so rather than implying the suite ran.

The 41 lint warnings are all non-functional: 39 are dependency-update notices — one of them for
the WorkManager added in this change, which is left at 2.10.0 rather than chased to 2.11.2, as the
other 38 are — one is a `RedundantLabel`, and one is an `ObsoleteSdkInt` that must stay.

Backend deployment: N/A by design ([ADR-006](docs/DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)).
`assembleDebug` needs a local `debug.keystore` at the repository root; it is git-ignored, see
[SETUP.md](docs/SETUP.md).

## Working

- **UI shell.** Bottom navigation, splash screen, Today, Week and Settings.
- **Room persistence.** `TrainingPlan`, `WorkoutSession`, `SessionEvent`, `OuraDailySummary`,
  `OuraWorkout` and `IntervalsActivity` entities, DAOs and `AppDatabase` at schema version 7.
  `WorkoutViewModel` observes a Room `Flow`.
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
- **Intervals.icu.** `data/intervals` reads `/api/v1/athlete/0/activities` between two dates and
  stores `intervals_activities`. HTTP Basic with a personal API key typed into Settings and held
  under its own Keystore alias — no OAuth, no callback activity, no refresh token, and no exported
  component. Runs match to planned sessions through the same use case Oura's workouts go through,
  and a matched session shows pace, calories and intervals.icu's own training load, none of which
  Oura carries. The sync is idempotent on the service's activity id. 50 unit tests against a local
  `com.sun.net.httpserver`, including a Robolectric pass over the whole path from HTTP response to
  an observable row. **No real account has been connected yet** — what is verified is the client,
  not that intervals.icu's answers match the shapes it expects.
  This replaced a Strava integration that lived for one day: Strava paywalled its API in June 2026.
  Suunto's own API was ruled out first — its FAQ restricts access to organisations and says
  outright that personal use is not provided for.
- **Readiness advice.** `ReadinessAdviceUseCase` turns a poor readiness reading into a question on
  the Today screen — shift the programme, or start today lighter — and never into an action. Both
  buttons call operations that already existed, and a day the ring was not worn produces no card
  at all. 15 unit tests, most of them about mornings that must stay quiet.
- **Reminders.** AlarmManager with a 7-day sliding window and a re-arm alarm, restored on
  `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and `TIMEZONE_CHANGED`, with a notification-permission
  check in Settings.
- **Room migrations.** `exportSchema = true`, schemas committed under `app/schemas/`, and
  `MIGRATION_3_4` (hand-written) plus the 4 → 5, 5 → 6 and 6 → 7 auto migrations each covered by an
  instrumented test that runs it against a populated database of the older version. 6 → 7 is the
  first that drops a table, declared with a `@DeleteTable` spec so Room knows the removal is
  deliberate rather than a rename. There is deliberately **no**
  `fallbackToDestructiveMigration`: a missing migration now fails loudly instead of silently
  emptying the database.
- **A scrollable calendar rather than a fixed week.** It opens on today and runs four weeks either
  way, further when the plan does, so a session from last week can be looked up with what Oura
  recorded for it. Outside the current week a day earns a row by having something on it. Tapping a
  row unrolls the session's content beneath it.

  The headings carry the real weekday and date. They were positional before — row three was always
  "Keskiviikko" — correct only in a week that began on a Monday and quietly wrong on every other
  day, which is the kind of error nobody finds by looking at a seven-row list.
- **Screens are functions of their state.** Each of the three is a stateless `…Content` taking
  plain values and callbacks, plus a thin wrapper that reads the ViewModel and owns the things
  only a real screen can do — the file picker, the clipboard, the notification permission. That is
  what makes a whole screen capturable, and states that are awkward to reach by hand — a rest day,
  a missing notification permission — are now baselines rather than something to remember.
- **Screenshot tests.** 46 Roborazzi baselines: all three screens whole, plus the Today and Week
  cards, a started workout's checklist, the recovery card, every status badge, both import
  dialogs, every state of the exercise-guide sheet, every state of the Oura card, and a session
  showing what Oura recorded for it — several of which cannot be produced on this machine at all,
  needing a connected account, a ring worn through a session, and Oura to have processed it.
  They run on the JVM, no device needed. The comparison tolerates 0.5% of
  changed pixels, because Windows and Linux antialias text differently — measured at 0.046%, with
  no pixel differing by more than 32/255. A 2dp shadow change still fails them.
- **Exercises shown as the plan wrote them.** Each movement displays its prescription —
  `3 × 12 · 17,5 kg`, `10 / puoli`, `30 s` — and a ramp lists every set. Timed movements carry a
  clock driven by `durationSec`, run once per side or per set, so a per-side hold asks for both
  sides instead of leaving the second to be counted in your head. The clock lives in the started
  workout alone: the read-only lists show what a hold asks for, not a button to start one for a
  session two days out.
- **Exercise guides, from two sources.** Tapping a movement opens a sheet with an animation or
  picture, numbered instructions, target muscles and equipment. ExerciseDB has an animation for
  every one of its 1500 movements but no plank, side plank, plain squat, bird dog or cat-cow at
  all; wger has those, under CC-BY-SA, though only a third of its movements carry a picture. A
  plan pins each movement to whichever source has it, and a reference is resolved by the provider
  it names — never by the other one. Nothing either returns is stored:
  the image loader is given no disk cache at all, nothing goes in Room, and the only cache is a
  64-entry map that dies with the process. wger's licences would permit more, but one image loader
  serves both sources and ExerciseDB's terms rule it out, so the stricter rule wins.

  Without a reference the app searches every source at once and offers what comes back as a
  suggestion that has to be picked, after filtering out everything whose name does not contain
  each word of the query — ExerciseDB invents rather than misses, answering `cat cow` with "cable
  squat row", so that filter is what keeps the suggestion honest. One source being down does not
  hide the other's answer. Every failure state (offline, 429, 5xx, not found, no match) is a
  normal reading of the sheet and leaves the session fully usable. Credit is carried per guide,
  because wger's images each name their own author. See
  [EXERCISE_GUIDE.md](docs/EXERCISE_GUIDE.md).

  Walked on the emulator (`treeni-test`, API 36) against both live services, with a plan imported
  through the real file picker: an ExerciseDB reference resolved to "barbell bench press" with its
  animation playing; a wger reference resolved "Sivulankku" to Side Plank, instructions intact and
  no empty picture box where it has none; another resolved "Bulgarialainen askelkyykky" with its
  picture and the credit line naming the image's author; "Plank" offered five suggestions, all of
  them actually planks; picking one kept the `Ehdotus` warning; and a movement hit a real `503`
  and drew the retry state, which retried. `cacheDir` held nothing but itself afterwards.

  A started workout runs the list in order: the clock belongs to the movement you are on, its
  last round ticks that movement off, and the rest stay locked until you get there. Walking back
  is one step at a time. Reachable from the read-only list and from a started workout's checklist
  alike — the two draw
  the same movements from the same place, so nothing the plan knows is lost the moment you tap
  "Aloita ohjattu treeni".

  Reachable only for sessions whose plan carries an `exercises` array. The seeded starter week
  does not — it describes its movements in prose — so a fresh install has nothing to tap until a
  plan is imported. `sample-data/plan.json` carries references for 248 of its 272 exercise rows
  and is the file to import to see the feature working.
- **Import asks where the plan lands.** Keep the file's dates, or move the whole plan so day one
  is today.
- **Correcting a plan costs nothing; replacing one asks first.** Re-importing the same programme
  with sessions corrected updates them in place — statuses, the event log and reschedule chains all
  survive, because nothing is deleted and so nothing cascades. Anything that would genuinely
  discard stored rows (a different `plan.id`, or the same one with sessions dropped) is offered as
  a replacement that names what it would destroy. Neither happens without an explicit yes; a new
  `plan.id` used to wipe the database silently. A replacement still deletes rather than
  deactivates, so the database does not grow and a superseded programme cannot keep sending its
  own reminders.
- **Update check.** Settings says whether the installed build is the one GitHub Actions last
  published, and offers the download when it is not.
- **Test APK distribution from GitHub Actions.** Every push to `main` that touches code builds,
  verifies and republishes one rolling prerelease at a permanent URL, signed with the same
  `debug.keystore` as local builds so it updates the phone in place. Installing a test build needs
  no PC, cable or adb — see [SETUP.md](docs/SETUP.md#7-installing-a-test-build-on-the-phone).
  Verified end to end: the published APK reports package `fi.merilainen.treenivalmentaja`,
  `versionName 1.0-e4dfc2a`, and certificate SHA-256 `ED:64:98:…:10:C0`, matching the local key.
- **An Oura API client that can read the API but has nothing to read it with.** `data/oura` asks
  for readiness, sleep, activity and workouts between two dates, follows `next_token` to the last
  page, and hands back rows for `oura_daily_summaries` and `oura_workouts`. All four collections
  are one request shape in the specification, so they are one paged fetch here rather than four
  endpoints. Every documented status code has its own type carrying an already-Finnish message and
  a `canRetry` flag — including `403`, which is not a failure to retry but the user's Oura
  subscription having ended, and is the odd one out among them. A missing score is carried as a
  missing score the whole way: Oura returns a *document with no `score`* for a day the ring was not
  worn, and that becomes a row that exists with a `null` in it, never a zero.

  Built on OkHttp rather than the Retrofit the roadmap used to promise
  ([ADR-007](docs/DECISIONS.md#adr-007-okhttp-not-retrofit-for-the-oura-client)) — it is already in
  the APK via Coil, and it is what the token renewal in `docs/AUTHENTICATION.md` is designed
  around. 30 unit tests against a `com.sun.net.httpserver`, the same way the guide providers are
  tested.

  It has never spoken to Oura: the fixtures under `src/test/resources/oura/` were derived from the
  vendored specification, which contains no response examples at all, rather than captured from the
  service the way the guide fixtures were. They prove the client obeys the specification. Only a
  token proves the specification obeys Oura.
- **Connecting Oura, from Settings, without a computer.** The card asks for the Client ID and
  Client Secret of an application registered in Oura's developer portal, stores them encrypted, and
  then "Yhdistä Oura" opens the system browser at Oura's authorization page; the redirect comes back
  to an exported activity that acts on nothing itself.

  **Typed rather than compiled in, and that is the whole point.** The original design put the secret
  in `BuildConfig` from a git-ignored `.env`, which quietly meant a PC, a checkout and a local
  build — while this app is installed by opening a GitHub release link on the phone, from a CI build
  that has no `.env`. The feature could never have connected on the only build its owner runs. The
  obvious simpler route, a single pasted personal access token, is not available: Oura withdrew
  those in December 2025, and the vendored specification is stale in still declaring `BearerAuth`.
  See [ADR-009](docs/DECISIONS.md#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it).
  A side effect worth naming: the published APK now carries no Oura secret at all.

  **PKCE with S256**, and the `code_verifier` is written to disk — encrypted — before the URL is
  handed out. Android may kill the process while a browser is in front of it, and a verifier lost
  that way turns a completed login into a failed exchange. The `state` is compared *before* the
  authorization code is read, so a redirect carrying a plausible code but the wrong state never
  reaches the token endpoint at all.

  **Tokens under an Android Keystore key**, AES-256-GCM, in ordinary `SharedPreferences` —
  not `EncryptedSharedPreferences`, which both ADR-006 and `AUTHENTICATION.md` specified before
  that library was deprecated in April 2025 and stopped receiving fixes
  ([ADR-008](docs/DECISIONS.md#adr-008-android-keystore-directly-rather-than-encryptedsharedpreferences)).
  The key cannot leave the device, the IV is fresh per encryption, and the tokens are excluded from
  backup and device transfer because the key does not travel with either. Anything undecryptable
  reads as "not connected" rather than crashing.

  **Renewal is an OkHttp `Authenticator`.** A `401` refreshes once and retries. Two requests failing
  at the same moment produce one refresh, not two: Oura rotates refresh tokens, so the second would
  spend an already-invalidated one and log the user out for being busy. A caller that lost that race
  notices and simply retries with the token that is now stored.

  **A real login has now been completed**, against a real Oura account, from the phone. That is
  what proves the client id and secret, the redirect URI, the PKCE exchange and the encrypted store
  all work together — none of which a local server can establish. What is still unproven is the
  *data*: no number the app displays has been compared with what Oura's own app shows for the same
  day.

  The instrumented tests found a fault the unit tests could not. `OuraTokenStore.clear()` emptied
  the whole preferences file, so disconnecting silently took the client credentials with it — while
  the in-memory fake the unit tests run against kept them, so the unit test asserting exactly that
  passed. A fake agreeing with the code it stands in for is not evidence, and this is what a real
  store on a real device is for. Fixed to remove named keys, and covered on the device.

  What *was* walked on the emulator (`treeni-test`, Android 16), because it needs no credentials: a
  forged redirect fired straight at the exported activity with
  `am start -a android.intent.action.VIEW -d "treenivalmentaja://oauth2callback?code=attacker-code&state=forged"`.
  The app did not crash, the process survived, the callback activity finished and returned to the
  screen behind it, and Settings showed the refusal rather than any sign of an exchange. That walk
  also found a real fault: on a build with **no** credentials the stray redirect left the card
  offering "Yritä uudelleen" for a connection that cannot be attempted at all. Fixed — such a
  redirect now leaves the card saying the build has no credentials — and covered by a test.

- **A recovery reading on Today, with a measurement behind it.** The indicator removed for being a
  constant is back, showing today's Oura readiness and a word for it, with sleep and activity beside
  it when they exist. Four states, and telling them apart is the design: Oura not connected shows no
  indicator at all; a day nothing has been fetched for says so; a day Oura answered about with **no
  score** says "ei tietoa"; and a reading shows the number. The third is what the whole thing turns
  on — the ring was not worn, and a zero would read as a verdict rather than as an absence. The word
  describes the score and never what to do about it.
- **What actually happened, under what was planned** — on the Today card and in the week list
  alike. A session Oura recorded shows its real duration, distance, calories and heart rate beneath
  the plan's own line; in the week they sit in the collapsed header, because scanning for what was
  done is the reason to open that screen. Either screen refreshes from Oura when it appears. Only
  what exists is drawn:
  a strength session has no distance, a ring on the charger has no heart rate, and a row of dashes
  standing in for them would be worse than their absence.

  **The pairing is same day, nearest in time, one-to-one, and the activity has to fit**
  (`MatchOuraWorkoutsUseCase`), with a twelve-hour limit so a midnight walk cannot claim a morning
  session.

  The activity check was left out at first, on the argument that `activity` is free-form and a rule
  that silently drops a workout is worse than one that occasionally mispairs. Real data reversed it:
  a fortnight held eleven `walking` entries against five `strengthTraining`, so the nearest workout
  to a 09:00 strength session was almost always a walk, and a 1.8 km stroll was displayed as that
  morning's strength training. Comparison strips case and punctuation, because Oura writes
  `strengthTraining` where its own prose writes `strength_training`. An activity with no mapping —
  `houseWork` is a real returned value — matches nothing, and is listed under its own day instead.

  **Heart rate is not a field Oura returns on a workout.** There is none on the object at all, so the
  average and maximum are reduced from the `heartrate` time series over the workout's own window,
  which is why the app now requests that scope. One request spans every workout in a sync rather
  than one per workout, and a failure to get it — the commonest being a connection granted before the
  scope existed — leaves the workouts stored without a heart rate instead of failing the sync.
- **Two real findings, both from the diagnostics screen on its first use.** Oura's collections
  disagree about whether `end_date` includes that day — readiness and sleep returned today, workouts
  and activity stopped at yesterday — so the client now asks one day beyond the range it was given.
  And a workout imported into Oura from Strava does **not** appear in the workout collection at all,
  which contradicts what `docs/API_INTEGRATIONS.md` claimed; a run visible in Oura's own app was
  absent from a request that returned a walk from the same day. That one has no fix here: a session
  recorded on a watch and synced through Strava will not show as completed in this app.
- **The app can say what Oura returned.** Settings → Oura → "Tarkista Oura-data" runs the same
  requests a sync runs, writes nothing, and reports the count from each collection plus one line per
  workout. Built after a real dead end: a strength session was in Oura's own app and not in this
  one, and from the outside "the API did not return it", "parsing dropped it" and "it was stored and
  not drawn" were indistinguishable. Each collection is caught separately, so one failing does not
  hide the others' answers.
- **Nothing fetched is invisible.** Oura workouts that no planned session claims are listed under
  "Muu Ourassa kirjattu liikunta" rather than dropped, because a workout the matcher could not place
  and a workout never fetched look identical from the screen otherwise. Both screens refresh on
  resume rather than only when first composed — an app left open in the background used to keep
  showing what was true when it was opened.
- **The Oura tables have a writer.** `OuraRepository` fetches a date range, maps it and writes it;
  nothing else touches those tables. The screens observe Room and never the network, so a failed
  sync leaves the last known reading on screen rather than an error. A daily WorkManager job and a
  fetch when Today opens both reach back several days, because Oura revises a day once the night is
  processed and an offline weekend would otherwise leave a permanent hole. The worker exists only
  while Oura is connected.

## Partially working

- "Kevyempi versio" applies the plan's lighter payload, or falls back to a 40% reduction. The
  wider rule engine (load balancing, stacking prevention) is not built.
- The recovery card shows a reading but gives no advice. The number is real now, and what to *do*
  about it is still the user's call: nothing connects readiness to "kevyempi versio" or to the
  training engine. That link is the next honest step, and it is deliberately not guessed at — the
  card this replaced was removed precisely for offering advice with nothing behind it.

## Not implemented

- **Anything that acts on a recovery reading.** The number is on screen and nothing uses it: no
  rule connects readiness to "kevyempi versio" or to the training engine. That link is a training
  decision, and the card this replaced was removed precisely for giving advice with nothing behind
  it — so it wants deciding before it is coded.
- **Workouts recorded elsewhere.** A run tracked on a watch and synced into Oura through Strava
  never reaches the workout collection, so it cannot appear as a completed session here. Measured,
  not assumed — see [API_INTEGRATIONS.md](docs/API_INTEGRATIONS.md). Getting those would mean a
  Strava integration of our own.
- **A workout Oura has not published yet.** Oura's app shows a session before its API does, so
  today's may only arrive later. Nothing on this side fixes that.
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

- None. A real Oura account is connected, the milestone is built end to end, and the readings have
  been checked by hand against Oura's own app and matched — readiness and sleep on 2026-08-10, and a
  strength session's duration and calories on 2026-08-11.

## Recommended next task

1. **Decide whether a heart rate this thin should be shown at all.** A strength session Oura itself
   marked "heart rate data unavailable" still displays "syke 75 (max 81)", computed from background
   samples that happened to fall in the window. Requiring five samples was not enough. Either the
   bar goes up, or the samples must cover some share of the session — both risk dropping real
   readings, which is why it is a decision rather than a tweak.
2. **Decide what a reading is allowed to do.** The card shows readiness and stops there on purpose.
   Connecting it to "kevyempi versio" or to the training engine is a training decision, not a
   parsing one — and the card this replaced was removed for giving advice with nothing behind it,
   so the rule wants writing down before it is coded.
3. **Watch the pairing over a real week.** It now requires the activity to fit, which fixed walks
   being shown as strength training, but it has still never met a day with two sessions of the same
   kind, or a session done far from its planned hour.

## Files most relevant to the next task

- `app/src/main/java/fi/merilainen/treenivalmentaja/data/oura/OuraMappers.kt` — where the
  heart-rate sample threshold lives
- `app/src/main/java/fi/merilainen/treenivalmentaja/domain/MatchOuraWorkoutsUseCase.kt` — the
  pairing rule, and the activity mapping it now requires
- `app/src/main/java/fi/merilainen/treenivalmentaja/TodayScreen.kt` — the recovery card and the
  completed-session line
- `app/src/main/java/fi/merilainen/treenivalmentaja/WeekScreen.kt` — the calendar
- `app/src/main/java/fi/merilainen/treenivalmentaja/OuraCard.kt` — the connection and the
  diagnostics that answered two questions the specification could not
- `docs/api/oura-openapi-1.37.json` — every response shape, and stale in two places this repository
  now records: the `end_date` boundary and third-party imports
