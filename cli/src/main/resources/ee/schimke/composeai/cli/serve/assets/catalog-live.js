// Long-press a catalog card to start a live daemon session *in place* — the grid's counterpart of
// the viewer's Static⇄Live toggle, without leaving the page.
//
// The card keeps its baked thumbnail as the stage: a <canvas> is mounted as an absolute overlay on
// the image's slot (the same trick the viewer uses), seeded with the thumbnail's pixels so there is
// no blank flash while the socket connects, and the daemon's frames paint over it. Pointer, wheel
// and key input are forwarded to the composition, so a component can be pressed, dragged and typed
// into from the grid.
//
// Everything here is progressive enhancement layered over the server-rendered grid: with scripting
// off (or on a session with no live lane) the cards are exactly the links they always were.
//
// The server emits its configuration as `window.cpCatalogLive` — an object literal in an inline
// script, NOT `data-` attributes — so no preview id this file puts into a URL originates as DOM
// text (the same discipline the themed-render URLs follow).
(function () {
  "use strict";
  var cfg = window.cpCatalogLive;
  if (!cfg || !cfg.cards || !cfg.cards.length) return;
  var cards = document.querySelectorAll(".cp-card");
  if (!cards.length) return;
  // How long a press has to be held before it means "go live" rather than "open this preview".
  var holdMs = cfg.holdMs || 500;
  // How far a pointer may drift during the hold before it reads as a scroll/drag instead.
  var slopPx = 10;

  // The card whose preview a DECLARED theme is currently showing, if any. Read off the pressed
  // chip so a live session started from a themed grid opens under that same theme rather than
  // snapping back to the catalog's baked palette.
  function themeProvider() {
    var pressed = document.querySelector('.cp-theme-btn[aria-pressed="true"]');
    var choice = pressed ? pressed.getAttribute("data-theme-choice") || "" : "";
    return choice.indexOf("theme:") === 0 ? choice.slice(6) : "";
  }

  // The preview a card is showing right now. A light/dark swap card carries both ids and the
  // filter script re-points `data-bg-theme` as it swaps, so the live session follows what is on
  // screen instead of pinning the server-side default.
  function previewIdOf(card, entry) {
    if (card.getAttribute("data-swap") !== "1") return entry.l || entry.d || "";
    return card.getAttribute("data-bg-theme") === "dark" ? entry.d || entry.l : entry.l || entry.d;
  }

  // --- The one live session. Only one card streams at a time, deliberately: a live seat is a
  // render daemon, and a grid is 80+ cards. Starting one ends the previous.
  var active = null; // { card, canvas, img, ws, chip, pointers }

  function chipText(session, text) {
    if (session.chip) session.chip.textContent = text;
  }

  function stopLive(reason) {
    var session = active;
    if (!session) return;
    active = null;
    if (session.ws) {
      session.ws.onmessage = null;
      session.ws.onclose = null;
      try {
        session.ws.close();
      } catch (e) {}
      session.ws = null;
    }
    session.card.classList.remove("cp-card-live");
    if (session.canvas && session.canvas.parentNode) {
      session.canvas.parentNode.removeChild(session.canvas);
    }
    if (session.chip && session.chip.parentNode) session.chip.parentNode.removeChild(session.chip);
    if (session.img) session.img.style.removeProperty("visibility");
    if (reason) announce(session.card, reason);
  }

  // A failure has to be visible on the card that failed — a live lane that silently does nothing
  // is indistinguishable from a long press that didn't register.
  function announce(card, message) {
    var wrap = card.querySelector(".cp-imgwrap");
    if (!wrap) return;
    var existing = wrap.querySelector(".cp-live-error");
    if (existing) existing.remove();
    var box = document.createElement("span");
    box.className = "cp-live-error";
    box.setAttribute("role", "status");
    box.textContent = message;
    wrap.appendChild(box);
    setTimeout(function () {
      if (box.parentNode) box.parentNode.removeChild(box);
    }, 4000);
  }

  // Maps the server's close codes the way the viewer does, so the two surfaces explain a refused
  // live lane in the same words.
  function closeReason(ev) {
    if (ev && ev.code === 1013) return "Live preview is at capacity — try again shortly.";
    if (ev && ev.code === 1008) return "Live preview unauthorized.";
    if (ev && ev.reason) return "Live preview unavailable: " + ev.reason;
    return "Live preview couldn't connect.";
  }

  function drawFrame(session, b64, codec) {
    var im = new Image();
    im.onload = function () {
      if (active !== session) return;
      session.canvas.width = im.naturalWidth;
      session.canvas.height = im.naturalHeight;
      session.canvas.getContext("2d").drawImage(im, 0, 0);
    };
    im.src = "data:image/" + (codec || "png") + ";base64," + b64;
  }

  function socketUrl(previewId) {
    var proto = location.protocol === "https:" ? "wss:" : "ws:";
    var qs = cfg.query ? cfg.query + "&codec=webp" : "codec=webp";
    var provider = themeProvider();
    if (provider) qs += "&themeProvider=" + encodeURIComponent(provider);
    return (
      proto +
      "//" +
      location.host +
      (cfg.base || "") +
      "/ws/" +
      encodeURIComponent(previewId) +
      "?" +
      qs
    );
  }

  function startLive(card, entry) {
    var previewId = previewIdOf(card, entry);
    if (!previewId) return;
    // Sign-in gates the daemon lane on a GitHub-authed box. Offering the press and then failing
    // the socket would read as a broken feature, so say what it needs and where to go.
    if (cfg.signInHref) {
      announce(card, "Sign in with GitHub to start a live session.");
      return;
    }
    stopLive(null);
    var img = card.querySelector("img");
    var wrap = card.querySelector(".cp-imgwrap");
    if (!img || !wrap) return;
    var canvas = document.createElement("canvas");
    canvas.className = "cp-card-canvas";
    // Seed from the thumbnail so the card shows real pixels for the whole connect window; the
    // first daemon frame overwrites the buffer.
    if (img.naturalWidth && img.naturalHeight) {
      canvas.width = img.naturalWidth;
      canvas.height = img.naturalHeight;
      try {
        canvas.getContext("2d").drawImage(img, 0, 0);
      } catch (e) {}
    }
    var chip = document.createElement("span");
    chip.className = "cp-live-chip";
    chip.setAttribute("role", "status");
    chip.textContent = "connecting…";
    wrap.appendChild(canvas);
    wrap.appendChild(chip);
    img.style.visibility = "hidden";
    card.classList.add("cp-card-live");
    var session = { card: card, canvas: canvas, img: img, ws: null, chip: chip, pointers: {} };
    active = session;
    var sock;
    try {
      sock = new WebSocket(socketUrl(previewId));
    } catch (e) {
      stopLive("Live preview couldn't connect.");
      return;
    }
    session.ws = sock;
    var gotFrame = false;
    sock.onmessage = function (ev) {
      if (active !== session) return;
      var m;
      try {
        m = JSON.parse(ev.data);
      } catch (e) {
        return;
      }
      if (m.type === "frame") {
        gotFrame = true;
        chipText(session, "live");
        drawFrame(session, m.dataBase64, m.codec);
      } else if (m.type === "error") {
        chipText(session, m.message || "error");
      }
    };
    sock.onclose = function (ev) {
      if (active !== session) return;
      // Closed before any frame ⇒ the lane never activated. Drop the seeded thumbnail from the
      // canvas rather than letting it pass for a live render, and say why.
      stopLive(gotFrame ? null : closeReason(ev));
    };
    wireInput(session);
  }

  // --- Input forwarding. Coordinates are image-natural pixels (the daemon's wire units), read off
  // the canvas' displayed box. A tap with no movement is sent as a single `click`, matching the
  // viewer: the daemon's click fast-path renders between press and release, which a batched
  // down+up can race.
  function wireInput(session) {
    var canvas = session.canvas;
    function send(msg) {
      var ws = session.ws;
      if (active !== session || !ws || ws.readyState !== 1) return;
      ws.send(JSON.stringify(Object.assign({ type: "input" }, msg)));
    }
    function pixel(ev) {
      var rect = canvas.getBoundingClientRect();
      if (!rect.width || !rect.height) return null;
      return {
        x: Math.round(((ev.clientX - rect.left) / rect.width) * canvas.width),
        y: Math.round(((ev.clientY - rect.top) / rect.height) * canvas.height),
      };
    }
    canvas.addEventListener("pointerdown", function (ev) {
      var p = pixel(ev);
      if (!p) return;
      ev.preventDefault();
      ev.stopPropagation();
      if (canvas.setPointerCapture) {
        try {
          canvas.setPointerCapture(ev.pointerId);
        } catch (e) {}
      }
      session.pointers[ev.pointerId] = { x: p.x, y: p.y, moved: false };
    });
    canvas.addEventListener("pointermove", function (ev) {
      var state = session.pointers[ev.pointerId];
      if (!state) return;
      var p = pixel(ev);
      if (!p) return;
      ev.preventDefault();
      if (!state.moved) {
        state.moved = true;
        send({ kind: "pointerDown", pixelX: state.x, pixelY: state.y, pointerId: ev.pointerId });
      }
      send({ kind: "pointerMove", pixelX: p.x, pixelY: p.y, pointerId: ev.pointerId });
    });
    function endPointer(ev) {
      var state = session.pointers[ev.pointerId];
      if (!state) return;
      delete session.pointers[ev.pointerId];
      var p = pixel(ev) || { x: state.x, y: state.y };
      ev.preventDefault();
      ev.stopPropagation();
      if (state.moved) send({ kind: "pointerUp", pixelX: p.x, pixelY: p.y, pointerId: ev.pointerId });
      else send({ kind: "click", pixelX: p.x, pixelY: p.y, pointerId: ev.pointerId });
    }
    canvas.addEventListener("pointerup", endPointer);
    canvas.addEventListener("pointercancel", function (ev) {
      delete session.pointers[ev.pointerId];
    });
    // The card is a link: a click that reached it after driving the composition must not navigate.
    canvas.addEventListener("click", function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
    });
    canvas.addEventListener(
      "wheel",
      function (ev) {
        var p = pixel(ev);
        if (!p) return;
        ev.preventDefault();
        send({ kind: "rotaryScroll", pixelX: p.x, pixelY: p.y, scrollDeltaY: ev.deltaY });
      },
      { passive: false }
    );
  }

  // --- The long press itself.
  //
  // A card is a link, so the gesture has to be unambiguous in both directions: a press held past
  // the threshold goes live AND must not follow the link, while a tap, a drag (a scroll on touch)
  // or a right-click keeps the card behaving exactly as before.
  var press = null; // { card, entry, timer, x, y }

  function cancelPress() {
    if (!press) return;
    clearTimeout(press.timer);
    press.card.classList.remove("cp-card-pressing");
    press = null;
  }

  function wireCard(card, entry) {
    card.classList.add("cp-card-livable");
    // Discoverability: the affordance is invisible otherwise. A plain span (not a button) — a card
    // is an <a>, and interactive content may not nest inside one.
    var wrap = card.querySelector(".cp-imgwrap");
    if (wrap && !wrap.querySelector(".cp-live-hint")) {
      var hint = document.createElement("span");
      hint.className = "cp-live-hint";
      hint.setAttribute("aria-hidden", "true");
      hint.textContent = "hold for live";
      wrap.appendChild(hint);
    }
    card.addEventListener("pointerdown", function (ev) {
      if (ev.button !== 0 || ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.altKey) return;
      if (active && active.card === card) return; // already live — the canvas owns the pointer
      cancelPress();
      card.classList.add("cp-card-pressing");
      press = {
        card: card,
        entry: entry,
        x: ev.clientX,
        y: ev.clientY,
        timer: setTimeout(function () {
          var target = press;
          cancelPress();
          if (!target) return;
          // The press became a gesture, so the click it will produce is not a navigation.
          suppressNextClick = true;
          startLive(target.card, target.entry);
        }, holdMs),
      };
    });
    card.addEventListener("pointermove", function (ev) {
      if (!press || press.card !== card) return;
      if (Math.abs(ev.clientX - press.x) > slopPx || Math.abs(ev.clientY - press.y) > slopPx) {
        cancelPress();
      }
    });
    card.addEventListener("pointerup", cancelPress);
    card.addEventListener("pointercancel", cancelPress);
    card.addEventListener("pointerleave", cancelPress);
    // Touch platforms pop a context menu / selection callout on a long press; that is exactly the
    // gesture being claimed here.
    card.addEventListener("contextmenu", function (ev) {
      if (press && press.card === card) ev.preventDefault();
    });
    card.addEventListener("click", function (ev) {
      if (suppressNextClick || (active && active.card === card)) {
        suppressNextClick = false;
        ev.preventDefault();
        ev.stopPropagation();
      }
    });
    // Keyboard equivalent: a long press is a pointer gesture, so `L` on a focused card is how the
    // same lane is reached without one. Escape leaves it (as does clicking anywhere off the card).
    card.addEventListener("keydown", function (ev) {
      if (ev.ctrlKey || ev.metaKey || ev.altKey) return;
      if (ev.key === "l" || ev.key === "L") {
        ev.preventDefault();
        if (active && active.card === card) stopLive(null);
        else startLive(card, entry);
      } else if (ev.key === "Escape" && active && active.card === card) {
        ev.preventDefault();
        stopLive(null);
      }
    });
  }

  var suppressNextClick = false;

  cards.forEach(function (card, i) {
    var entry = cfg.cards[i];
    if (!entry || (!entry.l && !entry.d)) return;
    wireCard(card, entry);
  });

  document.addEventListener("keydown", function (ev) {
    if (ev.key === "Escape") stopLive(null);
  });
  // A press anywhere outside the live card ends the session — the grid is for browsing, and a
  // stream nobody is looking at is a daemon nobody is using.
  document.addEventListener("pointerdown", function (ev) {
    if (active && !active.card.contains(ev.target)) stopLive(null);
  });
  window.addEventListener("pagehide", function () {
    stopLive(null);
  });
})();
