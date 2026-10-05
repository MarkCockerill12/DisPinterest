// Runs inside Pinterest's page, before the site's own scripts. It only reads,
// restyles and drives what the site renders: it makes no requests to Pinterest
// and replaces none of the site's functions.
(function () {
  if (window.__dpx || window.top !== window) return;
  if (!/(^|\.)pinterest\.[a-z]{2,3}(\.[a-z]{2})?$/.test(location.hostname)) return;
  window.__dpx = 1;
  var DEBUG = __DEBUG__;
  function log(text) { if (DEBUG) console.log('DPX ' + text); }

  var PIN = /^\/pin\/(\d+)\/?$/;
  var MAIN_IMAGE = 'img[elementtiming^="closeup-image-main"]';
  function openPin() { var m = PIN.exec(location.pathname); return m && m[1]; }

  // ── Sponsored pins ────────────────────────────────────────────────────────
  // Real pins have an all-digit id; sponsored ones carry a long token instead.
  // The grid measures each tile before placing it, so a tile that is already
  // collapsed by this rule takes up no room and the pins below close the gap.
  // It has to be a stylesheet, in place before the tile exists: hiding a tile
  // after it was measured leaves the hole behind (and the ad flashes first).
  var notNumeric = '';
  for (var d = 0; d < 10; d++) notNumeric += ':not([data-test-pin-id^="' + d + '"])';
  var css =
    '[data-test-id="pin"]' + notNumeric + '{display:none!important}' +
    '.dpx-next{position:fixed;inset:0;z-index:2147483000;overflow:hidden;will-change:transform,opacity}' +
    '.dpx-next img{position:absolute;height:auto;border-radius:16px}';
  var style = document.createElement('style');
  style.textContent = css;
  function attachStyle() { (document.head || document.documentElement).appendChild(style); }
  if (document.documentElement) attachStyle();
  else document.addEventListener('readystatechange', attachStyle, { once: true });

  // Backstop for a sponsored pin that ever shows up with an ordinary id: found
  // by its label and blanked (too late to reclaim the space).
  var AD_LABEL = /^(Sponsored|Promoted|Promoted by)$/;
  var blanked = [];
  var checks = new WeakMap();

  function isLabelled(pin) {
    var divs = pin.getElementsByTagName('div');
    for (var i = 0; i < divs.length; i++) {
      var el = divs[i];
      if (!el.firstElementChild && el.textContent.length < 14 && AD_LABEL.test(el.textContent.trim())) return true;
    }
    return false;
  }

  function scanAds(pins) {
    // The grid can reuse a tile for a different pin; never leave a real pin blank.
    blanked = blanked.filter(function (b) {
      if (b.item.isConnected && b.pin.isConnected && isLabelled(b.pin)) return true;
      b.item.style.visibility = '';
      b.item.style.pointerEvents = '';
      return false;
    });
    for (var i = 0; i < pins.length; i++) {
      var pin = pins[i];
      if (!/^\d/.test(pin.getAttribute('data-test-pin-id') || '')) continue; // the stylesheet has it
      var n = checks.get(pin) || 0;
      // A tile's label can arrive a moment after the tile; look a few times, then stop.
      if (n >= 4) continue;
      checks.set(pin, n + 1);
      if (!isLabelled(pin)) continue;
      var item = pin.closest('[data-grid-item]') || pin;
      item.style.visibility = 'hidden';
      item.style.pointerEvents = 'none';
      blanked.push({ item: item, pin: pin });
      checks.set(pin, 4);
      log('labelled ad blanked ' + pin.getAttribute('data-test-pin-id'));
    }
  }

  // ── Pin order ────────────────────────────────────────────────────────────
  // Every grid the user scrolls through is remembered in order, with each
  // pin's picture, so a pin can be swiped to long after its tile left the page.
  var grids = {};   // grid key -> pin ids by slot
  var pictures = {}; // pin id -> { small, large }

  function gridKey() {
    var pin = openPin();
    return pin ? 'pin:' + pin : location.pathname + location.search;
  }

  function record(pins) {
    var slots = grids[gridKey()] || (grids[gridKey()] = []);
    for (var i = 0; i < pins.length; i++) {
      var id = pins[i].getAttribute('data-test-pin-id');
      var slot = pins[i].getAttribute('data-test-pin-slot-index');
      if (slot === null || !/^\d+$/.test(id)) continue;
      slots[+slot] = id;
      if (pictures[id]) continue;
      var img = pins[i].querySelector('img[src*="pinimg.com"]');
      var small = img && (img.currentSrc || img.src);
      // The pin page shows the 736x file, so that is the one worth having ready.
      if (small) pictures[id] = { small: small, large: small.replace(/pinimg\.com\/[^/]+\//, 'pinimg.com/736x/') };
    }
  }

  function inOrder(key) {
    return (grids[key] || []).filter(function (id) { return id; });
  }

  // The pins either side of the open one. Set when a pin is opened from a grid.
  var trail = [];

  document.addEventListener('click', function (e) {
    var a = e.target.closest && e.target.closest('a[href^="/pin/"]');
    if (!a) return;
    record(document.querySelectorAll('[data-test-id="pin"]'));
    trail = inOrder(gridKey());
    // Once the pin has opened, get its neighbours' pictures ready.
    setTimeout(function () { warm(neighbour(1)); warm(neighbour(-1)); }, 900);
  }, true);

  function neighbour(by) {
    var here = openPin();
    if (!here) return null;
    var at = trail.indexOf(here);
    if (at < 0) { trail = [here]; at = 0; }
    // Past the end of the grid it came from, carry on into this pin's own
    // "more like this" grid, so swiping forward never runs dry.
    if (by > 0 && !trail[at + 1]) {
      record(document.querySelectorAll('[data-test-id="pin"]'));
      inOrder('pin:' + here).forEach(function (id) { if (trail.indexOf(id) < 0) trail.push(id); });
    }
    return trail[at + by] || null;
  }

  function warm(id) {
    if (id && pictures[id] && !pictures[id].warmed) {
      pictures[id].warmed = true;
      new Image().src = pictures[id].large;
    }
  }

  // ── Swipe left / right between pins ──────────────────────────────────────
  // The next pin's picture slides in over the page, following the finger. Once
  // it has landed, the site's own router is asked to open that pin underneath,
  // and the cover is lifted only when the site has the same picture on screen —
  // so nothing blinks.
  var drag = null;
  var busy = false;
  var hurry = null;

  function sidewaysScroller(el) {
    for (; el && el !== document.body; el = el.parentElement) {
      if (el.scrollWidth > el.clientWidth + 8) {
        var o = getComputedStyle(el).overflowX;
        if (o === 'auto' || o === 'scroll') return true;
      }
    }
    return false;
  }

  function cover(id) {
    var el = document.createElement('div');
    el.className = 'dpx-next';
    var bg = getComputedStyle(document.body).backgroundColor;
    el.style.background = !bg || bg === 'transparent' || bg === 'rgba(0, 0, 0, 0)' ? '#fff' : bg;
    // Same place the site puts a pin's picture once its page is scrolled to the top.
    var main = document.querySelector(MAIN_IMAGE);
    var box = main ? main.getBoundingClientRect() : null;
    var left = box ? box.left : 12;
    var top = box ? box.top + window.scrollY : 12;
    var width = box ? box.width : window.innerWidth - 24;
    var picture = pictures[id];
    if (picture) {
      // The grid's copy is already downloaded; the sharp one fades in over it.
      [picture.small, picture.large].forEach(function (src) {
        var img = document.createElement('img');
        img.style.left = left + 'px';
        img.style.top = top + 'px';
        img.style.width = width + 'px';
        img.src = src;
        el.appendChild(img);
      });
    }
    document.documentElement.appendChild(el);
    return el;
  }

  function slide(el, x, ms, then) {
    el.style.transition = ms ? 'transform ' + ms + 'ms cubic-bezier(.2,.8,.2,1)' : '';
    el.style.transform = 'translate3d(' + x + 'px,0,0)';
    if (then) setTimeout(then, ms + 20);
  }

  function land(id, el) {
    busy = true;
    // A new history entry, so Back steps to the pin that was swiped away from.
    history.pushState(history.state, '', '/pin/' + id + '/');
    window.dispatchEvent(new PopStateEvent('popstate', { state: history.state }));
    window.scrollTo(0, 0);
    log('swiped to ' + id);
    var file = pictures[id] && pictures[id].large.split('/').pop().split('.')[0];
    var began = Date.now();
    var lifted = false;
    function lift(fadeMs) {
      if (lifted) return;
      lifted = true;
      hurry = null;
      el.style.transition = 'opacity ' + fadeMs + 'ms';
      el.style.opacity = '0';
      setTimeout(function () { el.remove(); }, fadeMs + 20);
      busy = false;
      warm(neighbour(1));
      warm(neighbour(-1));
    }
    // A new swipe shouldn't have to wait for a slow picture: once the cover has
    // been up a moment, the next touch lifts it straight away.
    hurry = function () { if (Date.now() - began > 300) lift(0); };
    (function settle() {
      if (lifted) return;
      var waited = Date.now() - began;
      var main = document.querySelector(MAIN_IMAGE);
      var shown = main && main.complete && main.naturalWidth > 0 && (!file || (main.currentSrc || main.src).indexOf(file) >= 0);
      // Video pins have no main picture; don't hold the cover for them.
      var video = !main && waited > 700 && document.querySelector('video');
      if (!shown && !video && waited < 3000) return setTimeout(settle, 50);
      lift(140);
    })();
  }

  document.addEventListener('touchstart', function (e) {
    drag = null;
    if (busy && hurry) hurry();
    if (busy || e.touches.length !== 1 || !openPin() || sidewaysScroller(e.target)) {
      if (DEBUG && openPin()) log('touch ignored: ' + (busy ? 'still landing' : e.touches.length !== 1 ? 'multi-touch' : 'inside a sideways scroller'));
      return;
    }
    drag = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now(), dx: 0, el: null };
  }, { passive: true, capture: true });

  function onMove(e) {
    if (!drag) return;
    var dx = e.touches[0].clientX - drag.x;
    var dy = e.touches[0].clientY - drag.y;
    if (!drag.el) {
      // Decide once whether this is a sideways swipe or an ordinary scroll.
      if (Math.abs(dy) > 14 && Math.abs(dy) >= Math.abs(dx)) { log('read as a scroll: ' + Math.round(dx) + ',' + Math.round(dy)); drag = null; return; }
      if (Math.abs(dx) < 10 || Math.abs(dx) < Math.abs(dy) * 1.4) return;
      drag.by = dx < 0 ? 1 : -1;
      drag.id = neighbour(drag.by);
      if (!drag.id) { log('nothing to swipe to (' + trail.length + ' known, at ' + trail.indexOf(openPin()) + ')'); drag = null; return; }
      drag.el = cover(drag.id);
    }
    drag.dx = dx;
    var width = window.innerWidth;
    // Parked just off the edge it comes from, pulled in by the finger.
    var x = drag.by > 0 ? Math.max(0, width + dx) : Math.min(0, -width + dx);
    slide(drag.el, x, 0);
    // Keep the gesture. A finger never travels perfectly level, and without
    // this the browser takes the swipe over as a scroll part-way through and
    // the swipe is lost.
    if (e.cancelable) e.preventDefault();
  }

  // Claiming a gesture needs a listener the browser has to wait for, which
  // costs a little scrolling smoothness, so it is only attached on pin pages.
  var moveAttached = false;
  function attachMove() {
    var want = !!openPin();
    if (want === moveAttached) return;
    moveAttached = want;
    if (want) document.addEventListener('touchmove', onMove, { passive: false, capture: true });
    else document.removeEventListener('touchmove', onMove, { capture: true });
  }

  function release(e) {
    var g = drag;
    drag = null;
    if (g && !g.el) log(e.type + ' before any sideways movement was seen');
    if (!g || !g.el) return;
    var width = window.innerWidth;
    var towards = g.by > 0 ? -g.dx : g.dx; // distance travelled in the swipe's own direction
    var flick = Date.now() - g.t < 280 && towards > 40;
    log('released after ' + Math.round(towards) + 'px, ' + (Date.now() - g.t) + 'ms');
    if (towards > width * 0.22 || flick) {
      busy = true;
      slide(g.el, 0, 190, function () { land(g.id, g.el); });
    } else {
      slide(g.el, g.by > 0 ? width : -width, 160, function () { g.el.remove(); });
    }
  }
  document.addEventListener('touchend', release, { passive: true, capture: true });
  document.addEventListener('touchcancel', release, { passive: true, capture: true });

  // ── "Use the app" prompts ────────────────────────────────────────────────
  // Dismissed with the prompt's own close button when it has one, so the site
  // tidies up after itself (scroll lock, backdrop). Hidden only as a fallback.
  var APP_ACTION = /^(open|get|use|install|download|continue in|switch to|open in|view in)( the)?( pinterest)? app$/i;
  var DISMISS = /^(not now|no thanks|maybe later|close|dismiss|stay on (the )?web|continue in browser|continue on web)$/i;
  var handled = new WeakSet();

  function container(el) {
    var box = el.closest('[role="dialog"],[aria-modal="true"]');
    if (box) return box;
    for (var p = el.parentElement, depth = 0; p && p !== document.body && depth < 14; p = p.parentElement, depth++) {
      var pos = getComputedStyle(p).position;
      if (pos === 'fixed' || pos === 'sticky') return p;
    }
    return null;
  }

  function scanPrompts() {
    var els = document.querySelectorAll('button, a, [role="button"]');
    for (var i = 0; i < els.length; i++) {
      var text = (els[i].textContent || '').trim();
      if (text.length > 30 || !APP_ACTION.test(text)) continue;
      var box = container(els[i]);
      if (!box || handled.has(box)) continue;
      handled.add(box);
      log('prompt ' + box.outerHTML.slice(0, 1800));
      var close = box.querySelector('[aria-label="Close"],[aria-label="Dismiss"],[aria-label="close"]');
      if (!close) {
        var buttons = box.querySelectorAll('button, [role="button"]');
        for (var j = 0; j < buttons.length && !close; j++) {
          if (DISMISS.test((buttons[j].textContent || '').trim())) close = buttons[j];
        }
      }
      if (close) close.click();
      else box.style.display = 'none';
    }
  }

  // One pass per burst of page changes, and the button sweep at most once a second.
  var queued = false;
  var lastPrompts = 0;
  function run() {
    queued = false;
    if (!document.body) return;
    attachMove();
    var pins = document.querySelectorAll('[data-test-id="pin"]');
    scanAds(pins);
    record(pins);
    var now = Date.now();
    if (now - lastPrompts > 1000) {
      lastPrompts = now;
      scanPrompts();
    }
  }
  new MutationObserver(function () {
    if (queued) return;
    queued = true;
    setTimeout(run, 250);
  }).observe(document, { childList: true, subtree: true });
})();
