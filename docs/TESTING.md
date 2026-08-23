# Testing

## Overview
The testing strategy ensures the deterministic training engine works flawlessly and the UI reacts properly to state changes.

## Current Test Coverage

Run these checks for the current measured result; counts are recorded in one place only,
[PROJECT_STATUS.md](../PROJECT_STATUS.md), after a complete verification run:

- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:verifyRoborazziDebug`
- `./gradlew :app:connectedDebugAndroidTest` (when a device or emulator is available)

| Suite | Covers |
| --- | --- |
| `domain/SessionStatusTest` | The normative transition table: terminal statuses, forbidden moves, no self-transitions. |
| `domain/TrainingEngineTest` | Illness pause, gradual return after recovery, one missed session moving to the next rest day, several missed sessions shifting the plan. |
| `domain/ResolveReminderUseCaseTest`, `domain/RescheduleAlarmsUseCaseTest`, `data/alarm/ReminderSchedulerTest` | Reminder resolution from settings, the 7-day alarm window, the REARM alarm. |
| `data/importer/PlanValidatorTest` | Plan Schema v1: a valid document, unparseable text, missing/invalid fields, sessions with no content, duplicate session ids and week numbers, content hashing, and the `setPlan` and `guide` rules. |
| `data/guide/ExerciseDbProviderTest` | The ExerciseDB responses, parsed from bodies captured off the live service, plus the statuses (404, 429, a Cloudflare 503 whose body is not JSON, an unreachable host) driven by a throwaway JDK `HttpServer`, and the filter that drops the fuzzy matcher's inventions. |
| `data/guide/WgerProviderTest` | wger's payloads, captured from the live API, including one movement with nine pictures and one with none — two thirds of its movements are the second kind. Also the HTML-to-lines stripping and the per-image credit line, and that `search` makes no request at all. |
| `domain/LoadExerciseGuideUseCaseTest` | Every state of the guide sheet: a reference resolved by the provider it names and not by the other, a reference not found never falling back to a name search, hits pooled from both sources, one source failing without hiding the other, suggestions, no match, retryable failures not being cached, and the in-memory cache being consulted. |
| `data/repository/TrainingRepositoryTest` | Real Room schema in memory: import, event-history accumulation, rejected transitions writing nothing, lighter-version payload and fallback, reschedule chain, duplicate/conflict detection, seeding, cascade delete. |
| `ExercisePrescriptionTest`, `ExerciseTimerRoundsTest` | How a prescription reads (`3 × 10 · 18 kg`, a ramp set by set) and how many times a timed movement's clock runs. |
| `data/update/UpdateInfoParsingTest`, `domain/CheckForUpdateUseCaseTest` | The published build metadata, parsed through the real Moshi configuration, and the comparison against the installed version. |
| `WorkoutViewModelTest` | The guide sheet's states (loading, loaded, retry after a failure, a picked suggestion, closing) and the import confirmation: that a replacement is offered rather than performed, that confirming carries it out, that cancelling writes nothing, and that a broken or empty document is reported instead of asked about. Runs a real Room database on the test dispatcher so a virtual clock and the writes stay on one timeline. |
| `domain/EasyRunDriftUseCaseTest` | The easy-run drift rule as a pure function: three easy sessions above the athlete's own median raising the finding, and the fourteen ways it must stay quiet — two hard runs and an ordinary one, fewer than six comparable sessions, runs with no intensity or no matched activity, another type or another planned intensity, a flat history, a run exactly at the median, and an easy session already completed today. |
| `EasyRunDriftWiringTest` | The same rule from a real Room database through the ViewModel flow the Today screen collects, because the AI prompt shipped correct and unreachable once already. Covers the finding arriving, the dismissal clearing it for the day, a steady history producing nothing, and no intervals.icu account producing nothing. |
| `ui/ScreenScreenshotTest` | The three screens whole, which the state hoisting made possible: Today with sessions and on a rest day, the week list, and Settings with and without the notification permission. |
| `ui/ComponentScreenshotTest` | Visual regression of the Today and Week cards, the expanded week row, timed and loaded exercises, tappable exercise rows, a started workout drawn from the plan, every state of the exercise-guide sheet, the recovery card, the easy-run drift card, the update card, the import dialog and every status badge. |
| `ImageLoaderConfigurationTest` (instrumented) | That the image loader has no disk cache and creates no cache directory. A terms-of-use requirement, and a breach would leave no visible trace in the app — see [EXERCISE_GUIDE.md](EXERCISE_GUIDE.md). |
| `data/local/MigrationTest` (instrumented) | Room migrations 3 → 4 (hand-written) and 4 → 5 (auto) against the KSP-generated schemas. The 4 → 5 case inserts a workout first: an existing row must survive with its old values and nulls in the new columns, not be recreated empty. |
| `data/oura/OuraClientTest` | The Oura client against a `com.sun.net.httpserver`: the bearer header, the date parameters — including that `end_date` is sent one day past the range, which reads like an off-by-one and is not — paging, every documented status code, an unreachable host and a body that is not JSON. |
| `data/oura/OuraMappersTest` | Oura's documents as rows. Mostly about absence: which days exist, which scores are `null`, which workouts are dropped, and that a couple of stray heart-rate samples are not a workout's heart rate. |
| `data/oura/OuraOAuthTest` | PKCE, the authorization URL and what a redirect may be acted on as. The security-relevant half: the activity receiving redirects is exported, so anything on the device can start it with any URI. |
| `data/oura/OuraAuthServiceTest` | The token endpoint: the exchange, the refresh, and each OAuth2 rejection in the user's language. |
| `data/oura/OuraAuthenticatorTest` | Renewal on `401`, including two threads failing at once producing **one** refresh — Oura rotates refresh tokens, so the second would otherwise spend an invalidated one. |
| `data/oura/OuraConnectionTest` | Connecting, failing to connect, disconnecting, and the credentials typed into Settings. A forged redirect must never reach the token endpoint. |
| `data/repository/OuraRepositoryTest` | The whole data path, from an HTTP response to a row a screen observes: a real client against a local server and a real in-memory Room database. Includes that a failed sync leaves what was already stored alone, and that diagnostics report an empty workout collection as empty rather than as an error. |
| `domain/MatchOuraWorkoutsUseCaseTest` | Which workout belongs to which session — two sessions in a day, a workout hours from anything, and that a walk does not become strength training. |
| `DayLabelTest` | The calendar's day headings. Worth its own file because what it replaced was positional — row three was always "Keskiviikko" — and therefore right only in a week beginning on a Monday. |
| `data/oura/OuraTokenStoreTest` (instrumented) | The real token store on a device, because there is no Android Keystore on the JVM: that a round trip returns what went in, that what lands in `SharedPreferences` is not the token, that the same token encrypts differently each time, that a tampered ciphertext fails to decrypt, and that disconnecting keeps the client credentials. That last one caught a real bug the unit tests could not — the in-memory fake kept them while the real store wiped them. |
| `data/local/MigrationGuardTest` (instrumented) | That a missing migration throws and leaves the rows on disk, instead of emptying the database quietly. Fails if `fallbackToDestructiveMigration` is ever reintroduced. |
| `data/alarm/ReminderReceiverTest`, `ReminderReceiverNoPermissionTest`, `BootReceiverTest` (instrumented) | Alarm delivery, the missing-notification-permission path, the BootReceiver action guard, and that a session belonging to a replaced plan is ignored. |
| `ImportStartDialogTest` (instrumented) | That the import dialog returns the choice the user made. Returning it backwards would put a plan on the wrong dates while looking correct on screen, which no screenshot would catch. |

**Gaps:** no test has ever run against Oura itself. Everything above stands on a local server and
on fixtures **derived from the vendored specification** rather than captured from the service —
which is a real distinction, and the reason two behaviours the specification does not mention were
found by hand instead: that `end_date` excludes its own day for some collections, and that
third-party imports never appear in the workout collection at all. What the tests prove is that the
client obeys the specification. `WorkoutViewModel`'s cover is
its guide sheet and its import confirmation; the training-engine actions it delegates
(`markSick`, `checkMissedSessions`) are tested through `TrainingEngineTest` rather than through
it. No test drives a screen's interactions — the captures are of states, not of tapping through
them; `ImportStartDialogTest` is the only interaction test and it is instrumented.

## Test Types (Planned & Implemented)

### Unit Tests
- Location: `src/test/java/`
- Target: ViewModels, UseCases, Deterministic Engine rules.
- Run command: `./gradlew :app:testDebugUnitTest`

### Room Database Tests
- Target: DAO queries, transactional writes and foreign-key cascades.
- Executed via Robolectric against an in-memory database, so no device or emulator is needed.
- Deterministic by construction: the repository takes an injectable `Clock` and id generator.
- Migrations are the exception and run instrumented, because `MigrationTestHelper` needs a real
  device: see `MigrationTest` and `MigrationGuardTest`, and the migration policy in
  [DATA_MODEL.md](DATA_MODEL.md#schema-versions-and-migrations).

### UI & Screenshot Tests
- Framework: Roborazzi on Robolectric — runs on the JVM, no device or emulator needed.
- Target: the three screens whole, and the cards, dialogs and badges they are built from.
- Baselines live in `app/src/test/screenshots/` and are committed.
- Run command: `./gradlew :app:verifyRoborazziDebug`
- Record command: `./gradlew :app:recordRoborazziDebug`
- On a mismatch the run fails and writes `<name>_compare.png` under `app/build/outputs/roborazzi/`,
  a reference / diff / new triptych with the changed pixels boxed in red.
- **Re-record only after looking at that diff.** A baseline refreshed on reflex protects nothing;
  the point of the check is that an unintended visual change cannot pass unnoticed.
- Captures use Roborazzi's own composable entry point, not `createComposeRule()`: the default host
  activity resolves the launcher icon, and Robolectric cannot load the `adaptive-icon` XML from
  `mipmap-anydpi-v26`, which fails every capture before the composable is reached.

### API & OAuth tests
- The JDK's own `com.sun.net.httpserver`, not MockWebServer: the app already tests its two other
  HTTP callers that way, and this needs a handful of handlers rather than a dependency.
- Fixtures under `src/test/resources/oura/` are **derived from the vendored specification**, which
  contains no response examples at all — unlike the exercise-guide fixtures beside them, which are
  recorded responses. Worth knowing when one of them disagrees with reality.
- The one thing a unit test cannot reach is the token store: the Android Keystore has no JVM
  equivalent, so `OuraTokenStorage` is an interface with an in-memory fake, and the real
  implementation is covered by an instrumented test.

## Manual Test Scenarios
- Import a plan, complete a workout, verify UI updates.
- Mark a workout as skipped, verify the engine proposes a shift.
- Go completely offline, attempt to complete a workout, verify sync happens when connection is restored.

## Seeded Starter Data
- `MockData` is gone. On first launch `TrainingRepository.seedIfEmpty()` writes a starter week
  through the real JSON importer, so the app has content without an Oura ring or API connection —
  and the seed is continuously validated against the published plan schema.
