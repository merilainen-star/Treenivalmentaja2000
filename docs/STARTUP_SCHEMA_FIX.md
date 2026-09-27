# GUI preview startup crash — 27 September 2026

## Reproduction and cause

The previously published APK, `1.0-45fad49`, already used Room v17 for watch-lap tables. The local
GUI work began at commit `9e3be95`, whose schema was v16, and independently assigned v17 to
persistent AI analyses. The GUI APK was installed on the development emulator's matching schema,
so its startup check passed while the upgrade from the existing release did not.

Installing the old published APK followed by the GUI preview reproduced the user's startup
crash on `emulator-5554`: `Room cannot verify the data integrity`. Expected identity was
`038375625c7a7468613e09977d50e28d` (GUI); stored identity was
`cd550ff6118cb6d809a7583b4ea03a4b` (released laps). Both reported database version 17, so Room
could not select an upgrade and correctly refused to open an incompatible database.

## Correction

Version 18 includes all three tables: lap rows, lap-fetch markers and saved analyses. The explicit
17→18 migration uses `CREATE TABLE IF NOT EXISTS` for these tables only. Existing sessions,
events, connections, lap rows and analyses are not deleted or rewritten. A fresh GUI-v17
installation also upgrades; increasing the version while assuming only one v17 shape would not
have been enough. Earlier versions use the original auto-migration chain then this migration.

The official v17 schema was restored byte-for-byte from the release's generated artifact. The
GUI-generated v17 schema is preserved verbatim as an Android-test asset. Version 18 is generated
by KSP. No schema JSON was handwritten, no migration fallback was relaxed, and no personal
training file was changed. The fix is scoped to database compatibility; the two older lap tables
retain their exact released types and keys.

The broad automated source merge was rejected by automatic approval review because it could
overwrite uncommitted GUI work. It was not retried or bypassed. The repair instead makes explicit,
reviewable database/entity/test changes and keeps both historical data shapes.

## Regression checks

Device tests cover the original v17 with nonempty lap tables, the GUI v17 with saved analyses and
session history, and v16 upgrading through v17 to v18. A separate startup/recreation test loads a
saved program with configured test connections; its optional local input exercised the user's
complete supplied JSON without adding that personal file to the repository.

The corrected personal APK was installed directly over the reproduced failing installation,
without clearing its data. It cold-launched in 957 ms, retained the saved program on the Today
screen, and produced no AndroidRuntime errors. The screenshot was opened and inspected. Exact
suite results and APK measurements are recorded in `PROJECT_STATUS.md`.

For future releases, inspect the **currently downloadable APK and its source/schema**, as well as
the local checkout, before allocating a database version. An emulator initialized by the new
code is insufficient evidence for an upgrade.
