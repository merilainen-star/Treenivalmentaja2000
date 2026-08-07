# Tools

## ICS to JSON Parser

`parse_ics.py` and `parse_ics2.py` can be used to parse ICS calendar files into the `PlanDto` format used by the application for import.

Usage:
```bash
python tools/parse_ics.py sample-data/treeniohjelma_16-7-2026_kaikki.ics
```

## Icon and Splash Assets

`generate_icons.py` rebuilds every launcher and splash raster from the master artwork.

```bash
python tools/generate_icons.py path/to/Icon.png
```

The master is the finished 2048x2048 icon: the logo mark on its own dark background, inside a
rounded square, on a white margin. The script floods in from the corners to find that margin,
grows the mask so the anti-aliased rim of the rounded square goes with it, keys the mark off the
`#232323` background and unpremultiplies the edges, then writes the adaptive-icon foreground, the
themed-icon monochrome layer and the splash mark at all five densities as lossless WebP.

Verify by rendering, never by reading a header: `aapt2` and a RIFF size check both pass happily on
a file whose pixels are noise. That is exactly how the previous assets went unnoticed.

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
