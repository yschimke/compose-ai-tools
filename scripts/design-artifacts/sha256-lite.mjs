/**
 * SHA-256, in one function, because the acceptance engine needs it **synchronously and in a
 * browser**.
 *
 * `node:crypto` did this until batch 05 and does it faster. What it cannot do is run in
 * `format-compare.js`, and the browser's own `crypto.subtle.digest` is asynchronous — which would
 * turn the evaluation ladder inside out for a digest of at most 8 MiB, the artifact ceiling the
 * contract already enforces. The three hash fields
 * ([§4](../../docs/design/COMPONENT_PARITY_WORKFLOW.md#the-artifact)) are checked *before* an
 * artifact's bytes are used (I7), so the check sits in the middle of a synchronous ladder and every
 * alternative to writing this out moves that ladder rather than the digest.
 *
 * FIPS 180-4 as written, no streaming interface and no incremental update: every caller here holds
 * the whole artifact already, and an `update`/`digest` pair is API surface with a second state
 * machine to get wrong. `sha256-lite.test.mjs` checks it against `node:crypto` over the committed
 * artifact corpus and over randomised lengths around every block and padding boundary.
 */

/** The first 32 bits of the fractional parts of the cube roots of the first 64 primes. */
const K = Uint32Array.from([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98,
  0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
  0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8,
  0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
  0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819,
  0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
  0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
  0xc67178f2,
]);

/** The first 32 bits of the fractional parts of the square roots of the first eight primes. */
const INITIAL = Uint32Array.from([
  0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
]);

function rotr(value, bits) {
  return (value >>> bits) | (value << (32 - bits));
}

/** The lowercase hex digest the schema's three hash fields are spelled in. */
export function sha256Hex(bytes) {
  const state = Uint32Array.from(INITIAL);
  const schedule = new Uint32Array(64);

  // The padded message: the bytes, a `0x80`, zeroes to 56 mod 64, then the **bit** length as a
  // 64-bit big-endian integer. Written into one buffer rather than streamed, because the input is
  // already whole and bounded by the artifact cap.
  const bitLength = bytes.length * 8;
  const padded = new Uint8Array((Math.floor((bytes.length + 8) / 64) + 1) * 64);
  padded.set(bytes);
  padded[bytes.length] = 0x80;
  // A length past 2⁵³ bits cannot be written exactly from a double, and nothing here is remotely
  // near it — the artifact cap is 8 MiB — but the high word is written from the number rather than
  // assumed zero so the code says what the algorithm says.
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, Math.floor(bitLength / 0x100000000));
  view.setUint32(padded.length - 4, bitLength >>> 0);

  for (let block = 0; block < padded.length; block += 64) {
    for (let i = 0; i < 16; i++) schedule[i] = view.getUint32(block + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(schedule[i - 15], 7) ^ rotr(schedule[i - 15], 18) ^ (schedule[i - 15] >>> 3);
      const s1 = rotr(schedule[i - 2], 17) ^ rotr(schedule[i - 2], 19) ^ (schedule[i - 2] >>> 10);
      schedule[i] = (schedule[i - 16] + s0 + schedule[i - 7] + s1) | 0;
    }

    let [a, b, c, d, e, f, g, h] = state;
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const temp1 = (h + S1 + ch + K[i] + schedule[i]) | 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = (S0 + maj) | 0;
      h = g;
      g = f;
      f = e;
      e = (d + temp1) | 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) | 0;
    }
    state[0] = (state[0] + a) | 0;
    state[1] = (state[1] + b) | 0;
    state[2] = (state[2] + c) | 0;
    state[3] = (state[3] + d) | 0;
    state[4] = (state[4] + e) | 0;
    state[5] = (state[5] + f) | 0;
    state[6] = (state[6] + g) | 0;
    state[7] = (state[7] + h) | 0;
  }

  let hex = "";
  for (const word of state) hex += (word >>> 0).toString(16).padStart(8, "0");
  return hex;
}
