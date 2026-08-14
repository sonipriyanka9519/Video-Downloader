# Captures one screen per prompt into this folder.
#
# Usage:
#   .\capture.ps1              walk the whole checklist, in order
#   .\capture.ps1 -Only splash capture a single named screen and exit
#   .\capture.ps1 -From 12     resume the walk part-way down the list
#
# Each step tells you what to put on screen, waits for Enter, then pulls a PNG
# straight off the device. Press S to skip a screen you cannot reach right now;
# the file is simply not written and the run carries on.

param(
    [string]$Only = "",
    [int]$From = 1,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$outDir = $PSScriptRoot

if (-not (Test-Path $Adb)) {
    Write-Host "adb not found at $Adb" -ForegroundColor Red
    Write-Host "Pass the right path with -Adb <path to adb.exe>"
    exit 1
}

$devices = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    Write-Host "No device connected. Plug in the phone, enable USB debugging, and accept the prompt." -ForegroundColor Red
    exit 1
}

function Save-Screen([string]$name) {
    $path = Join-Path $outDir "$name.png"
    # Captured to the device and pulled back, rather than piped through
    # `adb exec-out ... > file`. PowerShell 5.1 redirection is text, not bytes: it
    # re-encodes the stream and every PNG comes out unopenable.
    $onDevice = "/sdcard/_vdshot.png"
    & $Adb shell screencap -p $onDevice
    & $Adb pull $onDevice $path | Out-Null
    & $Adb shell rm -f $onDevice

    if ((Test-Path $path) -and (Get-Item $path).Length -gt 0) {
        Write-Host "  saved $name.png" -ForegroundColor Green
    } else {
        if (Test-Path $path) { Remove-Item $path }
        Write-Host "  FAILED $name" -ForegroundColor Red
    }
}

# name, what to have on screen
$screens = @(
    @("splash",                  "Cold-start the app - relaunch from the launcher"),
    @("browser-home",            "Browser tab, home grid of shortcuts"),
    @("browser-page",            "Browser tab with any site loaded"),
    @("browser-detected",        "A page with video detected - FAB showing the count"),
    @("search",                  "Tap the address bar to open the search screen"),
    @("search-suggestions",      "Search screen with history and suggestions showing"),
    @("downloads-empty",         "Downloads tab with nothing downloaded"),
    @("downloads-list",          "Downloads tab, list layout, with items"),
    @("downloads-grid",          "Downloads tab, grid layout"),
    @("player",                  "Play a downloaded video - controls visible"),
    @("player-fullscreen",       "Player in fullscreen / landscape"),
    @("how-to-1",                "How to Download walkthrough, step 1"),
    @("how-to-2",                "How to Download walkthrough, step 2"),
    @("how-to-3",                "How to Download walkthrough, step 3"),
    @("how-to-4",                "How to Download walkthrough, step 4"),
    @("guide-facebook",          "Facebook guide screen"),
    @("guide-instagram",         "Instagram guide screen"),
    @("guide-pinterest",         "Pinterest guide screen"),
    @("guide-x",                 "X guide screen"),
    @("sheet-media",             "Detection sheet - the swipeable video cards"),
    @("sheet-quality",           "Quality picker inside the detection sheet"),
    @("sheet-tabs",              "Tab switcher sheet"),
    @("sheet-history",           "History sheet"),
    @("sheet-shortcut-picker",   "Add-a-site shortcut picker sheet"),
    @("sheet-default-browser",   "Set as default browser sheet"),
    @("sheet-download-more",     "Downloads row - the 3-dot more sheet"),
    @("dialog-clipboard-link",   "Copy a link elsewhere, reopen the app"),
    @("dialog-guide",            "Guide dialog shown over a loaded site"),
    @("dialog-rename",           "Rename dialog from the row menu"),
    @("dialog-delete",           "Delete confirmation dialog"),
    @("dialog-property",         "Property dialog from the row menu"),
    @("dialog-sort",             "Sort by dialog, top right of Downloads"),
    @("dialog-remove-shortcut",  "Long-press a home shortcut to remove it"),
    @("notification-progress",   "Pull down the shade during a download"),
    @("notification-complete",   "Shade after a download finishes"),
    @("notification-push",       "Shade with a pushed video link")
)

if ($Only) {
    Write-Host "Put the screen on the device, then press Enter." -ForegroundColor Cyan
    Read-Host | Out-Null
    Save-Screen $Only
    exit 0
}

Write-Host "$($screens.Count) screens. Enter captures, S skips, Q quits." -ForegroundColor Cyan
Write-Host "Files land in $outDir`n"

for ($i = $From - 1; $i -lt $screens.Count; $i++) {
    $name = $screens[$i][0]
    $what = $screens[$i][1]
    Write-Host "[$($i + 1)/$($screens.Count)] $name" -ForegroundColor Yellow
    Write-Host "  $what"
    $key = Read-Host "  Enter / s / q"
    if ($key -eq "q") { break }
    if ($key -eq "s") { Write-Host "  skipped"; continue }
    Save-Screen $name
}

Write-Host "`nDone. $(@(Get-ChildItem $outDir -Filter *.png).Count) PNGs in $outDir" -ForegroundColor Cyan
