# Video Downloader Growth Plan

Product review · MVP → v2 · Aug 14, 2026

Analysis of the current MVP (30 screenshots + developer component summary), the engagement gaps holding it back, and a prioritized feature roadmap — ending with a consolidated functionality checklist to verify.

---

## The verdict

**The engine is genuinely strong; the app around it is purely transactional.** Four-layer detection, honest quality ladders with real sizes, resumable HLS/DASH/progressive downloads, and safe MediaStore handling put the technical core ahead of most competitors in this category.

But every current flow ends the session: open → detect → download → leave. There is no reason to stay (empty home screen, single-video player, no queue) and few reasons to come back (no library organization, no re-engagement loop, no settings to personalize).

> **The growth thesis:** reposition from "downloader" to "private video browser + library + player." Downloads get users in the door; the library and player are what create daily sessions.

---

## What already works well

Worth protecting — these are the moats.

- **Detection depth** — network sniffer + DOM scan + MSE/fetch hook + 22 site extractors; quality ladders recovered from platform JSON, not just what the page fetched.
- **Honest quality picker** — real resolutions and byte sizes; DRM/unsupported streams marked up-front instead of failing mid-download.
- **Resilient downloads** — chunk/segment checkpointing survives process death; DASH audio+video muxed safely; foreground service with pause/resume/retry.
- **Session-count seeds already planted** — clipboard link prompt, default-browser prompt, FCM push, per-site guides. These just need a strategy on top.
- **Sane library plumbing** — unified downloads list, MediaStore rename that doesn't destroy files, regenerated thumbnails.

---

## UX gaps visible in the screenshots

Observed issues, each tied to the screen that shows it.

1. **The home screen is 70% dead space.** Seven shortcuts and nothing else. This is the app's front door and its biggest unused engagement surface. *(browser-home.png)*
2. **Generic auto-filenames poison the library.** "video player_55572", "video player_293" — once a user has 30 downloads, the library becomes unbrowsable. Title metadata exists in the extractors but isn't reaching the filename for many sources. *(downloads-list.png)*
3. **The "How to Download" pill permanently covers the last list row.** Onboarding aids shouldn't occupy the library forever; it belongs in the empty state and overflow menu. *(downloads-list.png, downloads-grid.png)*
4. **No settings screen exists anywhere.** The overflow menu holds only New tab / History / How to Download. There is nowhere to set default quality, Wi-Fi-only, theme, or download location — and nowhere to hang future features. *(menu-overflow.png)*
5. **Downloads has no search, filter, or multi-select.** Sorting exists, but finding one video among many, or deleting five at once, is impossible. *(downloads-list.png, dialog-sort.png)*
6. **History is a raw log.** Four identical consecutive "Threads" entries; no de-duplication, no search. *(sheet-history.png)*
7. **The quality sheet offers video only.** No audio-only / MP3 rung, one of the most-searched features in this category. *(sheet-quality.png)*
8. **The player is a dead end.** Good basics (speed, aspect, rotation), but no queue, no autoplay-next, no PiP, no seek/volume gestures — the session ends when the video does. *(player.png)*
9. **Light theme only.** Every screen is light; a video-centric app is used heavily at night. *(all screens)*
10. **Guides teach "Share to," but share-target behavior needs verifying.** The Instagram guide instructs users to tap "Share to" in the Instagram app — the app must reliably appear in that share sheet and route straight to detection, or the primary acquisition loop breaks. *(guide-instagram.png)*

---

## Recommended features, by the metric they move

Each item is tagged with the primary metric it targets.

### Bring users back (session count, DAU)

- **Share-sheet target from other apps** *(sessions / user)* — Register as an ACTION_SEND / link-share target so "Share → Video Downloader" from Instagram, TikTok, or X opens the app directly into detection. This is the single biggest session-count driver in this category and it completes the loop the per-site guides already teach.
- **Local re-engagement notifications** *(D1–D7 retention)* — "3 videos you downloaded are still unwatched", "Download complete — watch now" with a thumbnail, and a weekly library digest. FCM infrastructure exists; this adds a local, content-aware layer with sensible caps and opt-out.
- **Home-screen widget + "paste link" quick action** *(sessions / user)* — A small widget showing the last two downloads plus a paste-and-download button; an app shortcut for "Paste link." Puts the app one glance away instead of buried in the drawer.
- **Android App Links for supported sites** *(sessions / user)* — Complements the existing default-browser prompt: tapping a Pinterest/Dailymotion link anywhere can open in-app, landing users on a page where detection immediately fires.

### Keep users in the app (session length)

- **Home start page: Continue Watching + Recent** *(session length)* — Fill the dead space below the shortcut grid with three rows: Continue Watching (resume positions from the player), Recent Downloads, and Recently Visited sites. The front door becomes a reason to browse, not just a launcher.
- **Player v2: queue, autoplay-next, PiP, gestures** *(session length)* — Autoplay the next library video, a swipe-up queue, picture-in-picture on home press, double-tap ±10s, swipe for volume/brightness, and background audio playback. This is what converts a downloader into a daily player — the largest single session-length lever available.
- **Audio-only downloads (MP3/M4A)** *(new use case)* — An audio rung in the quality sheet, extracted with the existing remuxer where possible. Unlocks the music/podcast crowd — an entirely additive audience.
- **Batch download: "Download all" on multi-video pages** *(downloads / session)* — The media sheet already pages through every detected video; add select-all with one quality choice applied across the set, feeding the existing queue.
- **Collections and favorites in the library** *(session length)* — User-created folders ("Workout", "Recipes"), a favorites tab, and unwatched badges. Organization is what makes a library worth returning to — and it powers the Continue Watching row.

### Earn trust and habit (retention)

- **Private mode + app lock** *(D30 retention)* — Incognito tabs (no history writes), a PIN/biometric lock on the app or a hidden private folder for selected downloads. Privacy is a top-3 stated reason users keep apps in this category long-term.
- **Settings screen** *(enabler)* — Default quality, Wi-Fi-only downloads, max concurrent downloads, download location, theme, notification toggles, clear history/cache. Required scaffolding for half the features on this page.
- **Dark theme** *(satisfaction)* — Follow the system by default with a manual override in Settings. Table stakes for a night-heavy video app.
- **Smart file naming** *(library quality)* — Name files from extractor title → page title → "SiteName · date" in that order; never emit "video player_NNNNN." Retroactively offer a one-tap cleanup of existing generic names.
- **Storage manager** *(churn prevention)* — Space used by the app, largest files, and a "clean up watched videos" action — prevents the uninstall that happens when the phone fills up.
- **Rate prompt after the third successful download** *(store growth)* — Trigger at the moment of success, never on open; combine with a "Share this app" entry in Settings.

---

## Suggested build order

### P0 — Quick wins (~2–4 weeks)

- Settings screen (scaffolding first — other features hang off it)
- Home start page rows: Continue Watching, Recent Downloads, Recently Visited
- Smart file naming + retroactive rename offer
- Downloads search, filter, and multi-select (batch delete/share)
- Dark theme
- Move the "How to Download" pill into the empty state and overflow menu
- Verify/fix share-target registration so "Share to" from other apps works everywhere

### P1 — Core engagement (~4–8 weeks)

- Player v2: autoplay-next, queue, PiP, seek/volume/brightness gestures, background audio
- Audio-only (MP3/M4A) rung in the quality sheet
- Batch "Download all" on multi-video pages
- Private mode + PIN/biometric app lock + private folder
- Local re-engagement notifications (unwatched reminders, watch-now on complete)
- Collections, favorites, unwatched badges
- Android App Links for supported sites

### P2 — Differentiators (later)

- Home-screen widget + paste-link shortcut
- Cast to TV (Chromecast)
- Subtitle support in the player (embedded + sidecar .srt)
- Storage manager
- Scheduled / Wi-Fi-deferred download queue
- Multi-language UI

---

## Final functionality checklist

Everything the finished app should do — existing items to regression-verify (✓) and new items to build and accept (＋).

### Browser & detection

| | Functionality | Verify |
|---|---|---|
| ✓ | Tabbed browsing with tab switcher, previews, persistence across restarts | 4-tab LRU behavior; restored tabs reload fresh |
| ✓ | Detection FAB shows live count on pages with media | Pinterest, FB, Insta, TikTok, X, Reddit, Dailymotion at minimum |
| ✓ | MSE-only sites (FB/Instagram) still surface real media URLs | vd_hook installed before first fetch |
| ✓ | Quality ladder from site extractors, DRM/unsupported marked honestly | Sizes consistent within one ladder |
| ✓ | Search screen: history, suggestions, copied-link chip; page unchanged unless a choice is made | Back out without selecting → no navigation |
| ✓ | History sheet grouped by day, clear-all | — |
| ✓ | Editable shortcut grid + add-a-site picker; removed sites return to picker | — |
| ＋ | Home start page: Continue Watching, Recent Downloads, Recently Visited rows | Rows hide when empty |
| ＋ | Private (incognito) tabs — no history, no suggestions written | Verify nothing persists after close |
| ＋ | History de-duplicated consecutive visits + search box | — |
| ＋ | App Links open supported sites in-app | assetlinks.json verified |

### Download flow

| | Functionality | Verify |
|---|---|---|
| ✓ | Media sheet pages through all detected videos, opens on the playing one | Live-frame thumbnail on reels |
| ✓ | Progressive / HLS (AES-128) / DASH (mux) downloads with resume after process kill | Kill mid-download → resumes, not restarts |
| ✓ | Pause / resume / cancel / retry from queue; survives backgrounding | Foreground service notification present |
| ✓ | Progress + completion notifications; tap opens file | Separate channels |
| ✓ | Finished files published to gallery; partials never appear | Q+ no storage permission |
| ✓ | Error messages map to remedies (expired URL, login needed, timeout) | — |
| ✓ | Clipboard link prompt on open; declined links never re-offered; suppressed on share/deep-link entry | 8-link memory |
| ＋ | Audio-only (MP3/M4A) option in quality sheet | Correct duration + metadata tags |
| ＋ | "Download all" with one quality choice across detected set | Queue ordering sane |
| ＋ | Share-sheet target: share a link from any app → detection opens | Test from IG, TikTok, X, YouTube apps |
| ＋ | Smart naming: extractor title → page title → site+date; never "video player_N" | Retro-rename offer for old files |
| ＋ | Wi-Fi-only and max-concurrent settings respected by the queue | Toggle mid-download |

### Library & player

| | Functionality | Verify |
|---|---|---|
| ✓ | Unified downloads list; in-flight pinned above day sections | — |
| ✓ | Grid/list toggle; 5 sort orders; day headings only on date sort | — |
| ✓ | Row menu: Share / Rename / Property / Delete; rename preserves extension, uses write-grant on API 30+ | Rename must never delete |
| ✓ | Thumbnails regenerated from the file itself | Survive cache eviction |
| ✓ | Player: speed, aspect modes, rotation lock, fullscreen, share, decoder fallback | Awkward files still play |
| ＋ | Search + filter in Downloads; multi-select batch delete/share | — |
| ＋ | Collections/folders, favorites, unwatched badges | — |
| ＋ | Resume positions persisted; Continue Watching row consumes them | — |
| ＋ | Player v2: autoplay-next, queue, PiP, double-tap seek, swipe volume/brightness, background audio, loop | PiP on home press during playback |
| ＋ | App lock (PIN/biometric) and/or private folder | Locked content absent from gallery |
| ＋ | Storage manager: usage, largest files, clean watched | — |

### Onboarding, settings & growth

| | Functionality | Verify |
|---|---|---|
| ✓ | Splash, 4-step walkthrough (once + on demand), per-site guides with open-app button | — |
| ✓ | Default-browser sheet via RoleManager with quiet period | — |
| ✓ | FCM push opens link in new tab, suppresses clipboard dialog | Data-only and notification payloads |
| ＋ | Settings: default quality, Wi-Fi-only, concurrency, location, theme, notifications, clear data | — |
| ＋ | Dark theme (system-follow + manual override) | Every screen, both themes |
| ＋ | Local notifications: unwatched reminder, watch-now on complete — capped and opt-out | No more than 2/week idle pings |
| ＋ | "How to Download" pill only in empty state + overflow | Never covers list rows |
| ＋ | Home-screen widget + paste-link app shortcut | — |
| ＋ | Rate prompt after 3rd successful download; share-app entry | Never on cold open |

---

*Based on the 14 Aug 2026 screenshot set (Realme RMX5313, Android 15) and the developer component summary. Existing-feature descriptions come from that summary; verify against the current build.*
