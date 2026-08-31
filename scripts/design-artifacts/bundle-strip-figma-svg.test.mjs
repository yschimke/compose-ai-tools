import assert from "node:assert/strict";
import { test } from "node:test";
import { unzipSync, zipSync } from "fflate";

import { stripFigmaSvgSidecars } from "./bundle-strip-figma-svg.mjs";

const pngCover = new Uint8Array([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0, 0x49, 0x45, 0x4e,
  0x44, 0xae, 0x42, 0x60, 0x82,
]);

test("strips figma-svg and hybrid crops while retaining an executable bundle", () => {
  const zip = zipSync({
    "bundle.json": new TextEncoder().encode('{"backend":"android"}'),
    "classes/app.jar": new Uint8Array([1, 2, 3]),
    "previews/a.png": new Uint8Array([4]),
    "previews/a.figma.svg": new TextEncoder().encode("<svg/>"),
    "previews/a.figma-raster/7.png": new Uint8Array([5, 6]),
    "previews/a.layout.json": new Uint8Array([7]),
  });
  const bundle = new Uint8Array(pngCover.length + zip.length);
  bundle.set(pngCover);
  bundle.set(zip, pngCover.length);

  const stripped = stripFigmaSvgSidecars(bundle);
  const entries = unzipSync(stripped.bytes.slice(pngCover.length));

  assert.equal(stripped.removedEntries, 2);
  assert.equal(stripped.removedBytes, 8);
  assert.deepEqual(Object.keys(entries).sort(), [
    "bundle.json",
    "classes/app.jar",
    "previews/a.layout.json",
    "previews/a.png",
  ]);
  assert.deepEqual(stripped.bytes.slice(0, pngCover.length), pngCover);
});

test("is a byte-preserving no-op when a bundle carries no figma-svg", () => {
  const zip = zipSync({ "bundle.json": new Uint8Array([1]) });
  const result = stripFigmaSvgSidecars(zip);
  assert.equal(result.removedEntries, 0);
  assert.strictEqual(result.bytes, zip);
});
