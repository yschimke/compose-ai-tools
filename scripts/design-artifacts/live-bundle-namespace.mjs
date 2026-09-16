import { unzipSync, zipSync } from "fflate";

import { modulePreviewId } from "./multi-module-catalog.mjs";
import { zipOffset } from "./zip-offset.mjs";

function parseJson(bytes) {
  return JSON.parse(new TextDecoder().decode(bytes));
}

function encodeJson(value) {
  return new TextEncoder().encode(JSON.stringify(value));
}

function replacementPattern(replacements) {
  const escaped = replacements.map(([id]) => id.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  return new RegExp(escaped.join("|"), "g");
}

function rewriteString(value, replacements, pattern) {
  const byId = new Map(replacements);
  return value.replace(pattern, (id) => byId.get(id));
}

function replaceIds(value, replacements, pattern) {
  if (typeof value === "string") {
    return rewriteString(value, replacements, pattern);
  }
  if (Array.isArray(value)) return value.map((item) => replaceIds(item, replacements, pattern));
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [
        rewriteString(key, replacements, pattern),
        replaceIds(item, replacements, pattern),
      ]),
    );
  }
  return value;
}

/**
 * Namespace every preview identity in one executable module bundle.
 *
 * The module prefix is applied to the manifest, preview records, JSON sidecars and keyed entry
 * paths. App bytecode and classpath entries are left untouched. The operation is idempotent so the
 * catalog loader may apply its in-memory isolation pass to an already-namespaced live bundle.
 */
export function namespaceLiveBundle(bytes, module) {
  const offset = zipOffset(bytes);
  const prefix = bytes.slice(0, offset);
  const entries = unzipSync(bytes.slice(offset));
  const bundle = parseJson(entries["bundle.json"]);
  const previewsPayload = parseJson(entries["previews.json"]);
  const previews = Array.isArray(previewsPayload) ? previewsPayload : previewsPayload.previews ?? [];
  const ids = new Set([
    ...(bundle.previewIds ?? []),
    ...(bundle.rawPreviewIds ?? []),
    ...previews.map((preview) => preview.id),
  ].filter((id) => typeof id === "string" && id.length > 0));
  const replacements = [...ids]
    .map((id) => [id, modulePreviewId(module, id)])
    .filter(([oldId, newId]) => oldId !== newId)
    .sort(([a], [b]) => b.length - a.length);
  if (replacements.length === 0) return bytes;
  const pattern = replacementPattern(replacements);

  const rewrittenEntries = {};
  for (const [path, content] of Object.entries(entries)) {
    const rewrittenPath = rewriteString(path, replacements, pattern);
    let rewrittenContent = content;
    if (path.endsWith(".json")) {
      try {
        rewrittenContent = encodeJson(replaceIds(parseJson(content), replacements, pattern));
      } catch {
        // An opaque JSON-named payload is not part of the identity contract; retain its bytes.
      }
    }
    rewrittenEntries[rewrittenPath] = rewrittenContent;
  }
  const zip = zipSync(rewrittenEntries, { level: 6 });
  const result = new Uint8Array(prefix.length + zip.length);
  result.set(prefix);
  result.set(zip, prefix.length);
  return result;
}
