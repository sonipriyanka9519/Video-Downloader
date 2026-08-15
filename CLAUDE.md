# CLAUDE.md

Working rules for this repository. Read before making any change.

## What this is

An Android video-downloader app — a browser that detects playable media on a page and saves it.
Being rebuilt from an MVP to v2 against a complete design handoff in `designs/`.

- **Package:** `com.ms.webview` · **Language:** Java · **UI:** XML Views (not Compose)
- **minSdk 24 · targetSdk 36 · compileSdk 36 · Java 11**
- Dependencies are declared through the version catalog at `gradle/libs.versions.toml`. Add there
  first, reference as `libs.*` — never hardcode a coordinate in `app/build.gradle`.
- Sizes use `sdp`/`ssp` (scalable dp/sp) so one set of values holds across screen sizes.

---

## 1. Do not touch the detection and download engine

**This is the strictest rule in the repository.** The video-finding and downloading flow works. It
is the product's moat and it took a long time to get right.

Off-limits without asking first:

- `detect/` — `MediaRegistry`, `NetworkSniffer`, `DomScanner`, `Prober`, `UrlClassifier`,
  `MetadataReader`, the HLS/DASH parsers and resolvers, every `extract/` extractor, every
  `site/` policy, every `page/` resolver, and the injected `vd_scan.js` / `vd_hook.js`.
- `download/` — `DownloadService`, the three downloaders, `Remuxer`, `MediaStorePublisher`,
  `DownloadThumbnails`.
- `data/` — the Room entities and `MediaLibrary`.

If a task appears to need a change in any of these: **stop and ask, describing the change and why
it is unavoidable.** Do not make it and mention it afterwards. A UI task almost never needs one —
if it seems to, the UI is usually asking the wrong question of the engine.

Two consequences worth stating:

- **Never regress a bug that was already fixed here.** Notable ones: rename must use
  `createWriteRequest` and never `createDeleteRequest`; the remuxer's timestamp offset must come
  from the earliest sample, not the first; audio-only streams are identified by decoding, never by
  content type.
- **Keep per-site behaviour in its own file.** A site's quirk belongs in its extractor, its
  `SitePolicy` or its `SheetRules` — never in shared code where it reaches every other site. When
  a shared component needs to behave differently for one platform, add a knob to `SitePolicy` with
  a safe default and override it in that one policy.

### Instagram is frozen

Do not modify Instagram's extractor, policy, page resolver, or `brand_instagram.xml` under any
circumstances. If a change genuinely cannot avoid Instagram, ask first.

---

## 2. Scope discipline

- **Change only what the request names.** If the prompt does not mention a file, do not edit it.
  Fixing something unrelated that you noticed is a separate conversation — raise it, don't do it.
- **One screen at a time.** The design set is built to be executed screen by screen in the order
  in `designs/README.md`. Finish and confirm one before starting the next.
- Read the problem before writing the fix. When a symptom has more than one possible cause,
  gather evidence — a log, a fetched payload, a real file — rather than shipping a guess.

---

## 3. Correctness

The app must not ship errors, crashes, or broken functionality.

- **Nothing may crash.** Null checks on everything crossing a boundary: intent extras, cursor
  columns, JSON fields, MediaStore rows, WebView callbacks, parsed manifests.
- **Fragment and activity lifecycle:** never touch views after `onDestroyView`; check `isAdded()`
  before using a `Context` from an async callback. Cancel or guard every background result that
  lands on a dead screen.
- **RecyclerView:** every bind path must reset every mutable property it ever sets. A view that is
  configured in one branch and not the other will show the previous row's state after recycling.
- **Threading:** no network, disk, or `MediaMetadataRetriever` work on the main thread. UI updates
  only on the main thread.
- **Back-compat to API 24.** Guard every API above it with `Build.VERSION.SDK_INT` and provide a
  real fallback, not a silent no-op. Vector drawables, `RoleManager`, scoped storage, notification
  permission and PiP all differ across the supported range.
- **Verify before claiming done.** State plainly what was tested and what was not. If something is
  unverified, say so.

## 4. Error handling

- **Every failure states a remedy in the same surface.** Map engine errors to plain language:
  expired URL → "Reopen the page"; refused → "Open site"; timeout → "Retry"; no Wi-Fi → "Use
  mobile data". `core/Errors` is where that mapping lives.
- **Never swallow an exception silently.** Either recover meaningfully, or log with enough context
  to diagnose it. An empty `catch` block is a bug.
- **Never present a failure as success.** A download that produced a mute or truncated file must
  fail loudly rather than land in the library looking fine.
- **Degrade, don't disappear.** A missing thumbnail shows a placeholder; a failed row shows a
  retry; an empty list shows an empty state. Blank screens are never acceptable.
- Errors the user cannot act on do not belong in the UI — log them.

## 5. Design fidelity

`designs/` is authoritative. `designs/README.md` carries the tokens and build order; each
`screens/NN *.dc.html` carries the pixels, copy and states. **Where README and a screen file
disagree, the screen file wins.**

- **Pixel-accurate.** Colors, type sizes and weights, spacing, radii, and copy are final. Read the
  exact values out of the HTML — do not eyeball them from a rendering.
- **Build the theme layer first** (`00 Design Foundation`) and take every colour from it. No
  hardcoded hex in a layout or a drawable; everything resolves through theme attributes so light
  and dark come free.
- **Every screen must exist in both themes.** `values-night/` is mandatory, not optional.
- **Every list needs an empty, loading and error state**, as specified per screen.
- **Reuse before creating.** If a component exists, use it. The design is deliberately one system.
- Icons are Material Symbols Rounded, 24dp, one style throughout. Numerals in sizes, speeds,
  counts and timecodes use tabular figures so rows do not jitter as they update.
- Placeholders in the designs — gradient thumbnails, lettered favicons — map to the real pipelines,
  not to literal placeholder art.

## 6. Animation

Use motion where it explains a change; never for decoration.

- 200–250ms, standard easing. Sheets slide up, screens slide horizontally.
- **No bounce, no parallax, no looping animation.** The only continuous motion in the app is a
  download progress bar.
- The detection FAB gets a single 300ms pulse when its count increases — once, not repeating.
- Respect the system reduced-motion setting and animator duration scale.
- Never block interaction while an animation runs.

## 7. Policy compliance

The app must be publishable and stay publishable.

### Google Play

- **Do not add YouTube support** — extraction from YouTube breaks its terms and is specifically
  enforced against on Play. The current extractor set deliberately excludes it. Keep it that way.
- Do not present the app as a tool for pirating copyrighted material. Copy describes downloading
  *your own* or *permitted* content. No copyrighted logos, no platform trademarks used as branding.
- **Request the fewest permissions that work**, request them in context, and degrade gracefully on
  refusal. Never gate the whole app behind a permission.
- **Foreground services must declare a type** and a user-visible justification (API 34+). The
  download service is `dataSync`.
- `POST_NOTIFICATIONS` must be requested at the moment it becomes useful (API 33+), never on first
  launch as a wall.
- Keep the Data Safety declaration truthful. Anything collected, including analytics and crash
  reporting, must be declared.
- Meet the current target-API requirement; do not lower `targetSdk` to dodge a behaviour change.
- Privacy policy must be reachable from the About screen.

### AdMob (when ads are added)

- No ads on any screen showing content the policies prohibit.
- **No accidental clicks:** ads never adjacent to interactive controls, never under a finger's
  resting place, never overlapping the FAB, primary buttons, or the player's controls.
- No ads inside the video player during playback, and none in a notification or widget.
- Interstitials only at natural breaks, never on app open before content, never twice in a row,
  never on a back press that the user expects to dismiss something.
- Implement UMP consent (GDPR/CCPA) before any ad request, and honour the result.
- Never encourage clicks, never label ads misleadingly, never place them where content is expected.
- Test ads only with test unit IDs. A real unit ID must never be requested in a debug build.

### Third parties

- Firebase/FCM: no personal data in payloads; notifications must be opt-out.
- Respect each site's robots and terms; the app is a user-agent acting on the user's behalf.
- Any library added must have a licence compatible with distribution, and be listed in About.

## 8. Privacy invariants

These hold across every feature, without exception:

- **Private items never enter MediaStore.** They therefore never appear in the gallery, the
  widget, notifications, Continue Watching, Recent Downloads, or share targets.
- **Private browsing writes nothing** — no history, no search suggestions, no thumbnails on disk,
  no page titles.
- The clipboard is **never read on cold open**. It is read only when the user taps the address bar,
  which is already an intent to go somewhere. A declined link is never offered again (8-link memory).
- Resume positions power both Continue Watching and the unwatched count — one field, two surfaces.

## 9. Code conventions

- **Write code that reads like the code around it.** Match the existing comment density, naming and
  idiom. This codebase comments the *why* — the reasoning behind a non-obvious decision — not the
  what. Keep that.
- All user-visible text lives in `strings.xml`. No hardcoded strings, no concatenated sentences
  (use placeholders so translations can reorder).
- All dimensions in `dimens.xml` or via `sdp`/`ssp`. All colours via theme attributes.
- Content descriptions on every icon-only control. Touch targets ≥48dp. Text contrast ≥4.5:1 in
  both themes. Layouts must survive RTL and large font scales.
- Keep `Formats` the single source for byte sizes, durations and quality labels so two surfaces
  never disagree.

## 10. Working agreement

- **Do not run gradle builds.** The user builds manually. End each response with
  "Not compiled, as before."
- Do not commit or push unless asked.
- Ask for a log, a screenshot, or a file when a diagnosis needs one — guessing twice on the same
  bug is worse than asking once.
- Report outcomes honestly: if a fix is reasoned rather than verified, say which.
- When a design decision contradicts an earlier instruction from the user, flag the conflict and
  ask rather than silently picking one.

## 11. Reference

| Path | What it holds |
|---|---|
| `designs/README.md` | Tokens, build order, per-screen decisions |
| `designs/screens/` | 19 design canvases — authoritative pixels and copy |
| `designs/source/` | Original briefs, growth plan, engine summary |
| `DESIGN.md` | Pre-existing architecture notes for the MVP |
| `screenshots/` | Captured MVP screens, with `capture.ps1` to refresh them |

`adb` is not on PATH: `C:\Users\Admin\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
Detection logs use the tag `VideoDetect`; download logs use `HlsDownloader`, `Remuxer`,
`DownloadService`.
