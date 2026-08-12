/*
 * Layer 3 — MSE / network hook. Injected at document start, before page scripts run.
 *
 * Instagram, Facebook, X and ShareChat all play through MediaSource: the <video> element's
 * src is a blob: URL that cannot be re-fetched, and the real media URLs never appear in the
 * DOM at all. They do appear in the JSON their apps fetch, so this patches fetch and
 * XMLHttpRequest and forwards JSON bodies to the native side, which knows how to read each
 * platform's shape.
 *
 * Every patch is wrapped so that a failure here can never break the page.
 */
(function () {
  if (window.__vdHook) return;
  window.__vdHook = true;

  /*
   * Two megabytes, not half of one.
   *
   * The cap is here so a huge body cannot stall the bridge, but dropping one outright loses
   * exactly the payloads worth having: a response carrying a video's dash_manifests holds the
   * whole quality ladder inline as XML, which makes it far larger than the chatter around it.
   * At half a megabyte those were the first thing discarded, and the effect was a video
   * detected with a single quality — whichever rendition MediaSource happened to fetch — while
   * the other rungs sat in a manifest that never arrived.
   */
  var MAX_BODY = 2 * 1024 * 1024;
  var JSON_TYPE = /json|javascript|text\/plain/i;

  function post(payload) {
    try {
      if (window.__vdBridge && window.__vdBridge.postMessage) {
        window.__vdBridge.postMessage(JSON.stringify(payload));
      }
    } catch (e) { /* bridge not ready */ }
  }

  function report(url, body) {
    if (!body) return;
    if (body.length > MAX_BODY) {
      // Say so rather than vanishing. A ladder that never arrives looks identical to a video
      // that genuinely has one quality, and there is no way to tell them apart from outside.
      post({ type: 'oversize', url: url || location.href, size: body.length });
      return;
    }
    // Facebook and Google prefix responses with for(;;); or )]}' to defeat JSON hijacking.
    var brace = body.indexOf('{');
    var bracket = body.indexOf('[');
    var start = brace < 0 ? bracket : (bracket < 0 ? brace : Math.min(brace, bracket));
    if (start < 0) return;
    post({ type: 'json', url: url || location.href, body: body.slice(start) });
  }

  // ---- fetch ----------------------------------------------------------------
  try {
    if (window.fetch) {
      var originalFetch = window.fetch;
      window.fetch = function () {
        var url = '';
        try {
          url = typeof arguments[0] === 'string'
            ? arguments[0]
            : (arguments[0] && arguments[0].url) || '';
        } catch (e) { /* exotic Request object */ }

        return originalFetch.apply(this, arguments).then(function (response) {
          try {
            var type = response.headers && response.headers.get('content-type');
            // Only clone when we know it is text. Cloning a media stream would double
            // buffer the whole segment and can stall playback.
            if (type && JSON_TYPE.test(type)) {
              response.clone().text().then(function (text) {
                report(url, text);
              }).catch(function () { });
            }
          } catch (e) { /* opaque response */ }
          return response;
        });
      };
    }
  } catch (e) { /* fetch not patchable */ }

  // ---- XMLHttpRequest -------------------------------------------------------
  try {
    var originalOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      try { this.__vdUrl = url; } catch (e) { }
      return originalOpen.apply(this, arguments);
    };

    var originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function () {
      var xhr = this;
      try {
        xhr.addEventListener('load', function () {
          try {
            var kind = xhr.responseType;
            if (kind === '' || kind === 'text') {
              report(xhr.__vdUrl, xhr.responseText);
            } else if (kind === 'json' && xhr.response) {
              report(xhr.__vdUrl, JSON.stringify(xhr.response));
            }
          } catch (e) { }
        });
      } catch (e) { }
      return originalSend.apply(this, arguments);
    };
  } catch (e) { /* XHR not patchable */ }

  // ---- MediaSource ----------------------------------------------------------
  try {
    var originalCreate = URL.createObjectURL;
    URL.createObjectURL = function (object) {
      var url = originalCreate.apply(URL, arguments);
      try {
        if (window.MediaSource && object instanceof MediaSource) {
          // Tells the native side this page is adaptive-only, so a missing progressive URL
          // is expected rather than a detection failure.
          post({ type: 'mse', blob: url });
        }
      } catch (e) { }
      return url;
    };
  } catch (e) { /* createObjectURL not patchable */ }
})();
