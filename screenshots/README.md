# Screenshots

Captured 14 Aug 2026 from a Realme RMX5313, Android 15, 720×1600 @ 320dpi.

Re-capture with `capture.ps1` — it walks the same checklist, prompting for each screen:

```
powershell -ExecutionPolicy Bypass -File screenshots/capture.ps1
```

## Screens

| File | What it shows |
| --- | --- |
| `splash.png` | Opening screen |
| `browser-home.png` | Browser tab, shortcut grid |
| `browser-page.png` | Pinterest loaded in the browser |
| `browser-detected.png` | Same page, FAB carrying the detected count |
| `search.png` | Address entry screen |
| `search-suggestions.png` | Same, with suggestions and copied link |
| `downloads-list.png` | Downloads, list layout, day heading |
| `downloads-grid.png` | Downloads, grid layout |
| `player.png` | Player with controls |
| `player-fullscreen.png` | Player fullscreen |

## Walkthrough and guides

| File | What it shows |
| --- | --- |
| `how-to-1.png` … `how-to-4.png` | The four walkthrough steps |
| `guide-facebook.png` | Facebook guide over m.facebook.com |
| `guide-instagram.png` | Instagram guide |
| `guide-pinterest.png` | Pinterest guide |
| `guide-x.png` | X guide |
| `dialog-guide.png` | The guide dialog itself (Pinterest) |

## Sheets

| File | What it shows |
| --- | --- |
| `sheet-media.png` | Detection sheet, one video |
| `sheet-quality.png` | Detection sheet with a 3-rung ladder |
| `sheet-tabs.png` | Tab switcher |
| `sheet-history.png` | History, grouped by day |
| `sheet-shortcut-picker.png` | Add-a-site picker |
| `sheet-default-browser.png` | The app's own default-browser sheet |
| `sheet-download-more.png` | Row menu: Share / Rename / Property / Delete |
| `menu-overflow.png` | Toolbar overflow: New tab / History / How to Download |

## Dialogs

| File | What it shows |
| --- | --- |
| `dialog-clipboard-link.png` | Copied-link offer on reopen |
| `dialog-rename.png` | Rename |
| `dialog-property.png` | Property |
| `dialog-delete.png` | Delete confirmation |
| `dialog-sort.png` | Sort by |
| `dialog-remove-shortcut.png` | Remove shortcut |
| `system-default-browser-role.png` | The Android role dialog reached from "Set as default". Not our UI, kept because it is part of the flow. |

## Notifications

| File | What it shows |
| --- | --- |
| `notification-progress.png` | "Downloading 1 file", shade open |
| `notification-complete.png` | "Download complete" |

## Not captured

- **`downloads-empty`** — the library has downloads in it; needs a device with none.
- **`notification-push`** — needs a real FCM message sent to the device.
