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
    [string]$Serial
)

$ErrorActionPreference = 'Stop'

$package = 'fi.merilainen.treenivalmentaja'
$dbName = 'treenivalmentaja.db'
$devicePath = "/sdcard/$package-dbbackup"

# Not named $args: that is an automatic variable in PowerShell and cannot be used as a parameter.
$adbPrefix = @()
if ($Serial) { $adbPrefix = @('-s', $Serial) }

function Invoke-Adb([string[]]$AdbArgs) {
    & adb @adbPrefix @AdbArgs 2>&1
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

# --- copy out --------------------------------------------------------------
if (-not $OutputDir) {
    $stamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
    $OutputDir = Join-Path $PSScriptRoot "..\.scratch\db-backup-$stamp"
}
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null }
$OutputDir = (Resolve-Path $OutputDir).Path

# Staged through /sdcard because 'adb pull' cannot read the app's private directory directly.
Invoke-Adb @("shell", "rm -rf $devicePath") | Out-Null
Invoke-Adb @("shell", "run-as $package cp -r databases $devicePath") | Out-Null
Invoke-Adb @("pull", "$devicePath/.", $OutputDir) | Out-Null
Invoke-Adb @("shell", "rm -rf $devicePath") | Out-Null

$dbFile = Join-Path $OutputDir $dbName
if (-not (Test-Path $dbFile)) {
    throw "Copy finished but $dbName is not in $OutputDir. Check the adb output above."
}

# --- report ----------------------------------------------------------------
# SQLite file format: the user version is a 4-byte big-endian integer at offset 60, and Room
# stores its schema version there.
$header = [System.IO.File]::ReadAllBytes($dbFile)[60..63]
[Array]::Reverse($header)
$schemaVersion = [BitConverter]::ToInt32($header, 0)

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
Write-Host "To restore, with the app installed and stopped:"
Write-Host "  adb push `"$OutputDir\.`" $devicePath"
Write-Host "  adb shell `"run-as $package sh -c 'rm -f databases/* ; cp $devicePath/* databases/'`""
