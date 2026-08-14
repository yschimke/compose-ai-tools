/**
 * Project a `compose/semantics` tree into a **tag index** — `testTag → {count, bounds, space}`.
 *
 * The JavaScript twin of the serve host's `ServeSemanticsTags` (Kotlin). Both exist because the
 * index has two producers and the same consumer contract: the serve host projects it live from a
 * daemon render, and a *published catalog* has no daemon at render time, so its index has to be
 * computed here, at catalog-generation time, and committed beside the stickers.
 *
 * The two implementations must not drift, so every rule below is stated in both and pinned by the
 * shared fixture in `fixtures/tag-index/`. Where they disagree, an element gate resolves a tag
 * differently on the server than in an offline parity run — the precise cross-engine divergence the
 * component-parity contract exists to prevent.
 *
 * ## The rules, and why each is not negotiable
 *
 * - **`count` counts every node carrying the tag**, including nodes whose bounds are unusable. A tag
 *   is only a usable identity while exactly one node carries it, and Compose does not enforce that;
 *   dropping a zero-area duplicate would report `count: 1` for a genuinely ambiguous tag and let a
 *   consumer resolve it as unique. `count` is the whole reason the index exists rather than a plain
 *   `tag → bounds` map.
 * - **`bounds` is the FIRST usable box in depth-first order**, absent when no node carrying the tag
 *   has one. First rather than last because depth-first is the order both engines walk, so "first"
 *   is reproducible where "last" depends on where the duplicate happened to land.
 * - **The key is the tag verbatim.** Blank-or-absent decides omission and nothing else. Compose
 *   matches a `testTag` as the exact string, so trimming would be a second identity rule: `"item"`
 *   and `" item "` would collapse into one entry reporting `count: 2` (false ambiguity) while an
 *   acceptance recording `" item "` found no key at all (false disappearance).
 * - **`space` is on the wire.** Bounds are `boundsInRoot` — absolute-to-root render pixels, the same
 *   space the published sticker is in. The design doc currently says the index publishes bounds
 *   already transformed into an acceptance's canonical plane; neither producer can do that (the
 *   plane is resolved per comparison, from a reference raster and an acceptance record, and neither
 *   producer has one). Until that is settled, each entry names its own space so no consumer can
 *   silently treat these as canonical.
 *
 * Pure and I/O-free — no `@design-parity/*`, no filesystem — so it unit-tests without an `npm ci`,
 * like its siblings `catalog-image-path.mjs` / `catalog-priority.mjs`. The driver does the writing.
 */

import { catalogPreviewId } from "./live-preview.mjs";

/** The coordinate space {@link tagIndex} reports bounds in. Mirrors `ServeSemanticsTags`. */
export const RENDER_PIXELS = "render-pixels";

/** Schema token of the published `tags/index.json`. */
export const TAG_INDEX_SCHEMA = "compose-preview-tags/v1";

/**
 * Parse a `"left,top,right,bottom"` wire box into `{x, y, width, height}`, or null when it is
 * malformed or has no area. Mirrors `SlotBounds.parse` + the `hasArea` guard on the Kotlin side —
 * a zero-area box is *not* usable geometry, but the node carrying it still counts.
 */
function parseBounds(wire) {
  const parts = String(wire ?? "").split(",");
  if (parts.length !== 4) return null;
  const n = parts.map((p) => {
    const trimmed = p.trim();
    return /^-?\d+$/.test(trimmed) ? Number.parseInt(trimmed, 10) : Number.NaN;
  });
  if (n.some(Number.isNaN)) return null;
  const [left, top, right, bottom] = n;
  if (right <= left || bottom <= top) return null;
  return { x: left, y: top, width: right - left, height: bottom - top };
}

/**
 * The tag index for one `ComposeSemanticsPayload` (`{ root }`), in depth-first encounter order.
 *
 * Returns a plain object built with a null prototype: `__proto__` is a perfectly good `testTag` and
 * a catastrophic object key, and a catalog is third-party data. The Kotlin side keys a `Map` for
 * the same reason.
 */
export function tagIndex(payload) {
  const out = Object.create(null);
  const walk = (node) => {
    if (!node || typeof node !== "object") return;
    const raw = node.testTag;
    if (typeof raw === "string" && raw.trim() !== "") {
      const box = parseBounds(node.boundsInRoot);
      const existing = out[raw];
      if (existing === undefined) {
        out[raw] = { count: 1, ...(box ? { bounds: box } : {}), space: RENDER_PIXELS };
      } else {
        existing.count += 1;
        if (!existing.bounds && box) existing.bounds = box;
      }
    }
    const children = Array.isArray(node.children) ? node.children : [];
    for (const child of children) walk(child);
  };
  walk(payload?.root);
  return out;
}

/**
 * `daemon preview id → ComposeSemanticsPayload` from one bundle's carried sidecars.
 *
 * The sidecar is `previews/<id>.semantics.json`, written by `bundle pack --with-semantics`. The
 * by-id counterpart of `figmaSvgById`, and read the same way: off `bundle.entries`, keyed by the
 * daemon preview id, so it joins to a catalog image through the `previewId` `bridgeLivePreviewIds`
 * stamps on it. A preview with no carried tree is simply absent.
 */
export function semanticsById(bundle) {
  const out = new Map();
  for (const preview of bundle?.previews ?? []) {
    const bytes = bundle.entries?.[`previews/${preview.id}.semantics.json`];
    if (!bytes) continue;
    try {
      const payload = JSON.parse(new TextDecoder().decode(bytes));
      if (payload?.root) out.set(preview.id, payload);
    } catch {
      // A malformed sidecar costs that preview its index, not the whole catalog — the same
      // fail-soft posture every other carried artifact gets.
    }
  }
  return out;
}

/** Fold {@link semanticsById} across several bundles, later bundles winning. Mirrors `figmaSvgByIds`. */
export function semanticsByIds(bundles) {
  const out = new Map();
  for (const bundle of bundles ?? []) {
    if (!bundle) continue;
    for (const [id, payload] of semanticsById(bundle)) out.set(id, payload);
  }
  return out;
}

/**
 * The published tag index for a built catalog: `served preview id → { tag → {count, bounds, space} }`.
 *
 * Driven from `manifest.components[].images[]` rather than from a naming scheme of its own, exactly
 * like the per-variant figma-svg emit, because each image already carries both halves of the join:
 * `previewId` (stamped by `bridgeLivePreviewIds`) keys the bundle's semantics sidecar, and `path`
 * yields the served route id through {@link catalogPreviewId}'s rule. Re-deriving either would be a
 * second naming scheme to keep in step, and a mis-keyed index fails *silently* — every gate simply
 * never resolves.
 *
 * **Only exact pairs are published.** An image `bridgeLivePreviewIds` deliberately left unbridged
 * (a state with no desktop source, or a function the Android-only supplement overrode) gets no
 * entry, and neither does one whose bundle carried no tree. That matters more here than for a
 * vector: bounds are per-variant, so falling back to a sibling variant's tree would publish boxes
 * that describe different pixels, and an element gate would report movement that never happened. An
 * absent entry degrades to "no element gate"; a wrong one produces a wrong verdict.
 *
 * Returns the index plus the counts the driver reports, so a whole bundle silently missing its
 * semantics shows up as a number rather than as an empty file nobody looks at.
 */
export function catalogTagIndex(manifest, bundles) {
  const treesById = semanticsByIds(bundles);
  const previews = Object.create(null);
  let indexed = 0;
  let gaps = 0;
  for (const component of manifest?.components ?? []) {
    for (const image of component?.images ?? []) {
      if (!image?.previewId || typeof image.path !== "string") continue;
      const tree = treesById.get(image.previewId);
      if (!tree) {
        gaps += 1;
        continue;
      }
      const tags = tagIndex(tree);
      if (Object.keys(tags).length === 0) continue;
      previews[catalogPreviewId(image.path)] = tags;
      indexed += 1;
    }
  }
  return { schema: TAG_INDEX_SCHEMA, previews, indexed, gaps };
}
