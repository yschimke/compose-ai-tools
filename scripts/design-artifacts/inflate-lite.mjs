/**
 * A dependency-free, host-free zlib **decompressor**, sized to exactly what a PNG decode needs.
 *
 * `node:zlib` did this until batch 05, and it did it correctly. What it cannot do is run in a
 * browser, and that turned out to matter more than the convenience: the acceptance engine
 * [§4](../../docs/design/COMPONENT_PARITY_WORKFLOW.md#two-engines-one-semantics) asks for in
 * `format-compare.js` needs the *same* decoder as the offline run, or the two engines disagree about
 * what a hash-valid artifact contains — and the browser's only other decode path is `<img>` onto a
 * canvas, which normalises every colour type to 8-bit RGBA and so cannot see the encoding rules the
 * contract spends a section on. `DecompressionStream` is asynchronous and would turn the whole
 * evaluation ladder inside out for the same answer.
 *
 * So the decoder is written out. It is RFC 1950 + RFC 1951 and nothing else — no dictionary support
 * (`FDICT` is refused), no streaming, no compression. `zlib-lite`-style shortcuts are avoided on
 * purpose: the symbol decode below is the canonical count/offset walk from `puff`, which is the
 * reference implementation of the format and is short enough to check by reading.
 *
 * **Two properties are load-bearing beyond "it decompresses".** Both were `inflateSync` options
 * before and are why this returns a record rather than a buffer:
 *
 * - **A bounded output.** These artifacts are third-party and deflate expands by three orders of
 *   magnitude, so a compression bomb behind a small legal `IHDR` would exhaust the process after
 *   every preflight budget had passed. Inflation stops the moment it would exceed the caller's
 *   ceiling.
 * - **An exact input length.** A PNG's `IDAT` run is one zlib datastream and must be consumed whole;
 *   a decoder that stops at the end of the first stream and ignores what follows lets an artifact
 *   append a second stream inside a permitted chunk. `bytesRead` is what the caller compares.
 *
 * `inflate-lite.test.mjs` checks it against `node:zlib` over the committed fixture corpus and over
 * randomised inputs at every block type, which is the only way to be sure of a format decoder.
 */

/** Extra bits and base lengths for the literal/length alphabet, symbols 257–285 (RFC 1951 §3.2.5). */
const LENGTH_BASE = [
  3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
];
const LENGTH_EXTRA = [
  0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
];

/** The same, for the distance alphabet's 30 symbols. */
const DISTANCE_BASE = [
  1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
  8193, 12289, 16385, 24577,
];
const DISTANCE_EXTRA = [
  0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
];

/** The order the dynamic-block header writes its code-length code lengths in. */
const CODE_LENGTH_ORDER = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];

class InflateError extends Error {
  constructor(code, message) {
    super(message ?? code);
    this.code = code;
  }
}

/**
 * A canonical Huffman decoding table: how many codes of each length, and the symbols in code order.
 *
 * This is `puff`'s representation rather than a lookup table, and deliberately: a table keyed by the
 * maximum code length is faster and is also where a hand-written decoder goes wrong, because the
 * fill loop has to reverse bit order and pad short codes. The walk below reads one bit at a time and
 * has no such step — the *only* thing that can be wrong with it is the format itself.
 */
function huffman(lengths) {
  const counts = new Int32Array(16);
  for (const length of lengths) counts[length]++;
  counts[0] = 0;
  const offsets = new Int32Array(16);
  for (let length = 1; length < 16; length++) offsets[length] = offsets[length - 1] + counts[length - 1];
  const symbols = new Int32Array(lengths.length);
  for (let symbol = 0; symbol < lengths.length; symbol++) {
    if (lengths[symbol] !== 0) symbols[offsets[lengths[symbol]]++] = symbol;
  }
  return { counts, symbols };
}

class BitReader {
  constructor(bytes, start) {
    this.bytes = bytes;
    this.position = start;
    this.bitBuffer = 0;
    this.bitCount = 0;
  }

  bits(need) {
    let value = this.bitBuffer;
    while (this.bitCount < need) {
      if (this.position >= this.bytes.length) throw new InflateError("inflate-failed", "out of input");
      value |= this.bytes[this.position++] << this.bitCount;
      this.bitCount += 8;
    }
    this.bitBuffer = value >>> need;
    this.bitCount -= need;
    return value & ((1 << need) - 1);
  }

  /** Discard the partial byte — what a stored block's length fields are aligned to. */
  align() {
    this.bitBuffer = 0;
    this.bitCount = 0;
  }

  /** How many whole bytes have been consumed, counting a partially-read one as consumed. */
  consumed() {
    return this.position - (this.bitCount >> 3);
  }

  decode(table) {
    let code = 0;
    let first = 0;
    let index = 0;
    for (let length = 1; length < 16; length++) {
      code |= this.bits(1);
      const count = table.counts[length];
      if (code - count < first) return table.symbols[index + (code - first)];
      index += count;
      first = (first + count) << 1;
      code <<= 1;
    }
    throw new InflateError("inflate-failed", "invalid Huffman code");
  }
}

/** The fixed literal/length and distance tables of RFC 1951 §3.2.6, built once. */
const FIXED = (() => {
  const literals = new Uint8Array(288);
  literals.fill(8, 0, 144);
  literals.fill(9, 144, 256);
  literals.fill(7, 256, 280);
  literals.fill(8, 280, 288);
  return { literal: huffman(literals), distance: huffman(new Uint8Array(30).fill(5)) };
})();

class Output {
  constructor(limit) {
    this.limit = limit;
    this.bytes = new Uint8Array(Math.min(limit, 1 << 16));
    this.length = 0;
  }

  reserve(extra) {
    if (this.length + extra > this.limit) throw new InflateError("output-too-large");
    if (this.length + extra <= this.bytes.length) return;
    let size = this.bytes.length || 1;
    while (size < this.length + extra) size *= 2;
    const grown = new Uint8Array(Math.min(size, this.limit));
    grown.set(this.bytes.subarray(0, this.length));
    this.bytes = grown;
  }

  push(byte) {
    this.reserve(1);
    this.bytes[this.length++] = byte;
  }

  copy(distance, length) {
    if (distance > this.length) throw new InflateError("inflate-failed", "distance before the window");
    this.reserve(length);
    // Byte at a time, because an overlapping copy — `distance < length` — is how deflate encodes a
    // run, and a bulk copy of the source range would read bytes this loop is still writing.
    for (let i = 0; i < length; i++) this.bytes[this.length + i] = this.bytes[this.length - distance + i];
    this.length += length;
  }

  finish() {
    return this.bytes.subarray(0, this.length);
  }
}

/**
 * Inflate a raw DEFLATE stream starting at `start`.
 *
 * @returns `{ data, bytesRead }` — `bytesRead` counts from the start of `bytes`, so a caller can tell
 *   a stream that ends where the input does from one with bytes after it.
 */
export function inflateRaw(bytes, { start = 0, maxOutputLength = Infinity } = {}) {
  const reader = new BitReader(bytes, start);
  const out = new Output(maxOutputLength);
  for (;;) {
    const last = reader.bits(1);
    const type = reader.bits(2);
    if (type === 0) {
      reader.align();
      if (reader.position + 4 > bytes.length) throw new InflateError("inflate-failed", "truncated stored block");
      const length = bytes[reader.position] | (bytes[reader.position + 1] << 8);
      const complement = bytes[reader.position + 2] | (bytes[reader.position + 3] << 8);
      if ((length ^ 0xffff) !== complement) throw new InflateError("inflate-failed", "stored length mismatch");
      reader.position += 4;
      if (reader.position + length > bytes.length) throw new InflateError("inflate-failed", "truncated stored block");
      out.reserve(length);
      out.bytes.set(bytes.subarray(reader.position, reader.position + length), out.length);
      out.length += length;
      reader.position += length;
    } else if (type === 1 || type === 2) {
      const tables = type === 1 ? FIXED : dynamicTables(reader);
      inflateBlock(reader, out, tables);
    } else {
      throw new InflateError("inflate-failed", "reserved block type");
    }
    if (last) break;
  }
  return { data: out.finish(), bytesRead: reader.consumed() };
}

function dynamicTables(reader) {
  const literalCount = reader.bits(5) + 257;
  const distanceCount = reader.bits(5) + 1;
  const codeCount = reader.bits(4) + 4;
  if (literalCount > 286 || distanceCount > 30) throw new InflateError("inflate-failed", "too many codes");
  const codeLengths = new Uint8Array(19);
  for (let i = 0; i < codeCount; i++) codeLengths[CODE_LENGTH_ORDER[i]] = reader.bits(3);
  const codeTable = huffman(codeLengths);

  const lengths = new Uint8Array(literalCount + distanceCount);
  let index = 0;
  while (index < lengths.length) {
    const symbol = reader.decode(codeTable);
    if (symbol < 16) {
      lengths[index++] = symbol;
      continue;
    }
    let repeat;
    let value = 0;
    if (symbol === 16) {
      if (index === 0) throw new InflateError("inflate-failed", "no length to repeat");
      value = lengths[index - 1];
      repeat = 3 + reader.bits(2);
    } else if (symbol === 17) {
      repeat = 3 + reader.bits(3);
    } else {
      repeat = 11 + reader.bits(7);
    }
    if (index + repeat > lengths.length) throw new InflateError("inflate-failed", "repeat past the code lengths");
    for (let i = 0; i < repeat; i++) lengths[index++] = value;
  }
  if (lengths[256] === 0) throw new InflateError("inflate-failed", "no end-of-block code");
  return {
    literal: huffman(lengths.subarray(0, literalCount)),
    distance: huffman(lengths.subarray(literalCount)),
  };
}

function inflateBlock(reader, out, tables) {
  for (;;) {
    const symbol = reader.decode(tables.literal);
    if (symbol < 256) {
      out.push(symbol);
      continue;
    }
    if (symbol === 256) return;
    const lengthIndex = symbol - 257;
    if (lengthIndex >= LENGTH_BASE.length) throw new InflateError("inflate-failed", "invalid length symbol");
    const length = LENGTH_BASE[lengthIndex] + reader.bits(LENGTH_EXTRA[lengthIndex]);
    const distanceSymbol = reader.decode(tables.distance);
    if (distanceSymbol >= DISTANCE_BASE.length) throw new InflateError("inflate-failed", "invalid distance symbol");
    out.copy(DISTANCE_BASE[distanceSymbol] + reader.bits(DISTANCE_EXTRA[distanceSymbol]), length);
  }
}

/**
 * Inflate an RFC 1950 zlib datastream — the wrapper a PNG's `IDAT` run carries.
 *
 * The header and the trailing Adler-32 are both checked. Neither is optional here: a PNG whose
 * checksum does not verify is a corrupt artifact, and this contract's whole point is that two
 * consumers reach the same verdict on the same bytes rather than one of them tolerating damage.
 */
export function inflateZlib(bytes, { maxOutputLength = Infinity } = {}) {
  if (bytes.length < 6) throw new InflateError("inflate-failed", "too short for a zlib stream");
  const cmf = bytes[0];
  const flg = bytes[1];
  if ((cmf & 0x0f) !== 8) throw new InflateError("inflate-failed", "not deflate");
  if ((cmf >> 4) > 7) throw new InflateError("inflate-failed", "window too large");
  if (((cmf << 8) + flg) % 31 !== 0) throw new InflateError("inflate-failed", "header check failed");
  if (flg & 0x20) throw new InflateError("inflate-failed", "preset dictionary");
  const { data, bytesRead } = inflateRaw(bytes, { start: 2, maxOutputLength });
  if (bytesRead + 4 > bytes.length) throw new InflateError("inflate-failed", "missing Adler-32");
  const stored =
    ((bytes[bytesRead] << 24) | (bytes[bytesRead + 1] << 16) | (bytes[bytesRead + 2] << 8) | bytes[bytesRead + 3]) >>> 0;
  if (stored !== adler32(data)) throw new InflateError("inflate-failed", "Adler-32 mismatch");
  return { data, bytesRead: bytesRead + 4 };
}

/** RFC 1950's checksum: two running sums mod 65521, the largest prime below 2¹⁶. */
export function adler32(bytes) {
  let a = 1;
  let b = 0;
  // Chunked so the sums cannot leave the exact-integer range before they are reduced: 5552 is the
  // largest run of 0xff bytes for which `b` stays under 2³¹.
  for (let start = 0; start < bytes.length; start += 5552) {
    const end = Math.min(start + 5552, bytes.length);
    for (let i = start; i < end; i++) {
      a += bytes[i];
      b += a;
    }
    a %= 65521;
    b %= 65521;
  }
  return ((b << 16) | a) >>> 0;
}
