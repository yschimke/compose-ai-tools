// Where the ZIP payload starts inside a preview bundle.
//
// A bundle may be a plain zip or a polyglot PNG+ZIP — the PNG is what a reader shows, the zip
// carries the previews and their sidecars. Every reader of those sidecars needs this, and it had
// already been copied verbatim into two modules before a third reader wanted it. It lives here,
// once, and deliberately depends on nothing: `live-bundle-namespace.mjs` also imports `fflate`, so
// a reader that only needs an offset would otherwise pull a zip library in behind it.

const ZIP_LOCAL_HEADER = [0x50, 0x4b, 0x03, 0x04];
const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

function matches(bytes, offset, expected) {
  return expected.every((byte, index) => bytes[offset + index] === byte);
}

/** The offset just past a leading PNG's `IEND`, or null when [bytes] does not start with a PNG. */
function pngEndOffset(bytes) {
  if (bytes.length < PNG_SIGNATURE.length || !matches(bytes, 0, PNG_SIGNATURE)) return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let offset = PNG_SIGNATURE.length;
  while (offset + 12 <= bytes.length) {
    const length = view.getUint32(offset);
    const chunkEnd = offset + 12 + length;
    if (chunkEnd > bytes.length) return null;
    const type = new TextDecoder().decode(bytes.subarray(offset + 4, offset + 8));
    offset = chunkEnd;
    if (type === "IEND") return offset;
  }
  return null;
}

/**
 * The byte offset of the ZIP payload in [bytes]. Prefers the offset immediately after a leading
 * PNG, and otherwise scans for the first local-file header — a PNG's own bytes can contain that
 * signature by chance, so the polyglot case is resolved structurally rather than by the scan.
 *
 * Throws when there is no ZIP payload at all.
 */
export function zipOffset(bytes) {
  const pngEnd = pngEndOffset(bytes);
  if (pngEnd != null && matches(bytes, pngEnd, ZIP_LOCAL_HEADER)) return pngEnd;
  for (let i = 0; i <= bytes.length - ZIP_LOCAL_HEADER.length; i++) {
    if (matches(bytes, i, ZIP_LOCAL_HEADER)) return i;
  }
  throw new Error("bundle has no ZIP payload");
}
