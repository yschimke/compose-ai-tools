/**
 * `inflate-lite.mjs` against `node:zlib`.
 *
 * A format decoder is only as good as the inputs it has been shown, and the ones that matter are not
 * the ordinary ones: a stored block (`level: 0`), a fixed-Huffman block (short inputs), a dynamic
 * block with a full alphabet, and long runs, which are where the overlapping back-reference lives.
 * So the corpus is generated at every level and every shape rather than sampled, and the committed
 * fixture rasters are decompressed as well — those are the exact bytes the contract hashes and
 * decodes.
 *
 * The two properties beyond "it decompresses" get their own tests, because both are why this module
 * returns a record rather than a buffer: the output ceiling that stops a compression bomb, and the
 * consumed-input length that catches a second datastream appended inside a permitted chunk.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { deflateSync, inflateSync } from "node:zlib";
import { randomBytes } from "node:crypto";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { adler32, inflateZlib } from "./inflate-lite.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));

function roundTrip(bytes, options = {}) {
  const compressed = new Uint8Array(deflateSync(Buffer.from(bytes), options));
  const { data, bytesRead } = inflateZlib(compressed);
  assert.equal(bytesRead, compressed.length, "the whole datastream must be consumed");
  assert.deepEqual([...data], [...bytes]);
}

test("every deflate block type, over shapes that produce each", () => {
  const cases = {
    // Level 0 is a run of stored blocks — the branch with no Huffman decoding at all.
    stored: { bytes: new Uint8Array(randomBytes(200_000)), options: { level: 0 } },
    // Incompressible input at a normal level: the encoder falls back to stored blocks too, but
    // through a different path, and the block boundaries land elsewhere.
    random: { bytes: new Uint8Array(randomBytes(100_000)), options: {} },
    // A long run: overlapping back-references, where `distance < length` and a bulk copy is wrong.
    runs: { bytes: new Uint8Array(50_000).fill(0x5a), options: {} },
    // A tiny input, which is where an encoder emits the fixed-Huffman table rather than a dynamic one.
    tiny: { bytes: new TextEncoder().encode("ab"), options: {} },
    empty: { bytes: new Uint8Array(0), options: {} },
    // A full alphabet at maximum compression: a dynamic block whose code-length codes exercise the
    // 16/17/18 repeat symbols.
    alphabet: {
      bytes: Uint8Array.from({ length: 70_000 }, (_, i) => (i * 31 + (i >> 8)) & 0xff),
      options: { level: 9 },
    },
  };
  for (const [name, { bytes, options }] of Object.entries(cases)) {
    for (const level of [0, 1, 6, 9]) {
      try {
        roundTrip(bytes, { ...options, level: options.level ?? level });
      } catch (error) {
        throw new Error(`${name} at level ${level}: ${error.message}`, { cause: error });
      }
    }
  }
});

test("randomised lengths, compared byte for byte with node:zlib", () => {
  for (let length = 0; length < 300; length += 7) {
    const bytes = new Uint8Array(randomBytes(length));
    const compressed = new Uint8Array(deflateSync(Buffer.from(bytes)));
    assert.deepEqual([...inflateZlib(compressed).data], [...new Uint8Array(inflateSync(compressed))], `length ${length}`);
  }
});

test("the committed artifact corpus decompresses to the same bytes", () => {
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
      for (const stream of idatStreams(new Uint8Array(readFileSync(full)))) {
        let expected;
        try {
          expected = new Uint8Array(inflateSync(Buffer.from(stream)));
        } catch {
          // Several fixtures are deliberately undecodable — that is what they are for. What matters
          // is that the two decoders agree, so a stream `node:zlib` refuses is skipped here and
          // covered by the conformance suite's `decode-failed` cases instead.
          continue;
        }
        assert.deepEqual([...inflateZlib(stream).data], [...expected], full);
        checked++;
      }
    }
  };
  walk(root);
  assert.ok(checked > 100, `expected the corpus to carry rasters, saw ${checked}`);
});

/** Every `IDAT` run in a PNG, concatenated per image — the shape `decodePng` hands to the inflater. */
function idatStreams(bytes) {
  const streams = [];
  let parts = [];
  let offset = 8;
  while (offset + 8 <= bytes.length) {
    const length = readUint32(bytes, offset);
    const type = String.fromCharCode(bytes[offset + 4], bytes[offset + 5], bytes[offset + 6], bytes[offset + 7]);
    const start = offset + 8;
    if (start + length > bytes.length) break;
    if (type === "IDAT") parts.push(bytes.subarray(start, start + length));
    else if (parts.length > 0) {
      streams.push(concat(parts));
      parts = [];
    }
    offset = start + length + 4;
  }
  if (parts.length > 0) streams.push(concat(parts));
  return streams;
}

function concat(parts) {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

function readUint32(bytes, offset) {
  return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0;
}

test("the output ceiling fires before the bomb is materialised", () => {
  // Two megabytes of zeroes compress to a few hundred bytes. A ceiling that only checked the final
  // length would have allocated all of it first, which is the protection failing at the moment it is
  // supposed to fire.
  const compressed = new Uint8Array(deflateSync(Buffer.alloc(2_000_000)));
  assert.throws(() => inflateZlib(compressed, { maxOutputLength: 1024 }), (error) => error.code === "output-too-large");
  // And the ceiling is inclusive: exactly the declared size decodes.
  assert.equal(inflateZlib(compressed, { maxOutputLength: 2_000_000 }).data.length, 2_000_000);
});

test("bytes after the datastream are visible to the caller", () => {
  // The `IDAT` run is one zlib datastream and must be consumed whole. A decoder that stops at the end
  // of the first stream and ignores what follows lets an artifact append a second one inside a
  // permitted chunk — the same shape as the bytes-after-`IEND` case, one level down.
  const compressed = new Uint8Array(deflateSync(Buffer.from("payload")));
  const padded = new Uint8Array(compressed.length + 3);
  padded.set(compressed);
  const { bytesRead } = inflateZlib(padded);
  assert.equal(bytesRead, compressed.length);
  assert.ok(bytesRead < padded.length, "the caller must be able to see the trailing bytes");
});

test("a corrupt stream is refused rather than half-decoded", () => {
  const compressed = new Uint8Array(deflateSync(Buffer.from("x".repeat(5000))));
  const truncated = compressed.subarray(0, compressed.length - 8);
  assert.throws(() => inflateZlib(truncated), (error) => error.code === "inflate-failed");

  const wrongChecksum = Uint8Array.from(compressed);
  wrongChecksum[wrongChecksum.length - 1] ^= 0xff;
  assert.throws(() => inflateZlib(wrongChecksum), (error) => error.code === "inflate-failed");

  const notDeflate = Uint8Array.from(compressed);
  notDeflate[0] = 0x09;
  assert.throws(() => inflateZlib(notDeflate), (error) => error.code === "inflate-failed");
});

test("Adler-32 matches the checksum node:zlib wrote", () => {
  for (const length of [0, 1, 5552, 5553, 100_000]) {
    const bytes = new Uint8Array(randomBytes(length));
    const compressed = new Uint8Array(deflateSync(Buffer.from(bytes)));
    const tail = compressed.subarray(compressed.length - 4);
    assert.equal(adler32(bytes), ((tail[0] << 24) | (tail[1] << 16) | (tail[2] << 8) | tail[3]) >>> 0, `length ${length}`);
  }
});
