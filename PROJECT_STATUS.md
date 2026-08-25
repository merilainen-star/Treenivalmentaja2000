# Project status

## Last verified build

Every number here was measured from the current working tree; test counts are not duplicated in
other documentation.

- Date: 2026-08-24
- Base commit: working tree for the UI redesign remainder on top of `14bef1c`
- Toolchain: JDK 21 (Microsoft build 21.0.12+8), Gradle 9.6.1 via wrapper, Android SDK platform 36.1
  and build-tools 36.1.0, on Windows 11
- Emulator: `treeni-test`, android-36 google_apis x86_64, headless

All five ran in one invocation with `--rerun-tasks` and a cleared `app/build/test-results`, after
a full `recordRoborazziDebug`. No task line carried `FROM-CACHE` or `UP-TO-DATE`.

| Check | Command | Measured result |
| --- | --- | --- |
| Unit tests | `./gradlew :app:testDebugUnitTest --rerun-tasks` | 656 tests, 0 failures, 0 errors, 0 skipped |
| Screenshots | `./gradlew :app:verifyRoborazziDebug --rerun-tasks` | 65 comparisons, 0 changed, 65 unchanged |
| Lint | `./gradlew :app:lintDebug --rerun-tasks` → `lint-results-debug.xml` | 0 errors, 43 warnings |
| Debug APK | `./gradlew :app:assembleDebug --rerun-tasks` | 21,122,079 bytes |
| Instrumented | `./gradlew :app:connectedDebugAndroidTest --rerun-tasks` | 53 tests, 0 failures, 0 errors, 0 skipped |

The suite counts include the update-notice work that landed in `14bef1c` as well as this change's
own additions: four for `rowIndexForDate`, four for the stored workout position, four for restoring
and clearing it through the ViewModel, two for the upcoming-movement horizon, and three screenshot
cases.

**Five of the six defects in this change were caught by looking at the recorded images, not by a
check passing.** A running card said "Liikkeet 2" because `parseStrengthDescription` counts commas,
and a `FilledTonalButton` came out lilac because neither colour scheme ever defined
`secondaryContainer` — Material 3 fills a missing role from its own baseline palette.
`verifyRoborazziDebug` can catch neither: it compares against a baseline recorded from the same
code. **It prevents a baseline changing by accident; it does not prevent recording the wrong thing
on purpose.** Blue actions on a red error container, an empty progress bar drawn full-width in the
colour of completion, and a "next movement" card listing the movement being performed were all
found the same way — the last two on a run whose five checks were green. Recording is not
verification, and the step that does the verifying is a person opening the PNGs.

Two of those were caused by the colour-role fix itself. Defining `secondaryContainer` changed every
component that takes it by default, and `LinearProgressIndicator` takes its **track** from it: an
empty bar was drawn full-width in green. Filling a gap in a palette is not a local change.

The three colour roles the scheme was missing (`secondaryContainer`, `errorContainer` and their
`on-` pairs) had been absent since the Material 3 redesign landed. Nothing had used them, so nothing
had shown it. Any future component that reaches for a role this scheme does not define will draw
itself from the baseline palette and look like it belongs to a different app.

The APK is 21,122,079 B against the CI-built 21,105,711 B for `14bef1c` — one 16 KiB alignment page
over the base, less the 16 B by which local builds have measured under CI on every comparison so
far. The base was not rebuilt locally, so the delta is read from that consistent offset rather than
measured directly.

## Current implementation

- Room is the offline source of truth at schema version 13. Plans include an IANA timezone;
  exercises may name equipment and sessions may define a round rest.
- The ViewModel's current date changes at plan-zone midnight and refreshes on screen resume; sync
  windows, workout matching and missed-session classification use the same plan timezone.
- Missed sessions produce a visible, read-only proposal on Today. One missed workout proposes the
  next rest day; several propose shifting all open sessions. Only explicit acceptance writes Room,
  rejecting writes nothing, and an accepted/stale proposal cannot be applied twice. Rejecting is
  remembered for the rest of the plan-zone day, so the card does not return on the next resume of
  the screen the app opens on.
- Oura OAuth, intervals.icu activity sync and optional Anthropic/OpenAI/Google workout analysis are
  implemented. Read-only analysis never edits the plan. The separate Phase C advisor can return one
  clarification or a strictly parsed MOVE/LIGHTEN preview; only explicit approval applies the whole
  validated list atomically and records `AI_ADVISOR` events.
- Active Workout Mode guides structured strength sessions through equipment preparation, movement,
  rest and round-break phases, keeps the screen awake, and stores skips, duration, RPE and feel in
  the completion event payload.
- Two deterministic rules read the stored measurements and speak on the Today screen. The readiness
  rule offers to shift the programme or lighten a session; the easy-run drift rule offers nothing at
  all — it reports that the last three comparable easy sessions each exceeded the median intensity
  of this athlete's own comparable sessions, and has no plan-changing button because there is
  nothing to change. Both stay silent without a measurement behind them.
- Credential fields do not enter saved-instance state, and are cleared once the key is stored —
  only on success, so a failed write leaves the key in the field to retry. Each service keeps its
  own preferences file and Keystore alias while sharing one AES-GCM implementation. Failed secure
  writes are visible and never produce a connected/configured state.
- The colour scheme is a preference: vaalea, tumma, or järjestelmä, kept in the `settings`
  DataStore under `theme_preference` and read in `MainActivity` so it covers the splash and the
  system bars as well as the screens. It defaults to following the phone, which is what the app did
  before the preference existed. Material You dynamic hues are deliberately disabled so the
  Electric Blue semantic palette is stable across devices.
- The app updates itself. Settings compares the installed build against the rolling test release
  and, when they differ, downloads the APK straight into an Android `PackageInstaller` session over
  HTTPS — no browser, no `DownloadManager`, no Downloads folder and no storage permission. The
  SHA-256 is computed while the bytes are written and checked against the release's `apkSha256`
  along with the byte count; a mismatch abandons the session before anything is committed. Android's
  own install confirmation is required (`USER_ACTION_REQUIRED`) and the status callback lands in a
  non-exported receiver, never in the exported `MainActivity`.
- After an update the app posts a notice on its own channel and opens when tapped. It does **not**
  relaunch itself: installing kills the process being replaced, and Android blocks background
  activity starts, including from an `ACTION_MY_PACKAGE_REPLACED` receiver. One tap is what the
  platform allows.
- Android backup and device transfer are disabled for the whole app. Defence-in-depth XML rules
  separately exclude Room, DataStore and all credential files. Room is not SQLCipher-encrypted; the
  accepted boundary is Android private storage plus backup opt-out for this single-user build.

## Open risks

- Instrumented Keystore, migration and image-cache tests still need an attached Android device or
  emulator before release.
- The Oura flow is unit-tested against local HTTP fixtures but still depends on a real Oura account
  for end-to-end confirmation.
- A rooted or unlocked device can bypass the Android application sandbox; no at-rest database
  encryption is provided for that threat model.
- **The in-app update was performed end to end on the owner's phone on 2026-08-24**: permission
  prompt, in-app download with progress, digest check and Android's install confirmation, with no
  APK left in the Downloads folder. What that run did **not** cover is the post-update notice, which
  was written afterwards — it is proven on an emulator, not yet on the phone, and the first build
  carrying it is the one that will demonstrate it.
- Existing lint warnings and Kotlin/Java deprecations remain non-blocking maintenance work.

## Measurement history

**This section is append-only, and deleting it is not how a stale number gets fixed.** It was
removed once, on 2026-08-20, as the cure for the "Last verified build" block above having gone six
commits out of date. That was the wrong cure: the complaint was that the numbers were old, and the
fix for an old measurement is a new measurement. What went with it was the reasoning behind the
numbers — including the one methodological finding on this page that cost a full afternoon to
establish, and that anyone measuring an APK here needs before they start. Restored below,
unchanged, as it was written when each figure was taken.

### 2026-08-24 — the advisor fix, the skip accounting and the theme (measured on `1fcaa36`)

- Date: 2026-08-24
- Base commit: working tree for this change on top of `d9508d4`
- Toolchain: JDK 21 (Microsoft build 21.0.12+8), Gradle 9.6.1 via wrapper, Android SDK platform 36.1
  and build-tools 36.1.0, on Windows 11
- Emulator: `treeni-test`, android-36 google_apis x86_64, headless

All five ran in one invocation with `--rerun-tasks` and a cleared `app/build/test-results`, so every
number below was executed rather than replayed: Gradle reported `91 actionable tasks: 91 executed`
and no task line carried `FROM-CACHE` or `UP-TO-DATE`.

| Check | Command | Measured result |
| --- | --- | --- |
| Unit tests | `./gradlew :app:testDebugUnitTest --rerun-tasks` | 614 tests, 0 failures, 0 errors, 0 skipped |
| Screenshots | `./gradlew :app:verifyRoborazziDebug --rerun-tasks` | 60 comparisons, 0 changed, 60 unchanged |
| Lint | `./gradlew :app:lintDebug --rerun-tasks` → `lint-results-debug.xml` | 0 errors, 43 warnings |
| Debug APK | `./gradlew :app:assembleDebug --rerun-tasks` | 21,089,263 bytes |
| Instrumented | `./gradlew :app:connectedDebugAndroidTest --rerun-tasks` | 44 tests, 0 failures, 0 errors, 0 skipped |

The 43 warnings are the same non-functional families as before — dependency-update notices, two `UseKtx`
suggestions on the API-key stores, one `RedundantLabel` and one `ObsoleteSdkInt` that must stay.
None is in the code added here. CI reports **45** warnings for the same commit; the two extra are
dependency-update notices, which depend on what versions the build environment can see from the
network rather than on anything in the repository. Both environments report 0 errors.

**The instrumented run is the one that had been outstanding.** Schema 13 is the first migration this
branch adds, and `MigrationTest.migrate12To13` can only prove anything on a device — it ran here and
passed, alongside the other 43 instrumented cases. The APK is byte-identical to the measurement taken
before the skip-accounting change, which is what a pure refactor of already-compiled call sites
should produce.

**Read `--rerun` and a cleared `app/build/test-results` as part of the method, not as decoration.**
`org.gradle.caching=true` means a plain re-run can report `testDebugUnitTest FROM-CACHE` and execute
nothing, and a run interrupted partway leaves XML files that look like a complete pass. Both
happened while taking the 2026-08-20 numbers. Check that the task line reads `> Task
:app:testDebugUnitTest` with no `FROM-CACHE` or `UP-TO-DATE` suffix before quoting a count, and
check Gradle's own last line rather than the shell's exit code — a run that printed `BUILD FAILED`
here still exited 0.

Twice during the 2026-08-20 session `:app:testDebugUnitTest` failed with `NoSuchFileException:
…/binary/in-progress-results-generic.bin` after every test had already run and been written. It
recurred both with and without `--no-daemon`, so the cause is not established; deleting
`app/build/test-results` before the run has cleared it each time.

### 2026-08-24 — Material 3, Active Workout Mode and AI Phase C

The clean debug APK is **21,072,879 B**. A fresh detached worktree at the exact base `1fcaa36`,
built in the same sitting with the same copied debug keystore, is **20,941,783 B**. The combined
change is therefore **+131,096 B (+0.626%)**. No new runtime dependency was added; the delta is app
code, Room schema metadata and resources.

The verified JVM suite is 604 tests and the Roborazzi bank is 58 comparisons. Lint reports 0 errors
and 43 warnings, none in the new files. Instrumented tests were not run because `adb devices -l`
listed no device or emulator; schema 12→13, Keystore and image-cache coverage therefore still need
an attached Android target before release.


**2026-08-21.** The easy-run drift rule cost **16,384 B (+0.078 %)** — 20,909,015 B before and
20,925,399 B after, both from `./gradlew clean :app:assembleDebug` on one machine. One dex page, the
same quantum the diagnostics screen came to, and unsurprising: the whole feature is a pure function,
a card, no new dependency, no new column and nothing new fetched.

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

### 2026-08-23 — the theme preference

**It costs nothing measurable.** Built from fresh `git worktree`s with their own empty `build/`
directories, on one machine, in one sitting: the merge base `2f79a9e` builds **20,941,783 B** and
the same tree with the theme preference builds **20,941,783 B**. The two APKs differ (`sha256`
`c175228a…` and `0bf3edc0…`); their sizes do not.

**That is the 16 KiB quantum, and it is worth writing down before someone quotes a page as a
cost.** Measured here, `9cb8e49` builds 20,925,399 B, and *either* feature added on top of it —
the easy-run drift rule (`2f79a9e`) or this one — builds 20,941,783 B, as does the tree carrying
both. Each real cost is a few KB of dex; the APK reports them only when their sum crosses an
alignment page. An earlier draft of this entry read the 16,384 B between `9cb8e49` and this branch
as "what the theme preference cost". It was a page boundary that the drift rule had already
crossed, and the corrected figure is the one above.

**Do not read across machines, including the two dates below.** The 2026-08-21 block reports
`2f79a9e` at 20,925,399 B; this machine builds that same commit at 20,941,783 B — one page more.
20,925,399 B is also exactly what this machine builds `9cb8e49` at, which is a coincidence of the
quantum and not an agreement about anything. Only the same-machine, same-sitting pair at the top of
this entry is a delta. The 21,219,444 B from 2026-08-20 stays non-comparable for the reason that
block already gives: it was not taken with `clean`.

The theme preference adds three unit tests and two screenshot baselines; with main's easy-run drift
rule merged in, the counts stand at 589 and 54. No new dependency: one enum, one DataStore class
reusing the `settings` file three other stores already share, and one card.

**The instrumented suite was not run for this change.** There is no device or emulator in the
environment it was written in, so `MigrationTest`, `OuraTokenStoreTest` and the rest are unverified
here; nothing in this change touches Room, the Keystore or the image loader they cover.

**2026-08-21.** The block that stood before this one, kept because a measurement is history rather
than a status line:

- Date: 2026-08-21
- Base commit: `3e2ee7b` plus the easy-run drift rule, committed together
- Toolchain: JDK 21, Gradle 9.6.1 via wrapper

| Check | Command | Measured result |
| --- | --- | --- |
| Unit tests | `./gradlew :app:testDebugUnitTest --rerun` | 564 tests, 0 failures, 0 errors, 0 skipped |
| Screenshots | `./gradlew :app:verifyRoborazziDebug --rerun-tasks` | 52 comparisons, 0 changed, 52 unchanged |
| Lint | `./gradlew :app:lintDebug` → `lint-results-debug.xml` | 0 errors, 44 warnings |
| Debug APK | `./gradlew clean :app:assembleDebug` | 20,925,399 bytes |
| Instrumented | `adb devices -l` | Not run: no device or emulator attached |

Its 44 lint warnings were 40 dependency-update notices, two `UseKtx` suggestions on the two API-key
stores, one `RedundantLabel` and one `ObsoleteSdkInt` that must stay; none in the code added there,
and the count moved from 43 because upstream releases keep arriving.

Its APK note, kept verbatim: "The previous block's 21,219,444 B is **not** comparable with the
figure above: it was not taken with `clean`, and this page's own methodology says such a number
should never have been quoted. Measured the way the method requires — `./gradlew clean
:app:assembleDebug`, both sides, same machine — the parent commit `3e2ee7b` builds 20,909,015 B and
this change builds 20,925,399 B."

**2026-08-20.** The block that stood before that one:

- Date: 2026-08-20
- Base commit: `65d0f5a` plus the audit fixes and their follow-up review, committed together
- Toolchain: JDK 21, Gradle 9.6.1 via wrapper

| Check | Command | Measured result |
| --- | --- | --- |
| Unit tests | `./gradlew :app:testDebugUnitTest --rerun` | 539 tests, 0 failures, 0 errors, 0 skipped |
| Screenshots | `./gradlew :app:verifyRoborazziDebug` | 51 comparisons, 0 changed, 51 unchanged |
| Lint | `./gradlew :app:lintDebug` → `lint-results-debug.xml` | 0 errors, 43 warnings |
| Debug APK | `./gradlew :app:assembleDebug` | 21,219,444 bytes |
| Instrumented | `adb devices -l` | Not run: no device or emulator attached |

Backend deployment: N/A by design ([ADR-006](docs/DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)).
`assembleDebug` needs a local `debug.keystore` at the repository root; it is git-ignored, see
[SETUP.md](docs/SETUP.md).
