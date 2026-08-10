# Changelog

All notable changes to this project will be documented in this file.

Entries below a date describe what was true when they were written; they are history and are not
rewritten when the code moves on. For the current state, see [PROJECT_STATUS.md](PROJECT_STATUS.md).

## [Unreleased] - 2026-08-09

### Added
- Exercise guides. Tapping a movement opens a sheet with an animation or picture, numbered
  instructions, target muscles and equipment, so a name you do not recognise no longer sends you
  to a search engine. Fetched when the sheet opens and **never stored**: the image loader is given
  no disk cache at all, nothing goes in Room, and the only cache is a map that dies with the
  process. Credit is shown wherever guide data appears, as the sources require.
- A second guide source, **wger** (<https://wger.de>, CC-BY-SA), alongside ExerciseDB — because
  neither has everything. ExerciseDB carries an animation for all 1500 of its movements but has no
  plank, side plank, plain squat, bird dog or cat-cow *at all*; wger has every one of them, though
  only a third of its movements carry a picture and those are stills. A plan pins each movement to
  whichever source has it, and a reference is resolved by the provider it names — never quietly by
  the other. Without a reference both are searched at once, and one being down no longer hides the
  other's answer.
- `guide` on an exercise — `{ "provider": "exercisedb" | "wger", "id": "…" }`, the plan author's
  pointer into a catalogue. Optional and backwards compatible; an unknown `provider` is an import
  error rather than something ignored. No Room migration.
- An exercise without `guide` is searched by name, and the result is offered as a suggestion that
  has to be picked. The service's fuzzy matching does not miss when there is nothing to find —
  `cat cow` comes back as "cable squat row" — so every result is filtered down to names that
  contain each word of the query. A Finnish name matches nothing, which is the honest answer, and
  the sheet says to add a `guide` instead.
- Exercises are shown as the plan wrote them: the prescription under each name
  (`3 × 12 · 17,5 kg`, `10 / puoli`, `30 s`), and a clock for timed movements that runs once per
  side or per set. Previously the screens printed only the name, and decided a movement was timed
  by looking for "lankku" in it.
- `setPlan` on an exercise, for sets that differ from each other — a ramp such as 25/35/45/55 kg,
  or reps that fall as the load climbs. Optional and backwards compatible; no Room migration.
- Import asks where a plan should land: on the file's own dates, or shifted so day one is today.
- Settings says whether the installed build is the one GitHub Actions last published, and offers
  the download when it is not.
- [docs/EXERCISE_GUIDE.md](docs/EXERCISE_GUIDE.md) — the plan for per-movement animations and
  instructions, including the ExerciseDB terms that constrain it.

### Changed
- Each screen is now a stateless `…Content` taking plain values and callbacks, plus a thin wrapper
  that reads the ViewModel and owns the parts only a real screen can do — the file picker, the
  clipboard, the notification permission. No behaviour changed and no baseline moved; what changed
  is that a test can render a whole screen at all. Five now do, including two states that are
  awkward to reach by hand: a rest day, and Settings without the notification permission.
- `WorkoutViewModel` has its first tests, ten of them, over the guide sheet's states and the import
  confirmation — the one place in the app where saying yes destroys data. `RescheduleAlarmsUseCase`
  became `open` for the same reason `ReminderScheduler` already was: so a test can hand it a no-op
  instead of driving DataStore from a virtual clock.

### Fixed
- Correcting the programme you are running cost you the record of running it. Re-importing the
  same `plan.id` with any change was refused outright — "poista vanha suunnitelma ensin", for which
  there was no button — so the only way through was "Palauta esimerkkidata", which deletes every
  session status and the whole event log. Meanwhile importing a plan with a *different* id deleted
  all of that without asking at all: strict about the harmless case, silent about the destructive
  one. Now a corrected document updates the sessions in place and keeps everything recorded against
  them, a genuine replacement asks first and says how many marked sessions it would destroy, and
  neither happens without a yes.
- The Week view offered to start a hold for a session days away. `WorkoutDetails` is the read-only
  rendering shared by the expanded Week row and the Today card, and its own description says it
  shows "what it is, not what to do about it" — a running clock was never that. The clocks are now
  only in the started workout, where they are sequenced and where finishing one means something.
  The hold's duration still shows on every list, and the guide is still one tap away from both.
- The countdown lost its face. Moving timed movements onto the plan's own fields replaced the
  full-screen clock — a 240dp ring emptying around a 72pt number — with a line of small text, and
  dropped the notification sound at zero. A hold is done with your eyes shut or your face at the
  floor, so both are back, now for every timed movement rather than only the ones with "lankku"
  in their name.
- A started workout is a sequence again, and behaves like one. You could tick movements off in
  any order, including skipping ahead, and a finished clock left a "Valmis / Alusta" line to read
  and dismiss. Now the last round of a movement's clock ticks it off by itself, only the movement
  you are on can be ticked, and only the last one ticked can be unticked — which walks the
  session back one step at a time and resets that movement's clock. Everything below the current
  movement is visibly locked.
- A started workout lost half of what the plan knows about it. "Aloita ohjattu treeni" rendered
  its checklist from the session's free-text description rather than the plan's `exercises`
  array, so mid-session there were no guide links, no prescriptions, and the movement names came
  back with their numbers glued on ("sivulankku 20 s/puoli"). Worse, the checklist used a
  single-shot timer that decided a movement was timed by looking for "lankku" in its name: a
  per-side hold offered one clock for two sides, and there was no way to time the second. It now
  draws from the same movements the read-only list does, so a side plank asks for Vasen and then
  Oikea, and every movement is still tappable for its guide while the workout is running. Plans
  with no `exercises` array keep the old description-parsing path.
- App startup rewrote the training calendar. With a plan whose dates had passed, the engine
  counted every past session as missed and shifted the whole programme so week 1 landed on today,
  restarting an eight-week plan from the beginning on every launch — including the one after an
  app update.
- A replaced plan kept sending its own reminders: alarm scheduling had no active-plan filter, so a
  superseded programme notified beside the current one.
- Importing now deletes the plan it replaces instead of deactivating it, so the database stops
  growing and dead sessions cannot hold alarms.
- The week row's press ripple grew to the height of the expanded card, sweeping a grey circle
  across the exercise list.
- `tools/backup-db.ps1` did not work in either direction; every step of the copy path was wrong.

## [Unreleased] - 2026-08-08

### Added
- Week rows expand on tap to show the session's content, animated with `expandVertically` so the
  rest of the week is pushed down rather than jumping. State survives scrolling and process death.
- `WorkoutDetails`, one read-only rendering of a session's content, shared by the Today card and
  the expanded Week row instead of being written twice.
- Roborazzi screenshot tests reinstated: 10 baselines over the Today and Week cards, the recovery
  card, every status badge and the expanded row.
- `BootReceiverTest`, `MigrationGuardTest`, and `PlanValidatorTest` cases for exercises that carry
  neither reps nor a duration.
- GitHub Actions builds and publishes a signed test APK to one rolling prerelease on every push
  to `main` that touches code, and on demand from the Actions page. Installing the next test
  build needs only a phone; the permanent link is in the README. The APK is signed with the same
  debug key as local builds, restored from a secret, so it updates in place instead of demanding
  an uninstall — verified against the published binary, not assumed.
- `tools/generate_icons.py`, which rebuilds every launcher and splash raster from the master
  artwork, and `tools/backup-db.ps1`, which copies the database off a device and reports its
  schema version.

### Fixed
- **The app could not start.** Every image in the repository had been destroyed by being written
  through a text encoding — each byte `>= 0x80` replaced by U+FFFD — so `splash_logo` threw on
  launch. No intact copy existed in any commit. All assets regenerated from the master artwork.
- A missing Room migration silently emptied the database; `fallbackToDestructiveMigration` is gone
  and a missing migration now fails loudly with the data intact.
- `BootReceiver` re-armed alarms for any intent it was handed, without reading `intent.action`.
- The ICS parsers split a running session's description on its commas and emitted the sentence
  fragments as exercises, failing the import 16 times in an eight-week plan.
- The week row's press ripple was unbounded and drew a grey circle over the content; Material3 1.3
  no longer supplies a bounded ripple through `LocalIndication`.
- `TrainingEngineTest` did not compile: it built entities with fields that do not exist.

### Changed
- `minSdk` raised 24 → 26. Core library desugaring dropped with it, worth 1.2 MB of APK, along
  with both `InlinedApi` warnings and a dead `SDK_INT` guard.
- Documentation moved from `app/applet/` to the repository root and `docs/`, where the paths cited
  from the source actually point. `GEMINI.md` merged into `AGENTS.md`.
- `AGENTS.md` gained two rules learned the hard way: never write a binary through a text tool, and
  verify images by rendering them — `aapt2` compiled the corrupted icons without complaint.

## [Unreleased] - 2026-08-05

### Added (Room persistence)
- Room database: `TrainingPlan`, `WorkoutSession`, `SessionEvent`, `OuraDailySummary` and
  `OuraWorkout` entities, their DAOs, type converters, and `AppDatabase` (schema version 1).
- `TrainingRepository` — the single entry point to training data. Enforces the session state
  machine and writes an immutable `SessionEvent` in the same transaction as every accepted
  status change.
- Rescheduling creates a new session row linked by `originalSessionId` instead of rewriting a
  date in place.
- Training plan JSON import from a file (Storage Access Framework) and from the clipboard, in the
  Settings screen. Validated against `docs/PLAN_SCHEMA.md` before anything is written, with
  per-field Finnish error messages and duplicate/conflict detection.
- First-launch seeding with a starter week, routed through the real importer.
- 41 unit tests: state transitions, event-history accumulation, JSON validation (valid, broken,
  duplicate), import conflicts, reschedule chain, and cascade delete. Room tests run in memory
  under Robolectric.
- Core library desugaring so `java.time` is usable on the declared `minSdk` 24.

### Changed (Room persistence)
- `WorkoutViewModel` observes a Room `Flow` instead of `MockData`; `MockData` removed.
- `WorkoutStatus` replaced by `domain.SessionStatus`; `WorkoutType` moved to `domain`.
- Today screen gained a "Merkitse tehdyksi" action and hides actions on closed sessions.

### Added
- Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) pinned to Gradle 9.6.1 with a
  distribution checksum. All documented commands now use `./gradlew`.
- `docs/PLAN_SCHEMA.md` — Treenivalmentaja Training Plan Schema v1 (JSON import format).
- ADR-006 "No separate backend in the MVP"; ADR-004 marked Superseded.
- `SessionEvent` entity (immutable, append-only session history) in the data model.
- `OURA_CLIENT_ID` / `OURA_CLIENT_SECRET` documented in `.env.example`.

### Changed
- Package renamed `com.example` → `fi.merilainen.treenivalmentaja`; `applicationId` changed from
  `com.aistudio.treenivalmentaja.bvcxw` to `fi.merilainen.treenivalmentaja`.
- Session state model expanded to `PLANNED`, `NOTIFIED`, `STARTED`, `COMPLETED`, `SKIPPED`,
  `RESCHEDULED`, `REPLACED_WITH_LIGHTER_VERSION`, `PAUSED_DUE_TO_ILLNESS`, `CANCELLED`
  (replacing `LIGHTER` and `MOVED`), with a normative transition table.
- Rescheduling no longer rewrites a session's date in place: the old row closes as `RESCHEDULED`
  and a new row references it via `originalSessionId`.
- `AUTHENTICATION.md`, `ARCHITECTURE.md`, `SECURITY.md`, `SETUP.md`, `README.md` updated for the
  no-backend design (in-app OAuth exchange with PKCE, secret via `BuildConfig`, tokens in
  `EncryptedSharedPreferences`).

### Added (initial scaffolding)
- Initial project scaffolding and Gradle setup.
- Basic MVVM structure with Jetpack Compose.
- Splash screen with logo and animations.
- Bottom navigation with "Tänään", "Viikko", and "Asetukset" tabs.
- `WorkoutViewModel` with mock data for workouts.
- Static UI for viewing mock training sessions.
- Icons and basic styling for different workout types (Running, Strength, Skiing).
- Comprehensive documentation skeleton in `/docs`.

### Changed
- Replaced the default app icon with a custom adaptive icon using the user-provided `Icon.png`.
- Replaced the Material Design gradient background on the Splash screen with a custom background image (`Splash_notext.png`).
- App theme and colors configured to match the requested dark blue and vibrant green aesthetic.

### Planned
- Room database integration.
- Oura API V2 data fetching.
- Notification engine via AlarmManager.
- Background sync via WorkManager.

## [Unreleased]
- **Changed**: Erotettiin treenin suoritusaika ja muistutusaika toisistaan.
- **Added**: `timeIsFixed` ja valinnainen `time` JSON-skeemaan v1.
- **Added**: Room-migraatio versioon 2, jossa lisättiin `remindAtUtc`, `timeIsFixed`, `reminderOverride`.
- **Added**: `NotificationSettingsStore` (Datastore) lajikohtaisille hälytysasetuksille.
