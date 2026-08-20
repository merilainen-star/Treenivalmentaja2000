# Project status

## Last verified build

Every number here was measured from the current working tree; test counts are not duplicated in
other documentation.

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

**Read `--rerun` and a cleared `app/build/test-results` as part of the method, not as decoration.**
`org.gradle.caching=true` means a plain re-run can report `testDebugUnitTest FROM-CACHE` and execute
nothing, and a run interrupted partway leaves XML files that look like a complete pass. Both
happened while taking the numbers above. Check that the task line reads `> Task
:app:testDebugUnitTest` with no `FROM-CACHE` or `UP-TO-DATE` suffix before quoting a count, and
check Gradle's own last line rather than the shell's exit code — a run that printed `BUILD FAILED`
here still exited 0.

Twice during this session `:app:testDebugUnitTest` failed with `NoSuchFileException:
…/binary/in-progress-results-generic.bin` after every test had already run and been written. It
recurred both with and without `--no-daemon`, so the cause is not established; deleting
`app/build/test-results` before the run has cleared it each time.

## Current implementation

- Room is the offline source of truth at schema version 12. Plans include an IANA timezone.
- The ViewModel's current date changes at plan-zone midnight and refreshes on screen resume; sync
  windows, workout matching and missed-session classification use the same plan timezone.
- Missed sessions produce a visible, read-only proposal on Today. One missed workout proposes the
  next rest day; several propose shifting all open sessions. Only explicit acceptance writes Room,
  rejecting writes nothing, and an accepted/stale proposal cannot be applied twice. Rejecting is
  remembered for the rest of the plan-zone day, so the card does not return on the next resume of
  the screen the app opens on.
- Oura OAuth, intervals.icu activity sync and optional Anthropic/OpenAI/Google workout analysis are
  implemented. AI analysis is requested manually, stored only in memory and never edits the plan.
- Credential fields do not enter saved-instance state, and are cleared once the key is stored —
  only on success, so a failed write leaves the key in the field to retry. Each service keeps its
  own preferences file and Keystore alias while sharing one AES-GCM implementation. Failed secure
  writes are visible and never produce a connected/configured state.
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
- Existing lint warnings and Kotlin/Java deprecations remain non-blocking maintenance work.

## Measurement history

**This section is append-only, and deleting it is not how a stale number gets fixed.** It was
removed once, on 2026-08-20, as the cure for the "Last verified build" block above having gone six
commits out of date. That was the wrong cure: the complaint was that the numbers were old, and the
fix for an old measurement is a new measurement. What went with it was the reasoning behind the
numbers — including the one methodological finding on this page that cost a full afternoon to
establish, and that anyone measuring an APK here needs before they start. Restored below,
unchanged, as it was written when each figure was taken.


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
