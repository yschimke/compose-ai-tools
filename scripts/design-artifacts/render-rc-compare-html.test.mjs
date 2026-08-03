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

import {
  hasCmpWasmLane,
  hasEmbeddedJvmLane,
  hasEmbeddedLane,
  renderRcCompareHtml,
  summarizeRcCompare,
} from "./render-rc-compare-html.mjs";

function withCmpWasm(base) {
  return {
    ...base,
    rows: base.rows.map((r, index) => ({
      ...r,
      cmpWasmRendered: index !== 2,
      cmpWasmMismatchPct: index !== 2 ? 3 + index : null,
      cmpWasmMismatchPx: index !== 2 ? 8_268 + index : null,
      cmpWasmNote: index === 2 ? "unsupported operation" : undefined,
      cmpWasm: index !== 2 ? `rc-cmp-wasm/${r.id}.png` : "",
      cmpWasmDiff: index !== 2 ? `rc-cmp-wasm-diff/${r.id}.png` : "",
    })),
  };
}

/** The same model with a cmp-jvm result on every row — for the desktop-lane assertions. */
function withEmbeddedJvm(base) {
  const jvm = {
    TextRemoteButton: { embeddedJvmRendered: true, embeddedJvmMismatchPct: 2.5, embeddedJvmMismatchPx: 6_890 },
    ShaderGradientSticker: {
      embeddedJvmRendered: true,
      embeddedJvmMismatchPct: 1.1,
      embeddedJvmMismatchPx: 3_000,
    },
    Undecodable: { embeddedJvmRendered: true, embeddedJvmMismatchPct: 9, embeddedJvmMismatchPx: 24_806 },
  };
  return {
    ...base,
    rows: base.rows.map((r) => ({
      ...r,
      ...jvm[r.name],
      embeddedJvm: `rc-embedded-jvm/${r.id}.png`,
      embeddedJvmDiff: `rc-embedded-jvm-diff/${r.id}.png`,
    })),
  };
}

/** The same model with an embedded-player result on every row — the two-player page. */
function withEmbedded(base) {
  const emb = {
    // clean in JS, badly off in the embedded player: exercises independent scoring + sorting
    TextRemoteButton: { embeddedRendered: true, embeddedMismatchPct: 41.5, embeddedMismatchPx: 114_318 },
    // the reverse: the JS player is the bad one here
    ShaderGradientSticker: { embeddedRendered: true, embeddedMismatchPct: 1.2, embeddedMismatchPx: 3_307 },
    // JS could not decode it; the embedded player could
    Undecodable: { embeddedRendered: true, embeddedMismatchPct: 8, embeddedMismatchPx: 22_050 },
    // a blank baked reference: the embedded player renders it and "matches" for the wrong reason
    BrandedTextRemote: { embeddedRendered: true, embeddedMismatchPct: 0, embeddedMismatchPx: 0 },
  };
  return {
    ...base,
    rows: base.rows.map((r) => ({
      ...r,
      ...emb[r.name],
      embedded: `rc-embedded/${r.id}.png`,
      embeddedDiff: `rc-embedded-diff/${r.id}.png`,
    })),
  };
}

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
  assert.match(html, /1 not decodable/);
});

test("a model with no embedded results renders the JS-only page — no empty embedded columns", () => {
  const html = renderRcCompareHtml(model);
  assert.equal(hasEmbeddedLane(model.rows), false);
  assert.doesNotMatch(html, /RC · embedded player/);
  assert.doesNotMatch(html, /embedded player:<\/strong>/);
  // exactly the original four columns
  assert.match(html, /<thead><tr><th>preview<\/th><th>baked PNG<\/th><th>RC · JS player<\/th><th>pixel diff<\/th><\/tr><\/thead>/);
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

test("the embedded lane adds two columns and its own summary line", () => {
  const html = renderRcCompareHtml(withEmbedded(model));
  assert.match(html, /RC · embedded player/);
  assert.match(html, /<strong>embedded player:<\/strong>/);
  // both players keep their own score chip on every row
  assert.match(html, /<span class="scorelabel">js<\/span>/);
  assert.match(html, /<span class="scorelabel">embedded<\/span>/);
});

test("the cmp-jvm lane adds one column with a folded diff, a score chip and its own summary line", () => {
  const html = renderRcCompareHtml(withEmbeddedJvm(model));
  assert.equal(hasEmbeddedJvmLane(withEmbeddedJvm(model).rows), true);
  assert.match(html, /RC · cmp-jvm player/);
  assert.match(html, /<strong>cmp-jvm player:<\/strong>/);
  assert.match(html, /<span class="scorelabel">cmp-jvm<\/span>/);
  // The diff is a collapsible <details>, not a second column — keeps the page from ballooning.
  assert.match(html, /<details class="difffold"><summary>pixel diff<\/summary>/);
  // Header carries exactly one cmp-jvm column (no standalone "pixel diff" th for it).
  assert.equal((html.match(/<th>RC · cmp-jvm player<\/th>/g) || []).length, 1);
  // The lede must describe the cmp-jvm player even when the Android embedded lane is off —
  // otherwise it falls into the JS-only branch and claims the TypeScript player is the only one.
  assert.match(html, /<strong>cmp-jvm player<\/strong> runs that same/);
  assert.doesNotMatch(html, /The player is the vendored TypeScript/);
});

test("the cmp-jvm and embedded lanes coexist, each its own column and summary", () => {
  const html = renderRcCompareHtml(withEmbeddedJvm(withEmbedded(model)));
  assert.match(html, /RC · embedded player/);
  assert.match(html, /RC · cmp-jvm player/);
  assert.match(html, /\(JS \+ embedded \+ cmp-jvm players\)/);
  // The lede names all three players and the worst-scoring sort, not just JS + embedded.
  assert.match(html, /<strong>JS player<\/strong>/);
  assert.match(html, /<strong>embedded player<\/strong>/);
  assert.match(html, /<strong>cmp-jvm player<\/strong>/);
  assert.match(html, /rows sort worst-match-first on the worst-scoring player/);
});

test("the cmp-wasm lane adds one folded-diff column and independent parity stats", () => {
  const wasmModel = withCmpWasm(model);
  const html = renderRcCompareHtml(wasmModel);
  const stats = summarizeRcCompare(wasmModel.rows);
  assert.equal(hasCmpWasmLane(wasmModel.rows), true);
  assert.equal(stats.cmpWasmRendered, 2);
  assert.equal(stats.cmpWasmUnsupported, 1);
  assert.ok(Math.abs(stats.cmpWasmMeanPct - 3.5) < 1e-9);
  assert.match(html, /RC · cmp-wasm player/);
  assert.match(html, /<strong>cmp-wasm player:<\/strong>/);
  assert.match(html, /<span class="scorelabel">cmp-wasm<\/span>/);
  assert.match(html, /\(JS \+ cmp-wasm players\)/);
  assert.match(html, /runs the new Compose Multiplatform \/ Skiko player in browser Wasm/);
});

test("all rc-compare lanes can coexist without hiding the cmp-wasm result", () => {
  const html = renderRcCompareHtml(withCmpWasm(withEmbeddedJvm(withEmbedded(model))));
  assert.match(html, /\(JS \+ embedded \+ cmp-jvm \+ cmp-wasm players\)/);
  assert.equal((html.match(/<th>RC · cmp-wasm player<\/th>/g) || []).length, 1);
  assert.match(html, /data-cmp-wasm-pct=/);
});

test("cmp-jvm is summarized independently, like the embedded lane", () => {
  const s = summarizeRcCompare(withEmbeddedJvm(model).rows);
  assert.equal(s.embeddedJvmRendered, 3);
  assert.equal(s.embeddedJvmUnsupported, 0);
  assert.ok(Math.abs(s.embeddedJvmMeanPct - (2.5 + 1.1 + 9) / 3) < 1e-9);
  // A base model without the lane reports it absent and doesn't count it.
  assert.equal(hasEmbeddedJvmLane(model.rows), false);
  assert.equal(summarizeRcCompare(model.rows).embeddedJvmUnsupported, 0);
});

test("each player is summarized independently — one lane's failures don't touch the other's mean", () => {
  const s = summarizeRcCompare(withEmbedded(model).rows);
  // JS side is unchanged by the embedded results
  assert.equal(s.rendered, 2);
  assert.equal(s.unsupported, 1);
  assert.ok(Math.abs(s.meanPct - 38.15) < 1e-9);
  // embedded rendered all three, including the one the JS player could not decode
  assert.equal(s.embeddedRendered, 3);
  assert.equal(s.embeddedUnsupported, 0);
  assert.ok(Math.abs(s.embeddedMeanPct - (41.5 + 1.2 + 8) / 3) < 1e-9);
});

test("rows sort on the worse of the two players, so an embedded-only regression still surfaces", () => {
  const html = renderRcCompareHtml(withEmbedded(model));
  const iText = html.indexOf("TextRemoteButton");
  const iShader = html.indexOf("ShaderGradientSticker");
  // TextRemoteButton is 0% in JS but 41.5% embedded; ShaderGradientSticker is 76.3% in JS.
  // 76.3 still beats 41.5, so Shader stays first — but Text must now outrank the 8% row.
  const iUndecodable = html.indexOf("Undecodable");
  assert.ok(iShader < iText, "76.3% (js) sorts above 41.5% (embedded)");
  assert.ok(iText < iUndecodable, "41.5% (embedded) sorts above 8% (embedded)");
});

test("a row no player rendered sinks below every row either player rendered", () => {
  const rows = [
    { name: "Dead", rendered: false, embeddedRendered: false },
    { name: "EmbeddedOnly", rendered: false, embeddedRendered: true, embeddedMismatchPct: 0.5 },
  ];
  const html = renderRcCompareHtml({ system: "s", title: "t", rows });
  assert.ok(html.indexOf("EmbeddedOnly") < html.indexOf("Dead"));
});

/**
 * A blank baked reference is the case that used to score as a *perfect* match: both sides flatten
 * onto the same neutral background, so "the player drew nothing" and "the player matched exactly"
 * are the same pixels. These rows must be shown but never scored.
 */
const blankRow = {
  id: "pkg.CatalogPreviewsKt.BrandedTextRemote",
  name: "BrandedTextRemote",
  group: "",
  width: 525,
  height: 525,
  rendered: true,
  // What the diff *would* have said — the emitter must not report it.
  mismatchPct: 0,
  mismatchPx: 0,
  referenceBlank: true,
  baked: "rc-baked/pkg.CatalogPreviewsKt.BrandedTextRemote.png",
  rc: "rc/pkg.CatalogPreviewsKt.BrandedTextRemote.png",
  diff: "rc-diff/pkg.CatalogPreviewsKt.BrandedTextRemote.png",
};

const blankModel = { ...model, rows: [...model.rows, blankRow] };

test("a blank-reference row is rendered but not scored, and stays out of both means", () => {
  const s = summarizeRcCompare(blankModel.rows);
  assert.equal(s.total, 4);
  // it *rendered* — the player ran; it is only unscorable
  assert.equal(s.rendered, 3);
  assert.equal(s.scored, 2);
  assert.equal(s.blankReference, 1);
  // unchanged from the model without it: a 0% that never happened must not drag the mean down
  assert.ok(Math.abs(s.meanPct - 38.15) < 1e-9);
});

test("a blank reference is excluded from the embedded mean too — the reference is shared", () => {
  const s = summarizeRcCompare(withEmbedded(blankModel).rows);
  assert.equal(s.embeddedRendered, 4);
  assert.equal(s.embeddedScored, 3);
  assert.ok(Math.abs(s.embeddedMeanPct - (41.5 + 1.2 + 8) / 3) < 1e-9);
});

test("a blank-reference row shows 'no reference' rather than a green 0.00%", () => {
  const html = renderRcCompareHtml(blankModel);
  assert.match(html, /class="score na">no reference</);
  assert.match(html, /baked PNG is fully transparent/);
  // the 0.00% chip belongs to TextRemoteButton, which has a real reference — exactly one of them
  assert.equal(html.match(/class="score good">0\.00%/g).length, 1);
  assert.equal(html.match(/class="score na">no reference/g).length, 1);
});

test("a blank-reference row sinks rather than topping the table on an unearned 0%", () => {
  const html = renderRcCompareHtml(blankModel);
  const iBlank = html.indexOf("BrandedTextRemote");
  // below *every* scored row, including the 0.00% one it would otherwise have tied with
  assert.ok(html.indexOf("ShaderGradientSticker") < iBlank);
  assert.ok(html.indexOf("TextRemoteButton") < iBlank);
});

test("both lanes read 'no reference' on a blank row — neither player gets credit", () => {
  const html = renderRcCompareHtml(withEmbedded(blankModel));
  assert.equal(html.match(/class="score na">no reference/g).length, 2);
  assert.match(html, /1<\/strong> unscored \(blank reference\)/);
});

test("an empty catalog renders the no-RC-docs notice, not a table", () => {
  const html = renderRcCompareHtml({ system: "compose-m3", title: "Compose M3", rows: [] });
  assert.match(html, /ships no Remote Compose documents/);
  assert.doesNotMatch(html, /<table/);
});
