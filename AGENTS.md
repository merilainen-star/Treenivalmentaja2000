# Instructions for AI Coding Agents

These are binding instructions for future coding agents working on Treenivalmentaja.
This file is the single source of truth; there is no separate per-vendor copy.

## Reporting Protocol (v4)

Begin every completion report with the line: `AGENTS.md luettu (v4)`

## Verification Rules

- **Compiling is not working.** "Builds without errors" says nothing about behaviour.
  Instrumented tests must be *run* on a device or emulator, not merely compiled. If no
  emulator is available, say so plainly — never report an unrun test as passing, and do not
  attach a prediction about whether it would pass.
- **Never hand-write generated artifacts.** Room schema JSON under `app/schemas/` is written
  by KSP. If a schema file is missing or wrong, fix the build configuration and re-run the
  build. Editing the JSON by hand is always the wrong fix.
- **Do not undo previous fixes.** Before touching resources, dependencies, or build config,
  check `git log -- <path>` for recent deliberate changes. Re-introducing something that was
  removed on purpose is a regression, not a feature.
- **Stay in scope.** Do not change product or visual design while fixing tests or
  refactoring. Cosmetic changes belong in their own commit, proposed first.
- **Throwaway scripts stay out of the repo.** Patch and migration helper scripts go in
  `.scratch/` (git-ignored), never the repository root.
- **Reports must contain measured numbers**, not adjectives: APK size in MB, test counts as
  `tests/failures/errors`, and the exact commands that produced them.
- **Asset budget.** No bitmaps in `-nodpi` — it disables density stripping and ships every
  image to every device. WebP is the default format; PNG needs a reason. Launcher icons need
  every density bucket because `minSdk = 24`.

## General Rules
- **Inspect Before Implementing:** Always inspect existing implementations (via `cat`, `grep`, or file viewing tools) before adding new ones. Do not assume architecture.
- **Preserve Module Boundaries:** Maintain strict separation between UI (Compose), Presentation (ViewModel), Domain (UseCases/Entities), and Data (Repositories/Room/Network) layers.
- **No Secrets in Client Code:** Client secrets, API keys, and Oura credentials must **never** be hardcoded in source, resources, or committed files. The only sanctioned mechanism is `BuildConfig` injection from a git-ignored `.env` via the Secrets Gradle Plugin ([ADR-006](docs/DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)).
- **Repository Pattern:** Do not bypass repositories. Never access Room DAOs or Retrofit services directly from UI components or ViewModels.
- **Type Safety:** Use typed models and sealed classes for state management. Avoid primitive obsession.

## Data Handling
- **Explicit Nullability:** Handle nullable Oura values explicitly. Do not blindly use `!!`.
- **Missing Data:** Do not silently treat missing health data as zero. Handle missing data states appropriately in the UI and business logic.
- **User Confirmation:** Any plan-changing operation (especially from the future AI advisor) requires explicit user confirmation before being persisted to Room.

## Development Workflow
- **Testing:** Add or update unit and UI tests for any behavior changes.
- **Documentation:** Update documentation in `/docs` when architecture, behavior, or database schemas change.
- **Verification:** Run build, lint, and tests before declaring work complete.
- **Completion Criteria:** Do not claim a feature is complete unless it is fully wired into the actual application flow (not just a standalone function).
- **Reporting:** Report the exact commands run and any remaining failures when communicating task completion.

## Known Commands
Always use the Gradle wrapper (`./gradlew`, or `gradlew.bat` on Windows `cmd`). It pins the Gradle
version and its checksum, so builds are reproducible. Do **not** invoke a system-wide `gradle`.

- **Build:** `./gradlew assembleDebug`
- **Lint:** `./gradlew :app:lintDebug`
- **Run Unit Tests:** `./gradlew :app:testDebugUnitTest`
- **Run Roborazzi Screenshot Tests:** `./gradlew :app:verifyRoborazziDebug`
- **Record Roborazzi Screenshots:** `./gradlew :app:recordRoborazziDebug`

## Project Conventions
- **Package:** all Kotlin sources live under `fi.merilainen.treenivalmentaja`
  (`applicationId` and `namespace` are the same). Never reintroduce `com.example`.
- **Toolchain:** JDK 17+ (verified on Temurin 21), Android SDK Platform 36.1, Gradle via wrapper.
- **Secrets:** the Oura client secret is injected into `BuildConfig` from a git-ignored `.env`
  (see [ADR-006](docs/DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)). That is the only
  sanctioned mechanism — never hardcode a secret in Kotlin, XML, or `.env.example`.
