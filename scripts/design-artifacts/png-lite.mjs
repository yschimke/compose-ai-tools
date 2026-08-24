/**
 * A dependency-free, **host-free** PNG reader, sized to exactly what the known-difference contract
 * needs.
 *
 * Host-free since batch 05: no `node:zlib`, no `node:crypto`, nothing a browser lacks — see
 * [`inflate-lite.mjs`](./inflate-lite.mjs) and [`sha256-lite.mjs`](./sha256-lite.mjs). That is not
 * tidiness. The acceptance engine in `format-compare.js` and the offline one must decode the same
 * bytes the same way, and the browser's only other decode path is an `<img>` onto a canvas, which
 * normalises every colour type to 8-bit RGBA and so cannot see the encoding rules
 * [§4](../../docs/design/COMPONENT_PARITY_WORKFLOW.md#the-normative-contract) spends a section on.
 * The **writer** lives in [`png-write.mjs`](./png-write.mjs), which does need a compressor and is
 * Node-only; only the fixture generator writes a PNG.
 *
 * `pngjs` is already a driver dependency and is *not* what this is for. Two reasons, both from
 * [`COMPONENT_PARITY_WORKFLOW.md` §4](../../docs/design/COMPONENT_PARITY_WORKFLOW.md#the-normative-contract):
 *
 * 1. **The contract's preflight is a header walk, not a decode.** It reads `IHDR`, takes
 *    `width × height` from it, checks the two bytes after them, and walks chunk *headers* to the
 *    first `IDAT` looking for `acTL` — never a chunk's data, never an allocation sized by the file,
 *    and over a 4 KiB *prefix* of the artifact rather than the whole of it. A library decode
 *    allocates the oversized raster to measure it, which defeats the budget at the moment it is
 *    supposed to fire — and so, more quietly, does reading the whole file to look at its first
 *    kilobyte. {@link preflightPng} is that walk and nothing more.
 * 2. **The fixtures need files a well-behaved encoder refuses to write** — an APNG, a palette mask
 *    with strictly binary samples, a header that lies about its dimensions, a truncated file. Those
 *    are the cases a sample-only or decode-only check accepts, so the suite is worthless without
 *    them, and {@link buildPng} plus `png-write.mjs` exist to author them deliberately.
 *
 * Scope is deliberately narrow: bit depth 8, no interlacing, single `IDAT`. Anything else is a
 * decode failure rather than a feature, which is also the verdict the contract wants for it.
 */

import { inflateZlib } from "./inflate-lite.mjs";
import { sha256Hex as digest } from "./sha256-lite.mjs";

const SIGNATURE = Uint8Array.from([137, 80, 78, 71, 13, 10, 26, 10]);

/** Colour types this module understands. Greyscale and RGBA are the two the contract names. */
export const COLOUR_GREY = 0;
export const COLOUR_RGB = 2;
export const COLOUR_PALETTE = 3;
export const COLOUR_GREY_ALPHA = 4;
export const COLOUR_RGBA = 6;

const CHANNELS = {
  [COLOUR_GREY]: 1,
  [COLOUR_RGB]: 3,
  [COLOUR_PALETTE]: 1,
  [COLOUR_GREY_ALPHA]: 2,
  [COLOUR_RGBA]: 4,
};

/**
 * The **complete** set of chunks `v1` permits. Anything else refuses the artifact.
 *
 * An allowlist rather than a growing list of things to reject, and that is the whole point. Every
 * chunk PNG defines is a place where a lenient decoder and a colour-managed browser can disagree
 * about the pixels a gate then compares — `gAMA`, `sRGB` and `iCCP` transform the samples outright,
 * `tEXt` and friends do not but invite a rule about *which* ones do, and each new one caught this
 * way is one more round of the same argument. Enumerating what is understood makes the question
 * finite and closed: these five chunks, every one of which this decoder reads and whose CRC is
 * therefore fatal, and nothing else.
 *
 * The cost is that a producer must not emit ancillary chunks, which is one line in any encoder — and
 * these artifacts are machine-generated crops of an already-composited render, not photographs
 * carrying provenance. `acTL` is the one exception to the token, not the rule: it is caught earlier
 * in the preflight so an animated PNG reports `animated-png`, which says far more than "chunk not
 * permitted".
 */
const PERMITTED_CHUNKS = new Set(["IHDR", "PLTE", "IDAT", "tRNS", "IEND"]);

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** One chunk, length + type + data + CRC, as the file lays it out. */
export function chunk(type, data = new Uint8Array(0)) {
  const typeBytes = Uint8Array.from(type, (ch) => ch.charCodeAt(0));
  const out = new Uint8Array(12 + data.length);
  const view = new DataView(out.buffer);
  view.setUint32(0, data.length);
  out.set(typeBytes, 4);
  out.set(data, 8);
  view.setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)));
  return out;
}

/**
 * Grow a PNG to an exact encoded length **without changing the image it decodes to**.
 *
 * The fixtures need artifacts of a stated byte size — the encoded-byte cap and one byte past it —
 * and committing 8 MiB twice to pin a boundary is not a trade this tree should make. Appending zero
 * bytes after `IEND` was the obvious way and is now refused, correctly: `IEND` ends the datastream,
 * so anything after it bypasses the allowlist, the placement rules and every CRC.
 *
 * So the padding goes *inside* the compressed stream, where it is legal and inert:
 *
 * - an **empty stored deflate block** — `00 00 00 FF FF`, `BFINAL=0`, `BTYPE=00`, `LEN=0` — costs
 *   five bytes, inflates to nothing, and leaves the next block byte-aligned exactly as it found it.
 *   Inserted straight after the two-byte zlib header, before the original block, so the Adler-32
 *   trailer (which covers the *uncompressed* bytes) is untouched.
 * - a **zero-length `IDAT` chunk** costs twelve, contributes nothing to the stream, and keeps the
 *   `IDAT` run contiguous.
 *
 * Five and twelve are coprime, so every padding of 44 bytes or more is reachable exactly (43 is the
 * Frobenius number of the pair, and the sizes here are megabytes). The
 * result is a PNG a strict decoder accepts and any decoder renders identically to its input.
 */
export function padPngTo(bytes, targetLength) {
  const padding = targetLength - bytes.length;
  if (padding < 0) throw new Error(`cannot pad ${bytes.length} bytes down to ${targetLength}`);
  if (padding === 0) return Uint8Array.from(bytes);

  let emptyChunks = -1;
  let storedBlocks = 0;
  for (let candidate = 0; candidate <= 4; candidate++) {
    const remainder = padding - 12 * candidate;
    if (remainder >= 0 && remainder % 5 === 0) {
      emptyChunks = candidate;
      storedBlocks = remainder / 5;
      break;
    }
  }
  if (emptyChunks < 0) {
    throw new Error(`padding of ${padding} bytes is not expressible as 5a + 12b; 44 and above always are`);
  }

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const before = [];
  const after = [];
  let idatPayload = null;
  let offset = SIGNATURE.length;
  while (offset + 8 <= bytes.length) {
    const length = view.getUint32(offset);
    const type = String.fromCharCode(...bytes.subarray(offset + 4, offset + 8));
    const whole = bytes.subarray(offset, offset + 12 + length);
    if (type === "IDAT") {
      if (idatPayload) throw new Error("padPngTo expects a single IDAT");
      idatPayload = bytes.subarray(offset + 8, offset + 8 + length);
    } else if (idatPayload) {
      after.push(whole);
    } else {
      before.push(whole);
    }
    offset += 12 + length;
  }
  if (!idatPayload) throw new Error("padPngTo found no IDAT");

  const padded = new Uint8Array(idatPayload.length + storedBlocks * 5);
  padded.set(idatPayload.subarray(0, 2), 0);
  for (let i = 0; i < storedBlocks; i++) {
    padded.set([0x00, 0x00, 0x00, 0xff, 0xff], 2 + i * 5);
  }
  padded.set(idatPayload.subarray(2), 2 + storedBlocks * 5);

  const out = buildPng([
    ...before,
    chunk("IDAT", padded),
    ...Array.from({ length: emptyChunks }, () => chunk("IDAT", new Uint8Array(0))),
    ...after,
  ]);
  if (out.length !== targetLength) throw new Error(`padded to ${out.length}, not ${targetLength}`);
  return out;
}

/** Concatenate the signature and a list of chunks into a file. */
export function buildPng(chunks) {
  const total = chunks.reduce((sum, c) => sum + c.length, SIGNATURE.length);
  const out = new Uint8Array(total);
  out.set(SIGNATURE, 0);
  let offset = SIGNATURE.length;
  for (const c of chunks) {
    out.set(c, offset);
    offset += c.length;
  }
  return out;
}

/**
 * An `IHDR` chunk, spelled out so a fixture can lie in any one field.
 *
 * `compression` and `filter` are parameters rather than constants for the same reason the rest are:
 * the specification defines exactly one legal value for each, so a fixture that exercises the check
 * has to be able to write an illegal one — and it must be written *here*, where the chunk's CRC is
 * computed over it, rather than poked into the finished file afterwards. A poked byte leaves a stale
 * CRC, and the file is then refused by the CRC check before the method byte is ever read.
 */
export function ihdr({
  width,
  height,
  bitDepth = 8,
  colourType = COLOUR_RGBA,
  compression = 0,
  filter = 0,
  interlace = 0,
}) {
  const data = new Uint8Array(13);
  const view = new DataView(data.buffer);
  view.setUint32(0, width);
  view.setUint32(4, height);
  data[8] = bitDepth;
  data[9] = colourType;
  data[10] = compression;
  data[11] = filter;
  data[12] = interlace;
  return chunk("IHDR", data);
}

/**
 * The **complete** set of chunks that may sit between `IHDR` and the first `IDAT`.
 *
 * A subset of {@link PERMITTED_CHUNKS}, and the reason it is separate is what makes the preflight's
 * bound provable rather than hopeful. The preflight is served a *prefix* of the file — it never sees
 * the whole artifact — so "walk chunk headers to the first `IDAT`" is only a bounded read if the
 * bytes it must skip over are bounded. Enumerating what may appear there makes that arithmetic
 * finite: `PLTE` is at most 768 bytes of data, `tRNS` at most 256, and nothing else is allowed to
 * take up room before the image data starts.
 *
 * `acTL` is not on this list and is not a violation of it either: it is the animation signal, and
 * {@link preflightPng} returns on sight of it rather than skipping past it, so it costs the eight
 * bytes of its own header and nothing more. That also means a real APNG — which carries `acTL`,
 * then an `fcTL`, then `IDAT` — reports `animated-png` rather than tripping over the `fcTL` it never
 * reaches.
 */
const PRE_IDAT_CHUNKS = new Set(["PLTE", "tRNS"]);

/**
 * The most bytes a **conforming** artifact can put before the first `IDAT`'s type field, so that a
 * prefix at least this long always resolves the preflight.
 *
 * Signature 8, `IHDR` 12 + 13 = 25, `PLTE` 12 + 768 = 780, `tRNS` 12 + 256 = 268, and the eight-byte
 * length-and-type of the first `IDAT` = **1089**. An `acTL` standing where that `IDAT` header would
 * be costs the same eight bytes, because the walk returns on reading its *type* and never skips its
 * data — so the animated path is bounded by the same number rather than a longer one.
 *
 * This is an assertion about the format, not a budget knob: it is what licenses `maxPreflightBytes`
 * in `known-differences.mjs` being a fixed constant instead of "however much the file wants".
 * The prefix that constant names is roughly 3.7x this, which is margin for a future chunk rather
 * than slack this arithmetic needs.
 */
export const MAX_CONFORMING_HEADER_BYTES = 8 + 25 + 780 + 268 + 8;

/**
 * The contract's bounded header preflight: `IHDR` plus a walk of chunk *headers* to the first
 * `IDAT`.
 *
 * Returns `{ width, height, bitDepth, colourType, interlace, animated, hasTransparency, byteLength }`,
 * or
 * `{ error: "header-invalid" }` for anything it cannot read — a wrong signature, a file too short to
 * hold an `IHDR`, a chunk length that runs past what it was given, a chunk that has no business
 * sitting before the image data, a missing `IDAT`. Never reads chunk data, never allocates anything
 * sized by the file, so an 8192-cap breach costs the same handful of bytes as a legal header.
 *
 * **`bytes` may be a prefix of the artifact rather than the whole of it**, which is the point:
 * a reader that must materialise 8 MiB to have its header read has defeated the budget at the moment
 * it is supposed to fire. `byteLength` is the size of the *whole* file — the reader knows it from a
 * `stat` or a `Content-Length` before any bytes exist — and defaults to what was handed over for a
 * caller holding the complete artifact. It is reported back untouched so the second-read comparison
 * still sees a fact about the file rather than a fact about how much of it was read.
 *
 * A prefix that runs out before the first `IDAT` is `header-invalid`, and by
 * {@link MAX_CONFORMING_HEADER_BYTES} that can only happen to a file that was going to be refused
 * anyway: a `PLTE` claiming more than its 768 bytes, an ancillary chunk parked in front of the image
 * data, an `IHDR` followed by nothing. The token differs from the `decode-failed` those files reach
 * on a whole-file decode, and deliberately — the preflight is not decoding them, it is failing to
 * read their header, which is what `header-invalid` says.
 */
export function preflightPng(bytes, { byteLength = bytes?.length } = {}) {
  const fail = { error: "header-invalid" };
  if (!bytes || bytes.length < SIGNATURE.length + 12 + 13) return fail;
  for (let i = 0; i < SIGNATURE.length; i++) if (bytes[i] !== SIGNATURE[i]) return fail;

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (view.getUint32(8) !== 13 || readType(bytes, 12) !== "IHDR") return fail;
  const width = view.getUint32(16);
  const height = view.getUint32(20);
  if (width === 0 || height === 0) return fail;

  let hasTransparency = false;
  const header = (animated) => ({
    width,
    height,
    bitDepth: bytes[24],
    colourType: bytes[25],
    compression: bytes[26],
    filter: bytes[27],
    interlace: bytes[28],
    animated,
    hasTransparency,
    byteLength,
  });

  // The walk starts *past* `IHDR`, which the four checks above have already validated in full — its
  // position, its length and its type. Re-entering the loop at offset 8 would put it up against the
  // allowlist that exists to keep everything else out, and refuse every conforming file.
  let offset = 8 + 12 + 13;
  // Chunk lengths are unsigned 32-bit, so a hostile file can name a length that overflows what we
  // hold. Every step is bounds-checked against `bytes.length` rather than trusted — and since
  // `bytes` may be a prefix, "runs past the end" and "runs past the prefix" are the same check and
  // the same verdict. There is no third answer available to a reader that stopped at 4 KiB.
  while (offset + 8 <= bytes.length) {
    const length = view.getUint32(offset);
    const type = readType(bytes, offset + 4);
    // **`IDAT` ends the walk before its length is consulted.** The whole point of stopping here is
    // that the image data is never read, so requiring it to *fit* would make the preflight fail on
    // every prefix of every real file — the first `IDAT` of an 8 MiB artifact does not fit in 4 KiB
    // and never needed to. A length that lies about the data behind it is caught by the decoder,
    // which is the phase that has the data.
    if (type === "IDAT") return header(false);
    // **`acTL` ends it too, with the verdict already decided.** Nothing later in the file can make
    // an animated PNG acceptable, so there is no reason to keep walking — and walking on would run
    // into the `fcTL` that a real APNG puts next, which is not a chunk this list admits.
    if (type === "acTL") return header(true);
    if (!PRE_IDAT_CHUNKS.has(type)) return fail;
    if (type === "tRNS") hasTransparency = true;
    // Skipping a chunk means its data must actually be in hand. This is the check that bounds the
    // walk: a `PLTE` declaring 8000 bytes runs past a 4 KiB prefix and is refused here, exactly as a
    // conforming reader would refuse it for exceeding 768 one phase later.
    if (length > bytes.length - offset - 12) return fail;
    offset += 12 + length;
  }
  return fail;
}

function readType(bytes, offset) {
  return String.fromCharCode(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]);
}

/**
 * Decode to non-premultiplied 8-bit RGBA.
 *
 * Throws for anything outside the supported shape — bit depth other than 8, interlaced, a colour
 * type this module does not carry, scanline data that does not add up. The contract's verdict for
 * every one of those is `decode-failed`, so the caller catches rather than branching on the reason.
 */
export function decodePng(bytes) {
  const header = preflightPng(bytes);
  if (header.error) throw new Error("decode-failed: unreadable header");
  const { width, height, bitDepth, colourType, interlace } = header;
  if (bitDepth !== 8) throw new Error("decode-failed: bit depth " + bitDepth);
  if (interlace !== 0) throw new Error("decode-failed: interlaced");
  // `IHDR` declares a compression method and a filter method, and the specification defines exactly
  // one of each — `0`. A conforming decoder refuses anything else, so ignoring these two bytes means
  // inflating ordinary-looking scanlines and reaching a *gate verdict* where the browser reaches
  // `decode-failed`. Same class as the interlace check above, and the same token.
  if (header.compression !== 0) throw new Error("decode-failed: compression method " + header.compression);
  if (header.filter !== 0) throw new Error("decode-failed: filter method " + header.filter);
  if (!(colourType in CHANNELS)) throw new Error("decode-failed: colour type " + colourType);

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const parts = [];
  let palette = null;
  let transparency = null;
  let sawIend = false;
  let sawIdat = false;
  let idatEnded = false;
  let offset = 8;
  while (offset + 8 <= bytes.length) {
    const length = view.getUint32(offset);
    const type = readType(bytes, offset + 4);
    if (length > bytes.length - offset - 12) throw new Error("decode-failed: chunk overruns file");
    const data = bytes.subarray(offset + 8, offset + 8 + length);
    // Not on the allowlist — refused, whether it is critical, ancillary, known or invented. The
    // specification already requires stopping on an unrecognized *critical* chunk; this goes further
    // for the reason {@link PERMITTED_CHUNKS} gives.
    if (!PERMITTED_CHUNKS.has(type)) throw new Error("decode-failed: chunk not permitted: " + type);
    // **Permitted is not the same as well-placed.** A duplicate `IHDR`, a `PLTE` or `tRNS` after the
    // image data, a non-contiguous `IDAT` run or a non-empty `IEND` are all built from allowed
    // chunks and are all rejected by a conforming decoder — so admitting them on membership alone
    // reaches a gate verdict where the other side of the contract reaches `decode-failed`. There are
    // only five chunks to constrain, which is precisely what the allowlist bought: the structural
    // rules are finite because the vocabulary is.
    if (type === "IHDR" && offset !== 8) throw new Error("decode-failed: IHDR is not first");
    if (offset === 8 && type !== "IHDR") throw new Error("decode-failed: IHDR is not first");
    if (type === "IHDR" && length !== 13) throw new Error("decode-failed: IHDR is not 13 bytes");
    // Placement was only half of it. A chunk can sit exactly where it belongs and still be illegal
    // *for this image* — `PLTE` in a greyscale file, `tRNS` beside a colour type that already carries
    // alpha, a `tRNS` whose length does not match the colour type it describes. The specification
    // forbids each, so a conforming decoder rejects them while a placement-only check admits them and
    // reaches a gate verdict.
    if (type === "PLTE") {
      // `tRNS` describes the palette, so it follows it whenever both are present — for truecolor's
      // optional suggested palette as much as for an indexed image. The indexed branch below already
      // required `PLTE` first; this is the same rule for the colour types that reach it the other
      // way round.
      if (palette || transparency || sawIdat) throw new Error("decode-failed: misplaced PLTE");
      if (colourType === COLOUR_GREY || colourType === COLOUR_GREY_ALPHA) {
        throw new Error("decode-failed: PLTE on a greyscale image");
      }
      if (length === 0 || length % 3 !== 0 || length > 768) throw new Error("decode-failed: bad PLTE length");
    }
    if (type === "tRNS") {
      if (transparency || sawIdat) throw new Error("decode-failed: misplaced tRNS");
      if (colourType === COLOUR_GREY_ALPHA || colourType === COLOUR_RGBA) {
        throw new Error("decode-failed: tRNS on an alpha colour type");
      }
      if (colourType === COLOUR_GREY && length !== 2) throw new Error("decode-failed: bad tRNS length");
      if (colourType === COLOUR_RGB && length !== 6) throw new Error("decode-failed: bad tRNS length");
      // `tRNS` stores its samples as 16-bit values whatever the image's bit depth, and at depth 8
      // the range is 0–255 — so a non-zero high byte names a sample the image cannot contain. That
      // is not a harmless spare byte: reading the low half alone (`0x01ff` → 255) makes a real pixel
      // transparent, while a decoder honouring the range finds no match and leaves it opaque. Two
      // rasters, one hash-valid file, and the difference lands straight in the candidate gate.
      if (colourType === COLOUR_GREY && data[0] !== 0) throw new Error("decode-failed: tRNS sample out of range");
      if (colourType === COLOUR_RGB && (data[0] !== 0 || data[2] !== 0 || data[4] !== 0)) {
        throw new Error("decode-failed: tRNS sample out of range");
      }
      if (colourType === COLOUR_PALETTE) {
        if (!palette) throw new Error("decode-failed: tRNS before PLTE");
        if (length === 0 || length > palette.length / 3) throw new Error("decode-failed: bad tRNS length");
      }
    }
    if (type === "IDAT") {
      if (idatEnded) throw new Error("decode-failed: IDAT run is not contiguous");
    } else if (sawIdat) {
      idatEnded = true;
    }
    if (type === "IEND" && length !== 0) throw new Error("decode-failed: IEND is not empty");
    // Every permitted chunk is a consumed chunk, so every CRC here is fatal. The artifact's own
    // `sha256` proves nobody edited the file in flight; it says nothing about whether the file was
    // ever well-formed, and a committed-corrupt `IDAT` would otherwise decode here and be rejected by
    // a native decoder on the other side of the contract.
    if (view.getUint32(offset + 8 + length) !== crc32(bytes.subarray(offset + 4, offset + 8 + length))) {
      throw new Error("decode-failed: chunk CRC mismatch");
    }
    if (type === "IDAT") {
      parts.push(data);
      sawIdat = true;
    }
    if (type === "PLTE") palette = data;
    if (type === "tRNS") transparency = data;
    if (type === "IEND") {
      // **`IEND` ends the datastream, so it must end the file.** A chunk or arbitrary bytes appended
      // after it bypass the allowlist, the placement rules and every CRC — this loop simply stops —
      // so an artifact carrying a second `IHDR`, an `acTL`, or a kilobyte of anything at all past the
      // end reaches a gate verdict here while a strict decoder refuses the datastream. That is the
      // same divergence the allowlist exists to close, one byte past where it was looking.
      if (offset + 12 + length !== bytes.length) {
        throw new Error("decode-failed: bytes follow IEND");
      }
      sawIend = true;
      break;
    }
    offset += 12 + length;
  }
  if (parts.length === 0) throw new Error("decode-failed: no IDAT");
  // A stream truncated after a complete `IDAT` decodes to *something* — how much depends on where
  // the truncation landed, which is exactly the kind of consumer-dependent answer this contract
  // cannot have. `IEND` is mandatory, so requiring it is the deterministic reading. It makes this
  // stricter than a browser, which will happily paint a partial raster, and that is the intended
  // direction: a committed artifact that is missing its terminator is broken, not partial.
  if (!sawIend) throw new Error("decode-failed: no IEND");
  if (colourType === COLOUR_PALETTE && !palette) throw new Error("decode-failed: no PLTE");

  const channels = CHANNELS[colourType];
  const stride = width * channels;
  const expected = height * (stride + 1);
  let raw;
  try {
    // **Bounded inflation.** These artifacts are third-party and may carry up to 8 MiB of compressed
    // data, which deflate can expand by three orders of magnitude — a small, legal `IHDR` in front of
    // a compression bomb would otherwise exhaust the process *after* every preflight budget had
    // passed, since none of them can see past the header. The declared scanline size is the only
    // honest ceiling, and anything over it is a header that lied about its dimensions either way.
    const compressed = concatenate(parts);
    const result = inflateZlib(compressed, { maxOutputLength: expected });
    raw = result.data;
    // **The `IDAT` run is exactly one zlib datastream, and it must be consumed whole.** A decoder
    // that stops at the end of the first stream and silently ignores whatever follows lets an
    // artifact append a second compressed stream — or any bytes at all — inside a permitted `IDAT`
    // and still decode here while a strict decoder refuses it. The same shape as the
    // bytes-after-`IEND` case, one level down: the allowlist stops applying wherever the reader
    // stops reading.
    if (result.bytesRead !== compressed.length) {
      throw new Error("decode-failed: bytes follow the IDAT zlib stream");
    }
  } catch (error) {
    if (error?.code === "output-too-large") throw new Error("declared-dimensions-mismatch");
    if (error?.message?.startsWith("decode-failed:")) throw error;
    throw new Error("decode-failed: inflate failed");
  }
  // Strict equality, not "at least": a header that lies about its dimensions is otherwise a way to
  // walk straight past the budget cap, and the contract names that `header-invalid` rather than a
  // decode failure. Raised as its own message so the caller can tell the two verdicts apart.
  if (raw.length !== expected) throw new Error("declared-dimensions-mismatch");

  const lines = new Uint8Array(height * stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const row = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    unfilter(filter, row, lines, y * stride, stride, channels);
  }

  // `tRNS` is how colour types 0, 2 and 3 carry transparency, and ignoring it is not a shortcut: a
  // browser applies it and a decoder that hardcodes alpha to 255 does not, so the same hash-valid
  // accepted candidate yields different pixels — and different candidate and resolution verdicts —
  // on the two sides of this contract. Greyscale and RGB name one transparent *sample value* in
  // 16-bit fields (the low byte is the one that matters at bit depth 8); palette carries one alpha
  // per entry, defaulting to opaque past its end. Colour types 4 and 6 carry alpha directly and may
  // not have a `tRNS` at all.
  const greyTransparent =
    colourType === COLOUR_GREY && transparency && transparency.length >= 2 ? transparency[1] : null;
  const rgbTransparent =
    colourType === COLOUR_RGB && transparency && transparency.length >= 6
      ? [transparency[1], transparency[3], transparency[5]]
      : null;

  // For RGBA the unfiltered scanlines *are* the output layout, so the third buffer is skipped — at
  // the budget's ceiling each of these is hundreds of megabytes, and holding one fewer of them is
  // free.
  const pixels = colourType === COLOUR_RGBA ? lines : new Uint8Array(width * height * 4);
  for (let i = 0; i < width * height; i++) {
    const s = i * channels;
    const d = i * 4;
    if (colourType === COLOUR_GREY) {
      pixels[d] = pixels[d + 1] = pixels[d + 2] = lines[s];
      pixels[d + 3] = greyTransparent !== null && lines[s] === greyTransparent ? 0 : 255;
    } else if (colourType === COLOUR_GREY_ALPHA) {
      pixels[d] = pixels[d + 1] = pixels[d + 2] = lines[s];
      pixels[d + 3] = lines[s + 1];
    } else if (colourType === COLOUR_RGB) {
      pixels[d] = lines[s];
      pixels[d + 1] = lines[s + 1];
      pixels[d + 2] = lines[s + 2];
      pixels[d + 3] =
        rgbTransparent &&
        lines[s] === rgbTransparent[0] &&
        lines[s + 1] === rgbTransparent[1] &&
        lines[s + 2] === rgbTransparent[2]
          ? 0
          : 255;
    } else if (colourType === COLOUR_PALETTE) {
      const p = lines[s] * 3;
      if (p + 2 >= palette.length) throw new Error("decode-failed: palette index out of range");
      pixels[d] = palette[p];
      pixels[d + 1] = palette[p + 1];
      pixels[d + 2] = palette[p + 2];
      pixels[d + 3] = transparency && lines[s] < transparency.length ? transparency[lines[s]] : 255;
    }
  }

  // **A fully transparent pixel is normalised to zero RGB.** Reading a canvas back commonly returns
  // `0,0,0,0` for one, because premultiplying by zero alpha destroys the colour and unpremultiplying
  // cannot recover it — so two encodings of *invisible* compare equal in a browser and unequal under
  // a decoder that preserves the hidden channels. The match metric charges all four channels (D5
  // answer 6), so that difference would land directly in the candidate gate. Normalising here rather
  // than in the metric keeps every consumer of a decoded raster on the same pixels.
  for (let d = 0; d < pixels.length; d += 4) {
    if (pixels[d + 3] === 0) pixels[d] = pixels[d + 1] = pixels[d + 2] = 0;
  }
  return { width, height, colourType, pixels };
}

function unfilter(filter, row, out, base, stride, channels) {
  for (let i = 0; i < stride; i++) {
    const left = i >= channels ? out[base + i - channels] : 0;
    const up = base >= stride ? out[base + i - stride] : 0;
    const upLeft = base >= stride && i >= channels ? out[base + i - stride - channels] : 0;
    let value = row[i];
    if (filter === 1) value += left;
    else if (filter === 2) value += up;
    else if (filter === 3) value += (left + up) >> 1;
    else if (filter === 4) value += paeth(left, up, upLeft);
    else if (filter !== 0) throw new Error("decode-failed: filter " + filter);
    out[base + i] = value & 0xff;
  }
}

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}

/** The lowercase hex digest the schema's three hash fields are spelled in. */
export function sha256Hex(bytes) {
  return digest(bytes);
}

/** Concatenate the `IDAT` run into the one datastream the inflater reads. */
function concatenate(parts) {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}
