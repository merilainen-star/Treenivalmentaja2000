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
| Build | `./gradlew :app:assembleDebug` | Success — `app-debug.apk`, 20,565,936 B (19.61 MiB) |
| Unit tests | `./gradlew :app:testDebugUnitTest` | 303 tests, 0 failures, 0 errors |
| Screenshots | `./gradlew :app:verifyRoborazziDebug` | 35 comparisons, 0 changed |
| Lint | `./gradlew :app:lintDebug` | 0 errors, 40 warnings |
| Instrumented | `./gradlew :app:connectedDebugAndroidTest` | 35 tests, 0 failures, 0 errors, on `treeni-test` (AVD, Android 16) |

The whole Oura milestone so far cost **68,021 B (+0.33%)** — the client 14,801 B, and the
authentication, its Settings UI and the in-app credential fields a further 53,220 B. Those come
from stashing the work, rebuilding `assembleDebug` on this machine and measuring: 20,497,915 B
without any of it, 20,512,716 B with the client, 20,565,936 B with the authentication as well. Adding no dependency is why: OkHttp was already inside the APK, and the
token store uses platform crypto rather than a library.

The comparison is worth spelling out, because the obvious subtraction gives the wrong answer. The
line above used to record 20,266,654 B for the previous commit, which would make this change look
like +246,062 B. It is not: rebuilt here, that same source measures 20,497,915 B. The 231,261 B
difference is between the two machines or toolchains that produced the two numbers, not between the
two commits. **An APK delta is only meaningful when both sides were built on the same machine** —
which is why the earlier entry rebuilt `b57a3f5` from a clean worktree rather than quoting its old
figure, and why this one stashed rather than subtracting.

OkHttp adds nothing to that, and the claim was checked rather than assumed: the *baseline* APK's
DEX already contains `okhttp3/OkHttpClient`, extracted and searched after decompressing it — a
string search over the zipped APK finds nothing either way and proves nothing. Coil pulls OkHttp
4.12.0 in; `app/build.gradle.kts` now names the same version directly.

Instrumented tests ran this time, on the `treeni-test` AVD (Android 16), and had to: the token store
encrypts with an Android Keystore key, and there is no Keystore on the JVM. Seventeen of the
thirty-five cover it — that a round trip returns what went in, that what lands in `SharedPreferences` is not the
token, that the same token encrypts differently each time (a reused GCM nonce would break it
completely), that a tampered ciphertext fails to decrypt rather than decrypting to something else,
and that an unreadable store reads as "not connected" instead of crashing. CI still has no device
and its workflow says so rather than implying the suite ran.

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
- **Screens are functions of their state.** Each of the three is a stateless `…Content` taking
  plain values and callbacks, plus a thin wrapper that reads the ViewModel and owns the things
  only a real screen can do — the file picker, the clipboard, the notification permission. That is
  what makes a whole screen capturable, and states that are awkward to reach by hand — a rest day,
  a missing notification permission — are now baselines rather than something to remember.
- **Screenshot tests.** 35 Roborazzi baselines: all three screens whole, plus the Today and Week
  cards, a started workout's checklist, the recovery card, every status badge, both import
  dialogs, every state of the exercise-guide sheet, and every state of the Oura card — including
  the connected one, which cannot be produced on this machine at all because it needs credentials
  only the owner's Oura account can issue.
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

  **No login has ever been completed.** Everything above is tested against a local server; a real
  round trip needs a registered Oura application and a local `.env`, and that is the one step that
  cannot be done without the owner's account.

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

## Partially working

- "Kevyempi versio" applies the plan's lighter payload, or falls back to a 40% reduction. The
  wider rule engine (load balancing, stacking prevention) is not built.
- The Today screen no longer claims to know anything about recovery. The card there used to show
  a coloured indicator reading "Palautuminen: Kohtalainen" above the advice "Kevyempi versio voi
  olla järkevä" — a constant, set in two places, both to the same value, so it repeated the same
  verdict every day and nudged towards a lighter session on all of them. The indicator and the
  advice are gone; the "Sairastuin" and "Tervehdyin" buttons under them were always real and
  stay. An indicator belongs there again the day Oura can fill one in.

## Not implemented

- **A completed Oura login.** The client and the whole OAuth flow exist and are tested, but nothing
  has authenticated against the live service — that needs a registered developer application and a
  local `.env`. Until then the two Oura tables still have zero writers and stay empty.
- **Background sync.** Nothing schedules a fetch, so even a connected build would hold no rows.
  This is where WorkManager goes.
- **Anything on screen from Oura.** The Today screen still says nothing about recovery.
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

- **One real login.** Everything remaining in the Oura milestone is downstream of it, and it needs
  an Oura developer application registered against `treenivalmentaja://oauth2callback` — which only
  the owner's account can create. What it no longer needs is a PC: the credentials are pasted into
  Settings on the phone. Everything that could be built without them has been.

## Recommended next task

1. **Register the Oura developer application, then paste its credentials into Settings.** All of it
   works in a phone browser: create the application at `developer.ouraring.com` with the redirect
   URI `treenivalmentaja://oauth2callback`, then Asetukset → Oura. Scopes are `daily` and `workout`.
   With that done, "Yhdistä Oura" can be walked end to end for the first time — which is also the
   first check of whether the specification-derived fixtures match what Oura actually sends.
2. **WorkManager sync**, writing what the client returns through a repository into
   `oura_daily_summaries` and `oura_workouts`.
3. **The recovery card**, which is where any of this first becomes visible. Its design constraint
   is already known: `score` is optional, so a day the ring was not worn is a row that exists with
   no number in it, and the card has to say "ei tietoa" rather than draw a zero.

## Files most relevant to the next task

- `app/src/main/java/fi/merilainen/treenivalmentaja/data/oura/OuraApi.kt` — `OuraTokenSource` is
  the seam OAuth plugs into
- `app/src/main/java/fi/merilainen/treenivalmentaja/data/oura/OuraClient.kt`
- `app/src/main/java/fi/merilainen/treenivalmentaja/SettingsScreen.kt` — where "Yhdistä Oura" goes
- `app/src/main/java/fi/merilainen/treenivalmentaja/TodayScreen.kt` — the recovery card
- `app/src/main/java/fi/merilainen/treenivalmentaja/WorkoutViewModel.kt`
- `docs/api/oura-openapi-1.37.json` — the authorization and token URLs, and every response shape
