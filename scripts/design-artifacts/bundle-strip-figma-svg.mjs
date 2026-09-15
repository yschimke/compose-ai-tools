import { unzipSync, zipSync } from "fflate";

import { zipOffset } from "./zip-offset.mjs";

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
