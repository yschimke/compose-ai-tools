// Shared address-bar state for the serve pages.
//
// Every serve surface keeps some selection client-side — the catalog grid's section tab, theme
// chip, filter text and stage backing; the viewer's overrides and knobs; the compare page's
// format/theme. Until now all of it lived only in `localStorage`, so the URL of a page someone
// was looking at described a *different* page than the one on their screen: a link to
// `/meshcore-mobile/` reopened on whatever the last visitor to that browser had picked, and there
// was no way to bookmark or share "Components, Dynamic Dark".
//
// This module is the one place that writes it into the URL. It only ever touches the params a
// page declares it owns, so `token` / `session` (and anything else the server put there) survive
// untouched, and it never navigates — a state change is a `pushState` / `replaceState`, so the
// page is never reloaded and no render is re-requested by the browser.
//
// `push` for a discrete choice (a tab, a theme, a mode) so Back returns to the previous one;
// `replace` for continuous input (typing in a filter, dragging a slider) so one search doesn't
// bury the page the visitor arrived from under twenty history entries.
(function () {
  "use strict";

  function params() {
    return new URLSearchParams(location.search);
  }

  // "" for an absent param, so callers can treat missing and empty as the same "not chosen".
  function get(name) {
    var value = params().get(name);
    return value === null ? "" : value;
  }

  function urlFor(next) {
    var query = next.toString();
    return location.pathname + (query ? "?" + query : "") + location.hash;
  }

  function write(next, replace) {
    var url = urlFor(next);
    if (url === location.pathname + location.search + location.hash) return;
    // A page in an opaque origin (a sandboxed iframe) throws SecurityError on either call. The
    // URL is a nicety there; the page itself must keep working.
    try {
      if (replace) history.replaceState(history.state, "", url);
      else history.pushState(history.state, "", url);
    } catch (e) {}
  }

  // Set/clear the named params, leaving every other one alone. An empty (or null/undefined) value
  // clears the param rather than writing `?tab=`, so the default state is the clean URL a visitor
  // can be handed.
  function apply(values, replace) {
    var next = params();
    Object.keys(values).forEach(function (name) {
      var value = values[name];
      if (value === null || value === undefined || value === "") next.delete(name);
      else next.set(name, String(value));
    });
    write(next, replace);
  }

  // Replace a whole *slice* of the query: every param `owned` claims is dropped unless `values`
  // supplies it. The viewer needs this because its knob params are open-ended (`knob.<key>`) —
  // clearing a knob has to remove the param, which a per-name update can't express.
  function sync(values, owned, replace) {
    var next = params();
    var stale = [];
    next.forEach(function (_, name) {
      if (owned(name) && !(name in values)) stale.push(name);
    });
    stale.forEach(function (name) {
      next.delete(name);
    });
    Object.keys(values).forEach(function (name) {
      var value = values[name];
      if (value === null || value === undefined || value === "") next.delete(name);
      else next.set(name, String(value));
    });
    write(next, replace);
  }

  window.cpUrlState = {
    get: get,
    push: function (values) {
      apply(values, false);
    },
    replace: function (values) {
      apply(values, true);
    },
    sync: sync,
    onPop: function (callback) {
      window.addEventListener("popstate", callback);
    },
  };
})();
