/**
 * Unit tests for the PNG↔Remote-Compose parity emitter (`rc-compare.html`). The
 * driver (`rc-compare.mjs`) does the browser render + pixel diff; this module is
 * a pure emitter over its result model, so we exercise it with a hand-built model
 * — no browser, no filesystem.
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { renderRcCompareHtml, summarizeRcCompare } from "./render-rc-compare-html.mjs";

const model = {
  system: "remote-m3",
  title: "Remote M3",
  rows: [
    {
      id: "pkg.CatalogPreviewsKt.TextRemoteButton",
      name: "TextRemoteButton",
      group: "Buttons",
      width: 525,
      height: 525,
      rendered: true,
      mismatchPct: 0,
      mismatchPx: 0,
      baked: "rc-baked/pkg.CatalogPreviewsKt.TextRemoteButton.png",
      rc: "rc/pkg.CatalogPreviewsKt.TextRemoteButton.png",
      diff: "rc-diff/pkg.CatalogPreviewsKt.TextRemoteButton.png",
    },
    {
      id: "pkg.CatalogPreviewsKt.ShaderGradientSticker",
      name: "ShaderGradientSticker",
      group: "Stickers",
      width: 525,
      height: 525,
      rendered: true,
      mismatchPct: 76.3,
      mismatchPx: 210301,
      baked: "rc-baked/pkg.CatalogPreviewsKt.ShaderGradientSticker.png",
      rc: "rc/pkg.CatalogPreviewsKt.ShaderGradientSticker.png",
      diff: "rc-diff/pkg.CatalogPreviewsKt.ShaderGradientSticker.png",
    },
    {
      id: "pkg.CatalogPreviewsKt.Undecodable",
      name: "Undecodable",
      group: "",
      width: 525,
      height: 525,
      rendered: false,
      note: "player could not decode the document",
      mismatchPct: null,
      mismatchPx: null,
      baked: "rc-baked/pkg.CatalogPreviewsKt.Undecodable.png",
      rc: "",
      diff: "",
    },
  ],
};

test("summarizeRcCompare counts rendered/unsupported and means only rendered rows", () => {
  const s = summarizeRcCompare(model.rows);
  assert.equal(s.total, 3);
  assert.equal(s.rendered, 2);
  assert.equal(s.unsupported, 1);
  // mean of 0 and 76.3 over the two rendered rows
  assert.ok(Math.abs(s.meanPct - 38.15) < 1e-9);
});

test("summarizeRcCompare yields null mean when nothing rendered", () => {
  const s = summarizeRcCompare([{ rendered: false }]);
  assert.equal(s.rendered, 0);
  assert.equal(s.meanPct, null);
});

test("the page is a self-contained document with the three-column header", () => {
  const html = renderRcCompareHtml(model);
  assert.match(html, /^<!doctype html>/);
  assert.match(html, /baked PNG/);
  assert.match(html, /RC · JS player/);
  assert.match(html, /pixel diff/);
  // summary line reflects the counts
  assert.match(html, /mean mismatch <strong>38\.15%<\/strong>/);
  assert.match(html, /not decodable by the JS player/);
});

test("rows sort worst-match-first, and unrenderable rows sink to the bottom", () => {
  const html = renderRcCompareHtml(model);
  const iShader = html.indexOf("ShaderGradientSticker");
  const iText = html.indexOf("TextRemoteButton");
  const iUndecodable = html.indexOf("Undecodable");
  assert.ok(iShader < iText, "76% should sort above 0%");
  assert.ok(iText < iUndecodable, "rendered rows sort above unrenderable ones");
});

test("mismatch % gets a severity band; a clean row is 'good', a far row 'bad'", () => {
  const html = renderRcCompareHtml(model);
  assert.match(html, /class="score good">0\.00%/);
  assert.match(html, /class="score bad">76\.30%/);
});

test("an unrenderable row shows its note instead of a percentage and omits the rc/diff images", () => {
  const html = renderRcCompareHtml(model);
  assert.match(html, /player could not decode the document/);
  assert.doesNotMatch(html, /src="rc\/pkg\.CatalogPreviewsKt\.Undecodable\.png"/);
});

test("an empty catalog renders the no-RC-docs notice, not a table", () => {
  const html = renderRcCompareHtml({ system: "compose-m3", title: "Compose M3", rows: [] });
  assert.match(html, /ships no Remote Compose documents/);
  assert.doesNotMatch(html, /<table/);
});
