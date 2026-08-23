/**
 * The PNG **writer** — the half of the old `png-lite.mjs` that needs a compressor.
 *
 * Split out in batch 05 so its sibling can be bundled into `format-compare.js`. Reading a PNG is now
 * host-free (see [`inflate-lite.mjs`](./inflate-lite.mjs) and
 * [`sha256-lite.mjs`](./sha256-lite.mjs)), because the browser engine and the offline one must decode
 * the same bytes the same way or two consumers disagree about what a hash-valid artifact contains.
 * *Writing* has no such requirement: only the fixture generator writes a PNG, it runs on Node, and
 * `deflateSync` is both better at its job than anything worth hand-writing here and — more to the
 * point — already the encoder every committed byte in `fixtures/known-differences/` was produced
 * with. Replacing it would rewrite the whole tree's digests for no gain.
 *
 * So the rule is one line: **read in `png-lite.mjs`, write here.** Anything that imports this file
 * cannot run in a browser, and nothing that needs to should.
 */

import { deflateSync } from "node:zlib";

import { COLOUR_RGBA, buildPng, chunk, ihdr } from "./png-lite.mjs";

/** How many bytes a pixel occupies per colour type — the writer's half of the same table. */
const CHANNELS = {
  0: 1,
  2: 3,
  3: 1,
  4: 2,
  6: 4,
};

/**
 * `IDAT` whose scanlines carry a **non-zero** filter type — one per row, cycling `1…4`.
 *
 * Every other artifact this generator writes uses filter `0`, so the whole committed tree could be
 * decoded by an engine that implements none of Sub, Up, Average or Paeth. Those four are ordinary
 * PNG that any encoder emits, and a decoder missing them refuses or mis-decodes a perfectly legal
 * accepted candidate, so the suite needs at least one artifact that exercises them.
 *
 * The filters are applied here rather than left to a library, for the same reason the rest of this
 * file exists: the fixture has to state which filter each row carries, not hope one was chosen.
 */
export function filteredIdat(rows, channels) {
  const stride = rows[0].length;
  const raw = new Uint8Array(rows.length * (stride + 1));
  const previous = new Uint8Array(stride);
  let offset = 0;
  rows.forEach((row, y) => {
    const filter = (y % 4) + 1;
    raw[offset++] = filter;
    for (let i = 0; i < stride; i++) {
      const left = i >= channels ? row[i - channels] : 0;
      const up = previous[i];
      const upLeft = i >= channels ? previous[i - channels] : 0;
      let delta;
      if (filter === 1) delta = row[i] - left;
      else if (filter === 2) delta = row[i] - up;
      else if (filter === 3) delta = row[i] - ((left + up) >> 1);
      else delta = row[i] - paethPredictor(left, up, upLeft);
      raw[offset + i] = delta & 0xff;
    }
    offset += stride;
    previous.set(row);
  });
  return chunk("IDAT", new Uint8Array(deflateSync(raw, { level: 9 })));
}

function paethPredictor(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}

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
