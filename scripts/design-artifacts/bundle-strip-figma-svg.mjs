import { unzipSync, zipSync } from "fflate";

const ZIP_LOCAL_HEADER = [0x50, 0x4b, 0x03, 0x04];
const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

function matches(bytes, offset, expected) {
  return expected.every((byte, index) => bytes[offset + index] === byte);
}

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

function zipOffset(bytes) {
  const pngEnd = pngEndOffset(bytes);
  if (pngEnd != null && matches(bytes, pngEnd, ZIP_LOCAL_HEADER)) return pngEnd;
  for (let i = 0; i <= bytes.length - ZIP_LOCAL_HEADER.length; i++) {
    if (matches(bytes, i, ZIP_LOCAL_HEADER)) return i;
  }
  throw new Error("bundle has no ZIP payload");
}

export function isFigmaSvgSidecar(path) {
  return (
    path.startsWith("previews/") &&
    (path.endsWith(".figma.svg") || path.includes(".figma-raster/"))
  );
}

/**
 * Remove baked editable-vector sidecars from an executable PNG+ZIP bundle. The daemon does not
 * consume these files when rendering: it regenerates `compose/figma-svg` from the live scene, so
 * retaining thousands of them only duplicates the request-time data product.
 */
export function stripFigmaSvgSidecars(bytes) {
  const offset = zipOffset(bytes);
  const prefix = bytes.slice(0, offset);
  const entries = unzipSync(bytes.slice(offset));
  const kept = {};
  let removedEntries = 0;
  let removedBytes = 0;
  for (const [path, content] of Object.entries(entries)) {
    if (isFigmaSvgSidecar(path)) {
      removedEntries += 1;
      removedBytes += content.length;
    } else {
      kept[path] = content;
    }
  }
  if (removedEntries === 0) return { bytes, removedEntries, removedBytes };
  const zip = zipSync(kept, { level: 6 });
  const result = new Uint8Array(prefix.length + zip.length);
  result.set(prefix);
  result.set(zip, prefix.length);
  return { bytes: result, removedEntries, removedBytes };
}
