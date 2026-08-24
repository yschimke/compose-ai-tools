/**
 * `sha256-lite.mjs` against `node:crypto`.
 *
 * A digest is either exactly right or worthless, and the failure mode of a hand-written one is a
 * length that lands on a padding boundary — so the lengths below are chosen around every one of
 * them rather than sampled, and the corpus half runs it over the artifacts the contract actually
 * hashes.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { sha256Hex } from "./sha256-lite.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));

const reference = (bytes) => createHash("sha256").update(bytes).digest("hex");

test("the published test vectors", () => {
  assert.equal(sha256Hex(new Uint8Array(0)), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  assert.equal(
    sha256Hex(new TextEncoder().encode("abc")),
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  );
  assert.equal(
    sha256Hex(new TextEncoder().encode("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")),
    "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
  );
});

test("every length around a block and padding boundary", () => {
  // 55/56 is where the length no longer fits in the final block and a second one is appended, 63/64
  // is the block itself, and 119/120 is the same pair one block along. A padding bug lands on
  // exactly these and nowhere else.
  const lengths = new Set([0, 1, 2, 3, 54, 55, 56, 57, 63, 64, 65, 119, 120, 121, 127, 128, 129, 1000, 65536]);
  for (const length of lengths) {
    const bytes = new Uint8Array(randomBytes(length));
    assert.equal(sha256Hex(bytes), reference(bytes), `length ${length}`);
  }
});

test("the committed artifact corpus", () => {
  // The bytes this module exists to hash. A digest that agrees on random buffers and disagrees on a
  // real PNG would be a very strange bug, and it costs nothing to rule out.
  const root = join(HERE, "fixtures", "known-differences");
  let checked = 0;
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) {
        walk(full);
        continue;
      }
      if (!name.endsWith(".png")) continue;
      const bytes = new Uint8Array(readFileSync(full));
      assert.equal(sha256Hex(bytes), reference(bytes), full);
      checked++;
    }
  };
  walk(root);
  assert.ok(checked > 100, `expected the corpus to carry rasters, saw ${checked}`);
});
