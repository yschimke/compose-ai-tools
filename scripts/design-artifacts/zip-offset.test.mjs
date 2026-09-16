// `zipOffset` was copied verbatim into two modules before a third reader wanted it, and had no
// test of its own in either. It has one now, covering the case the structural check exists for:
// a PNG whose own bytes contain the ZIP local-file signature.

import test from "node:test";
import assert from "node:assert/strict";

import { zipOffset } from "./zip-offset.mjs";

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const ZIP_LOCAL_HEADER = [0x50, 0x4b, 0x03, 0x04];

/** A minimal PNG: signature, one chunk of [payload], then a zero-length `IEND`. */
function png(payload = []) {
  const chunk = (type, data) => {
    const out = [
      (data.length >>> 24) & 0xff,
      (data.length >>> 16) & 0xff,
      (data.length >>> 8) & 0xff,
      data.length & 0xff,
    ];
    return [...out, ...[...type].map((c) => c.charCodeAt(0)), ...data, 0, 0, 0, 0];
  };
  return [...PNG_SIGNATURE, ...chunk("IDAT", payload), ...chunk("IEND", [])];
}

test("a plain zip starts at zero", () => {
  assert.equal(zipOffset(Uint8Array.from([...ZIP_LOCAL_HEADER, 9, 9])), 0);
});

test("a polyglot's zip starts just past the PNG's IEND", () => {
  const prefix = png();
  const bytes = Uint8Array.from([...prefix, ...ZIP_LOCAL_HEADER, 9]);
  assert.equal(zipOffset(bytes), prefix.length);
});

test("a ZIP signature occurring inside the PNG's own bytes is not mistaken for the payload", () => {
  const prefix = png(ZIP_LOCAL_HEADER);
  const bytes = Uint8Array.from([...prefix, ...ZIP_LOCAL_HEADER, 9]);
  assert.equal(zipOffset(bytes), prefix.length);
});

test("bytes with no ZIP payload throw rather than returning a bogus offset", () => {
  assert.throws(() => zipOffset(Uint8Array.from(png())), /no ZIP payload/);
});

test("a truncated PNG falls back to the scan rather than returning null", () => {
  // A PNG signature with a chunk claiming more bytes than exist: pngEndOffset gives up, and the
  // scan finds the real payload appended after it.
  const bytes = Uint8Array.from([...PNG_SIGNATURE, 0xff, 0xff, 0xff, 0xff, ...ZIP_LOCAL_HEADER]);
  assert.equal(zipOffset(bytes), PNG_SIGNATURE.length + 4);
});
