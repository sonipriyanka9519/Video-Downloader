/*
 * Layer 2 — DOM scanner, inline-JSON harvester and live frame capture.
 *
 * Runs in every frame (the document-start injection applies to subframes too), so iframe
 * players are covered without traversing into them from the parent.
 *
 * Three jobs:
 *   1. Find media in the DOM, including inside shadow roots.
 *   2. Post inline JSON blobs — __NEXT_DATA__, application/json blocks, window.X = {...}
 *      assignments — for the native extractors to read. News sites and player embeds put
 *      their whole quality ladder there long before any <video> exists.
 *   3. Track the playing video, grab frames from it, and work out which post it belongs to.
 */
(function () {
  if (window.__vd) return;
  window.__vd = true;

  var MEDIA_RE = /\.(mp4|m4v|webm|mkv|mov|3gp|flv|m3u8|mpd)(\?|#|$)/i;
  var FRAME_INTERVAL_MS = 2000;
  var FRAME_WIDTH = 360;
  // Two megabytes. A server-rendered page keeps its data in inline script tags, and on a video
  // permalink the largest of them is the one holding the media JSON — dash manifests and all.
  // Half a megabyte cut off exactly that script and left the quality ladder unread.
  var MAX_SCRIPT_CHARS = 2 * 1024 * 1024;
  var MAX_SHADOW_DEPTH = 4;

  var seen = Object.create(null);
  var seenScripts = Object.create(null);
  var lastFrameAt = 0;

  function post(payload) {
    try {
      if (window.__vdBridge && window.__vdBridge.postMessage) {
        window.__vdBridge.postMessage(JSON.stringify(payload));
      }
    } catch (e) { /* bridge not installed yet */ }
  }

  function abs(u) {
    if (!u) return null;
    try { return new URL(u, location.href).href; } catch (e) { return u; }
  }

  // ---- roots: document plus every open shadow root ---------------------------

  function collectRoots() {
    var roots = [document];
    function descend(root, depth) {
      if (depth > MAX_SHADOW_DEPTH) return;
      var all;
      try { all = root.querySelectorAll('*'); } catch (e) { return; }
      var limit = Math.min(all.length, 4000);
      for (var i = 0; i < limit; i++) {
        var sr = all[i].shadowRoot;
        if (sr) {
          roots.push(sr);
          descend(sr, depth + 1);
        }
      }
    }
    try { descend(document, 0); } catch (e) { }
    return roots;
  }

  function list(sel, root) {
    var out = [];
    var roots = root ? [root] : collectRoots();
    for (var i = 0; i < roots.length; i++) {
      try {
        var found = roots[i].querySelectorAll(sel);
        for (var j = 0; j < found.length; j++) out.push(found[j]);
      } catch (e) { }
    }
    return out;
  }

  function ogImage() {
    var m = document.querySelector('meta[property="og:image"], meta[name="og:image"]');
    return m ? abs(m.content) : null;
  }

  // ---- post identity ---------------------------------------------------------

  /*
   * A stable id for the post a video belongs to, matching the hints the native extractors
   * derive from each platform's JSON. This is what lets a captured frame from a blob:-backed
   * <video> find the right card — the element's own src tells us nothing.
   */
  var POST_PATTERNS = [
    [/instagram\.com\/(?:reels?|p|tv)\/([A-Za-z0-9_-]+)/, 'ig:'],
    [/facebook\.com\/[^/]+\/videos\/(?:[^/]+\/)?(\d+)/, 'fb:'],
    [/facebook\.com\/(?:watch\/?)?\?v=(\d+)/, 'fb:'],
    [/facebook\.com\/reel\/(\d+)/, 'fb:'],
    [/fb\.watch\/([A-Za-z0-9_-]+)/, 'fb:'],
    [/(?:twitter|x)\.com\/[^/]+\/status\/(\d+)/, 'tw:'],
    // Every country gets its own domain — pinterest.com, .co.uk, in.pinterest.com — so the
    // suffix is matched loosely and the pin id, which is what the extractor keys on, is not.
    // Numeric on purpose: pin.it short links carry an opaque code the extractor never sees,
    // and admitting one here would invent an id that matches nothing.
    [/pinterest\.[a-z.]+\/pin\/(\d+)/, 'pin:'],
    // The vi-number is what IMDb calls a video, and it turns up in both address forms:
    // /video/vi123 and /title/tt456/videoplayer/vi123. A title page carrying no vi-number
    // names no video in particular and correctly yields nothing.
    [/imdb\.com\/(?:[^?#]*\/)?(vi\d+)/, 'imdb:'],
    // Both shapes Tumblr uses: blog.tumblr.com/post/<id> and tumblr.com/<blog>/<id>. The id is
    // long, and requiring that keeps the second form from reading a word like "explore" or
    // "dashboard" as a post.
    [/tumblr\.com\/(?:post\/|[^/?#]+\/)(\d{9,})/, 'tmb:'],
    [/sharechat\.com\/(?:post|video|p)\/([A-Za-z0-9_-]+)/, 'sc:']
  ];

  function hintFromUrl(u) {
    if (!u) return null;
    for (var i = 0; i < POST_PATTERNS.length; i++) {
      var m = u.match(POST_PATTERNS[i][0]);
      if (m && m[1]) return POST_PATTERNS[i][1] + m[1];
    }
    return null;
  }

  /*
   * Recomputed every time, never cached on the element.
   *
   * Instagram recycles its <video> nodes as the feed scrolls: the element playing reel three
   * is the same object that played reel one. A hint stashed on it therefore names the post
   * that used it before, which is how a reel's live frame ended up on a neighbour's card
   * wearing that neighbour's caption. The walk is bounded and runs about once a second.
   *
   * allowLocation is only for the element that is actually playing. The address bar names the
   * reel on screen, so handing it to every off-screen <video> found in a sweep would give a
   * whole feed of them the same identity.
   */
  function hintForVideo(v, allowLocation) {
    var hint = null;
    var node = v;
    // Walk out from the element looking for the permalink the card wraps.
    for (var depth = 0; depth < 8 && node && !hint; depth++) {
      if (node.tagName === 'A' && node.href) hint = hintFromUrl(node.href);
      if (!hint && node.querySelectorAll) {
        var links;
        try { links = node.querySelectorAll('a[href]'); } catch (e) { links = []; }
        for (var i = 0; i < links.length && i < 8 && !hint; i++) {
          hint = hintFromUrl(links[i].href);
        }
      }
      node = node.parentElement;
    }
    if (!hint && allowLocation) hint = hintFromUrl(location.href);
    return hint;
  }

  /*
   * Facebook only, and gated on the host so it cannot reach any other platform.
   *
   * Facebook does not put the caption in its media JSON where the extractor can reach it, and
   * its document title is just "Facebook" — which is why these cards had no name and fell back
   * to the bare host. The caption is in the DOM, though, in the post wrapped around the video.
   *
   * Best effort by design: if none of these selectors match, the caller is no worse off than
   * before. Nothing here throws, and nothing here runs off Facebook.
   */
  var FB_MESSAGE = '[data-ad-comet-preview="message"], [data-ad-preview="message"], ' +
    '[data-testid="post_message"]';

  /*
   * What the caption looks like on today's Facebook. The named hooks above match nothing on the
   * current site — checked against a live post, zero hits — because the markup around a caption
   * is now attribute-free obfuscated divs. What survives is the direction marker every block of
   * user-written text carries.
   *
   * Loose on its own, so it is never used as a plain first-match: an ancestor holds the author's
   * name and the button labels in the same kind of node. The longest one wins instead, which is
   * the caption in every layout checked.
   */
  var FB_TEXT = 'div[dir="auto"], span[dir="auto"]';

  function oneLine(text) {
    if (!text) return null;
    text = text.replace(/\s+/g, ' ').trim();
    if (!text) return null;
    return text.length > 120 ? text.slice(0, 117) + '…' : text;
  }

  /*
   * The page's own description. On Facebook this is the post's caption, and it is filled in
   * even on the pages whose markup carries no message node at all — a reel permalink, or a
   * /share/r/ link opened logged out, which is where the DOM walk below finds nothing and the
   * card ends up named after the bare host.
   *
   * Two gates, and both are needed. One video on the page, for the same reason the address bar
   * needs it: with several on screen it describes none of them, and stamping it on each one
   * puts a single description across every card. And an address that is itself a video, because
   * on a listing page this meta tag describes the product — /watch/ answers "Video is the place
   * to enjoy videos and shows together" — and a title is written once and kept, so letting that
   * through would name a real video after Facebook's own marketing copy for good.
   *
   * Hence an id in the address, not merely a video-shaped path: /watch/?v=123 is one video and
   * /watch/ is a shelf of them, and only the first may speak for what is on screen.
   */
  var FB_VIDEO_PAGE = new RegExp(
    '/reels?/\\d+' +              // /reel/123
    '|/videos/(?:[^/?#]+/)?\\d+' + // /videos/123, /videos/slug/123
    '|[?&]v=\\d+' +                // /watch/?v=123, /video.php?v=123
    '|[?&]story_fbid=\\d+' +       // /story.php?story_fbid=123
    '|/share/[rv]/');              // /share/r/<opaque> — a permalink to exactly one video

  function ogDescription() {
    if (!FB_VIDEO_PAGE.test(location.pathname + location.search)) return null;
    var m = document.querySelector('meta[property="og:description"], ' +
      'meta[name="og:description"], meta[name="description"]');
    return m ? oneLine(m.getAttribute('content')) : null;
  }

  function facebookCaption(v, soleVideo) {
    if (location.hostname.indexOf('facebook.com') < 0) return null;
    var message = facebookPostMessage(v);
    if (message) return message;
    return soleVideo ? ogDescription() : null;
  }

  /* The longest block of user-written text under this ancestor, which is the caption. */
  function longestText(node) {
    var best = null;
    var all;
    try { all = node.querySelectorAll(FB_TEXT); } catch (e) { return null; }
    for (var i = 0; i < all.length; i++) {
      var t = oneLine(all[i].innerText || all[i].textContent);
      if (t && (!best || t.length > best.length)) best = t;
    }
    return best;
  }

  // Fifteen, not ten. On a real post the caption sits eleven levels above the video — the walk
  // used to stop four short of it and report nothing.
  function facebookPostMessage(v) {
    var node = v.parentElement;
    for (var depth = 0; depth < 15 && node; depth++) {
      // Stop the moment the ancestor holds a second video: we have climbed out of this post
      // and into the feed. querySelector returns the first match in document order, so
      // searching from up here handed every video the caption of the topmost post — which is
      // exactly the one title that appeared on every card.
      var others;
      try { others = node.querySelectorAll('video'); } catch (e) { others = []; }
      if (others.length > 1) return null;

      // The named hook first where it still exists — it is precise and says "this is the
      // message" — then the loose one, which is all the current markup offers.
      var el;
      try { el = node.querySelector(FB_MESSAGE); } catch (e) { el = null; }
      var text = oneLine(el && (el.innerText || el.textContent));
      if (!text) text = longestText(node);
      if (text) return text;
      node = node.parentElement;
    }
    return null;
  }

  /**
   * The thumbnail Facebook drew for this video, for the case where the element has no poster.
   *
   * Facebook's feed videos carry no poster attribute — the still you see before one plays is a
   * separate <img> stacked behind the element — so the playing report went out with no poster on
   * it at all. That matters more than it sounds: the element's src is a blob and the post id is
   * often missing from reel markup, which leaves the poster as the only thing tying what is on
   * screen to something already detected. Without it the matcher falls back to picking whichever
   * indexed clip runs closest to the same length, and on a feed of clips that are all much the
   * same length it picks wrong as often as right.
   *
   * Climbs from the video to its post and takes the largest image there — largest because a post
   * also holds an avatar and a set of icons, and the thumbnail is the big one. Stops as soon as
   * an ancestor holds a second video, exactly as the caption walk does: past that point we are in
   * the feed and would be handing this video the picture from someone else's post.
   *
   * Facebook only. Every other site keeps reporting whatever the element itself declares.
   */
  // Far shorter than the caption walk, and for the opposite reason. The caption walk climbs
  // because a caption sits high above its video; a thumbnail is stacked directly behind one. Once
  // the climb passes the post it reaches the feed, where the largest image belongs to whichever
  // post happens to hold it — and on a feed that recycles its video elements the "second video"
  // guard does not stop it, because there is only ever one video element to find. That is how a
  // card came to carry a neighbour's picture and the badge with it.
  var POSTER_DEPTH = 5;
  // Below this it is an avatar or an icon, not a thumbnail.
  var POSTER_MIN_SIDE = 80;

  function facebookPoster(v) {
    if (location.hostname.indexOf('facebook.com') < 0) return null;

    var node = v.parentElement;
    for (var depth = 0; depth < POSTER_DEPTH && node; depth++) {
      var others;
      try { others = node.querySelectorAll('video'); } catch (e) { others = []; }
      if (others.length > 1) return null;

      var imgs;
      try { imgs = node.querySelectorAll('img'); } catch (e) { imgs = []; }

      var best = null;
      var bestArea = 0;
      for (var i = 0; i < imgs.length; i++) {
        var img = imgs[i];
        var src = img.currentSrc || img.src || '';
        // A data URI is a placeholder or a spinner, and says nothing about which video this is.
        if (!src || src.indexOf('data:') === 0) continue;
        var w = img.naturalWidth || img.width || 0;
        var h = img.naturalHeight || img.height || 0;
        // Avatars, reaction icons, badges. A post thumbnail is never this small, and taking one
        // by mistake is worse than taking nothing: the poster is what decides which video is on
        // screen and which cards are the same video, so a wrong one is wrong twice over.
        if (w < POSTER_MIN_SIDE || h < POSTER_MIN_SIDE) continue;
        var area = w * h;
        if (area > bestArea) { bestArea = area; best = src; }
      }
      if (best) return abs(best);
      node = node.parentElement;
    }
    return null;
  }

  // ---- discovery -------------------------------------------------------------

  function push(out, url, opts) {
    url = abs(url);
    if (!url || url.indexOf('javascript:') === 0) return;
    opts = opts || {};
    var key = url + '|' + (opts.w || 0) + 'x' + (opts.h || 0);
    if (seen[key]) return;
    seen[key] = 1;
    out.push({
      url: url,
      poster: opts.poster || null,
      dur: opts.dur || 0,
      w: opts.w || 0,
      h: opts.h || 0,
      // No document.title fallback here either, for the reason given in fromVideos: a page
      // title is the page's, and the native side already tracks it against the URL it came
      // from. Copying it onto individual videos is what let one reel name another.
      title: opts.title || '',
      hint: opts.hint || null,
      src: opts.src || 'dom'
    });
  }

  function fromVideos(out) {
    var videos = list('video');
    // On a page holding exactly one video, the address bar is unambiguously about that video,
    // and letting it name the element is what merges the DOM's view of a permalink page with
    // the one the JSON extractor built. With several on screen it identifies none of them.
    var soleVideo = videos.length === 1;

    videos.forEach(function (v) {
      var meta = {
        // This video's own still before the page's, and on a feed never the page's at all.
        // og:image describes the page, so on a feed it hands every video one picture — which
        // makes the posters useless for telling the videos apart and, where the poster is what
        // decides whether two cards are the same video, actively wrong.
        poster: v.poster ? abs(v.poster)
          : (facebookPoster(v) || (soleVideo ? ogImage() : null)),
        dur: isFinite(v.duration) && v.duration > 0 ? Math.round(v.duration * 1000) : 0,
        w: v.videoWidth || 0,
        h: v.videoHeight || 0,
        // Deliberately not falling back to document.title. On a feed that title belongs to
        // whichever reel is on screen right now, and stamping it on a video as *its* name is
        // permanent — it outranks the page title the native side tracks per URL, so every
        // clip discovered during one reel would keep that reel's name for good.
        title: v.getAttribute('title') || v.getAttribute('aria-label')
          || facebookCaption(v, soleVideo) || null,
        hint: hintForVideo(v, soleVideo),
        src: 'video'
      };
      if (v.currentSrc) push(out, v.currentSrc, meta);
      if (v.src && v.src !== v.currentSrc) push(out, v.src, meta);
      list('source', v).forEach(function (s) {
        if (s.src) push(out, s.src, meta);
      });
    });
  }

  function fromMeta(out) {
    ['og:video', 'og:video:url', 'og:video:secure_url', 'twitter:player:stream']
      .forEach(function (p) {
        list('meta[property="' + p + '"], meta[name="' + p + '"]').forEach(function (m) {
          if (m.content) push(out, m.content, { poster: ogImage(), src: 'meta' });
        });
      });
  }

  function walkLd(node, out) {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) { node.forEach(function (n) { walkLd(n, out); }); return; }
    if (node.contentUrl) {
      push(out, node.contentUrl, {
        poster: typeof node.thumbnailUrl === 'string' ? abs(node.thumbnailUrl) : ogImage(),
        title: node.name || document.title,
        src: 'jsonld'
      });
    }
    Object.keys(node).forEach(function (k) {
      if (typeof node[k] === 'object') walkLd(node[k], out);
    });
  }

  function fromJsonLd(out) {
    list('script[type="application/ld+json"]').forEach(function (s) {
      try { walkLd(JSON.parse(s.textContent), out); } catch (e) { /* malformed block */ }
    });
  }

  function fromLinks(out) {
    list('a[href]').forEach(function (a) {
      if (MEDIA_RE.test(a.href)) {
        push(out, a.href, { title: (a.textContent || '').trim() || document.title, src: 'link' });
      }
    });
  }

  /*
   * Server-rendered pages ship their whole content model inline. For a news site that is where
   * every article's video lives — including the full quality ladder — while the DOM has only
   * the one clip currently on screen, if any.
   */
  function fromInlineJson() {
    list('script').forEach(function (s) {
      var text;
      try { text = s.textContent; } catch (e) { return; }
      if (!text) return;
      if (text.length < 60) return;
      if (text.length > MAX_SCRIPT_CHARS) {
        var seenKey = 'big:' + text.length;
        if (!seenScripts[seenKey]) {
          seenScripts[seenKey] = 1;
          post({ type: 'oversize', url: location.href, size: text.length });
        }
        return;
      }

      var key = text.length + ':' + text.slice(0, 48);
      if (seenScripts[key]) return;
      seenScripts[key] = 1;

      var type = (s.type || '').toLowerCase();
      if (s.id === '__NEXT_DATA__' || type.indexOf('json') >= 0) {
        post({ type: 'json', url: location.href, body: text });
        return;
      }
      if (type && type.indexOf('javascript') < 0) return;

      // window.__INITIAL_STATE__ = {...};  /  var config = {...};
      var m = text.match(/=\s*(\{[\s\S]{200,}\})\s*;?\s*$/);
      if (m) {
        post({ type: 'json', url: location.href, body: m[1] });
      }
    });
  }

  function scan() {
    var out = [];
    try {
      fromVideos(out);
      fromMeta(out);
      fromJsonLd(out);
      fromLinks(out);
      fromInlineJson();
    } catch (e) { /* never let a scan break the page */ }
    if (out.length) post({ type: 'media', page: location.href, items: out });
  }

  // ---- current video and live frames -----------------------------------------

  /** The playing video with the largest visible area — what the user is actually watching. */
  function currentVideo() {
    var best = null;
    var bestArea = 0;
    list('video').forEach(function (v) {
      if (v.paused || v.ended || !v.videoWidth) return;
      var r;
      try { r = v.getBoundingClientRect(); } catch (e) { return; }
      var visibleH = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
      var visibleW = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
      var area = visibleH * visibleW;
      if (area > bestArea) { bestArea = area; best = v; }
    });
    return best;
  }

  function describe(v) {
    return {
      src: v.currentSrc || v.src || '',
      // The element's own poster where it has one, then the still Facebook stacked behind it.
      // Not the page's og:image as a last resort: on a feed that is one picture for every video
      // on the page, and a poster that matches everything identifies nothing.
      poster: v.poster ? abs(v.poster) : facebookPoster(v),
      dur: isFinite(v.duration) && v.duration > 0 ? Math.round(v.duration * 1000) : 0,
      w: v.videoWidth || 0,
      h: v.videoHeight || 0,
      hint: hintForVideo(v, true),
      // Carried here as well as in the discovery pass because on a site that plays through
      // MediaSource the element's src is a blob, and the discovery pass drops it — so this is
      // the only route by which anything the page says about the video reaches its card.
      title: v.getAttribute('title') || v.getAttribute('aria-label')
        || facebookCaption(v, list('video').length === 1) || null
    };
  }

  function grabFrame(v) {
    try {
      if (!v.videoWidth || !v.videoHeight) return null;
      var canvas = document.createElement('canvas');
      canvas.width = FRAME_WIDTH;
      canvas.height = Math.max(1, Math.round(FRAME_WIDTH * v.videoHeight / v.videoWidth));
      canvas.getContext('2d').drawImage(v, 0, 0, canvas.width, canvas.height);
      return canvas.toDataURL('image/jpeg', 0.7);
    } catch (e) {
      // SecurityError: cross-origin video without CORS taints the canvas.
      return null;
    }
  }

  function tick() {
    var v = currentVideo();
    if (!v) return;
    var info = describe(v);
    post({ type: 'playing', video: info });

    var now = Date.now();
    if (now - lastFrameAt >= FRAME_INTERVAL_MS) {
      var data = grabFrame(v);
      if (data) {
        lastFrameAt = now;
        post({ type: 'frame', video: info, data: data });
      }
    }
  }

  // ---- scheduling ------------------------------------------------------------

  var timer = null;
  function schedule() {
    if (timer) clearTimeout(timer);
    timer = setTimeout(scan, 400);
  }

  try {
    new MutationObserver(schedule).observe(document.documentElement || document, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['src', 'poster', 'href']
    });
  } catch (e) { /* observer unavailable */ }

  window.addEventListener('scroll', schedule, true);
  document.addEventListener('loadedmetadata', schedule, true);
  document.addEventListener('playing', schedule, true);
  window.addEventListener('load', schedule);

  setInterval(tick, 1000);
  schedule();
})();
