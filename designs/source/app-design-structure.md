# Video Downloader — Screen-by-Screen Design Structure

Purpose: This file is the single source of truth for designing every screen of the app in Claude design.
Execute **one screen per design session**, in the order listed. Always paste **Section 0 (Design Foundation)** together with the screen's section so the design stays consistent.

Design philosophy (applies to every screen):
- **One primary action per screen.** Everything else is secondary or hidden behind progressive disclosure.
- **Never over-complicate.** If a feature can live behind a long-press, overflow menu, or sheet — put it there. The first glance at any screen must look as simple as the current MVP.
- **Thumb-first.** Primary actions in the bottom 40% of the screen. Destructive actions never near primary actions.
- **Bottom sheets over new screens** for transient tasks (quality pick, sort, item menu). Full screens only for destinations (Home, Downloads, Player, Settings).
- **Every list has an empty state, a loading state, and an error state.** Specified per screen below.

---

## 0. DESIGN FOUNDATION (paste with every screen)

### 0.1 Brand & Color

App name: **Free Video Downloader**. Brand personality: fast, safe, private. Not playful, not corporate — clean utility.

Light theme:
- `bg` #FAF7F6 — app background (warm off-white)
- `surface` #FFFFFF — cards, sheets, dialogs
- `surface-alt` #F2ECEA — input fields, chips, secondary buttons
- `ink` #211B1A — primary text
- `ink-soft` #6B605D — secondary text
- `ink-faint` #9C8F8A — tertiary text, placeholders, disabled
- `accent` #D93A3A — brand red: primary buttons, FAB, active tab, links, progress bars
- `accent-soft` #FBE3E1 — selected states, badges background, highlights
- `line` #E7DDDA — dividers, card borders
- `success` #2E7D4F — completed states
- `warning` #B07818 — paused/attention states
- `error` #C62828 — failed states (distinct from accent by context: always paired with an error icon)

Dark theme:
- `bg` #171212, `surface` #201A19, `surface-alt` #2A2322
- `ink` #F0E8E5, `ink-soft` #B3A6A1, `ink-faint` #7D716C
- `accent` #E05F5C, `accent-soft` #3E2422
- `line` #362D2B, `success` #5CBB86, `warning` #D9A64C, `error` #E57373
- Dark theme follows system by default; manual override lives in Settings.

### 0.2 Typography

One family throughout: **Roboto / system sans** (Android native). No display font — this is a utility app.
- `title-xl` 24sp / bold — screen titles ("Downloads", "Settings")
- `title` 18sp / semibold — sheet titles, dialog titles, video titles in player
- `body` 15sp / regular — list item titles (max 2 lines, ellipsize end)
- `body-sm` 13sp / regular — metadata lines (size • quality • duration)
- `label` 12sp / medium / letter-spacing 0.08em / UPPERCASE — section labels ("QUALITY", "CONTINUE WATCHING")
- `button` 15sp / semibold
- Numbers in progress/sizes use tabular figures.

### 0.3 Shape, Spacing, Elevation

- Base spacing unit 4dp. Screen side padding **16dp**. Section vertical gap **24dp**. List item internal padding **12dp**.
- Corner radius: cards/thumbnails **12dp**, sheets **20dp top corners**, buttons **12dp**, chips/pills **full round**, dialogs **16dp**.
- Elevation: sheets and dialogs use scrim (40% black) + shadow. Cards use 1dp border (`line`) instead of shadow — flatter, calmer.
- Bottom sheets always have a **32×4dp drag handle**, centered, `ink-faint`, 8dp from top.

### 0.4 Core Components (reused everywhere)

- **Bottom navigation bar**: 2 tabs only — `Home` (house icon) and `Downloads` (download icon). 56dp tall, `surface` background, top hairline `line`. Active tab: `accent` icon + label; inactive: `ink-faint`. A small numeric badge (accent dot with count) appears on Downloads while anything is downloading.
- **Detection FAB**: 56dp circle, `accent`, white download icon, count badge (white circle, accent text, top-right, 20dp) showing number of detected videos. Sits bottom-right, 16dp margins, above bottom nav. Hidden when count is 0. Gets a single 300ms pulse animation when count increases (no looping animation).
- **Primary button**: full-width, 52dp tall, `accent` bg, white text, radius 12dp. One per screen/sheet maximum.
- **Secondary button**: same shape, `surface-alt` bg, `ink` text.
- **Video thumbnail**: 16:9, radius 12dp, duration chip bottom-left (black 70% pill, white 11sp text), play glyph centered (white 60% circle 40dp) when tappable-to-play.
- **List row (video)**: 96×54dp thumbnail left, title (body, 2 lines) + metadata (body-sm, `ink-soft`) right, 3-dot overflow (24dp touch target 48dp) far right.
- **Empty state pattern**: centered, max 280dp wide — 96dp line-art illustration in `ink-faint`/`accent-soft`, title (title, `ink`), one sentence (body-sm, `ink-soft`), optional single primary button. Never more than that.
- **Snackbar**: bottom, above nav bar, `ink` bg (`surface` in dark), 4s, single action word in `accent`.

### 0.5 Iconography & Motion

- Material Symbols, rounded style, 24dp default. Never mix icon styles.
- Motion: 200–250ms, standard easing. Sheets slide up. Screens slide horizontally. No bounce, no parallax. Respect reduced-motion.

### 0.6 Navigation Map

```
Splash → (first run) Walkthrough → Home
Bottom nav:  Home ⟷ Downloads
Home → Search screen, Browser page, Tab switcher, Settings, History
Browser page → Media sheet → (download) → snackbar → stays on page
Downloads → Player, item sheets/dialogs, Collections, Storage manager
Player → PiP (system), queue sheet
Settings → App lock setup, theme, etc.
```

---

## SCREEN EXECUTION ORDER

| # | Screen | Priority |
|---|--------|----------|
| 01 | Home (browser start page) | P0 |
| 02 | Browser page view | P0 |
| 03 | Media detection sheet (single + multi) | P0/P1 |
| 04 | Search / address screen | P0 |
| 05 | Tab switcher (incl. private) | P0/P1 |
| 06 | Downloads (library) | P0 |
| 07 | Downloads — select mode & item sheets | P0 |
| 08 | Collections & collection detail | P1 |
| 09 | Player | P1 |
| 10 | Settings | P0 |
| 11 | App lock & private folder | P1 |
| 12 | History sheet | P0 |
| 13 | Storage manager | P2 |
| 14 | Splash & walkthrough | polish |
| 15 | Per-site guide dialog | polish |
| 16 | Notifications & widget | P1/P2 |

---

## SCREEN 01 — HOME (Browser start page)

**Purpose:** The front door. Launch sites, resume watching, jump back into recent activity. Replaces today's mostly-empty grid screen. Must still feel as light as the MVP — the new rows only appear when they have content.

**Entry:** App open (after splash), Home tab, home button in browser toolbar.

**Layout, top to bottom:**

1. **Toolbar** (56dp, `bg`, no elevation):
   - Left: app logo mark 28dp (red rounded square with white down-arrow).
   - Center-left: **address/search pill** — `surface-alt`, full-round, 40dp tall, flex-grows. Placeholder "Search or paste link" in `ink-faint`. Leading search icon 20dp. Tapping opens Screen 04 (whole pill is the touch target).
   - Right of pill: **tab counter** — 32dp rounded square outline with tab count centered (opens Screen 05). If a private tab is open, counter square is filled `ink` with a small mask icon.
   - Far right: **overflow (3-dot)** → menu: New tab, New private tab, History, How to Download, Settings.
2. **Paste-link helper (conditional):** if clipboard contains a URL from a supported site and it hasn't been offered before — a dismissible one-line card under toolbar: `surface`, 1dp `line` border, site favicon 20dp, "Link copied — open it?" (body), `Open` text button in `accent`, X to dismiss. Max 1 line, never a dialog on Home itself. (Cold-start clipboard dialog is Screen 15 companion behavior.)
3. **Shortcut grid** — label row: "SITES" (label style) + `Edit` text button (`ink-soft`, toggles jiggle-remove mode).
   - Grid 4 columns, icon 56dp rounded-square (12dp radius) with official-style site glyph, name below (body-sm, 1 line). Rows as needed; last cell is always **Add** (dashed 1.5dp `ink-faint` circle, plus icon) → opens Add-a-site sheet (bottom sheet, 4-column grid of remaining supported sites with names; sheet title "Add a site", subtitle "Sites this app can detect videos from").
   - Long-press a shortcut = same as Edit mode: icons get a small ⊖ badge top-left; tapping ⊖ removes (with snackbar "Removed — Undo"). Tap anywhere else exits edit mode.
4. **Continue Watching** (only if ≥1 video has a saved position and is <95% watched):
   - Label "CONTINUE WATCHING" + `See all` (→ Downloads filtered).
   - Horizontal scroll of cards: 148×83dp thumbnail, thin `accent` progress bar (3dp) flush at thumbnail bottom showing watch position, title below (body-sm, 2 lines). Tap → Player, resumes at position. Max 10 items.
5. **Recent Downloads** (only if ≥1 download exists):
   - Label "RECENT DOWNLOADS" + `See all` (→ Downloads tab).
   - Same horizontal card style, duration chip instead of progress bar. Currently-downloading item shows an indeterminate 3dp accent bar + "Downloading…" as its subtitle. Max 10.
6. **Recently Visited** (only if history exists):
   - Label "RECENTLY VISITED".
   - Horizontal row of compact chips: favicon 16dp + page domain (body-sm), `surface-alt` pill, 36dp tall. Tap → opens that URL in current tab. Max 8, deduplicated by domain.
7. **Bottom nav** (Home active).
8. **Detection FAB:** hidden on Home (nothing to detect).

**States:**
- **Brand-new user:** only toolbar + shortcut grid + a single compact hint card under the grid: illustration 64dp, "Tap a site and play any video — we'll detect it for download." + `How it works` text button (opens walkthrough). No Continue/Recent/Visited rows. This is intentionally almost identical to the current MVP home.
- Rows never show skeletons on Home; they simply appear when data exists.

**Keep it simple:** No feeds, no trending content, no news. Maximum 3 content rows. Home must render instantly.

---

## SCREEN 02 — BROWSER PAGE VIEW

**Purpose:** Web page with detection running. The user should barely notice the app around the page — until a video is found.

**Entry:** Tapping a shortcut, history item, suggestion, link, push, or clipboard link.

**Layout:**

1. **Toolbar** (same 56dp bar as Home):
   - Left: home icon (→ Home).
   - Back/forward chevrons (forward only visible when available).
   - **Address pill**: shows current domain (not full URL), lock icon if https, refresh icon at right inside pill (becomes X while loading). Tap pill → Screen 04 with URL prefilled and selected.
   - Tab counter + overflow (same as Home; overflow adds: "Find in page", "Share page", "Open in external browser").
   - **Private tab variant:** toolbar background `ink` (dark strip), pill darker, small mask icon before domain. Everything else identical.
2. **Progress bar:** 3dp `accent` line directly under toolbar while loading.
3. **WebView:** full remaining area. Pull-to-refresh disabled (sites have their own scroll behaviors).
4. **Detection FAB** (bottom-right, above nav): per Foundation 0.4. Badge = detected count. Tap → Screen 03.
   - While a download is starting from this page: FAB briefly morphs to a checkmark (600ms) then returns.
5. **Bottom nav:** visible. Home tab is active-state since browser lives in Home tab context.

**States:**
- Page error: centered empty-state (broken-link illustration, "Couldn't load this page", "Check your connection and try again", `Retry` primary button).
- Detection found first video: FAB animates in with single pulse; **no** toast, **no** auto-opening sheets.

**Keep it simple:** No bookmarks bar, no reader mode, no zoom controls. The browser is a means to an end.

---

## SCREEN 03 — MEDIA DETECTION SHEET

**Purpose:** Review detected videos, choose quality (or audio-only), download one or many. This is the money screen — it must stay one-glance simple for the single-video case while scaling to multi-video pages.

**Entry:** Detection FAB tap. Bottom sheet over the page (page dimmed 40%).

**Layout (single video or default view):**

1. Drag handle.
2. **Header row:** "1 video detected" / "4 videos detected" (title) — right side: page indicator "1 / 4" (body-sm, `ink-soft`) when multiple.
3. **Video pager** (horizontal swipe, one card per detected video):
   - Thumbnail 16:9 full-width (live frame if playing), duration chip, centered play glyph — tapping the thumbnail previews the video muted inline (tap again to pause).
   - Below: **title** (title style, 2 lines) then metadata line (body-sm, `ink-soft`): duration • qualities count • source domain.
4. **Quality section:** label "QUALITY".
   - Horizontal row of **quality cards**: min 3 visible, scrollable if more. Card: 100dp wide, `surface-alt`, radius 12dp, centered — resolution (body, semibold, e.g. "720p"), size below (body-sm, `ink-soft`, e.g. "24.1 MB"). Selected: `accent-soft` bg, 1.5dp `accent` border, small accent check badge top-right. Default selection = highest quality ≤ 720p (or the setting "Default quality").
   - **Audio card** always last in the row: music-note icon + "Audio" + estimated size; behaves like any other rung. Downloads M4A/MP3.
   - Protected/DRM stream: card grayed (`ink-faint` text), lock icon, non-selectable; if ALL rungs locked, replace download button with inline notice: "This video is protected and can't be downloaded" (body-sm, `ink-soft`, shield icon).
5. **Primary button:** `Download • 24.1 MB` (updates live with selection). One tap = queue + dismiss sheet + snackbar "Added to downloads — View" (View → Downloads tab).
6. **Multi-video affordance:** when >1 detected, a text button above the primary button, centered: `Download all 4…` (`accent`, body). Tapping switches the sheet to **select mode**:
   - Pager becomes a vertical checklist: each row = small thumbnail 72×40dp, title 1 line, size of currently chosen quality, checkbox right (all pre-checked).
   - One shared quality chip-row at top of the list: "Best ≤720p / Best / Smallest / Audio" (4 segment chips) applied to all.
   - Primary button becomes `Download 4 • 96 MB`. `Cancel` text button returns to pager view.

**States:**
- Still probing sizes: size text shows shimmer placeholder (3-dot), never blocks selection.
- Sheet reopened while items from this page already downloading: their card shows a small accent progress ring on the thumbnail + button reads `Downloading…` disabled for that item.

**Keep it simple:** No format nerd-info (codec/bitrate) on cards — that lives in Property dialog after download. Select mode is hidden until the user asks for it.

---

## SCREEN 04 — SEARCH / ADDRESS SCREEN

**Purpose:** Type or paste a URL / search. Nothing changes in the browser until the user commits.

**Entry:** Address pill tap (Home or Browser). Full screen, keyboard auto-open.

**Layout:**

1. **Top bar:** back arrow + input field (`surface-alt` pill, 44dp): cursor ready, "Search or paste link" placeholder; X clear button when text present. If arrived from a page: URL prefilled and fully selected so typing replaces it.
2. **Copied-link card** (if clipboard URL, not previously declined): favicon + one-line URL (middle-ellipsized) + eye icon to reveal full URL; tap = go. `surface-alt` card, 12dp radius.
3. **Suggestion list** (updates while typing), grouped with label headers:
   - "FROM YOUR HISTORY" — favicon, page title (body), domain (body-sm `ink-faint`), X per row to delete that entry. Deduplicated by URL.
   - "SEARCHES" — search icon rows with insert-arrow (↖) at right that fills the field without committing.
   - Empty field state: shows "RECENT SEARCHES" (max 6) + "RECENTLY VISITED" (max 6) + `Clear history` text row at bottom (`ink-soft`, opens confirm dialog).
4. Enter key / row tap → navigates in current tab, returns to Screen 02.

**Keep it simple:** No voice search, no QR scanner. Google-search fallback for non-URL input (engine choice not exposed except in Settings).

---

## SCREEN 05 — TAB SWITCHER

**Purpose:** Switch, open, and close tabs; enter private browsing.

**Entry:** Tab counter tap. Full screen slide-up.

**Layout:**

1. **Top bar:** X (close switcher, returns to active tab) — title "Tabs" + count — right: overflow (Close all tabs).
2. **Segmented control** (only if a private tab has ever been opened this session; otherwise hidden): `Tabs` | `Private` — `surface-alt` track, active segment `surface` + `ink`. Private segment shows mask icon.
3. **Tab grid:** 2 columns. Card = page thumbnail (radius 12dp, 1dp `line` border), header strip inside top of card: favicon 16dp + page title 1 line (body-sm) + X (28dp target). Active tab card: 2dp `accent` border. Tap = switch & close switcher. Swipe a card sideways = close (with snackbar Undo).
4. **Private tab grid** (private segment): same layout on `ink`-tinted background wash; caption pinned at bottom of grid: "Private tabs and their history vanish when closed" (body-sm, `ink-soft`).
5. **Bottom primary button:** `＋ New tab` (in Private segment: `＋ New private tab`, button colored `ink` with white text instead of accent).

**Keep it simple:** No tab groups, no drag reorder. LRU keeps max 4 live WebViews (older tabs show snapshot and reload on tap) — invisible to design except tabs never "die" visually.

---

## SCREEN 06 — DOWNLOADS (Library)

**Purpose:** Everything downloaded, at a glance; the app's second home. Adds search, filters, collections access, and download-queue clarity without cluttering the MVP list.

**Entry:** Downloads tab; "View" snackbars; notifications.

**Layout:**

1. **Header row:** "Downloads" (title-xl) — right icons: **search** (magnifier), **sort** (arrows icon → existing sort dialog: Newest/Oldest/Name/Largest/Smallest), **layout toggle** (grid/list).
   - Search tap: header morphs into inline search field (back arrow + field + clear); list filters live by filename/title. No separate screen.
2. **Filter chip row** (horizontal scroll, 32dp chips, `surface-alt`; selected = `accent-soft` + `accent` text): `All` · `Videos` · `Audio` · `Unwatched` · `Collections ▾` · `Private 🔒` (Private chip only after app lock configured; requires auth on tap).
   - `Collections ▾` chip opens Screen 08 sheet.
3. **Downloading section** (only when active): label "DOWNLOADING (2)".
   - Row: thumbnail 96×54 with dark overlay + white percent text centered, title 1 line, progress bar 3dp `accent` full row width below title, metadata line: `12.4 MB of 24.1 MB • 2.1 MB/s`, right control: pause/resume icon button; overflow has Cancel. Failed row: bar `error`, metadata shows plain-language remedy ("Link expired — reopen the page"), right control becomes retry icon.
   - Paused (Wi-Fi-only waiting): bar `warning`, metadata "Waiting for Wi-Fi".
4. **Library sections:** day headers ("Today · Aug 14", label style) when sorted by date, else flat.
   - **List row:** per Foundation 0.4. Metadata: `1.2 MB • 720p • 0:30`. **Unwatched dot:** 8dp `accent` dot to the left of the title, disappears after first play. Audio files: thumbnail replaced by square `accent-soft` tile with music-note icon.
   - **Grid tile:** 2 columns, thumbnail + title 2 lines + metadata 1 line + overflow.
   - Watched progress: thin 3dp `accent` bar at thumbnail bottom if partially watched.
5. **Tap** = open Player (Screen 09). **Long-press** = enter select mode (Screen 07). **Overflow (3-dot)** = item sheet (Screen 07).
6. **Bottom nav** (Downloads active, badge with active-download count).

**States:**
- **Empty:** illustration (empty box + play), "No downloads yet", "Videos you download will appear here.", primary button `How to download` (walkthrough). — This is where the How-to entry lives now; the old floating pill is **removed** from populated lists.
- Search-no-results: "No matches for 'xyz'".

**Keep it simple:** Chips replace any tabbed sub-navigation. Collections are a filter, not a new tab.

---

## SCREEN 07 — DOWNLOADS: SELECT MODE, ITEM SHEET & DIALOGS

**Purpose:** Batch actions and per-item actions without leaving the library.

**A. Select mode** (entered by long-press or overflow → Select):
1. Header morphs: X (exit) — "3 selected" (title) — `Select all` text button.
2. Every row/tile shows a 22dp checkbox (right side in list, top-left overlay on grid). Tap toggles; rows animate a subtle `accent-soft` background when selected.
3. **Bottom action bar** slides up over the nav (56dp, `surface`, top hairline): 4 evenly spaced icon+label actions: `Share` · `Add to collection` · `Lock` (move to private; only if app lock set) · `Delete` (icon `error` tint). Delete → confirm dialog: "Delete 3 videos? They'll be removed from your device." `Cancel` / `Delete` (error text).

**B. Item sheet** (3-dot on any item; bottom sheet):
1. **Header:** small thumbnail 64×36 + full filename (2 lines max) + metadata line. (Replaces the current orange gradient header — use plain `surface`.)
2. Action rows (56dp, icon 24dp + body text): `Play` · `Share` · `Rename` · `Add to collection` · `Move to private` (lock icon; only if app lock set) · `Properties` · `Delete` (icon+text `error`).

**C. Dialogs** (all: `surface`, 16dp radius, title + content + right-aligned text buttons):
- **Rename:** single text field prefilled without extension, extension shown as fixed suffix chip ".mp4" (`ink-faint`), helper text "Extension can't be changed". `Cancel` / `Save`.
- **Properties:** two-column key/value list — Name, Size, Duration, Quality, Type, Source (domain, tappable → opens page), Saved date, Location. `OK`.
- **Delete:** as above.
- **Sort:** radio list (Newest/Oldest/Name A–Z/Largest/Smallest). Applies instantly on tap (no OK button).

**Keep it simple:** No move-to-SD, no format conversion here.

---

## SCREEN 08 — COLLECTIONS

**Purpose:** Lightweight folders for the library. Powers organization without a mandatory step — downloads never *require* a collection.

**A. Collections sheet** (from `Collections ▾` chip or "Add to collection"):
1. Title "Collections".
2. Row list: folder icon (in `accent-soft` square 40dp) + name + count (body-sm `ink-soft`) + (in add-mode) checkbox.
3. Last row: `＋ New collection` (accent text) → inline text field appears in place with `Create` button. No separate dialog.
4. In filter-mode: tapping a collection closes the sheet and filters Downloads with a dismissible chip `Workout ✕` shown in the chip row.

**B. Collection detail** (optional full screen when opened from See-all contexts): standard Downloads list layout scoped to the collection; header shows collection name + overflow (Rename collection, Delete collection — deleting a collection never deletes videos, dialog states this explicitly).

**Keep it simple:** One level deep. No nesting, no color labels, no cover editing (cover = latest item's thumbnail).

---

## SCREEN 09 — PLAYER

**Purpose:** Watch downloaded videos with modern player ergonomics; keep the session going with a queue and autoplay. Full-screen immersive, dark regardless of theme.

**Entry:** Any video tap; Continue Watching; notification "Watch now".

**Layout (controls overlay, auto-hides after 3s, tap to toggle):**

1. **Top bar** (gradient scrim from black 60% to transparent): back arrow — title 1 line middle-ellipsized — icons: rotate-lock, aspect-mode cycle, PiP, overflow (Playback speed, Loop, Subtitles, Share, Delete).
2. **Center controls:** previous (skip to previous library item) · replay-10s · **play/pause 64dp** · forward-10s · next.
3. **Bottom area:**
   - **Seek bar:** `accent` played portion, white 30% remainder, 14dp thumb (grows on touch). Elapsed / total (body-sm, tabular) at ends.
   - Below bar left: `1x` speed chip (tap cycles 0.5/1/1.25/1.5/2; long-press opens the list). Right: **queue icon** with count.
4. **Gestures** (always active, controls hidden or not):
   - Double-tap left/right half = ±10s with ripple arc + "-10s"/"+10s" label.
   - Vertical swipe right half = volume; left half = brightness — thin vertical indicator pill with icon appears at swipe side.
   - Swipe down = exit to library (or into PiP if setting "PiP on swipe/home" is on — default on).
   - Long-press anywhere = 2× speed while held ("2x ▶▶" chip at top).
5. **Queue sheet** (queue icon or swipe-up on bottom edge): bottom sheet, current item highlighted with `accent` bar + moving-equalizer icon; reorder by drag handle; "AUTOPLAY NEXT" toggle switch pinned at sheet top (default on). Queue = current filter context from library.
6. **Autoplay countdown:** on video end (if enabled): thumbnail card of next video center-screen, circular 5s countdown around a play button, "Up next: {title}", `Cancel` text button below.
7. **PiP:** system PiP with play/pause + close actions. Entered via PiP icon, home press, or swipe-down (per setting).
8. **Background audio:** overflow → "Play in background" — continues audio with a media-style notification (artwork thumbnail, play/pause/close).
9. **Audio files:** same player; artwork area shows large square thumbnail or music tile on `bg` black; identical controls.

**States:** Corrupt/unplayable file → centered dialog "Can't play this file" with `Delete` / `Close`.

**Keep it simple:** No filters/EQ, no A-B loop, no screenshot button. Subtitles = auto-load embedded + same-name .srt only, one toggle in overflow.

---

## SCREEN 10 — SETTINGS

**Purpose:** Home for personalization and every toggle referenced elsewhere. Plain, grouped, boring — in the best way.

**Entry:** Overflow menu → Settings.

**Layout:** Full screen, back arrow + "Settings" title. Grouped sections with label headers; rows 56dp: icon (`ink-soft`) + title (body) + value/subtitle (body-sm `ink-faint`) + control (switch / chevron).

1. **DOWNLOADS**
   - Default quality — chevron → radio sheet: Always ask (default) / Best / Best ≤720p / Smallest / Audio only.
   - Download over Wi-Fi only — switch (off default). Subtitle: "Queued downloads wait for Wi-Fi".
   - Parallel downloads — chevron → radio: 1 / 2 (default) / 3.
   - Download location — chevron (system picker), subtitle shows current path.
2. **APPEARANCE**
   - Theme — chevron → radio sheet: System (default) / Light / Dark.
   - App language — chevron (system locale picker).
3. **PRIVACY**
   - App lock — chevron → Screen 11 (subtitle: Off / Fingerprint / PIN).
   - Private folder — chevron → Screen 11B (visible only when app lock set).
   - Clear browsing history — row, tap → confirm dialog.
   - Clear search history — row → confirm.
4. **NOTIFICATIONS**
   - Download complete — switch (on).
   - Unwatched reminders — switch (on). Subtitle: "At most twice a week".
5. **GENERAL**
   - Set as default browser — row (hidden if already default), opens system role dialog.
   - How to download — row (walkthrough).
   - Storage used — chevron → Screen 13. Subtitle: "1.2 GB in 43 videos".
6. **ABOUT**
   - Share this app · Rate this app · Version row (version number as subtitle).

**Keep it simple:** No search-in-settings; the list fits on ~2 screens. Every row's effect is stated in its subtitle.

---

## SCREEN 11 — APP LOCK & PRIVATE FOLDER

**A. App lock setup** (from Settings):
1. Choice screen: two large option cards (icon + title + one-line description): "Fingerprint / Face" (biometric, recommended badge) and "PIN". Below: "App lock protects the whole app when opened" (body-sm).
2. PIN setup: 4-digit, custom keypad (large 64dp keys, `surface-alt` circles), dots indicator, step 1 "Create PIN" → step 2 "Confirm PIN". Error shake on mismatch.
3. Lock screen (whenever app opens with lock on): logo, "Unlock Video Downloader", biometric auto-prompt; PIN keypad fallback below. `bg` background, nothing else visible (content behind never rendered).

**B. Private folder:**
1. Intro sheet (first use): shield illustration, "Private folder", bullets: "Hidden from your gallery" / "Only visible after unlock" / "Not shown in Downloads". Primary `Turn on`.
2. Access: `Private 🔒` chip in Downloads → biometric/PIN prompt → Downloads list scoped to private items, header shows "Private" with lock icon and a distinct `ink`-tinted header wash so the user always knows where they are. Items here are absent from MediaStore/gallery.
3. Moving items: "Move to private" from item sheet / select mode → snackbar "Moved to Private".

**Keep it simple:** One PIN for app + folder (no separate credentials). No decoy modes.

---

## SCREEN 12 — HISTORY SHEET

**Purpose:** Find and reopen visited pages fast.

**Entry:** Overflow → History. Bottom sheet at 90% height.

**Layout:**
1. Drag handle; header: "History" (title) — `Clear all` text button (`accent`) → confirm dialog.
2. **Search field** (`surface-alt` pill, 40dp) under header, filters live.
3. List grouped by day labels ("Today – Aug 14", "Yesterday", "Earlier"). Row: favicon 24dp + page title (body, 1 line) + domain (body-sm `ink-faint`) + time (body-sm, right) + X to delete row. **Consecutive duplicate visits collapsed** into one row with a subtle "×4" count chip.
4. Tap → opens in current tab, closes sheet.
5. Empty: "No history yet" mini empty-state; private-tab browsing writes nothing (note only in Screen 05 caption, not here).

---

## SCREEN 13 — STORAGE MANAGER

**Purpose:** Prevent the storage-full uninstall.

**Entry:** Settings → Storage used.

**Layout:**
1. Back + "Storage" title.
2. **Summary card:** horizontal stacked bar (rounded 8dp): segments `accent` = this app's videos, `ink-faint` = other content, `line` = free. Legend below with values ("Videos 1.2 GB · Other 38 GB · Free 12 GB"). Headline above bar: "1.2 GB used by 43 videos" (title).
3. **Quick action card:** "Watched videos" — "18 videos you've finished watching · 640 MB" + secondary button `Review & free up` → opens Downloads in select-mode pre-filtered to watched, all pre-checked, delete bar ready. (Reuses Screen 07 — no new UI.)
4. **Largest files:** label + top-10 list rows (standard rows + size emphasized bold) with overflow per row.

**Keep it simple:** No auto-delete rules, no cache management exposure.

---

## SCREEN 14 — SPLASH & WALKTHROUGH

**A. Splash:** `bg` full screen, centered 96dp app icon, app name (title-xl) below, tagline "One-click fast download" (body-sm `ink-soft`), thin indeterminate `accent` bar bottom (only if load >600ms). Subtle brand shapes in corners at 4% opacity. Max 1.5s.

**B. Walkthrough (4 pages, shown once; re-entry from Settings/empty state):**
- Full screen, `bg`. Top-right `Skip` text button (pages 1–3).
- Each page: numbered accent chip + heading (title-xl, e.g. "1 · Open a site"), flat illustration area ~50% height (simplified app-UI mock in brand colors — reuse actual component styles, not generic clip-art), one supporting sentence (body, `ink-soft`, max 12 words).
- Pages: 1 Open a site → 2 Play any video → 3 Tap the download button (FAB illustration with badge) → 4 Pick a quality (quality cards illustration).
- Bottom: 4-dot page indicator (active = accent pill) + primary button `Next` → page 4: `Start browsing`.

---

## SCREEN 15 — PER-SITE GUIDE DIALOG & CLIPBOARD DIALOG

**A. Per-site guide** (over a just-opened site, e.g. Instagram):
- Dialog card (16dp radius, 90% width): title "How to download from Instagram" — horizontal step pager (2–4 steps): step chip + short instruction (bold app/menu names) + real annotated screenshot with accent arrow overlay — dot indicator — primary `Open Instagram app` (deep link) + `Got it` text button. X top-right. "Don't show again" checkbox bottom-left (body-sm).

**B. Clipboard link dialog** (cold app open with copied supported link):
- Compact dialog: title "Link you copied", URL in `surface-alt` rounded box (middle-ellipsized, 2 lines max), primary `Open`, text `Cancel`. Site favicon 24dp above URL. Never re-offers a declined link.

---

## SCREEN 16 — NOTIFICATIONS & WIDGET

**A. Download progress notification:** app icon, title = video filename (1 line), progress bar, subtitle "12.4 MB of 24.1 MB • 2.1 MB/s", actions: `Pause` / `Cancel`. Silent channel.
**B. Complete notification:** thumbnail as large icon, "Download complete", filename subtitle, actions: `Watch now` (→ Player) / `Share`. Default-priority channel.
**C. Unwatched reminder (local, ≤2/week, opt-out in Settings):** "3 videos waiting for you" + grid-style BigPicture of latest thumbnail, action `Open library`. Tapping → Downloads filtered to Unwatched.
**D. Widget (4×2):** rounded 16dp container, `surface`; header row: app icon 20dp + "Downloads" + `＋ Paste link` chip (accent, opens app with clipboard flow); below: 2 recent thumbnails (16:9) with duration chips, tap → Player. Empty state: single row "Paste a link to download" with paste icon.

---

## APPENDIX — CROSS-SCREEN RULES CHECKLIST

- [ ] Every screen designed in **both light and dark** themes.
- [ ] Detection FAB appears **only** in Screen 02 and never overlaps sheets.
- [ ] "How to Download" floating pill removed everywhere; entries live in: empty state (06), overflow (01), Settings (10), walkthrough re-entry.
- [ ] All destructive actions (delete, clear) always behind a confirm dialog with the consequence stated in one sentence.
- [ ] File names shown to users always come from smart naming (title → page title → Site · date); never "video player_NNNNN".
- [ ] Private content: never in gallery, never in widget, never in notifications, never in Continue Watching / Recent rows.
- [ ] Snackbars for reversible actions (remove shortcut, close tab, move to private); dialogs only for irreversible ones.
- [ ] Touch targets ≥48dp; text contrast ≥4.5:1 in both themes; TalkBack labels on all icon-only buttons.
