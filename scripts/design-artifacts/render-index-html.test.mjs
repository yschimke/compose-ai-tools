/**
 * Unit tests for the catalog gallery (`index.html`). The full page is exercised by the driver's
 * golden tests; here we pin the client-side hero-crop the gallery carries — the script that reads
 * each component's figma-svg content bbox (root translate + viewBox) and clips the hero PNG to it,
 * so a wear sticker rendered on a 454² device canvas displays cropped to the component instead of
 * floating in an empty frame.
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { renderIndexHtml } from "./render-index-html.mjs";

const png = (path, extra = {}) => ({
  path,
  variant: "ideal",
  state: "default",
  theme: "light",
  width: 454,
  height: 454,
  ...extra,
});

const catalog = {
  system: "wear-m3",
  title: "Wear M3",
  components: [
    {
      componentId: "filled-button",
      group: "Buttons",
      images: [png("images/filled-button/ideal__default__light.png")],
    },
  ],
};

test("the gallery carries the hero content-crop script + framed style", () => {
  const html = renderIndexHtml(catalog, { figmaSvgSlugs: new Set(["filled-button"]) });
  assert.match(html, /\.shot--framed/);
  assert.match(html, /function parseBox/); // reads translate + viewBox
  assert.match(html, /a\.figma-svg-link/); // finds each card's figma-svg
});

test("the crop is a no-op when the render already fills the frame (close-cropped)", () => {
  // Phone catalogs render tight to the component, so the bbox already ~= the image; the script
  // must skip framing then (guarded on the bbox nearly filling the render) so those unchanged.
  const html = renderIndexHtml(catalog, { figmaSvgSlugs: new Set(["filled-button"]) });
  assert.match(html, /box\.vw >= rw \* 0\.9 && box\.vh >= rh \* 0\.9/);
});

test("a component with no figma-svg gets no crop wiring (link absent)", () => {
  const html = renderIndexHtml(catalog, { figmaSvgSlugs: new Set() });
  assert.doesNotMatch(html, /href="figma\/filled-button\.svg"/);
});

test("a deferred figma-svg links to the image's live vector route", () => {
  const deferred = structuredClone(catalog);
  deferred.components[0].images[0].figmaSvg =
    "https://preview.coo.ee/wear-m3/render/filled-button__ideal__default__light.svg";
  const html = renderIndexHtml(deferred, { figmaSvgSlugs: new Set(["filled-button"]) });
  assert.match(
    html,
    /href="https:\/\/preview\.coo\.ee\/wear-m3\/render\/filled-button__ideal__default__light\.svg"/,
  );
  assert.match(html, /class="wf figma-svg-link"/);
});

// Run the gallery's emitted crop script against a minimal fake DOM. It touches only
// `document.querySelectorAll`, `IntersectionObserver` and `fetch`, so the whole environment is
// three stubs — and driving the real emitted source is the point: an earlier version of this fix
// was dead on arrival because a `\/` escape collapses inside the template literal that emits it,
// and only executing the shipped text catches that.
function runCropScript(html, { hasIntersectionObserver = true } = {}) {
  const script = html.slice(html.lastIndexOf("<script>") + 8, html.lastIndexOf("</script>"));
  const fetched = [];
  const observed = [];
  const hero = { style: {}, classList: { add() {}, remove() {} }, getAttribute: () => null };
  const link = { getAttribute: () => "https://preview.coo.ee/wear-m3/render/b.svg" };
  const card = {
    querySelector: (sel) => (sel === "a.shot" ? hero : sel === "a.figma-svg-link" ? link : null),
  };
  const document = { querySelectorAll: (sel) => (sel === ".card" ? [card] : []) };
  const fetch = (url) => {
    fetched.push(url);
    return { then: () => ({ then: () => ({ catch: () => {} }) }) };
  };
  class FakeIntersectionObserver {
    constructor(cb) {
      this.cb = cb;
      observed.push(this);
    }
    observe(target) {
      this.target = target;
    }
    unobserve() {}
    trigger() {
      this.cb([{ isIntersecting: true, target: this.target }]);
    }
  }
  const globals = hasIntersectionObserver
    ? { document, fetch, IntersectionObserver: FakeIntersectionObserver }
    : { document, fetch, IntersectionObserver: undefined };
  new Function(...Object.keys(globals), script)(...Object.values(globals));
  return { fetched, observers: observed };
}

test("the crop script fetches no vector until a card is near the viewport", () => {
  // #4930 asked for no eager render-per-card on load; the follow-up asked that deferred cards keep
  // their crop, since the vector is the only content box a card without a declared gutter has.
  // Lazy-loading is what satisfies both: nothing on load, the real fetch once it is looked at.
  const html = renderIndexHtml(catalog, { figmaSvgSlugs: new Set(["filled-button"]) });

  const { fetched, observers } = runCropScript(html);
  assert.deepEqual(fetched, [], "no vector is fetched while the card is off-screen");
  assert.equal(observers.length, 1, "the card is observed instead");

  observers[0].trigger();
  assert.deepEqual(
    fetched,
    ["https://preview.coo.ee/wear-m3/render/b.svg"],
    "the same fetch happens once the card comes into view, so the crop is not lost",
  );
});

test("the crop script still fetches where IntersectionObserver is unavailable", () => {
  const html = renderIndexHtml(catalog, { figmaSvgSlugs: new Set(["filled-button"]) });

  const { fetched } = runCropScript(html, { hasIntersectionObserver: false });

  assert.equal(fetched.length, 1, "no observer means the old eager behaviour, not a lost crop");
});

test("a declared capture gutter rides on the hero as data-gutter, in render pixels", () => {
  // The renderer grew this canvas by 11/11/11/13px so a shadow could fall outside the component's
  // bounds. A card fitting the whole canvas to its column draws the component that much smaller
  // than its gutter-less siblings (m3-catalog#179), so the gallery is told what to subtract.
  const guttered = {
    ...catalog,
    components: [
      {
        componentId: "elevated-button",
        group: "Buttons",
        images: [
          png("images/elevated-button/ideal__default__light.png", {
            previewParams: { captureGutter: { left: 11, top: 11, right: 11, bottom: 13 } },
          }),
        ],
      },
    ],
  };
  const html = renderIndexHtml(guttered, { figmaSvgSlugs: new Set() });
  assert.match(html, /data-gutter="11,11,11,13"/);
  assert.match(html, /function frameGutter/);
});

test("the bleed rule comes after the framed rule, or the cascade eats it", () => {
  // Same specificity, so the later declaration wins — and a bleeding shot always carries BOTH
  // classes. Ordered the other way, `.shot--framed { overflow:hidden }` clips the shadow the
  // declared gutter exists to keep, with nothing in the markup to show for it.
  const html = renderIndexHtml(catalog, { figmaSvgSlugs: new Set() });
  const framed = html.indexOf(".shot--framed { overflow:hidden");
  const bleed = html.indexOf(".shot--bleed { overflow:visible; }");
  assert.ok(framed >= 0 && bleed > framed, "the bleed override is declared after the framed rule");
  assert.match(html, /\.card:has\(\.shot--bleed\) \{ overflow:visible; \}/);
});

test("a vector-framed gutter shot bleeds too, not only the close-cropped branch", () => {
  // A guttered render whose vector box is NOT close-cropped takes the normal framing path. That
  // path adds `shot--framed`, which hides overflow — so it has to add the bleed as well, or the
  // shadow is clipped by the very window meant to line the component up.
  const html = renderIndexHtml(catalog, { figmaSvgSlugs: new Set(["filled-button"]) });
  assert.match(html, /if \(gutterEdges\) shot\.classList\.add\("shot--bleed"\);/);
});

test("a component that declares no gutter carries no data-gutter", () => {
  const html = renderIndexHtml(catalog, { figmaSvgSlugs: new Set() });
  assert.doesNotMatch(html, /data-gutter=/);
});

test("an all-zero gutter is the same as none — nothing to subtract", () => {
  const zeroed = {
    ...catalog,
    components: [
      {
        componentId: "filled-button",
        group: "Buttons",
        images: [
          png("images/filled-button/ideal__default__light.png", {
            previewParams: { captureGutter: { left: 0, top: 0, right: 0, bottom: 0 } },
          }),
        ],
      },
    ],
  };
  assert.doesNotMatch(renderIndexHtml(zeroed, { figmaSvgSlugs: new Set() }), /data-gutter=/);
});

test("failed renders become visible cards with expandable diagnostics", () => {
  const html = renderIndexHtml({
    system: "broken",
    title: "Broken catalog",
    components: [],
    failures: [
      {
        componentId: "Button/Filled",
        preview: "FilledButtonPreview",
        group: "Buttons",
        phase: "render",
        errorClass: "java.lang.NoSuchMethodError",
        message: "androidx.compose.runtime.snapshots.SnapshotStateList",
        stackTrace: "java.lang.NoSuchMethodError: boom\n  at Buttons.kt:42",
      },
    ],
  });
  assert.match(html, /card--failed/);
  assert.match(html, /1 failed render/);
  assert.match(html, /NoSuchMethodError/);
  assert.match(html, /Stack trace/);
  assert.doesNotMatch(html, />no render</);
});

test("a partially rendered component keeps both pixels and failure diagnostics", () => {
  const html = renderIndexHtml({
    ...catalog,
    failures: [
      {
        componentId: "filled-button",
        preview: "FilledButtonPreview_Dark",
        errorClass: "java.lang.LinkageError",
        message: "dark variant failed",
      },
    ],
  });

  assert.match(html, /ideal__default__light\.png/);
  assert.match(html, /LinkageError: dark variant failed/);
  assert.match(html, /1 failed render/);
});
