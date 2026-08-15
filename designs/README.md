# Handoff: Video Downloader — full app redesign (v2)

## Overview

A complete screen-by-screen design for the Video Downloader Android app, covering the existing MVP surfaces (browser, detection, downloads, player) and the new v2 surfaces called for in the growth plan (settings, private mode, collections, storage manager, widget, share-target, growth prompts).

19 design files, 18 numbered screens plus a design foundation. Every screen is drawn in **both light and dark theme**, with real states — empty, loading, error, selected, in-flight — not just the happy path.

The product direction behind the work: **reposition from "downloader" to "private video browser + library + player."** Downloads get users in the door; the library and player create the return visit. Every design decision below serves that thesis.

---

## About the design files

The files in `screens/` are **design references written as HTML**. They are prototypes showing intended look, layout, spacing, copy and state — **not production code to copy into the app**.

Each file is a canvas showing several phone frames side by side (360 × 812 CSS px each, which maps to a 360dp-wide Android screen at 1× — multiply by the target density for pixels). A frame is one screen state. Panel labels above each frame (`A · DEFAULT VIEW`, `B · EMPTY`…) identify the state. Below the frames, every file carries a **DESIGN NOTES** block explaining the reasoning, and a **DECIDED** block recording a resolved product question.

The task is to **rebuild these designs natively in the existing Android codebase** using its established patterns — Jetpack Compose or Views, whichever the MVP already uses. Do not embed the HTML, do not port the markup. Read the HTML for exact values (colors, sizes, spacing, copy), then write idiomatic Android.

To view them: open any `.dc.html` in a browser. `support.js` must sit alongside them (it is included in `screens/`). No build step, no network needed beyond the Google Fonts link.

## Fidelity

**High fidelity.** Colors, type, spacing, radii, and copy are final. Recreate pixel-faithfully using the codebase's existing components where they exist. Every value in this README is authoritative; where the README and a file disagree, the file wins.

Two deliberate exceptions, both placeholders:
- **Thumbnails and video frames** are flat gradient blocks. Real thumbnails come from the existing regeneration pipeline.
- **Site favicons** are lettered squares (`Ig`, `Fb`, `Pi`). Use real favicons from the shortcut grid's existing source.

---

## Where to start

Build in this order. It follows the growth plan's P0 → P1 → P2 and puts scaffolding before the features that hang off it.

### Phase 1 — scaffolding (nothing else lands cleanly without these)

| Order | Screen file | Why first |
|---|---|---|
| 1 | `00 Design Foundation` | Theme, colors, type, spacing, components. Build this as the theme layer before any screen. |
| 2 | `10 Settings` + `17 Settings Detail Sheets` | The MVP has no Settings screen at all. Half of v2 needs a toggle to live somewhere. |
| 3 | Dark theme across existing screens | `00` defines both palettes. Do it before adding screens, not after — retrofitting doubles the work. |

### Phase 2 — fix what the MVP already shows (visible wins, low risk)

| Order | Screen file | Replaces |
|---|---|---|
| 4 | `01 Home` | The 70%-empty home screen. Adds the shortcut grid rework, and toolbar home/back/forward. |
| 5 | `06 Downloads` + `07 Select Mode and Item Sheet` | Adds search, filter, multi-select, batch delete/share. Moves the "How to Download" pill out of the list into the empty state and overflow. |
| 6 | Smart file naming + `18 Growth Surfaces` panel C | Kills `video player_55572`. Panel C is the retroactive rename offer. |
| 7 | `12 History Sheet` | De-duplicated consecutive visits, search, retention prompt. |
| 8 | `04 Search Address` | Copied-link chip lives here now (see the clipboard decision below). |

### Phase 3 — core engagement

| Order | Screen file | Feature |
|---|---|---|
| 9 | `09 Player` | Player v2: queue, autoplay-next, PiP, gestures, background audio. Largest session-length lever. |
| 10 | `03 Media Detection Sheet` | Audio-only rung and batch "Download all" with select-all. |
| 11 | `18 Growth Surfaces` panel A | Share-sheet target. Biggest session-count driver in the category. |
| 12 | `11 App Lock and Private Folder` + `05 Tab Switcher` private segment | Private mode, app lock, private folder. |
| 13 | `08 Collections` | Folders, favorites, unwatched badges. |
| 14 | `16 Notifications and Widget` panels A–B | Progress, complete and reminder notifications. |

### Phase 4 — the rest

`13 Storage Manager`, `16` panels C–D (widget + app shortcuts), `14 Splash and Walkthrough`, `15 Per-site Guide and Clipboard Dialogs`, `18` panels B/D/E (rate prompt, share app, shortcuts).

---

## Design tokens

Define these once, in the theme layer, from `00 Design Foundation`.

### Color — light

| Token | Hex | Use |
|---|---|---|
| `surface` | `#FAF7F6` | Screen background |
| `surface-card` | `#FFFFFF` | Cards, sheets, dialogs, bottom nav |
| `surface-alt` | `#F2ECEA` | Inset fills, chips, unselected rungs, keyboard keys |
| `line` | `#E7DDDA` | 1dp borders and dividers |
| `ink` | `#211B1A` | Primary text |
| `ink-soft` | `#6B605D` | Secondary text, icons |
| `ink-faint` | `#9C8F8A` | Tertiary text, timestamps, disabled |
| `accent` | `#D93A3A` | FAB, primary buttons, selection, active tab |
| `accent-soft` | `#FBE3E1` | Accent fills, selected rows, badges |
| `error` | `#C62828` | Destructive text and icons |
| `success` | `#2E7D4F` | Confirmation ticks |

### Color — dark

| Token | Hex | Use |
|---|---|---|
| `surface` | `#171212` | Screen background |
| `surface-card` | `#201A19` | Cards, sheets, dialogs |
| `surface-alt` | `#2A2322` | Inset fills, chips |
| `line` | `#362D2B` | Borders and dividers |
| `ink` | `#F0E8E5` | Primary text |
| `ink-soft` | `#B3A6A1` | Secondary text |
| `ink-faint` | `#7D716C` | Tertiary text |
| `accent` | `#E05F5C` | Same roles as light accent |
| `accent-soft` | `#3E2422` | Accent fills |
| `error` | `#E57373` | Destructive |
| `success` | `#5CBB86` | Confirmation |

Text on dark `accent` is `#171212`, not white — the accent is light enough that white fails contrast.

### Typography

Roboto throughout. No second family.

| Role | Size / weight / line-height | Notes |
|---|---|---|
| Screen title | 22px · 700 · 1.2 | Toolbar titles |
| Sheet / dialog title | 18px · 600 · 1.3 | |
| Section heading | 15–16px · 600 · 1.3 | Card headings |
| Body | 15px · 400 · 1.35 | List rows, settings rows |
| Body compact | 14–14.5px · 400 · 1.3 | Dense list rows |
| Body small | 12.5–13px · 400 · 1.45 | Subtitles, descriptions |
| Label | 12px · 500 · 1 · letter-spacing .08em · uppercase | Group headers (`DOWNLOADS`, `TODAY`) |
| Chip / caption | 11–11.5px · 400–500 | Nav labels, duration pills |
| Button | 14–15px · 600 · 1 | |

Every numeric run — sizes, durations, counts, percentages, times — uses `font-variant-numeric: tabular-nums`. On Android: enable the `tnum` font feature so digits don't jitter as they update.

### Spacing, radius, elevation

- **Spacing scale:** 4 / 8 / 12 / 16 / 20 / 24 / 32dp. Screen horizontal padding is 16dp.
- **Radius:** 999 (pill) · 20 (bottom sheet top corners) · 16 (dialog, large card) · 12 (button, card, thumbnail-large) · 10 (inset fill) · 8 (thumbnail, small tile) · 6 (chip square, favicon) · 2 (progress bar).
- **Elevation:** flat by default. Sheets `0 -6px 24px rgba(33,27,26,.22)` light / `rgba(0,0,0,.6)` dark. Dialogs `0 8px 28px rgba(33,27,26,.3)`. Scrim `rgba(0,0,0,.4)` light / `.6` dark. FAB `0 4px 14px rgba(217,58,58,.35)`.
- **Touch targets:** minimum 48 × 48dp, including the icon-only ones drawn at 40dp with padding. Keypad keys are 64dp circles.

### Motion

200–250ms, standard easing. Sheets slide up; screens slide horizontally. No bounce, no parallax, no looping animation. The only continuous motion in the app is a download progress bar.

---

## Screens

Each entry names the file, what it is, and the decisions a developer can't infer from the pixels.

### `00 Design Foundation`
Not a screen — the system. Palette both themes, type scale, spacing, radii, component inventory (buttons, chips, list rows, sheets, dialogs, snackbars, progress states, FAB, bottom nav, empty states), and motion rules. **Build this first as the theme layer.**

### `01 Home`
The Home tab: toolbar (app mark, address pill, tab counter, overflow), home/back/forward controls, `SITES` shortcut grid (4 columns, 56dp rounded-square tiles, last cell is Add), with edit/jiggle-remove mode. Panels cover default, edit mode, and the paste-link helper card.

### `02 Browser Page`
The WebView screen. Toolbar collapses on scroll. FAB appears with a count badge the moment detection fires. Panels: page loading, page with media detected, no media, dark.

### `03 Media Detection Sheet`
The core interaction. Bottom sheet from the FAB: detected video with live thumbnail, title, quality ladder with **real byte sizes**, audio rung, Download button carrying the size. Panels: single video with a `◀ 1 / 3 ▶` pager, four detected, **select mode with "Select all 4 · 96.0 MB" for batch download**, and protected/DRM plus in-flight states. DRM and unsupported streams are marked up front, never failed mid-download.

### `04 Search Address`
Address bar focus state: history, suggestions, and the **copied-link chip**. Backing out without choosing must leave the page untouched.

### `05 Tab Switcher`
2-column card grid, active tab in a 2dp accent border, per-card close, "New tab" primary. Private segment appears only once a private tab exists this session; it sits on an ink wash with an ink (not accent) primary button. **Private cards are labelled `Private 1`, `Private 2` — numbered, never titled, no favicon.**

### `06 Downloads`
The library. In-flight items pinned above day sections, grid/list toggle, 5 sort orders, search and filter. Panels: list, grid, empty, in-flight, error rows with a remedy action.

### `07 Select Mode and Item Sheet`
Multi-select (batch delete/share/move) and the per-item sheet (Share / Rename / Move to private / Properties / Delete). **Rename must preserve the extension and use the write-grant path on API 30+ — it must never delete and recreate the file.**

### `08 Collections`
User folders, Favorites, unwatched badges. Empty state included.

### `09 Player`
Player v2. Portrait with controls, landscape fullscreen, queue sheet with drag handles, autoplay-next countdown with a cancel ring, gesture zones (double-tap ±10s, swipe volume/brightness) with controls hidden. Resume positions persist and feed the Continue Watching row.

### `10 Settings` + `17 Settings Detail Sheets`
The grouped list (Downloads / Appearance / Privacy / Notifications / General / About) and everything its chevrons open: default-quality radio sheet with consequence subtitles, theme radio, parallel-downloads 3-up segmented control, clear-data confirms naming the real count, the About page with legal rows, and the default-browser prompt sheet. Radio selections apply on tap and dismiss — there is no Save button.

### `11 App Lock and Private Folder`
Method choice (biometric recommended, PIN always set as backup), **3-step PIN setup — enter, confirm, recovery question**, lock screen on cold open, private folder intro, private library on an ink wash, and the mismatch error state. **`Forgot PIN?` on the lock screen answers the recovery question — that is the only route back in. No email reset.** Private items are absent from MediaStore, so they never surface in the gallery, notifications, widget or Recent Downloads.

### `12 History Sheet`
90%-height sheet, grouped Today / Yesterday / Earlier. **Consecutive visits to one URL collapse into one row with a `×4` chip.** Search filters in place with a result count. Per-row delete plus Clear all behind a confirm. **The first Clear all carries a pre-ticked "also clear history older than 30 days automatically", which then becomes a Settings row.** Private browsing writes nothing here.

### `13 Storage Manager`
Space used, largest files, clean-up-watched action, and the post-cleanup state.

### `14 Splash and Walkthrough`
Splash (brand only; progress bar appears only if loading exceeds 600ms, capped at 1.5s) and a 4-page walkthrough. **The walkthrough artwork is deliberately abstract — circles, blocks, one accent shape — so users never mistake an illustration for a live screen they should operate. Do not substitute real screenshots.** Skip is top-right on pages 1–3 and absent on page 4.

### `15 Per-site Guide and Clipboard Dialogs`
Per-site guide as a 3-step horizontal pager with annotated captures, a per-site "Don't show again", and a deep-link primary that falls back to the Play Store. Plus the copied-link dialog. **We never read the clipboard on cold open** — Android 14+ would fire its own paste-access toast for a prompt the user didn't ask for. The clipboard is read only when the user taps the address bar; mid-page the offer is a dismissible strip. A declined link is never offered again (8-link memory).

### `16 Notifications and Widget`
Three channels: **Progress** (silent, ongoing, not swipeable), **Complete** (default priority, thumbnail as large icon, Watch now / Share), **Reminders** (low priority, ≤2/week). Errors carry their remedy as an action — "Use mobile data", "Reopen page". Concurrent downloads collapse into one summary with Pause all. Plus the 4×2 widget (empty / downloading / dark). **Reminders are off by default and offered in-app once three items sit unwatched — panel D shows that opt-in card.**

### `18 Growth Surfaces`
**Share-target landing** (a full screen, not a sheet, since it arrives from outside; shows the detection result inline; **always stops for confirmation — a shared link never auto-downloads**), the **rate prompt after the third successful download** (never on cold open, with "Send feedback" beside "Not now"), the **retroactive rename offer**, **share this app**, and **launcher long-press shortcuts** (Paste link / New private tab / My downloads).

---

## Interactions & behavior

- **Detection → FAB:** the FAB appears only when media is found, with a live count badge. Tapping opens `03`.
- **Sheets** slide up over a 40% (light) / 60% (dark) scrim, dismissible by drag or scrim tap. Sheet height is content-driven except History, which is fixed at 90%.
- **Undo, not confirm:** anything reversible (move to private, delete from a list, close tab) uses a snackbar with Undo. Confirm dialogs are only for genuinely destructive bulk actions, and they name the real count.
- **Errors** always state a remedy in the same surface, mapped from the engine's error types (expired URL → Reopen page; login needed → Open site; timeout → Retry; no Wi-Fi → Use mobile data).
- **Selection** is shown by both an accent-soft row fill and a control state, never by the control alone.
- **Empty states** carry an icon block, one heading, one sentence, and at most one action.

## State management

Screens depend on: detection results per tab (list of media with quality ladders and byte sizes), download queue (progress, speed, paused/failed/queued, checkpoint offsets), library index (with resume positions, watched flags, collection membership, private flag), tab list including private tabs, history with visit collapsing, and the settings store (default quality, Wi-Fi-only, concurrency, location, theme, notification opt-ins, retention, app-lock method).

Two invariants worth stating: **private items must never enter MediaStore**, and **resume positions are what power both Continue Watching and the unwatched count** — the same field, two surfaces.

## Assets

- **Icons:** Material Symbols Rounded throughout. Use the Android Material Symbols font or vector drawables — every glyph name in the HTML (`download`, `visibility_off`, `content_paste`…) maps 1:1.
- **Font:** Roboto, the platform default.
- **Thumbnails and favicons:** placeholders in the designs; wire to the existing pipelines.
- No custom illustration assets are needed — the walkthrough artwork is CSS shapes and should be rebuilt as simple drawables or Compose shapes.

## Files

```
screens/     19 design files (00–18) + support.js — open any in a browser
source/      app-design-structure.md, video-downloader-growth-plan.md, engine-summary.txt
```

`source/` holds the original briefs. `engine-summary.txt` describes the existing detection and download engine, and is the reference for which states the UI must honestly represent.
