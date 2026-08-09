# Testing

## Overview
The testing strategy ensures the deterministic training engine works flawlessly and the UI reacts properly to state changes.

## Current Test Coverage
**Status:** 175 unit tests + 18 instrumented tests, all passing.

- `./gradlew :app:testDebugUnitTest` — 175 tests / 0 failures / 0 errors
- `./gradlew :app:verifyRoborazziDebug` — 22 screenshot comparisons (a subset of the 175 above)
- `./gradlew :app:connectedDebugAndroidTest` — 18 tests / 0 failures / 0 errors

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
| `ui/ComponentScreenshotTest` | Visual regression of the Today and Week cards, the expanded week row, timed and loaded exercises, tappable exercise rows, a started workout drawn from the plan, every state of the exercise-guide sheet, the recovery card, the update card, the import dialog and every status badge. |
| `ImageLoaderConfigurationTest` (instrumented) | That the image loader has no disk cache and creates no cache directory. A terms-of-use requirement, and a breach would leave no visible trace in the app — see [EXERCISE_GUIDE.md](EXERCISE_GUIDE.md). |
| `data/local/MigrationTest` (instrumented) | Room migration 3 → 4 against the KSP-generated schemas. |
| `data/local/MigrationGuardTest` (instrumented) | That a missing migration throws and leaves the rows on disk, instead of emptying the database quietly. Fails if `fallbackToDestructiveMigration` is ever reintroduced. |
| `data/alarm/ReminderReceiverTest`, `ReminderReceiverNoPermissionTest`, `BootReceiverTest` (instrumented) | Alarm delivery, the missing-notification-permission path, the BootReceiver action guard, and that a session belonging to a replaced plan is ignored. |
| `ImportStartDialogTest` (instrumented) | That the import dialog returns the choice the user made. Returning it backwards would put a plan on the wrong dates while looking correct on screen, which no screenshot would catch. |

**Gaps:** no ViewModel tests, and no test renders a whole screen — `TodayScreen`, `WeekScreen` and
`SettingsScreen` each take a `WorkoutViewModel`, so screenshot cover stops at the stateless cards
they are built from. Splitting each screen into a stateless `…Content(state, callbacks)` plus a
thin stateful wrapper would let the screens themselves be captured. Nothing covers the Oura
layer, which does not exist yet.

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
- Target: visual regression of the Today and Week cards, the recovery card and the status badges.
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

### API & OAuth Tests
- MockWebServer will be used to simulate Oura API responses and OAuth token exchanges.

## Manual Test Scenarios
- Import a plan, complete a workout, verify UI updates.
- Mark a workout as skipped, verify the engine proposes a shift.
- Go completely offline, attempt to complete a workout, verify sync happens when connection is restored.

## Seeded Starter Data
- `MockData` is gone. On first launch `TrainingRepository.seedIfEmpty()` writes a starter week
  through the real JSON importer, so the app has content without an Oura ring or API connection —
  and the seed is continuously validated against the published plan schema.
