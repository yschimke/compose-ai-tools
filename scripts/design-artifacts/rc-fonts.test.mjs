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
/** The served viewer's own copy of the table, and the build wiring that gives it the files. */
const SERVE_RC_FONTS = path.resolve(
  HERE,
  "../../cli/serve/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeRcFonts.kt",
);
// The staging task moved to `:cli:serve` with the sources it feeds (#3824 item 7): the
// faces are the served viewer's, so they are staged into the server module's jar rather
// than the CLI's.
const CLI_BUILD = path.resolve(HERE, "../../cli/serve/build.gradle.kts");

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
test("the serve viewer's face table matches this one", () => {
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

// The faces reach the CLI jar by a `processResources` copy from DEFAULT_FONTS_DIR — the same files
// this module inlines — rather than a second committed copy. A face the copy doesn't stage is served
// as nothing at all (`ServeRcFonts.css` omits it) and the lane falls back for that family only, which
// is the quietest possible half-fix.
test("the CLI stages every declared face into its jar", () => {
  const build = fs.readFileSync(CLI_BUILD, "utf8");
  const stage = build.slice(build.indexOf("val stageRcFontResources"));
  const block = stage.slice(0, stage.indexOf("sourceSets"));
  assert.ok(block.includes(path.basename(DEFAULT_FONTS_DIR)), "the copy must read the vendored dir");
  for (const { file } of FONT_FACES) {
    assert.ok(block.includes(`"${file}"`), `${file} is declared but never staged into the CLI jar`);
  }
});

test("a missing font directory degrades to generic families instead of throwing", () => {
  assert.equal(fontFaceCss(path.join(DEFAULT_FONTS_DIR, "does-not-exist")), "");
  assert.equal(fontFaceCss(undefined), "");
});
