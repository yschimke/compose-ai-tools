import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { FONT_FACES, DEFAULT_FONTS_DIR, fontFaceCss } from "./rc-fonts.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PAINT_CONTEXT = path.resolve(
  HERE,
  "../../third_party/remote-compose-player/src/web/CanvasPaintContext.ts",
);
// The served viewer's own copy of the table, and the build wiring that gives it the files. Both
// left with the server (#4732), so this is now a CROSS-REPO check against an optional sibling
// checkout of yschimke/compose-preview-server — the same shape `scripts/check-daemon-launch-schema.py`
// uses for the contracts and VS Code readers, and the residual gate item 1 on that issue asks for.
//
// Resolution order: `COMPOSE_PREVIEW_SERVER_ROOT`, else a `compose-preview-server` sibling of this
// checkout. With neither, the two cross-repo tests SKIP with a notice rather than fail: a developer
// with one checkout must not be blocked, and a green run that silently checked nothing is worse
// than a stated skip. CI can set the variable to make them assert.
function serverRoot() {
  const explicit = (process.env.COMPOSE_PREVIEW_SERVER_ROOT ?? "").trim();
  // The marker is the file this test actually reads, not an unrelated one. CI materialises only
  // the four mirror sources (it fetches them rather than checking the repository out — see the
  // "Fetch the pinned preview-server mirror sources" step and why), so a marker naming anything
  // else would report "no checkout" for a tree that has exactly what is needed.
  const marker = "server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeRcFonts.kt";
  if (explicit) {
    if (!fs.existsSync(path.join(explicit, marker))) {
      throw new Error(`COMPOSE_PREVIEW_SERVER_ROOT=${explicit} does not contain ${marker}`);
    }
    return explicit;
  }
  const sibling = path.resolve(HERE, "../../../compose-preview-server");
  return fs.existsSync(path.join(sibling, marker)) ? sibling : null;
}

const SERVER_ROOT = serverRoot();
const SERVE_RC_FONTS =
  SERVER_ROOT &&
  path.join(SERVER_ROOT, "server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeRcFonts.kt");
const SERVER_BUILD = SERVER_ROOT && path.join(SERVER_ROOT, "server/build.gradle.kts");
const NO_SERVER = {
  skip: SERVER_ROOT
    ? false
    : "no compose-preview-server checkout (set COMPOSE_PREVIEW_SERVER_ROOT) — the served " +
      "viewer's face table lives there since #4732",
};

test("every declared face has a vendored file", () => {
  for (const { file } of FONT_FACES) {
    assert.ok(
      fs.existsSync(path.join(DEFAULT_FONTS_DIR, file)),
      `${file} missing from ${DEFAULT_FONTS_DIR} — the parity page would silently fall back to a substituted typeface`,
    );
  }
});

// The player names concrete faces in `cssFontStackFor`; this module registers them. If the two
// drift — a family renamed on one side only — the request no longer matches anything registered and
// the page quietly reverts to generic families. That reads as a small parity regression spread
// across every preview containing text, which is exactly the failure mode that hid here before.
test("every non-generic family the player requests is registered here", () => {
  const src = fs.readFileSync(PAINT_CONTEXT, "utf8");
  const body = src.slice(src.indexOf("export function cssFontStackFor"));
  const fn = body.slice(0, body.indexOf("\n}"));

  const quoted = [...fn.matchAll(/'"([^"]+)",\s*[a-z-]+'/g)].map((m) => m[1]);
  const bare = [...fn.matchAll(/return '([A-Z][A-Za-z]*),\s*[a-z-]+'/g)].map((m) => m[1]);
  const requested = [...new Set([...quoted, ...bare])];

  assert.ok(requested.length > 0, "parsed no families out of cssFontStackFor — did it move?");

  const registered = new Set(FONT_FACES.map((f) => f.family));
  for (const family of requested) {
    assert.ok(registered.has(family), `player requests "${family}" but no face registers it`);
  }
});

test("fontFaceCss inlines one @font-face per file and needs no network", () => {
  const css = fontFaceCss(DEFAULT_FONTS_DIR);
  assert.equal((css.match(/@font-face/g) ?? []).length, FONT_FACES.length);
  assert.ok(css.includes("data:font/ttf;base64,"), "faces must be inlined, not fetched");
  assert.ok(!/url\((?!data:)/.test(css), "no non-data: url() — the page has no server");
});

// A weight the ranges do not cover is resolved by CSS's own matching rules, which for a target
// inside 400..500 search upward and pick Medium — rendering heavier than the baked raster. Wear M3
// asks for 450, so the gap is not hypothetical.
test("each family's weight ranges are contiguous and cover every usable weight", () => {
  const byFamily = new Map();
  for (const f of FONT_FACES) {
    if (!byFamily.has(f.family)) byFamily.set(f.family, []);
    byFamily.get(f.family).push(f.range.split(" ").map(Number));
  }
  for (const [family, ranges] of byFamily) {
    ranges.sort((a, b) => a[0] - b[0]);
    assert.equal(ranges[0][0], 1, `${family} must start at weight 1`);
    assert.equal(ranges[ranges.length - 1][1], 1000, `${family} must reach weight 1000`);
    for (let i = 1; i < ranges.length; i++) {
      assert.equal(
        ranges[i][0],
        ranges[i - 1][1] + 1,
        `${family} has a gap or overlap around weight ${ranges[i - 1][1]}`,
      );
    }
  }
});

// The served viewer registers the same faces for its own client-side lanes, from its own copy of the
// table (`ServeRcFonts.FACES`, in Kotlin — it has no way to import this module). Two tables, one
// meaning: if they drift, the offline parity numbers stop describing what a visitor's browser draws,
// and the disagreement is invisible in both outputs. So parse the Kotlin one and compare.
test("the serve viewer's face table matches this one", NO_SERVER, () => {
  const src = fs.readFileSync(SERVE_RC_FONTS, "utf8");
  const faces = [...src.matchAll(/Face\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)"\)/g)].map((m) => ({
    family: m[1],
    range: m[2],
    file: m[3],
  }));
  assert.ok(faces.length > 0, "parsed no faces out of ServeRcFonts.kt — did FACES move?");
  assert.deepEqual(
    faces,
    FONT_FACES.map(({ family, range, file }) => ({ family, range, file })),
    "ServeRcFonts.FACES and FONT_FACES disagree — the served viewer would register different faces " +
      "from the ones rc-compare measures parity against",
  );
});

// The faces reach the server jar by a `processResources` copy from DEFAULT_FONTS_DIR — the same
// files this module inlines — rather than a second committed copy. A face the copy doesn't stage is
// served as nothing at all (`ServeRcFonts.css` omits it) and the lane falls back for that family
// only, which is the quietest possible half-fix. The vendored font directory stayed here; the copy
// that consumes it is in the server's build, which is why this reads across the repository line.
test("the server stages every declared face into its jar", NO_SERVER, () => {
  const build = fs.readFileSync(SERVER_BUILD, "utf8");
  const stage = build.slice(build.indexOf("val stageRcFontResources"));
  const block = stage.slice(0, stage.indexOf("sourceSets"));
  assert.ok(block.includes(path.basename(DEFAULT_FONTS_DIR)), "the copy must read the vendored dir");
  for (const { file } of FONT_FACES) {
    assert.ok(block.includes(`"${file}"`), `${file} is declared but never staged into the server jar`);
  }
});

test("a missing font directory degrades to generic families instead of throwing", () => {
  assert.equal(fontFaceCss(path.join(DEFAULT_FONTS_DIR, "does-not-exist")), "");
  assert.equal(fontFaceCss(undefined), "");
});
