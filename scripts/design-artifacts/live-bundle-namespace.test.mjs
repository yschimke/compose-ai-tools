import assert from "node:assert/strict";
import test from "node:test";

import { unzipSync, zipSync } from "fflate";

import { namespaceLiveBundle } from "./live-bundle-namespace.mjs";
import { moduleIdentityPrefix } from "./multi-module-catalog.mjs";

const json = (value) => new TextEncoder().encode(JSON.stringify(value));

function pngChunk(type, payload = new Uint8Array()) {
  const bytes = new Uint8Array(12 + payload.length);
  new DataView(bytes.buffer).setUint32(0, payload.length);
  bytes.set(new TextEncoder().encode(type), 4);
  bytes.set(payload, 8);
  return bytes;
}

function concat(...parts) {
  const output = new Uint8Array(parts.reduce((size, part) => size + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.length;
  }
  return output;
}

test("namespaces an executable bundle's ids, sidecars and keyed paths idempotently", () => {
  // A valid PNG prefix whose compressed payload happens to contain a ZIP local-header signature.
  // The namespace pass must start at IEND, not at those coincidental bytes.
  const cover = concat(
    new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk("IDAT", new Uint8Array([0x50, 0x4b, 0x03, 0x04])),
    pngChunk("IEND"),
  );
  const zip = zipSync({
    "bundle.json": json({ previewIds: ["activity__MainActivity"], rawPreviewIds: ["activity__MainActivity"] }),
    "previews.json": json({ previews: [{ id: "activity__MainActivity", functionName: "MainActivity" }] }),
    "previews/activity__MainActivity.png": new Uint8Array([1, 2, 3]),
    "previews/activity__MainActivity.overrides.json": json({ previewId: "activity__MainActivity" }),
    "classes/app.jar": new Uint8Array([4, 5, 6]),
  });
  const input = new Uint8Array(cover.length + zip.length);
  input.set(cover);
  input.set(zip, cover.length);

  const output = namespaceLiveBundle(input, ":tv");
  const prefix = moduleIdentityPrefix(":tv");
  const entries = unzipSync(output.slice(cover.length));
  const bundle = JSON.parse(new TextDecoder().decode(entries["bundle.json"]));
  const previews = JSON.parse(new TextDecoder().decode(entries["previews.json"]));

  assert.deepEqual(bundle.previewIds, [`${prefix}activity__MainActivity`]);
  assert.equal(previews.previews[0].id, `${prefix}activity__MainActivity`);
  assert.ok(entries[`previews/${prefix}activity__MainActivity.png`]);
  assert.equal(
    JSON.parse(new TextDecoder().decode(entries[`previews/${prefix}activity__MainActivity.overrides.json`])).previewId,
    `${prefix}activity__MainActivity`,
  );
  assert.deepEqual(entries["classes/app.jar"], new Uint8Array([4, 5, 6]));
  assert.deepEqual(namespaceLiveBundle(output, ":tv"), output);
});

test("namespaces overlapping ids in one pass", () => {
  const zip = zipSync({
    "bundle.json": json({ previewIds: ["A", "AB"] }),
    "previews.json": json({ previews: [{ id: "A" }, { id: "AB" }] }),
    "previews/AB.json": json({ previewId: "AB", byPreview: { AB: { selected: true } } }),
  });
  const prefix = moduleIdentityPrefix(":tv");
  const entries = unzipSync(namespaceLiveBundle(zip, ":tv"));
  const bundle = JSON.parse(new TextDecoder().decode(entries["bundle.json"]));

  assert.deepEqual(bundle.previewIds, [`${prefix}A`, `${prefix}AB`]);
  assert.ok(entries[`previews/${prefix}AB.json`]);
  const sidecar = JSON.parse(new TextDecoder().decode(entries[`previews/${prefix}AB.json`]));
  assert.equal(sidecar.byPreview[`${prefix}AB`].selected, true);
});
