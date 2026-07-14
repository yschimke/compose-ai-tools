/**
 * Unit tests for the reusable preview embed (render-preview-embed.mjs): a static
 * PNG thumbnail that links to the live preview server. The pixels/CSS live in a
 * browser; here we pin the pure HTML shape — link vs inert, the baked <img>, the
 * fallback state, per-embed sizing, frame variants — and the hero-image pick.
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { esc, heroImageOf, previewEmbed, previewEmbedStyles } from "./render-preview-embed.mjs";

test("an embed with an image and a live URL is a clickable, baked thumbnail", () => {
  const html = previewEmbed({
    imageUrl: "images/button-filled/ideal__default__light.png",
    liveUrl: "https://preview.coo.ee/remote-m3/p/button-filled__ideal__default__light",
    alt: "Button/Filled",
  });
  // Whole thumbnail is a link to the live server.
  assert.match(html, /^<a class="pv-embed"/);
  assert.match(html, /href="https:\/\/preview\.coo\.ee\/remote-m3\/p\/button-filled__ideal__default__light"/);
  assert.match(html, /target="_blank" rel="noopener"/);
  // Static baked <img> — no fetch, renders anywhere.
  assert.match(html, /<img class="pv-img" src="images\/button-filled\/ideal__default__light\.png" alt="Button\/Filled"/);
  // A live-server affordance is shown.
  assert.match(html, /class="pv-live">live ↗</);
});

test("an embed with no live URL is an inert span, not a link", () => {
  const html = previewEmbed({ imageUrl: "images/x/ideal.png", alt: "X" });
  assert.match(html, /^<span class="pv-embed"/);
  assert.doesNotMatch(html, /href=/);
  assert.doesNotMatch(html, /pv-live/);
});

test("an embed with no image renders the fallback state, never a broken <img>", () => {
  const html = previewEmbed({ liveUrl: "https://preview.coo.ee/x", fallback: "not rendered yet" });
  assert.doesNotMatch(html, /<img/);
  assert.match(html, /class="pv-missing">not rendered yet</);
  // No image ⇒ nothing to click through to; stays an inert span.
  assert.match(html, /^<span class="pv-embed"/);
  assert.doesNotMatch(html, /href=/);
});

test("per-embed sizing rides inline custom properties", () => {
  const html = previewEmbed({ imageUrl: "a.png", maxWidth: 320, maxHeight: 240 });
  assert.match(html, /style="--pv-max-w:320px;--pv-max-h:240px"/);
});

test("frame variants select their class", () => {
  assert.match(previewEmbed({ imageUrl: "a.png", frame: "solid" }), /pv-frame--solid/);
  assert.match(previewEmbed({ imageUrl: "a.png", frame: "none" }), /pv-frame--bare/);
  assert.match(previewEmbed({ imageUrl: "a.png" }), /pv-frame--checker/);
});

test("text and URLs are HTML-escaped", () => {
  const html = previewEmbed({ imageUrl: "a.png?x=1&y=2", alt: '<b>"hi"</b>', liveUrl: "https://x/?a=1&b=2" });
  assert.match(html, /src="a\.png\?x=1&amp;y=2"/);
  assert.match(html, /alt="&lt;b&gt;&quot;hi&quot;&lt;\/b&gt;"/);
  assert.match(html, /href="https:\/\/x\/\?a=1&amp;b=2"/);
});

test("previewEmbedStyles is a single namespaced block with themable accent/muted", () => {
  const css = previewEmbedStyles({ accent: "var(--link)", muted: "var(--muted)" });
  assert.match(css, /\.pv-embed \{/);
  assert.match(css, /\.pv-frame--checker \{/);
  assert.match(css, /color:var\(--link\)/);
  assert.match(css, /color:var\(--muted\)/);
});

const png = (o) => ({ variant: "ideal", state: "default", width: 100, height: 100, ...o });

test("heroImageOf prefers ideal / default / light / largest", () => {
  const component = {
    images: [
      png({ path: "layout.svg", variant: "layout", theme: "light" }),
      png({ path: "dark.png", theme: "dark", width: 400 }),
      png({ path: "small-light.png", theme: "light", width: 100 }),
      png({ path: "big-light.png", theme: "light", width: 400 }),
      png({ path: "pressed.png", theme: "light", state: "pressed", width: 999 }),
    ],
  };
  assert.equal(heroImageOf(component).path, "big-light.png");
});

test("heroImageOf falls back to any theme when the render carries none (e.g. wear device pins)", () => {
  const component = { images: [png({ path: "images/iconbutton/ideal__default__compact.png", size: "compact" })] };
  // No `theme` field on the record → still resolves rather than returning undefined.
  assert.equal(heroImageOf(component).path, "images/iconbutton/ideal__default__compact.png");
});

test("heroImageOf returns undefined for a component with no images", () => {
  assert.equal(heroImageOf({ images: [] }), undefined);
  assert.equal(heroImageOf({}), undefined);
});

test("esc escapes the five HTML-significant characters", () => {
  assert.equal(esc(`<a href="x" data='y'>&`), "&lt;a href=&quot;x&quot; data=&#39;y&#39;&gt;&amp;");
});
