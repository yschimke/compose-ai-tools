#!/usr/bin/env node
/**
 * rc-compare.mjs — build the PNG↔Remote-Compose parity page for a catalog.
 *
 * For every preview a catalog bundle carries as both a baked raster
 * (`previews/<id>.png`) and a Remote Compose document (`ir/<id>.rc`), this
 * renders the `.rc` client-side with the vendored TypeScript player
 * (`RC.RcdPlayer`) in headless Chromium — the exact code path the browser
 * render lane (`compose-preview serve`, viewer `rc` mode) uses — sizes the
 * canvas to the baked PNG, pixel-diffs the two (`pixelmatch`), and emits:
 *
 *   <out>/rc/<id>.png            client-side render
 *   <out>/rc-baked/<id>.png      baked PNG (copied so the page is self-contained)
 *   <out>/rc-diff/<id>.png       pixel diff
 *   <out>/rc-compare.html        the gallery (render-rc-compare-html.mjs)
 *   <out>/rc-compare-summary.json machine-readable per-preview results
 *
 * A catalog that ships no `ir/*.rc` (most non-Remote-Compose systems) is a
 * clean no-op: nothing is written and the tool exits 0, so it is safe to run
 * unconditionally in the shared reusable workflow.
 *
 * Usage:
 *   node rc-compare.mjs --bundle <bundle.png> --player <rc-player bundle.js> \
 *     --out <dir> [--system <id>] [--title <t>] [--threshold 0.1] [--theme light] \
 *     [--fonts <dir>] [--cmp-wasm <rc-player-wasm distribution>] \
 *     [--require-cmp-wasm] [--cmp-wasm-allowlist <json>] \
 *     [--cmp-wasm-max-cold-first-frame-ms <ms>] [--cmp-wasm-max-warm-first-frame-ms <ms>]
 *
 * `--fonts` defaults to the vendored faces the snapshot renderer itself rasterizes with (see
 * rc-fonts.mjs). Point it elsewhere to compare against a different font set, or at a
 * non-existent path to fall back to the host's generic families — which renders every string in a
 * substituted typeface and inflates the mismatch for anything containing text.
 *
 * The polyglot `bundle.png` is a PNG with a ZIP appended; we read the ZIP's
 * `ir/*.rc` + `previews/*.png` entries directly (no external unzip).
 */
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import zlib from "node:zlib";
import { PNG } from "pngjs";
import pixelmatch from "pixelmatch";
import { chromium } from "playwright";

import { renderRcCompareHtml } from "./render-rc-compare-html.mjs";
import { CHROMIUM_LAUNCH_ARGS } from "./rc-chromium.mjs";
import { settledScreenshot } from "./rc-settle.mjs";
import {
  applyCmpWasmPerformanceBudgets,
  evaluateCmpWasmGate,
  formatCmpWasmGate,
  readCmpWasmAllowlist,
  summarizeCmpWasmPixelParity,
} from "./rc-compare-gate.mjs";
import { BG, flattenOnto, isFullyTransparent, splitCoverage } from "./rc-compare-pixels.mjs";
import { laneSplit as laneSplitFor } from "./rc-compare-means.mjs";
import { generationDensity } from "./rc-document-header.mjs";
import { DEFAULT_FONTS_DIR, fontFaceCss, loadAndVerifyFonts } from "./rc-fonts.mjs";

function arg(name, def = undefined) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : def;
}

const BUNDLE = arg("bundle");
const PLAYER = arg("player");
const OUT = arg("out");
const SYSTEM = arg("system", "");
const TITLE = arg("title", SYSTEM);
const THRESHOLD = Number(arg("threshold", "0.1"));
const THEME = arg("theme", "light");
const EXEC = arg("chromium", process.env.RC_COMPARE_CHROMIUM || undefined);
const FONTS = arg("fonts", DEFAULT_FONTS_DIR);
// Embedded-player lane (`:third-party-rc-embedded-player`). Two halves, because the render itself
// is a Gradle/Robolectric step that has no business living inside a Playwright driver:
//
//   --stage-embedded <dir>  write `<id>.rc` + `manifest.json` (id/width/height) for the harness
//   --embedded <dir>        read `<id>.png` the harness produced, diff them, add the columns
//
// Run the two around the harness:
//   node rc-compare.mjs … --stage-embedded /tmp/rc-in
//   ./gradlew :third-party-rc-embedded-player:testDebugUnitTest \
//     -Prc.embedded.input=/tmp/rc-in -Prc.embedded.output=/tmp/rc-out
//   node rc-compare.mjs … --embedded /tmp/rc-out
//
// Omitting both keeps the JS-only page exactly as before.
const STAGE_EMBEDDED = arg("stage-embedded");
const EMBEDDED = arg("embedded");
// The cmp-jvm (desktop Skiko embedded player) lane. It reuses the same staged inputs as the
// embedded lane (`--stage-embedded` writes `<id>.rc` + `manifest.json` that both harnesses read), so
// there is no separate stage flag — only a separate output dir to read PNGs back from.
const EMBEDDED_JVM = arg("embedded-jvm");
// The browser Wasm CMP player added by :rc-player-wasm. Unlike the JS player above, this is a
// complete Compose/Skiko application, so the driver serves its distribution over localhost and
// screenshots its viewport after the player's readiness marker appears.
const CMP_WASM = arg("cmp-wasm");
const REQUIRE_CMP_WASM = process.argv.includes("--require-cmp-wasm");
const CMP_WASM_ALLOWLIST = arg("cmp-wasm-allowlist");
const CMP_WASM_MAX_COLD_FIRST_FRAME_MS = optionalNumber(
  "cmp-wasm-max-cold-first-frame-ms",
);
const CMP_WASM_MAX_WARM_FIRST_FRAME_MS = optionalNumber(
  "cmp-wasm-max-warm-first-frame-ms",
);

function optionalNumber(name) {
  const value = arg(name);
  if (value === undefined) return null;
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    console.error(`rc-compare: --${name} must be a positive number`);
    process.exit(2);
  }
  return parsed;
}

if (!BUNDLE || !PLAYER || !OUT) {
  console.error("rc-compare: --bundle, --player and --out are required");
  process.exit(2);
}
if (REQUIRE_CMP_WASM && !CMP_WASM) {
  console.error("rc-compare: --require-cmp-wasm requires --cmp-wasm");
  process.exit(2);
}

// ---- minimal ZIP reader over the polyglot bundle (central directory walk) ----
// Entries are STORE (0) or DEFLATE (8); RC docs/PNGs are small, so we read the
// whole file into memory and slice per entry.
function readZipEntries(buf) {
  // Find End Of Central Directory record (0x06054b50), scanning from the tail.
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 22 - 0x10000; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error("no ZIP end-of-central-directory found");
  const count = buf.readUInt16LE(eocd + 10);
  const cdSize = buf.readUInt32LE(eocd + 12);
  const cdOffset = buf.readUInt32LE(eocd + 16);
  // Polyglot bundle: a PNG is prepended, so the archive's stored offsets are
  // relative to the start of the *ZIP*, not the file. Recover the prepend the
  // way python's zipfile does and add it to every stored offset.
  const prepend = eocd - cdSize - cdOffset;
  let off = cdOffset + prepend;
  const entries = new Map();
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) break; // central file header
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42) + prepend;
    const name = buf.toString("utf8", off + 46, off + 46 + nameLen);
    // Local header: recompute payload start (its name/extra lengths can differ).
    const lNameLen = buf.readUInt16LE(localOff + 26);
    const lExtraLen = buf.readUInt16LE(localOff + 28);
    const dataStart = localOff + 30 + lNameLen + lExtraLen;
    const comp = buf.subarray(dataStart, dataStart + compSize);
    entries.set(name, () => (method === 8 ? zlib.inflateRawSync(comp) : Buffer.from(comp)));
    off += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

function baseName(name, prefix, suffix) {
  return name.slice(prefix.length, name.length - suffix.length);
}

const bundleBuf = fs.readFileSync(BUNDLE);
const entries = readZipEntries(bundleBuf);
const previewParameters = new Map();
if (entries.has("previews.json")) {
  const manifest = JSON.parse(entries.get("previews.json")().toString("utf8"));
  for (const preview of manifest.previews ?? []) {
    if (preview.id && preview.params) previewParameters.set(preview.id, preview.params);
  }
}

const rcIds = [];
for (const name of entries.keys()) {
  if (name.startsWith("ir/") && name.endsWith(".rc")) rcIds.push(baseName(name, "ir/", ".rc"));
}
rcIds.sort();

if (rcIds.length === 0) {
  console.log(`rc-compare: ${BUNDLE} ships no ir/*.rc documents — nothing to compare, skipping.`);
  process.exit(0);
}

const dirs = {
  rc: path.join(OUT, "rc"),
  baked: path.join(OUT, "rc-baked"),
  diff: path.join(OUT, "rc-diff"),
  embedded: path.join(OUT, "rc-embedded"),
  embeddedDiff: path.join(OUT, "rc-embedded-diff"),
  embeddedJvm: path.join(OUT, "rc-embedded-jvm"),
  embeddedJvmDiff: path.join(OUT, "rc-embedded-jvm-diff"),
  cmpWasm: path.join(OUT, "rc-cmp-wasm"),
  cmpWasmDiff: path.join(OUT, "rc-cmp-wasm-diff"),
  cmpWasmErrors: path.join(OUT, "rc-cmp-wasm-errors"),
};
for (const d of Object.values(dirs)) fs.mkdirSync(d, { recursive: true });

/** PNG dimensions straight out of the IHDR — cheaper than decoding the whole image to size it. */
function pngSize(buf) {
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

// --stage-embedded: hand the Gradle harness its inputs and stop. The id→size mapping lives here
// because this is the only place that has both the `ir/*.rc` entry and its baked PNG.
if (STAGE_EMBEDDED) {
  fs.mkdirSync(STAGE_EMBEDDED, { recursive: true });
  const staged = [];
  for (const id of rcIds) {
    const pngName = `previews/${id}.png`;
    if (!entries.has(pngName)) continue;
    const { width, height } = pngSize(entries.get(pngName)());
    const document = entries.get(`ir/${id}.rc`)();
    fs.writeFileSync(path.join(STAGE_EMBEDDED, `${id}.rc`), document);
    staged.push({ id, width, height, density: generationDensity(document) });
  }
  fs.writeFileSync(
    path.join(STAGE_EMBEDDED, "manifest.json"),
    JSON.stringify(staged, null, 1),
  );
  console.log(`rc-compare: staged ${staged.length} document(s) for the embedded player in ${STAGE_EMBEDDED}`);
  process.exit(0);
}

// --embedded: the harness records per-document failures rather than aborting, so a document it
// could not render still gets a row — with the reason in place of a percentage.
const embeddedErrors = new Map();
if (EMBEDDED) {
  const errorsFile = path.join(EMBEDDED, "errors.txt");
  if (fs.existsSync(errorsFile)) {
    for (const line of fs.readFileSync(errorsFile, "utf8").split("\n")) {
      const [id, ...rest] = line.split("\t");
      if (id && rest.length) embeddedErrors.set(id, rest.join("\t"));
    }
  }
}

const embeddedJvmErrors = new Map();
if (EMBEDDED_JVM) {
  const errorsFile = path.join(EMBEDDED_JVM, "errors.txt");
  if (fs.existsSync(errorsFile)) {
    for (const line of fs.readFileSync(errorsFile, "utf8").split("\n")) {
      const [id, ...rest] = line.split("\t");
      if (id && rest.length) embeddedJvmErrors.set(id, rest.join("\t"));
    }
  }
}

/**
 * Diff one document's embedded-player render against its baked PNG and emit the row's embedded
 * fields. Returns `{}` when the lane wasn't requested, which is what keeps the page at its original
 * four columns rather than showing empty ones.
 *
 * `baked` is already flattened onto the neutral background by the caller, so the embedded render is
 * flattened the same way before diffing — otherwise a transparent-background render would score as
 * a false match the same way the baked stickers would.
 *
 * `referenceBlank` suppresses the percentage (the images are still written, so the blank reference
 * is visible on the page) — see `isFullyTransparent`.
 */
function embeddedFor(id, baked, bakedUnflattened, width, height, referenceBlank) {
  if (!EMBEDDED) return {};
  const png = path.join(EMBEDDED, `${id}.png`);
  if (!fs.existsSync(png)) {
    return {
      embeddedRendered: false,
      // The harness writes `<id>.error` next to the PNGs; surface its reason rather than a generic
      // "missing", so the page distinguishes "the player threw" from "never attempted".
      embeddedNote:
        embeddedErrors.get(id) ??
        (fs.existsSync(path.join(EMBEDDED, `${id}.error`))
          ? fs.readFileSync(path.join(EMBEDDED, `${id}.error`), "utf8").trim().slice(0, 200)
          : "no embedded render"),
      embeddedMismatchPct: null,
      embeddedMismatchPx: null,
      embedded: "",
      embeddedDiff: "",
    };
  }
  const embRaw = PNG.sync.read(fs.readFileSync(png));
  const embCoverage =
    embRaw.width === width && embRaw.height === height
      ? splitCoverage(bakedUnflattened, embRaw.data, width, height)
      : null;
  const emb = flattenOnto(embRaw, BG);
  if (emb.width !== width || emb.height !== height) {
    return {
      embeddedRendered: false,
      embeddedNote: `size ${emb.width}×${emb.height} ≠ baked ${width}×${height}`,
      embeddedMismatchPct: null,
      embeddedMismatchPx: null,
      embedded: "",
      embeddedDiff: "",
    };
  }
  const diff = new PNG({ width, height });
  const px = pixelmatch(baked.data, emb.data, diff.data, width, height, { threshold: THRESHOLD });
  fs.writeFileSync(path.join(dirs.embedded, `${id}.png`), PNG.sync.write(emb));
  fs.writeFileSync(path.join(dirs.embeddedDiff, `${id}.png`), PNG.sync.write(diff));
  return {
    embeddedRendered: true,
    embeddedMismatchPct: referenceBlank ? null : (100 * px) / (width * height),
    embeddedCoverageDeltaPct: referenceBlank ? null : (embCoverage?.coverageDeltaPct ?? null),
    embeddedContentMismatchPct: referenceBlank ? null : (embCoverage?.contentMismatchPct ?? null),
    embeddedMismatchPx: referenceBlank ? null : px,
    embedded: `rc-embedded/${id}.png`,
    embeddedDiff: `rc-embedded-diff/${id}.png`,
  };
}

/**
 * The cmp-jvm (desktop Skiko embedded player) counterpart of {@link embeddedFor}: diff its render
 * against the baked PNG and emit the row's `embeddedJvm*` fields. Same shape and same
 * `{}`-when-not-requested gate, so the cmp-jvm column only appears when the lane ran. The player is
 * the *same* embedded interpreter as the Android lane, run off Android over Skiko — so this is a
 * second view of embedded parity, not a fourth renderer.
 */
function embeddedJvmFor(id, baked, bakedUnflattened, width, height, referenceBlank) {
  if (!EMBEDDED_JVM) return {};
  const png = path.join(EMBEDDED_JVM, `${id}.png`);
  if (!fs.existsSync(png)) {
    return {
      embeddedJvmRendered: false,
      embeddedJvmNote:
        embeddedJvmErrors.get(id) ??
        (fs.existsSync(path.join(EMBEDDED_JVM, `${id}.error`))
          ? fs.readFileSync(path.join(EMBEDDED_JVM, `${id}.error`), "utf8").trim().slice(0, 200)
          : "no cmp-jvm render"),
      embeddedJvmMismatchPct: null,
      embeddedJvmMismatchPx: null,
      embeddedJvm: "",
      embeddedJvmDiff: "",
    };
  }
  const embRaw = PNG.sync.read(fs.readFileSync(png));
  const embCoverage =
    embRaw.width === width && embRaw.height === height
      ? splitCoverage(bakedUnflattened, embRaw.data, width, height)
      : null;
  const emb = flattenOnto(embRaw, BG);
  if (emb.width !== width || emb.height !== height) {
    return {
      embeddedJvmRendered: false,
      embeddedJvmNote: `size ${emb.width}×${emb.height} ≠ baked ${width}×${height}`,
      embeddedJvmMismatchPct: null,
      embeddedJvmMismatchPx: null,
      embeddedJvm: "",
      embeddedJvmDiff: "",
    };
  }
  const diff = new PNG({ width, height });
  const px = pixelmatch(baked.data, emb.data, diff.data, width, height, { threshold: THRESHOLD });
  fs.writeFileSync(path.join(dirs.embeddedJvm, `${id}.png`), PNG.sync.write(emb));
  fs.writeFileSync(path.join(dirs.embeddedJvmDiff, `${id}.png`), PNG.sync.write(diff));
  return {
    embeddedJvmRendered: true,
    embeddedJvmMismatchPct: referenceBlank ? null : (100 * px) / (width * height),
    embeddedJvmCoverageDeltaPct: referenceBlank ? null : (embCoverage?.coverageDeltaPct ?? null),
    embeddedJvmContentMismatchPct: referenceBlank ? null : (embCoverage?.contentMismatchPct ?? null),
    embeddedJvmMismatchPx: referenceBlank ? null : px,
    embeddedJvm: `rc-embedded-jvm/${id}.png`,
    embeddedJvmDiff: `rc-embedded-jvm-diff/${id}.png`,
  };
}

function contentType(file) {
  if (file.endsWith(".html")) return "text/html; charset=utf-8";
  if (file.endsWith(".mjs") || file.endsWith(".js")) return "text/javascript; charset=utf-8";
  if (file.endsWith(".wasm")) return "application/wasm";
  return "application/octet-stream";
}

/** Serve the assembled Wasm player plus the currently-selected RC document on loopback only. */
async function startCmpWasmServer(dir) {
  const root = path.resolve(dir);
  if (!fs.existsSync(path.join(root, "index.html"))) {
    throw new Error(`rc-compare: --cmp-wasm ${dir} has no index.html`);
  }
  let document = Buffer.alloc(0);
  const server = http.createServer((request, response) => {
    const pathname = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
    if (pathname === "/document.rc") {
      response.writeHead(200, {
        "Content-Type": "application/octet-stream",
        "Cache-Control": "no-store",
      });
      response.end(document);
      return;
    }
    const relative = pathname === "/" ? "index.html" : pathname.slice(1);
    const file = path.resolve(root, relative);
    if (file !== root && !file.startsWith(`${root}${path.sep}`)) {
      response.writeHead(403).end();
      return;
    }
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(200, { "Content-Type": contentType(file), "Cache-Control": "no-store" });
    fs.createReadStream(file).pipe(response);
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  return {
    origin: `http://127.0.0.1:${server.address().port}`,
    setDocument(bytes) {
      document = bytes;
    },
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

async function cmpWasmFor(id, bytes, baked, bakedUnflattened, width, height, referenceBlank, previewParams) {
  if (!CMP_WASM) return {};
  try {
    const density = previewParams?.density ?? 1;
    const viewportWidth = previewParams?.widthDp ?? Math.round(width / density);
    const viewportHeight = previewParams?.heightDp ?? Math.round(height / density);
    const pageState = await cmpWasmPageFor(density);
    const { page: cmpWasmPage, consoleErrors: cmpWasmConsoleErrors } = pageState;
    // `cold`/`warm` describes *this browser context*, and contexts are keyed by density (see
    // `cmpWasmPageFor`) — so a catalog whose previews span two densities legitimately reports two
    // cold rows, the second of them partway through the run. Both are recorded with their density
    // so a slow row can be attributed instead of guessed at. Every render navigates (see below), so
    // the labels now separate a first boot from one that finds the assets and the JIT warm: 1.1 s
    // against 0.55–1.07 s measured over the `remote-m3` corpus.
    const contextRender = pageState.renders++;
    const startup = contextRender === 0 ? "cold" : "warm";
    cmpWasmConsoleErrors.length = 0;
    cmpWasmServer.setDocument(bytes);
    await cmpWasmPage.setViewportSize({ width: viewportWidth, height: viewportHeight });
    const startedAt = performance.now();
    // **Every document navigates.** #3445 replaced the navigation with `window.rcPlayerLoad` — an
    // in-place handoff to the running player, ~0.15 s against ~0.5 s, minutes across a large
    // catalog — and `rc-cmp-wasm-document-swap.test.mjs` pinned a swapped render as byte-identical
    // to a navigated one. That equivalence holds for the two documents it checks and does not hold
    // across a corpus: run the 27 `remote-m3` documents through one player and a *band* of the text
    // ones come back with no text at all, shapes drawn and every glyph missing. Which band depends
    // on the order (reverse the corpus and a different set loses its text) and on the machine,
    // which is exactly the shape #3558 reported from CI — the same commit scoring
    // `VariableWidthRemote` at 0.19% or 2.45%, both perfectly stable. It is not a race the capture
    // can wait out: a blank row stays blank for the full 5 s settle timeout, while the same
    // document renders correctly when it is navigated to. So the lane pays the navigation. The
    // player keeps `rcPlayerLoad` and its test — this is the driver declining to depend on it until
    // the swap path can show it has finished, not a revert of the feature.
    //
    // `handoffDelayMs=0` drops the player's cold-start tail — 1.5 s it holds back `ready` so a host
    // that reveals it on that signal cannot show a blank surface. This lane is not such a host: the
    // screenshot below goes through CDP, which drives its own compositor frame, and every pixel of
    // the result is then checked against the baked reference. The viewer keeps the default.
    // External URL images are deliberately transparent in this offline lane. Rendering the rest of
    // the document is more useful than allowlisting the whole row, and the missing pixels remain in
    // the parity score rather than being mistaken for a successful image fetch.
    await cmpWasmPage.goto(
      `${cmpWasmServer.origin}/index.html?src=${encodeURIComponent("/document.rc")}&theme=${encodeURIComponent(THEME)}&handoffDelayMs=0&allowExternalImagePlaceholders=1`,
    );
    await cmpWasmPage.waitForFunction(
      () => ["ready", "error"].includes(document.documentElement.dataset.rcPlayerState),
      null,
      { timeout: 30_000 },
    );
    const state = await cmpWasmPage.evaluate(() => ({
      state: document.documentElement.dataset.rcPlayerState,
      error: document.documentElement.dataset.rcPlayerError,
    }));
    const firstFrameMs = performance.now() - startedAt;
    if (state.state !== "ready") throw new Error(state.error || "player reported an error");
    // `ready` is three frames, not a settled render: Compose resolves the host fonts asynchronously
    // and a text row redraws after them. Capturing on convergence instead of on the marker took the
    // corpus mean mismatch from 0.79% to 0.49% and made every rendered PNG reproducible run to run
    // (see `rc-settle.mjs`); it also replaces the player's flat 1,500 ms tail, which is the other
    // way to get a settled capture and costs three times as much.
    //
    // Convergence alone still let a *blank* frame through, which is how #3558 happened: a document
    // whose text has not resolved paints its shapes and nothing else, and "blank now, blank in
    // 500 ms" converges immediately. The expectation is the missing half — the reference tells us
    // whether this document draws anything at all, so a capture with no ink against a reference
    // with ink is a render that has not finished rather than a parity number.
    const settled = await settledScreenshot(cmpWasmPage, {
      expectation: referenceBlank ? null : (buffer) => !isFullyTransparent(PNG.sync.read(buffer)),
    });
    const settleMs = settled.settleMs;
    const pngRaw = PNG.sync.read(settled.buffer);
    // Still nothing when the clock ran out. Failing beats scoring it: a blank capture lands at a
    // perfectly stable mismatch (2.45% for `VariableWidthRemote`, every time) that reads as a
    // parity regression and is really a missing render, and the row it displaces would otherwise be
    // compared against a baseline recorded from a run that *did* draw.
    if (!referenceBlank && isFullyTransparent(pngRaw)) {
      throw new Error(
        `the player drew nothing in ${Math.round(settleMs)} ms while the baked reference has ink — ` +
          "the render did not finish, so there is no parity number to report",
      );
    }
    const wasmCoverage =
      pngRaw.width === width && pngRaw.height === height
        ? splitCoverage(bakedUnflattened, pngRaw.data, width, height)
        : null;
    const png = flattenOnto(pngRaw, BG);
    if (cmpWasmConsoleErrors.length) {
      throw new Error(`unexpected console error: ${cmpWasmConsoleErrors.join(" | ")}`);
    }
    if (png.width !== width || png.height !== height) {
      throw new Error(`size ${png.width}×${png.height} ≠ baked ${width}×${height}`);
    }
    const diff = new PNG({ width, height });
    const px = pixelmatch(baked.data, png.data, diff.data, width, height, { threshold: THRESHOLD });
    fs.writeFileSync(path.join(dirs.cmpWasm, `${id}.png`), PNG.sync.write(png));
    fs.writeFileSync(path.join(dirs.cmpWasmDiff, `${id}.png`), PNG.sync.write(diff));
    return {
      cmpWasmRendered: true,
      cmpWasmMismatchPct: referenceBlank ? null : (100 * px) / (width * height),
      cmpWasmCoverageDeltaPct: referenceBlank ? null : (wasmCoverage?.coverageDeltaPct ?? null),
      cmpWasmContentMismatchPct: referenceBlank ? null : (wasmCoverage?.contentMismatchPct ?? null),
      cmpWasmMismatchPx: referenceBlank ? null : px,
      cmpWasmFirstFrameMs: firstFrameMs,
      cmpWasmSettleMs: settleMs,
      cmpWasmStartup: startup,
      cmpWasmDensity: density,
      cmpWasmContextRender: contextRender,
      cmpWasmViewport: `${viewportWidth}×${viewportHeight}`,
      cmpWasm: `rc-cmp-wasm/${id}.png`,
      cmpWasmDiff: `rc-cmp-wasm-diff/${id}.png`,
    };
  } catch (error) {
    const detail = String(error?.stack || error?.message || error);
    const errorFile = `${encodeURIComponent(id)}.txt`;
    fs.writeFileSync(path.join(dirs.cmpWasmErrors, errorFile), detail);
    return {
      cmpWasmRendered: false,
      cmpWasmNote: String(error?.message || error).slice(0, 500),
      cmpWasmError: `rc-cmp-wasm-errors/${errorFile}`,
      cmpWasmMismatchPct: null,
      cmpWasmMismatchPx: null,
      cmpWasm: "",
      cmpWasmDiff: "",
    };
  }
}

const bundleJs = fs.readFileSync(PLAYER, "utf8");

const browser = await chromium.launch({
  headless: true,
  ...(EXEC ? { executablePath: EXEC } : {}),
  args: [...CHROMIUM_LAUNCH_ARGS],
});
const page = await browser.newContext({ deviceScaleFactor: 1 }).then((c) => c.newPage());
const cmpWasmServer = CMP_WASM ? await startCmpWasmServer(CMP_WASM) : null;
const cmpWasmPages = new Map();
/**
 * One browser context per preview density, because Playwright binds `deviceScaleFactor` at context
 * creation — there is no per-render way to change it, and the player takes its density from the
 * page's `devicePixelRatio`. Contexts do not share a cache, so the first render in each pays a
 * fresh player load; that render is the one labelled `cold` (measured at ~0.3 s over a warm one
 * here, small next to the ~2 s every render costs). Everything after it in that context is `warm`.
 */
async function cmpWasmPageFor(density) {
  if (cmpWasmPages.has(density)) return cmpWasmPages.get(density);
  const context = await browser.newContext({ deviceScaleFactor: density });
  const page = await context.newPage();
  const consoleErrors = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  const value = { page, consoleErrors, renders: 0 };
  cmpWasmPages.set(density, value);
  return value;
}
const pageWarnings = [];
page.on("console", (m) => {
  if (m.type() === "warning" || m.type() === "error") pageWarnings.push(m.text());
});
const fontCss = fontFaceCss(FONTS);
await page.setContent(`<!doctype html><html><head>${fontCss}</head><body></body></html>`);
if (fontCss) await loadAndVerifyFonts(page);
await page.addScriptTag({ content: bundleJs });

const rows = [];
for (const id of rcIds) {
  const pngName = `previews/${id}.png`;
  if (!entries.has(pngName)) {
    console.log(`rc-compare: no baked PNG for ${id}, skipping`);
    continue;
  }
  // Ask about blankness first: `flattenOnto` composites the alpha away in place.
  const bakedRaw = PNG.sync.read(entries.get(pngName)());
  const referenceBlank = isFullyTransparent(bakedRaw);
  // Keep the un-flattened pixels: `splitCoverage` below needs to know which side actually painted,
  // and `flattenOnto` composites that away in place.
  const bakedUnflattened = Buffer.from(bakedRaw.data);
  const baked = flattenOnto(bakedRaw, BG);
  const rcB64 = entries.get(`ir/${id}.rc`)().toString("base64");
  const { width, height } = baked;

  pageWarnings.length = 0;
  const result = await page.evaluate(
    async ({ b64, w, h, theme }) => {
      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;
      document.body.appendChild(canvas);
      const bin = atob(b64);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      try {
        const player = new window.RcdPlayer(canvas);
        player.setTheme(theme);
        await player.loadFromArrayBuffer(bytes.buffer);
        await new Promise((r) => setTimeout(r, 250));
        // The first paint is what *discovers* which named font families the document asks for —
        // resolution happens mid-paint, per TYPEFACE op — so the wait has to come after it. A
        // single-shot render has no later frame in which a face could appear, so without this the
        // branded text would screenshot in the fallback typeface.
        player.repaint();
        await player.fontsReady();
        player.repaint();
        return { dataUrl: canvas.toDataURL("image/png") };
      } catch (e) {
        return { error: String((e && e.stack) || e) };
      } finally {
        canvas.remove();
      }
    },
    { b64: rcB64, w: width, h: height, theme: THEME },
  );

  const name = id.split(".").pop();
  const truncated = pageWarnings.some((t) => /Unknown operation opcode/.test(t));

  // Embedded lane, computed independently of whether the JS player managed this document — either
  // player can render one the other chokes on, and the page scores them separately.
  const embedded = embeddedFor(id, baked, bakedUnflattened, width, height, referenceBlank);
  const embeddedJvm = embeddedJvmFor(id, baked, bakedUnflattened, width, height, referenceBlank);
  const cmpWasm = await cmpWasmFor(
    id,
    entries.get(`ir/${id}.rc`)(),
    baked,
    bakedUnflattened,
    width,
    height,
    referenceBlank,
    previewParameters.get(id),
  );

  if (result.error || truncated) {
    rows.push({
      id,
      name,
      group: "",
      width,
      height,
      rendered: false,
      note: truncated ? "player could not decode the document" : "render error",
      mismatchPct: null,
      mismatchPx: null,
      baked: `rc-baked/${id}.png`,
      rc: "",
      diff: "",
      referenceBlank,
      ...embedded,
      ...embeddedJvm,
      ...cmpWasm,
    });
    fs.writeFileSync(path.join(dirs.baked, `${id}.png`), PNG.sync.write(baked));
    console.log(`  ${name}: NOT RENDERED (${rows[rows.length - 1].note})`);
    continue;
  }

  const rcRaw = PNG.sync.read(Buffer.from(result.dataUrl.split(",")[1], "base64"));
  // Split before flattening: `mismatchPct` below can't tell "the player drew this the wrong colour"
  // from "the player didn't draw here at all", and on an under-filling card the second dominates.
  const coverage = splitCoverage(bakedUnflattened, rcRaw.data, width, height);
  const rcPng = flattenOnto(rcRaw, BG);
  const diff = new PNG({ width, height });
  const mismatchPx = pixelmatch(baked.data, rcPng.data, diff.data, width, height, {
    threshold: THRESHOLD,
  });
  const mismatchPct = (100 * mismatchPx) / (width * height);

  fs.writeFileSync(path.join(dirs.baked, `${id}.png`), PNG.sync.write(baked));
  fs.writeFileSync(path.join(dirs.rc, `${id}.png`), PNG.sync.write(rcPng));
  fs.writeFileSync(path.join(dirs.diff, `${id}.png`), PNG.sync.write(diff));

  rows.push({
    id,
    name,
    group: "",
    width,
    height,
    rendered: true,
    mismatchPct: referenceBlank ? null : mismatchPct,
    // Coverage vs content, so a framing/background gap can't read as a content error. See
    // `splitCoverage`.
    coverageDeltaPct: referenceBlank ? null : coverage.coverageDeltaPct,
    contentMismatchPct: referenceBlank ? null : coverage.contentMismatchPct,
    bothPaintedPct: referenceBlank ? null : coverage.bothPaintedPct,
    mismatchPx: referenceBlank ? null : mismatchPx,
    baked: `rc-baked/${id}.png`,
    rc: `rc/${id}.png`,
    diff: `rc-diff/${id}.png`,
    referenceBlank,
    ...embedded,
    ...embeddedJvm,
    ...cmpWasm,
  });
  if (referenceBlank) {
    // Worth a line of its own: a blank baked capture is a catalog bug, and it is exactly the case
    // that used to disappear into a green 0.00%.
    console.log(`  ${name}: UNSCORED — baked PNG is fully transparent (${width}×${height})`);
    continue;
  }
  const embNote =
    embedded.embeddedRendered === undefined
      ? ""
      : embedded.embeddedRendered
        ? `  |  embedded ${embedded.embeddedMismatchPct.toFixed(2)}%`
        : `  |  embedded NOT RENDERED`;
  const embJvmNote =
    embeddedJvm.embeddedJvmRendered === undefined
      ? ""
      : embeddedJvm.embeddedJvmRendered
        ? `  |  cmp-jvm ${embeddedJvm.embeddedJvmMismatchPct.toFixed(2)}%`
        : `  |  cmp-jvm NOT RENDERED`;
  const cmpWasmNote =
    cmpWasm.cmpWasmRendered === undefined
      ? ""
      : cmpWasm.cmpWasmRendered
        ? `  |  cmp-wasm ${cmpWasm.cmpWasmMismatchPct.toFixed(2)}% ` +
          `(${cmpWasm.cmpWasmStartup} ${cmpWasm.cmpWasmFirstFrameMs.toFixed(0)} ms, ` +
          `${cmpWasm.cmpWasmViewport}@${cmpWasm.cmpWasmDensity})`
        : `  |  cmp-wasm NOT RENDERED`;
  console.log(
    `  ${name}: ${mismatchPct.toFixed(2)}% (${mismatchPx} px, ${width}×${height})${embNote}${embJvmNote}${cmpWasmNote}`,
  );
}

if (cmpWasmServer) await cmpWasmServer.close();
await browser.close();

const model = { system: SYSTEM, title: TITLE, rows };
const html = renderRcCompareHtml(model, {
  generatedNote: `${rows.length} Remote Compose preview(s) · pixelmatch threshold ${THRESHOLD} · theme ${THEME}`,
  // The page can diff two *players* against each other client-side, which nothing here precomputes;
  // handing it the driver's threshold keeps those in-browser numbers on the same scale as ours.
  threshold: THRESHOLD,
});
fs.writeFileSync(path.join(OUT, "rc-compare.html"), html);

const rendered = rows.filter((r) => r.rendered);
// Blank-reference rows are rendered but unscorable, so they are kept out of the mean — see
// `isFullyTransparent`. Left in `rendered` because the player did in fact render them.
const scored = rendered.filter((r) => !r.referenceBlank);
const meanPct = scored.length ? scored.reduce((s, r) => s + r.mismatchPct, 0) / scored.length : null;
const meanOf = (pick) => {
  const vals = scored.map(pick).filter((v) => typeof v === "number");
  return vals.length ? vals.reduce((s, v) => s + v, 0) / vals.length : null;
};
const meanCoverageDeltaPct = meanOf((r) => r.coverageDeltaPct);
const meanContentMismatchPct = meanOf((r) => r.contentMismatchPct);
let cmpWasmGate = null;
if (REQUIRE_CMP_WASM) {
  try {
    cmpWasmGate = evaluateCmpWasmGate(rcIds, rows, readCmpWasmAllowlist(CMP_WASM_ALLOWLIST));
    applyCmpWasmPerformanceBudgets(
      cmpWasmGate,
      rows,
      CMP_WASM_MAX_COLD_FIRST_FRAME_MS,
      CMP_WASM_MAX_WARM_FIRST_FRAME_MS,
    );
    summarizeCmpWasmPixelParity(cmpWasmGate, rows);
  } catch (error) {
    cmpWasmGate = evaluateCmpWasmGate(rcIds, rows);
    cmpWasmGate.passed = false;
    cmpWasmGate.failures.unshift({
      id: "allowlist",
      note: String(error?.message || error),
    });
  }
}
fs.writeFileSync(
  path.join(OUT, "rc-compare-summary.json"),
  JSON.stringify(
    {
      system: SYSTEM,
      total: rows.length,
      rendered: rendered.length,
      scored: scored.length,
      blankReference: rows.filter((r) => r.referenceBlank).length,
      unsupported: rows.length - rendered.length,
      meanMismatchPct: meanPct,
      meanCoverageDeltaPct,
      meanContentMismatchPct,
      threshold: THRESHOLD,
      theme: THEME,
      cmpWasmGate,
      embedded: EMBEDDED
        ? {
            rendered: rows.filter((r) => r.embeddedRendered).length,
            scored: rows.filter((r) => r.embeddedRendered && !r.referenceBlank).length,
            meanMismatchPct: (() => {
              const ok = rows.filter((r) => r.embeddedRendered && !r.referenceBlank);
              return ok.length ? ok.reduce((s, r) => s + r.embeddedMismatchPct, 0) / ok.length : null;
            })(),
            ...laneSplitFor(rows, "embedded"),
          }
        : null,
      embeddedJvm: EMBEDDED_JVM
        ? {
            rendered: rows.filter((r) => r.embeddedJvmRendered).length,
            scored: rows.filter((r) => r.embeddedJvmRendered && !r.referenceBlank).length,
            meanMismatchPct: (() => {
              const ok = rows.filter((r) => r.embeddedJvmRendered && !r.referenceBlank);
              return ok.length
                ? ok.reduce((s, r) => s + r.embeddedJvmMismatchPct, 0) / ok.length
                : null;
            })(),
            ...laneSplitFor(rows, "embeddedJvm"),
          }
        : null,
      cmpWasm: CMP_WASM
        ? {
            rendered: rows.filter((r) => r.cmpWasmRendered).length,
            scored: rows.filter((r) => r.cmpWasmRendered && !r.referenceBlank).length,
            meanMismatchPct: (() => {
              const ok = rows.filter((r) => r.cmpWasmRendered && !r.referenceBlank);
              return ok.length
                ? ok.reduce((s, r) => s + r.cmpWasmMismatchPct, 0) / ok.length
                : null;
            })(),
            ...laneSplitFor(rows, "cmpWasm"),
            firstFrame: (() => {
              const summarize = (kind) => {
                const values = rows
                  .filter((row) => row.cmpWasmRendered && row.cmpWasmStartup === kind)
                  .map((row) => row.cmpWasmFirstFrameMs);
                return values.length
                  ? {
                      count: values.length,
                      meanMs: values.reduce((sum, value) => sum + value, 0) / values.length,
                      maxMs: Math.max(...values),
                      budgetMs:
                        kind === "cold"
                          ? CMP_WASM_MAX_COLD_FIRST_FRAME_MS
                          : CMP_WASM_MAX_WARM_FIRST_FRAME_MS,
                    }
                  : null;
              };
              return { cold: summarize("cold"), warm: summarize("warm") };
            })(),
          }
        : null,
      rows: rows.map((r) => ({
        id: r.id,
        rendered: r.rendered,
        mismatchPct: r.mismatchPct,
        coverageDeltaPct: r.coverageDeltaPct,
        contentMismatchPct: r.contentMismatchPct,
        // The share of the canvas the content verdict is computed over. Without it a content
        // number read alone can't be told apart from one drawn from a sliver of overlap.
        bothPaintedPct: r.bothPaintedPct ?? null,
        mismatchPx: r.mismatchPx,
        width: r.width,
        height: r.height,
        note: r.note ?? null,
        referenceBlank: r.referenceBlank ?? false,
        embeddedRendered: r.embeddedRendered ?? null,
        embeddedMismatchPct: r.embeddedMismatchPct ?? null,
        embeddedCoverageDeltaPct: r.embeddedCoverageDeltaPct ?? null,
        embeddedContentMismatchPct: r.embeddedContentMismatchPct ?? null,
        embeddedMismatchPx: r.embeddedMismatchPx ?? null,
        embeddedNote: r.embeddedNote ?? null,
        embeddedJvmRendered: r.embeddedJvmRendered ?? null,
        embeddedJvmMismatchPct: r.embeddedJvmMismatchPct ?? null,
        embeddedJvmCoverageDeltaPct: r.embeddedJvmCoverageDeltaPct ?? null,
        embeddedJvmContentMismatchPct: r.embeddedJvmContentMismatchPct ?? null,
        embeddedJvmMismatchPx: r.embeddedJvmMismatchPx ?? null,
        embeddedJvmNote: r.embeddedJvmNote ?? null,
        cmpWasmRendered: r.cmpWasmRendered ?? null,
        cmpWasmMismatchPct: r.cmpWasmMismatchPct ?? null,
        cmpWasmCoverageDeltaPct: r.cmpWasmCoverageDeltaPct ?? null,
        cmpWasmContentMismatchPct: r.cmpWasmContentMismatchPct ?? null,
        cmpWasmMismatchPx: r.cmpWasmMismatchPx ?? null,
        cmpWasmFirstFrameMs: r.cmpWasmFirstFrameMs ?? null,
        cmpWasmSettleMs: r.cmpWasmSettleMs ?? null,
        cmpWasmStartup: r.cmpWasmStartup ?? null,
        cmpWasmDensity: r.cmpWasmDensity ?? null,
        cmpWasmContextRender: r.cmpWasmContextRender ?? null,
        cmpWasmViewport: r.cmpWasmViewport ?? null,
        cmpWasmNote: r.cmpWasmNote ?? null,
        cmpWasmError: r.cmpWasmError ?? null,
      })),
    },
    null,
    2,
  ),
);

// Link the page from the catalog gallery. The index is generated earlier in the
// pipeline (before this step runs), so splice the nav link in next to the
// existing "SVG vs PNG compare" one rather than threading a flag through it.
const indexPath = path.join(OUT, "index.html");
if (fs.existsSync(indexPath)) {
  let index = fs.readFileSync(indexPath, "utf8");
  const anchor = '<a class="pagelink" href="compare.html">SVG vs PNG compare ↗</a>';
  const rcLink = ' <a class="pagelink" href="rc-compare.html">PNG vs Remote Compose ↗</a>';
  if (index.includes(anchor) && !index.includes('href="rc-compare.html"')) {
    index = index.replace(anchor, anchor + rcLink);
    fs.writeFileSync(indexPath, index);
    console.log("rc-compare: linked rc-compare.html from index.html");
  }
}

console.log(
  `rc-compare: wrote ${OUT}/rc-compare.html — ${rendered.length}/${rows.length} rendered` +
    (rendered.length === scored.length
      ? ""
      : `, ${rendered.length - scored.length} unscored (blank reference)`) +
    (meanPct == null ? "" : `, mean mismatch ${meanPct.toFixed(2)}%`),
);

if (REQUIRE_CMP_WASM) {
  const message = formatCmpWasmGate(cmpWasmGate);
  console.log(message);
  if (process.env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `\n### Remote Compose CMP/Wasm\n\n${message}\n`);
  }
  if (!cmpWasmGate.passed) process.exitCode = 1;
}
