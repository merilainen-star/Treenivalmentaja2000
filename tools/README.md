# Tools

## ICS to JSON Parser

`parse_ics.py` and `parse_ics2.py` can be used to parse ICS calendar files into the `PlanDto` format used by the application for import.

Usage:
```bash
python tools/parse_ics.py sample-data/treeniohjelma_16-7-2026_kaikki.ics
```

## Database Backup

`backup-db.ps1` copies the app database off an attached device and prints its Room schema version.
Run it before installing a build that bumps the version.

```powershell
.\tools\backup-db.ps1
```

Writes a timestamped directory under `.scratch/` (git-ignored) unless `-OutputDir` says otherwise;
pass `-Serial` when more than one device is attached. The script prints the restore commands when
it finishes.

Two details it handles that a hand-rolled `adb pull` usually gets wrong: the journal mode is WAL,
so the database is three files and copying only `treenivalmentaja.db` loses the most recent
writes; and the schema version is read from bytes 60-63 of the SQLite header, because `sqlite3`
is not present on current Android system images. Debug builds only — `run-as` needs a debuggable
package.
