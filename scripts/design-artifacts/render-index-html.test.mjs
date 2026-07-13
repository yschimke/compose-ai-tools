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
