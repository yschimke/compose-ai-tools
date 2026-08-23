/**
 * A dependency-free PNG reader and writer, sized to exactly what the known-difference contract needs.
 *
 * `pngjs` is already a driver dependency and is *not* what this is for. Two reasons, both from
 * [`COMPONENT_PARITY_WORKFLOW.md` §4](../../docs/design/COMPONENT_PARITY_WORKFLOW.md#the-normative-contract):
 *
 * 1. **The contract's preflight is a header walk, not a decode.** It reads `IHDR`, takes
 *    `width × height` from it, checks the two bytes after them, and walks chunk *headers* to the
 *    first `IDAT` looking for `acTL` — never a chunk's data, never an allocation sized by the file.
 *    A library decode allocates the oversized raster to measure it, which defeats the budget at the
 *    moment it is supposed to fire. {@link preflightPng} is that walk and nothing more.
 * 2. **The fixtures need files a well-behaved encoder refuses to write** — an APNG, a palette mask
 *    with strictly binary samples, a header that lies about its dimensions, a truncated file. Those
 *    are the cases a sample-only or decode-only check accepts, so the suite is worthless without
 *    them, and {@link buildPng} exists to author them deliberately.
 *
 * Scope is deliberately narrow: bit depth 8, no interlacing, single `IDAT`. Anything else is a
 * decode failure rather than a feature, which is also the verdict the contract wants for it.
 */

import { deflateSync, inflateSync } from "node:zlib";
import { createHash } from "node:crypto";

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

/** An `IHDR` chunk, spelled out so a fixture can lie in any one field. */
export function ihdr({ width, height, bitDepth = 8, colourType = COLOUR_RGBA, interlace = 0 }) {
  const data = new Uint8Array(13);
  const view = new DataView(data.buffer);
  view.setUint32(0, width);
  view.setUint32(4, height);
  data[8] = bitDepth;
  data[9] = colourType;
  data[10] = 0;
  data[11] = 0;
  data[12] = interlace;
  return chunk("IHDR", data);
}

/** Scanlines with filter byte 0, deflated — the only `IDAT` shape this module writes. */
export function idat(rows) {
  const raw = new Uint8Array(rows.reduce((sum, row) => sum + row.length + 1, 0));
  let offset = 0;
  for (const row of rows) {
    raw[offset++] = 0;
    raw.set(row, offset);
    offset += row.length;
  }
  return chunk("IDAT", new Uint8Array(deflateSync(raw, { level: 9 })));
}

/**
 * Encode an image whose samples are already laid out per its colour type.
 *
 * `samples` is row-major with `CHANNELS[colourType]` bytes per pixel — RGBA for {@link COLOUR_RGBA},
 * one grey byte per pixel for {@link COLOUR_GREY}.
 */
export function encodePng({ width, height, colourType = COLOUR_RGBA, samples, extraChunks = [] }) {
  const stride = width * CHANNELS[colourType];
  const rows = [];
  for (let y = 0; y < height; y++) rows.push(samples.subarray(y * stride, (y + 1) * stride));
  return buildPng([
    ihdr({ width, height, colourType }),
    ...extraChunks,
    idat(rows),
    chunk("IEND"),
  ]);
}

/**
 * The contract's bounded header preflight: `IHDR` plus a walk of chunk *headers* to the first
 * `IDAT`.
 *
 * Returns `{ width, height, bitDepth, colourType, interlace, animated, byteLength }`, or
 * `{ error: "header-invalid" }` for anything it cannot read — a wrong signature, a file too short to
 * hold an `IHDR`, a chunk length that runs past the end, a missing `IDAT`. Never reads chunk data,
 * never allocates anything sized by the file, so an 8192-cap breach costs the same handful of bytes
 * as a legal header.
 */
export function preflightPng(bytes) {
  const fail = { error: "header-invalid" };
  if (!bytes || bytes.length < SIGNATURE.length + 12 + 13) return fail;
  for (let i = 0; i < SIGNATURE.length; i++) if (bytes[i] !== SIGNATURE[i]) return fail;

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (view.getUint32(8) !== 13 || readType(bytes, 12) !== "IHDR") return fail;
  const width = view.getUint32(16);
  const height = view.getUint32(20);
  if (width === 0 || height === 0) return fail;

  let animated = false;
  let offset = 8;
  let sawIdat = false;
  // Chunk lengths are unsigned 32-bit, so a hostile file can name a length that overflows the
  // buffer. Every step is bounds-checked against `bytes.length` rather than trusted.
  while (offset + 8 <= bytes.length) {
    const length = view.getUint32(offset);
    const type = readType(bytes, offset + 4);
    if (length > bytes.length - offset - 12) return fail;
    if (type === "acTL") animated = true;
    if (type === "IDAT") {
      sawIdat = true;
      break;
    }
    offset += 12 + length;
  }
  if (!sawIdat) return fail;

  return {
    width,
    height,
    bitDepth: bytes[24],
    colourType: bytes[25],
    interlace: bytes[28],
    animated,
    byteLength: bytes.length,
  };
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
  if (!(colourType in CHANNELS)) throw new Error("decode-failed: colour type " + colourType);

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const parts = [];
  let palette = null;
  let offset = 8;
  while (offset + 8 <= bytes.length) {
    const length = view.getUint32(offset);
    const type = readType(bytes, offset + 4);
    if (length > bytes.length - offset - 12) throw new Error("decode-failed: chunk overruns file");
    const data = bytes.subarray(offset + 8, offset + 8 + length);
    if (type === "IDAT") parts.push(data);
    if (type === "PLTE") palette = data;
    if (type === "IEND") break;
    offset += 12 + length;
  }
  if (parts.length === 0) throw new Error("decode-failed: no IDAT");
  if (colourType === COLOUR_PALETTE && !palette) throw new Error("decode-failed: no PLTE");

  const raw = inflateSync(Buffer.concat(parts.map((p) => Buffer.from(p))));
  const channels = CHANNELS[colourType];
  const stride = width * channels;
  // Strict equality, not "at least": a header that lies about its dimensions is otherwise a way to
  // walk straight past the budget cap, and the contract names that `header-invalid` rather than a
  // decode failure. Raised as its own message so the caller can tell the two verdicts apart.
  if (raw.length !== height * (stride + 1)) throw new Error("declared-dimensions-mismatch");

  const lines = new Uint8Array(height * stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const row = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    unfilter(filter, row, lines, y * stride, stride, channels);
  }

  const pixels = new Uint8Array(width * height * 4);
  for (let i = 0; i < width * height; i++) {
    const s = i * channels;
    const d = i * 4;
    if (colourType === COLOUR_GREY) {
      pixels[d] = pixels[d + 1] = pixels[d + 2] = lines[s];
      pixels[d + 3] = 255;
    } else if (colourType === COLOUR_GREY_ALPHA) {
      pixels[d] = pixels[d + 1] = pixels[d + 2] = lines[s];
      pixels[d + 3] = lines[s + 1];
    } else if (colourType === COLOUR_RGB) {
      pixels[d] = lines[s];
      pixels[d + 1] = lines[s + 1];
      pixels[d + 2] = lines[s + 2];
      pixels[d + 3] = 255;
    } else if (colourType === COLOUR_PALETTE) {
      const p = lines[s] * 3;
      if (p + 2 >= palette.length) throw new Error("decode-failed: palette index out of range");
      pixels[d] = palette[p];
      pixels[d + 1] = palette[p + 1];
      pixels[d + 2] = palette[p + 2];
      pixels[d + 3] = 255;
    } else {
      pixels.set(lines.subarray(s, s + 4), d);
    }
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
  return createHash("sha256").update(bytes).digest("hex");
}
