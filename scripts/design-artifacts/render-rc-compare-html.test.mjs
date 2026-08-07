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
  activeLanes,
  hasCmpWasmLane,
  hasEmbeddedJvmLane,
  hasEmbeddedLane,
  renderRcCompareHtml,
  summarizeRcCompare,
} from "./render-rc-compare-html.mjs";

/** The row model the page inlines for its client-side differ. */
function clientModel(html) {
  const match = html.match(/<script type="application\/json" id="rc-model">([\s\S]*?)<\/script>/);
  assert.ok(match, "the page must inline its row model");
  return JSON.parse(match[1].replace(/\\u003c/g, "<"));
}

/** The `Diff against` picker's option values, in order. */
function referenceOptions(html) {
  const select = html.match(/<select id="refselect">([\s\S]*?)<\/select>/);
  assert.ok(select, "the page must carry a reference picker");
  return [...select[1].matchAll(/value="([^"]+)"/g)].map((m) => m[1]);
}

function withCmpWasm(base) {
  return {
    ...base,
    rows: base.rows.map((r, index) => ({
      ...r,
      cmpWasmRendered: index !== 2,
      cmpWasmMismatchPct: index !== 2 ? 3 + index : null,
      cmpWasmMismatchPx: index !== 2 ? 8_268 + index : null,
      cmpWasmNote: index === 2 ? "unsupported operation" : undefined,
      cmpWasmError: index === 2 ? `rc-cmp-wasm-errors/${r.id}.txt` : undefined,
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

test("the page is a self-contained document with one column per player", () => {
  const html = renderRcCompareHtml(model);
  assert.match(html, /^<!doctype html>/);
  assert.match(html, /baked PNG/);
  assert.match(html, /RC · JS player/);
  // summary line reflects the counts
  assert.match(html, /mean mismatch <strong>38\.15%<\/strong>/);
  assert.match(html, /1 not decodable/);
});

test("nothing is diffed by default: no diff images, no score chips, picker on 'none'", () => {
  const html = renderRcCompareHtml(withCmpWasm(withEmbeddedJvm(withEmbedded(model))));
  // No diff column, and no diff image anywhere in the emitted markup — the diff slots are empty
  // placeholders the client fills only once a reference is picked.
  assert.doesNotMatch(html, /<th>pixel diff<\/th>/);
  assert.doesNotMatch(html, /<img[^>]*src="rc-diff\//);
  assert.doesNotMatch(html, /<img[^>]*src="rc-embedded-diff\//);
  assert.doesNotMatch(html, /<img[^>]*src="rc-cmp-wasm-diff\//);
  assert.match(html, /<div class="diffslot" hidden><\/div>/);
  // Score chips are a property of a chosen reference, so the server renders none.
  assert.doesNotMatch(html, /class="score /);
  assert.match(html, /<div class="scores" data-scores><\/div>/);
  // The picker defaults to not diffing at all.
  assert.match(html, /<option value="none" selected>/);
});

/**
 * Switching straight from one reference to another (baked → cmp-jvm, no reload, no scrolling) used
 * to leave every on-screen row blank: `observe()` on an already-observed target is a no-op, so the
 * rows that were still visible never got a second callback and nothing rescored them. The observer
 * has to be disconnected *before* the re-observe, not only on the way through `none`.
 */
test("changing reference re-arms the observer, so visible rows rescore without scrolling", () => {
  const apply = renderRcCompareHtml(model).match(/function apply\(\)\s*\{[\s\S]*?\n  \}/);
  assert.ok(apply, "the page must inline its apply() handler");
  const disconnect = apply[0].indexOf("observer.disconnect()");
  const observe = apply[0].indexOf("observer.observe(row)");
  assert.ok(disconnect > -1, "apply() must disconnect the observer");
  assert.ok(observe > -1, "apply() must re-observe the rows");
  assert.ok(disconnect < observe, "the disconnect must precede the re-observe, not follow it");
  // And it must not be gated on the `none` branch, which is what made the bug reference-to-reference
  // only: everything before the early return runs for every reference.
  assert.ok(disconnect < apply[0].indexOf('if (ref === "none")'));
});

test("the reference picker offers every lane the run produced, and only those", () => {
  assert.deepEqual(referenceOptions(renderRcCompareHtml(model)), ["none", "baked", "js"]);
  assert.deepEqual(referenceOptions(renderRcCompareHtml(withEmbedded(model))), [
    "none",
    "baked",
    "js",
    "embedded",
  ]);
  assert.deepEqual(
    referenceOptions(renderRcCompareHtml(withCmpWasm(withEmbeddedJvm(withEmbedded(model))))),
    ["none", "baked", "js", "embedded", "cmp-jvm", "cmp-wasm"],
  );
  assert.deepEqual(
    activeLanes(model.rows).map((l) => l.id),
    ["baked", "js"],
  );
});

test("the inlined row model carries each lane's render, build-time diff and score", () => {
  const html = renderRcCompareHtml(withEmbedded(model));
  const client = clientModel(html);
  assert.deepEqual(
    client.lanes.map((l) => l.id),
    ["baked", "js", "embedded"],
  );
  // Rows are inlined in display order, so `data-row` indexes straight into this array.
  const shader = client.rows.find((r) => r.name === "ShaderGradientSticker");
  assert.equal(shader.lanes.js.src, "rc/pkg.CatalogPreviewsKt.ShaderGradientSticker.png");
  assert.equal(shader.lanes.js.diff, "rc-diff/pkg.CatalogPreviewsKt.ShaderGradientSticker.png");
  assert.equal(shader.lanes.js.pct, 76.3);
  assert.equal(shader.lanes.js.px, 210301);
  assert.equal(shader.lanes.embedded.rendered, true);
  // The lane the JS player could not decode carries its reason instead of an image.
  const dead = client.rows.find((r) => r.name === "Undecodable");
  assert.equal(dead.lanes.js.rendered, false);
  assert.equal(dead.lanes.js.src, "");
  assert.equal(dead.lanes.js.note, "player could not decode the document");
});

test("a model with no embedded results renders the JS-only page — no empty embedded columns", () => {
  const html = renderRcCompareHtml(model);
  assert.equal(hasEmbeddedLane(model.rows), false);
  assert.doesNotMatch(html, /RC · embedded player/);
  assert.doesNotMatch(html, /embedded player:<\/strong>/);
  // exactly the baked + JS columns
  assert.match(
    html,
    /<thead><tr><th>preview<\/th><th>baked PNG<\/th><th>RC · JS player<\/th><\/tr><\/thead>/,
  );
});

test("rows sort worst-match-first, and unrenderable rows sink to the bottom", () => {
  const html = renderRcCompareHtml(model);
  const iShader = html.indexOf("ShaderGradientSticker");
  const iText = html.indexOf("TextRemoteButton");
  const iUndecodable = html.indexOf("Undecodable");
  assert.ok(iShader < iText, "76% should sort above 0%");
  assert.ok(iText < iUndecodable, "rendered rows sort above unrenderable ones");
});

test("the severity band is computed client-side from the inlined build-time scores", () => {
  const client = clientModel(renderRcCompareHtml(model));
  assert.equal(client.rows.find((r) => r.name === "TextRemoteButton").lanes.js.pct, 0);
  assert.equal(client.rows.find((r) => r.name === "ShaderGradientSticker").lanes.js.pct, 76.3);
});

test("an unrenderable row shows its note instead of an image and omits the rc/diff images", () => {
  const html = renderRcCompareHtml(model);
  assert.match(html, /<div class="missing">player could not decode the document<\/div>/);
  assert.doesNotMatch(html, /src="rc\/pkg\.CatalogPreviewsKt\.Undecodable\.png"/);
});

test("the embedded lane adds one column and its own summary line", () => {
  const html = renderRcCompareHtml(withEmbedded(model));
  assert.match(html, /RC · embedded player/);
  assert.match(html, /<strong>embedded player:<\/strong>/);
  // One column per player — the embedded lane no longer drags a diff column along with it.
  assert.equal((html.match(/<th>RC · embedded player<\/th>/g) || []).length, 1);
  // Both players are diffable references, and both are in the client model.
  const client = clientModel(html);
  assert.ok(client.lanes.some((l) => l.id === "js"));
  assert.ok(client.lanes.some((l) => l.id === "embedded"));
});

test("the cmp-jvm lane adds one column, a picker entry and its own summary line", () => {
  const html = renderRcCompareHtml(withEmbeddedJvm(model));
  assert.equal(hasEmbeddedJvmLane(withEmbeddedJvm(model).rows), true);
  assert.match(html, /RC · cmp-jvm player/);
  assert.match(html, /<strong>cmp-jvm player:<\/strong>/);
  assert.ok(referenceOptions(html).includes("cmp-jvm"));
  // Header carries exactly one cmp-jvm column.
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
  assert.match(html, /Rows sort worst-match-first on the worst-scoring player/);
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
  assert.ok(referenceOptions(html).includes("cmp-wasm"));
  assert.match(html, /\(JS \+ cmp-wasm players\)/);
  assert.match(html, /runs the new Compose Multiplatform \/ Skiko player in browser Wasm/);
  assert.match(
    html,
    /href="rc-cmp-wasm-errors\/pkg\.CatalogPreviewsKt\.Undecodable\.txt">details<\/a>/,
  );
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

test("a blank-reference row is flagged so the client never scores it against the baked PNG", () => {
  const html = renderRcCompareHtml(blankModel);
  assert.match(html, /baked PNG is fully transparent/);
  const client = clientModel(html);
  const blank = client.rows.find((r) => r.name === "BrandedTextRemote");
  assert.equal(blank.referenceBlank, true);
  // Exactly one row carries the flag — the scored rows must not inherit it.
  assert.equal(client.rows.filter((r) => r.referenceBlank).length, 1);
  assert.equal((html.match(/baked PNG is fully transparent/g) || []).length, 1);
});

test("a blank-reference row sinks rather than topping the table on an unearned 0%", () => {
  const html = renderRcCompareHtml(blankModel);
  const iBlank = html.indexOf("BrandedTextRemote");
  // below *every* scored row, including the 0.00% one it would otherwise have tied with
  assert.ok(html.indexOf("ShaderGradientSticker") < iBlank);
  assert.ok(html.indexOf("TextRemoteButton") < iBlank);
});

test("a blank row is called out once per page, not once per lane — the reference is shared", () => {
  const html = renderRcCompareHtml(withEmbedded(blankModel));
  assert.equal((html.match(/baked PNG is fully transparent/g) || []).length, 1);
  // Both lanes' summary lines report it, since the blank reference costs them the same row.
  assert.equal((html.match(/1<\/strong> unscored \(blank reference\)/g) || []).length, 2);
});

test("an empty catalog renders the no-RC-docs notice, not a table", () => {
  const html = renderRcCompareHtml({ system: "compose-m3", title: "Compose M3", rows: [] });
  assert.match(html, /ships no Remote Compose documents/);
  assert.doesNotMatch(html, /<table/);
});
