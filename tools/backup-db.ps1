<#
.SYNOPSIS
Copies the Treenivalmentaja database off an attached device and reports its schema version.

.DESCRIPTION
Run this before installing a build that bumps the Room schema version. The app database lives in
three files because the journal mode is WAL (treenivalmentaja.db, -wal, -shm); copying only the
first loses the most recent writes, so the whole databases directory is taken.

Reads the schema version from bytes 60-63 of the SQLite header rather than shelling out to
sqlite3, which is not present on current Android system images.

Works on debug builds only: run-as requires a debuggable package.

This file is deliberately ASCII-only. Windows PowerShell 5.1 reads .ps1 files as ANSI unless they
carry a BOM, so a stray non-ASCII character turns into a parse error.

.PARAMETER OutputDir
Where to write the backup. Defaults to a timestamped directory under .scratch/ (git-ignored).

.PARAMETER Serial
Device serial as reported by 'adb devices'. Only needed when more than one device is attached.

.EXAMPLE
.\tools\backup-db.ps1

.EXAMPLE
.\tools\backup-db.ps1 -Serial 46251FDAS008UV -OutputDir C:\backups\treeni
#>
[CmdletBinding()]
param(
    [string]$OutputDir,
    [string]$Serial,
    [string]$Restore
)

$ErrorActionPreference = 'Stop'

$package = 'fi.merilainen.treenivalmentaja'
$dbName = 'treenivalmentaja.db'
$devicePath = "/sdcard/$package-dbbackup"

# Not named $args: that is an automatic variable in PowerShell and cannot be used as a parameter.
$adbPrefix = @()
if ($Serial) { $adbPrefix = @('-s', $Serial) }

function Invoke-Adb([string[]]$AdbArgs) {
    # 2>&1 is needed because run-as reports "unknown package" and the like on stderr, but in
    # Windows PowerShell 5.1 redirecting a native command's stderr wraps each line in an
    # ErrorRecord - and with $ErrorActionPreference = 'Stop' that aborts the script on nothing
    # worse than adb push printing its transfer rate. Relaxed for the duration of the call.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & adb @adbPrefix @AdbArgs 2>&1
    } finally {
        $ErrorActionPreference = $previous
    }
}

# --- device present? -------------------------------------------------------
$devices = @((& adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' })
if ($devices.Count -eq 0) {
    throw "No device with status 'device' is attached. Check 'adb devices' and accept the USB debugging prompt on the phone."
}
if ($devices.Count -gt 1 -and -not $Serial) {
    throw ("More than one device attached. Re-run with -Serial <serial>:`n" + ($devices -join "`n"))
}

# --- app installed and debuggable? ----------------------------------------
$probe = (Invoke-Adb @("shell", "run-as $package ls databases/ 2>&1")) -join "`n"
if ($probe -match 'unknown package') {
    throw "$package is not installed on the device, so there is nothing to back up."
}
if ($probe -match 'not debuggable') {
    throw "$package is installed but not debuggable. run-as only works on a debug build."
}
if ($probe -match 'No such file') {
    throw "$package is installed but has no databases/ directory yet. Launch the app once first."
}

# --- restore ---------------------------------------------------------------
# Staged through /data/local/tmp, which adb owns and can write to. /sdcard is unusable because
# scoped storage denies the app uid, and piping the bytes into 'adb shell' from PowerShell is
# worse: the pipe applies a text encoding, and what lands on the device is not the file.
if ($Restore) {
    if (-not (Test-Path $Restore)) { throw "Backup directory not found: $Restore" }
    $files = Get-ChildItem $Restore -File | Where-Object { $_.Name -like "$dbName*" }
    if (-not $files) { throw "No $dbName files in $Restore." }

    Invoke-Adb @("shell", "am force-stop $package") | Out-Null
    Invoke-Adb @("shell", "run-as $package sh -c 'mkdir -p databases; rm -f databases/$dbName*'") | Out-Null

    foreach ($f in $files) {
        $tmp = "/data/local/tmp/$($f.Name)"
        Invoke-Adb @("push", $f.FullName, $tmp) | Out-Null
        # 0771 on /data/local/tmp lets the app traverse but not read, so the file itself has to
        # be world-readable for run-as to see it.
        Invoke-Adb @("shell", "chmod 666 $tmp") | Out-Null
        Invoke-Adb @("shell", "run-as $package sh -c 'cat $tmp > databases/$($f.Name)'") | Out-Null
        Invoke-Adb @("shell", "rm -f $tmp") | Out-Null
    }

    $listing = (Invoke-Adb @("shell", "run-as $package ls -l databases/")) -join "`n"
    Write-Host ""
    Write-Host "Restored from $Restore"
    Write-Host $listing
    Write-Host ""
    Write-Host "Start the app to check it opened the database."
    exit 0
}

# --- copy out --------------------------------------------------------------
if (-not $OutputDir) {
    $stamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
    $OutputDir = Join-Path $PSScriptRoot "..\.scratch\db-backup-$stamp"
}
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null }
$OutputDir = (Resolve-Path $OutputDir).Path

# The app is stopped first so nothing is mid-write while the three files are read one at a time.
# Without this the .db and its -wal can be captured a moment apart and disagree.
Invoke-Adb @("shell", "am force-stop $package") | Out-Null

# Streamed with 'exec-out cat', not staged through /sdcard: scoped storage denies the app uid
# write access there, so 'run-as ... cp -r databases /sdcard/...' fails with Permission denied.
# PowerShell's own '>' is not used either - it applies a text encoding and corrupts binary.
function Copy-DeviceFile([string]$RemoteName, [string]$LocalPath) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'adb'
    $psi.Arguments = (($adbPrefix + @('exec-out', 'run-as', $package, 'cat', "databases/$RemoteName")) -join ' ')
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $proc = [System.Diagnostics.Process]::Start($psi)
    $out = [System.IO.File]::Create($LocalPath)
    try {
        $proc.StandardOutput.BaseStream.CopyTo($out)
    } finally {
        $out.Close()
        $proc.WaitForExit()
    }
    return $proc.ExitCode
}

foreach ($name in @($dbName, "$dbName-wal", "$dbName-shm")) {
    $target = Join-Path $OutputDir $name
    $code = Copy-DeviceFile -RemoteName $name -LocalPath $target
    # -wal and -shm are absent when the database was closed cleanly; only the main file is required.
    if ((Test-Path $target) -and ((Get-Item $target).Length -eq 0)) { Remove-Item $target }
    if ($code -ne 0 -and $name -eq $dbName) {
        throw "Reading $name off the device failed (adb exit $code)."
    }
}

$dbFile = Join-Path $OutputDir $dbName
if (-not (Test-Path $dbFile)) {
    throw "Copy finished but $dbName is not in $OutputDir. Check the adb output above."
}

# A SQLite file starts with "SQLite format 3\0". Checking it here turns a silently corrupt copy
# into an error now, rather than a surprise on the day it is restored.
$magic = [System.IO.File]::ReadAllBytes($dbFile)[0..14]
if (-join ([char[]]$magic) -ne 'SQLite format 3') {
    throw "$dbFile does not look like a SQLite database - the copy is corrupt."
}

# --- report ----------------------------------------------------------------
# SQLite keeps the user version - which is where Room stores its schema version - in a 4-byte
# big-endian integer at offset 60 of page 1.
function Get-UserVersion([byte[]]$Page1) {
    $b = $Page1[60..63]
    [Array]::Reverse($b)
    return [BitConverter]::ToInt32($b, 0)
}

$schemaVersion = Get-UserVersion ([System.IO.File]::ReadAllBytes($dbFile))

# Under WAL the main file can lag far behind: until a checkpoint runs, page 1 still holds its
# original contents and reports version 0 while the real schema lives in the -wal. Reading only
# the main file therefore reports a number that is confidently wrong. The newest copy of page 1
# is in the last WAL frame that carries it.
$walFile = Join-Path $OutputDir "$dbName-wal"
if ($schemaVersion -eq 0 -and (Test-Path $walFile)) {
    $wal = [System.IO.File]::ReadAllBytes($walFile)
    if ($wal.Length -gt 32) {
        # WAL header: page size is a big-endian int at offset 8. Frames follow, each a 24-byte
        # header (page number big-endian first) plus one page of data.
        $ps = [uint32]$wal[8] * 16777216 + [uint32]$wal[9] * 65536 + [uint32]$wal[10] * 256 + [uint32]$wal[11]
        if ($ps -ge 512 -and $ps -le 65536) {
            $offset = 32
            while ($offset + 24 + $ps -le $wal.Length) {
                $pageNo = [uint32]$wal[$offset] * 16777216 + [uint32]$wal[$offset + 1] * 65536 +
                          [uint32]$wal[$offset + 2] * 256 + [uint32]$wal[$offset + 3]
                if ($pageNo -eq 1) {
                    $page = $wal[($offset + 24)..($offset + 24 + 63)]
                    $v = Get-UserVersion $page
                    if ($v -ne 0) { $schemaVersion = $v }
                }
                $offset += 24 + $ps
            }
        }
    }
}

$files = Get-ChildItem $OutputDir -File
$totalKb = [math]::Round((($files | Measure-Object -Property Length -Sum).Sum) / 1KB, 1)

Write-Host ""
Write-Host "Backed up to $OutputDir"
foreach ($f in $files) {
    Write-Host ("  {0,-28} {1,10} bytes" -f $f.Name, $f.Length)
}
Write-Host ""
Write-Host "  Schema version: $schemaVersion"
Write-Host "  Total:          $totalKb KB"
Write-Host ""
# Restoring goes back the same way it came out: piped over stdin into a shell running as the app.
# /sdcard is not usable in either direction.
Write-Host "To restore it:"
Write-Host "  .\tools\backup-db.ps1 -Restore '$OutputDir'"
