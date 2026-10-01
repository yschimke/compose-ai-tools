/**
 * rc-cmp-wasm-compare-concurrency.test.mjs — `rc-compare.mjs --concurrency N` produces exactly what
 * the serial loop does: the same rows, in the same order, and byte-identical renders in both browser
 * lanes.
 *
 * Run with `node --test 'scripts/design-artifacts/*.test.mjs'`. Needs the staged TypeScript player
 * (`./gradlew stageVendoredRcPlayerJs`) and the published CMP/Wasm distribution
 * (`./gradlew stageVendoredRcPlayerWasm`, or point `RC_CMP_WASM_DIST` at one). It self-skips when
 * either is missing; set `RC_CMP_WASM_REQUIRE=1` to turn every skip into a failure, which is how the
 * `CMP/Wasm Frame Pacing` CI job runs it.
 *
 * Why this exists: the parity loop used to render one document at a time, which is 17 of the 26
 * minutes the `remote-m3` parity step takes in wear-m3-catalog. Running it concurrently is only safe
 * if it changes *nothing* but wall time, and the two ways it could change the output are both
 * cross-document: the TypeScript player page carries state from one document to the next (#4177),
 * and the Wasm lane used to serve every render from one shared "current document" slot. The driver is
 * run end to end on a synthetic catalog — every committed `.rc` fixture, several times over, so each
 * worker renders several documents and the round-robin split is exercised — once serially and once
 * concurrently, and the two outputs are compared file by file.
 *
 * The baked references are flat white squares, so the mismatch numbers are meaningless as parity;
 * they are only compared between the two runs, where they must be identical.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { zipSync } from "fflate";
import { PNG } from "pngjs";

import { CHROMIUM_LAUNCH_ARGS } from "./rc-chromium.mjs";
import { RC_PLAYER_JS_BUNDLE, rcPlayerBundleIssue } from "./rc-player-bundle.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(
  process.env.RC_CMP_WASM_DIST || path.join(HERE, "../../rc-player/wasm/build/wasmDist"),
);
const FIXTURES = path.join(HERE, "fixtures");
const REQUIRE = process.env.RC_CMP_WASM_REQUIRE === "1";
const COPIES = 3;
const SIZE = 200;
const CONCURRENCY = 3;

let skip = false;
let work;
let runs;

/** A bundle of every fixture `.rc`, [COPIES] times each, against a flat white baked reference. */
function syntheticBundle(file) {
  const white = new PNG({ width: SIZE, height: SIZE });
  white.data.fill(255);
  const baked = PNG.sync.write(white);
  const entries = {};
  const fixtures = fs.readdirSync(FIXTURES).filter((f) => f.endsWith(".rc"));
  for (const fixture of fixtures) {
    const bytes = fs.readFileSync(path.join(FIXTURES, fixture));
    for (let copy = 0; copy < COPIES; copy++) {
      const id = `fixture.${path.basename(fixture, ".rc").replace(/[^A-Za-z0-9]/g, "_")}_${copy}`;
      entries[`ir/${id}.rc`] = new Uint8Array(bytes);
      entries[`previews/${id}.png`] = new Uint8Array(baked);
    }
  }
  fs.writeFileSync(file, zipSync(entries, { level: 0 }));
  return fixtures.length * COPIES;
}

function runCompare(bundle, concurrency) {
  const out = path.join(work, `out-c${concurrency}`);
  const result = spawnSync(
    process.execPath,
    [
      path.join(HERE, "rc-compare.mjs"),
      "--bundle",
      bundle,
      "--player",
      RC_PLAYER_JS_BUNDLE,
      "--out",
      out,
      "--system",
      "concurrency-test",
      "--cmp-wasm",
      DIST,
      "--concurrency",
      String(concurrency),
    ],
    { encoding: "utf8", timeout: 600_000 },
  );
  return { out, ...result };
}

/** The per-document lines, with the only legitimately run-dependent part — timings — removed. */
function rowLines(stdout) {
  return stdout
    .split("\n")
    .filter((line) => line.startsWith("  "))
    .map((line) => line.replace(/\((cold|warm) \d+ ms, /, "("));
}

function filesUnder(dir) {
  return fs.existsSync(dir) ? fs.readdirSync(dir).sort() : [];
}

before(async () => {
  let chromium;
  try {
    ({ chromium } = await import("playwright"));
  } catch {
    skip = "playwright is not installed";
    return;
  }
  if (!fs.existsSync(path.join(DIST, "index.html"))) {
    skip = "the CMP/Wasm player distribution is not built";
    return;
  }
  const bundleIssue = rcPlayerBundleIssue();
  if (bundleIssue) {
    skip = bundleIssue;
    return;
  }
  try {
    const browser = await chromium.launch({
      headless: true,
      ...(process.env.RC_COMPARE_CHROMIUM
        ? { executablePath: process.env.RC_COMPARE_CHROMIUM }
        : {}),
      args: [...CHROMIUM_LAUNCH_ARGS],
    });
    await browser.close();
  } catch (e) {
    skip = `chromium unavailable: ${String(e).split("\n")[0]}`;
    return;
  }
  work = fs.mkdtempSync(path.join(os.tmpdir(), "rc-compare-concurrency-"));
  const bundle = path.join(work, "bundle.zip");
  const documents = syntheticBundle(bundle);
  runs = { documents, serial: runCompare(bundle, 1), concurrent: runCompare(bundle, CONCURRENCY) };
});

after(() => {
  if (work) fs.rmSync(work, { recursive: true, force: true });
});

function guard(t) {
  if (skip && REQUIRE) assert.fail(`RC_CMP_WASM_REQUIRE is set but the guard cannot run: ${skip}`);
  if (skip) {
    t.skip(skip);
    return false;
  }
  return true;
}

test("both runs complete, and the concurrent one actually splits the catalog", (t) => {
  if (!guard(t)) return;
  for (const [name, run] of Object.entries({ serial: runs.serial, concurrent: runs.concurrent })) {
    assert.equal(run.status, 0, `${name} run failed:\n${run.stdout}\n${run.stderr}`);
  }
  assert.match(
    runs.serial.stdout,
    new RegExp(`${runs.documents} document\\(s\\) across 1 page\\(s\\)`),
  );
  assert.match(
    runs.concurrent.stdout,
    new RegExp(`${runs.documents} document\\(s\\) across ${CONCURRENCY} page\\(s\\)`),
  );
});

test("rows come out identical and in catalog order", (t) => {
  if (!guard(t)) return;
  const serial = rowLines(runs.serial.stdout);
  assert.equal(serial.length, runs.documents);
  assert.deepEqual(rowLines(runs.concurrent.stdout), serial);
  assert.deepEqual(
    serial.map((line) => line.trim().split(":")[0]),
    [...serial.map((line) => line.trim().split(":")[0])].sort(),
    "the serial run is in catalog order, so equality above means the concurrent one is too",
  );
});

test("both lanes render byte-identical PNGs, and the Wasm lane really ran", (t) => {
  if (!guard(t)) return;
  // A lane that renders nothing in both runs would compare equal and prove nothing.
  assert.ok(
    filesUnder(path.join(runs.serial.out, "rc")).length > 0,
    "TypeScript lane rendered nothing",
  );
  assert.ok(
    filesUnder(path.join(runs.serial.out, "rc-cmp-wasm")).length > 0,
    `Wasm lane rendered nothing; errors: ${filesUnder(path.join(runs.serial.out, "rc-cmp-wasm-errors")).join(", ")}`,
  );
  for (const lane of ["rc", "rc-diff", "rc-cmp-wasm", "rc-cmp-wasm-diff", "rc-cmp-wasm-errors"]) {
    const serialDir = path.join(runs.serial.out, lane);
    const concurrentDir = path.join(runs.concurrent.out, lane);
    const names = filesUnder(serialDir);
    assert.deepEqual(filesUnder(concurrentDir), names, `${lane}: different files`);
    if (lane === "rc-cmp-wasm-errors") continue; // stacks carry worker-specific line state
    for (const name of names) {
      assert.ok(
        fs
          .readFileSync(path.join(serialDir, name))
          .equals(fs.readFileSync(path.join(concurrentDir, name))),
        `${lane}/${name} differs between --concurrency 1 and --concurrency ${CONCURRENCY}`,
      );
    }
  }
});
