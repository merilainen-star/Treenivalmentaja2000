# Instructions for AI Coding Agents

These are binding instructions for future coding agents working on Treenivalmentaja.

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
