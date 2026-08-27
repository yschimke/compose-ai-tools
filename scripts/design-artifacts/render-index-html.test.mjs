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
  assert.match(html, /a\.wf\[href\^="figma\/"\]/); // finds each card's figma-svg
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
