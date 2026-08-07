# Testing

## Overview
The testing strategy ensures the deterministic training engine works flawlessly and the UI reacts properly to state changes.

## Current Test Coverage
**Status:** 41 unit tests, all passing (`./gradlew :app:testDebugUnitTest`).

| Suite | Covers |
| --- | --- |
| `domain/SessionStatusTest` | The normative transition table: terminal statuses, forbidden moves, no self-transitions. |
| `data/importer/PlanValidatorTest` | Plan Schema v1: a valid document, unparseable text, missing/invalid fields, sessions with no content, duplicate session ids and week numbers, content hashing. |
| `data/repository/TrainingRepositoryTest` | Real Room schema in memory: import, event-history accumulation, rejected transitions writing nothing, lighter-version payload and fallback, reschedule chain, duplicate/conflict detection, seeding, cascade delete. |

**Gaps:** no ViewModel tests, no UI or screenshot tests, no Room migration tests (schema is still
at version 1).

## Test Types (Planned & Implemented)

### Unit Tests
- Location: `src/test/java/`
- Target: ViewModels, UseCases, Deterministic Engine rules.
- Run command: `./gradlew :app:testDebugUnitTest`

### Room Database Tests
- Target: DAO queries, transactional writes, foreign-key cascades, and (later) migrations.
- Executed via Robolectric against an in-memory database, so no device or emulator is needed.
- Deterministic by construction: the repository takes an injectable `Clock` and id generator.

### UI & Screenshot Tests
- Framework: Roborazzi and Compose UI Testing.
- Target: Visual regression of key screens (Today, Week).
- Run command: `./gradlew :app:verifyRoborazziDebug`
- Record command: `./gradlew :app:recordRoborazziDebug`

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
