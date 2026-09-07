/**
 * Re-key a published catalog's **preview-side design annotations** to the render each one describes.
 *
 * `@design-parity/catalog-export` walks ONE semantics tree per component — the tree the candidate
 * join kept after folding a function's renders together — and then writes that single layer under
 * every sticker id the component publishes:
 *
 * ```js
 * // Every ideal variant of a component shares its geometry, so the same layer
 * // is correct for each id it renders under (light/dark, locales).
 * ```
 *
 * That holds for a palette or a locale arm, which redraw one layout in other colours or words. It
 * does not hold for a *content* variant. `Button/Filled`'s `icon` cell draws an icon, a primary and
 * a secondary label where the default cell draws one centred label, so the component's layer names
 * one text node at the default's coordinates while the render under it has two somewhere else — and
 * the viewer's Typography layer duly drew the default's box over the variant's pixels
 * (compose-preview-server#555). Every one of the 17 components on `remote-m3` published byte-
 * identical layers across all of its previews, which is the fan-out rather than a coincidence.
 *
 * So each sticker's layer is rebuilt here from **its own** carried semantics tree, joined exactly
 * the way {@link catalogTagIndex} joins the tag index and the figma-svg emit joins vectors: through
 * the `previewId` `bridgeLivePreviewIds` stamps on each image. A render whose tree is in hand gets
 * a layer measured on itself; one whose tree is not gets **no layer at all**, for the reason the
 * tag index states in the same situation — an absent overlay degrades to "no redline", a wrong one
 * asserts a spec the component does not have, and a reader cannot tell the second from the truth.
 *
 * A component where NOTHING resolved is left exactly as the export package wrote it: this pass
 * corrects a fan-out it can see, and a catalog packed without `--with-semantics` has no better
 * answer to offer than the one already published.
 *
 * Pure and I/O-free — no `@design-parity/*`, no filesystem — like `tag-index.mjs` and
 * `catalog-priority.mjs`, so it unit-tests without an `npm ci`. The layer builder is injected
 * (`treeAnnotations` from the export package) and the driver does the writing.
 */

import { catalogPreviewId } from "./live-preview.mjs";
import { semanticsByIds } from "./tag-index.mjs";

/** Schema token of the published `annotations/index.json`. Mirrors the export package's. */
export const ANNOTATION_SCHEMA = "compose-preview-annotations/v1";

/**
 * The ids one image is published under: the **sticker id** a preview server routes on, plus the
 * fully-qualified `previewId` the export package emits as an alias for a consumer holding that one.
 * Both are rewritten together — leaving the alias behind would keep serving the fan-out under a key
 * that resolves to the very same picture.
 */
function keysFor(image) {
  const out = [catalogPreviewId(image.path)];
  if (typeof image.previewId === "string" && image.previewId !== "") {
    out.push(image.previewId);
  }
  return out.filter((key) => typeof key === "string" && key !== "");
}

/**
 * Rebuild `published.previews` per render.
 *
 * @param {object} published the manifest `writeCatalog` wrote (`{schema, previews, references}`)
 * @param {object} manifest the built `catalog.json`, AFTER `bridgeLivePreviewIds`
 * @param {Array<object>} bundles the render bundles whose `previews/<id>.semantics.json` sidecars
 *   carry the trees
 * @param {Map<string,string>|undefined} semanticsIdByPath `resolveSemanticsIds` — the unfiltered
 *   image-path → daemon-id resolution, covering images deliberately left without a live alias
 * @param {(tree: object) => Array<object>} annotate builds one layer from one tree
 * @returns {{manifest: object, measured: number, dropped: number, gaps: number, unresolved: number}}
 *   `measured` renders given their own layer, `dropped` renders whose fanned-out layer was removed,
 *   `gaps` renders with an id but no carried tree, `unresolved` components left as published.
 */
export function perRenderAnnotations(
  published,
  manifest,
  bundles,
  semanticsIdByPath,
  annotate,
) {
  const treesById = semanticsByIds(bundles);
  const previews = { ...(published?.previews ?? {}) };
  let measured = 0;
  let dropped = 0;
  let gaps = 0;
  let unresolved = 0;
  for (const component of manifest?.components ?? []) {
    const renders = (component?.images ?? [])
      .filter((image) => typeof image?.path === "string")
      .map((image) => {
        // The live alias first (it alone reconstructs `@OverrideVariant` ids), then the unfiltered
        // resolution — the same order and the same reason as the tag index's join.
        const id = image.previewId ?? semanticsIdByPath?.get(image.path);
        return { image, tree: id ? treesById.get(id) : undefined };
      });
    // Nothing to say about this component that the export package has not already said. Rewriting
    // it from no trees at all would only delete the one layer a semantics-less catalog publishes.
    if (!renders.some((render) => render.tree)) {
      unresolved += 1;
      continue;
    }
    for (const { image, tree } of renders) {
      const keys = keysFor(image);
      if (keys.length === 0) continue;
      const layer = tree ? (annotate(tree) ?? []) : [];
      if (layer.length === 0) {
        // A render with no tree of its own, or one whose tree annotates to nothing. Either way the
        // layer standing under these keys was measured on a SIBLING, so it goes.
        const had = keys.filter((key) => key in previews);
        for (const key of had) delete previews[key];
        if (had.length > 0) dropped += 1;
        if (!tree) gaps += 1;
        continue;
      }
      for (const key of keys) previews[key] = layer;
      measured += 1;
    }
  }
  return {
    manifest: {
      ...published,
      schema: published?.schema ?? ANNOTATION_SCHEMA,
      previews,
      references: published?.references ?? {},
    },
    measured,
    dropped,
    gaps,
    unresolved,
  };
}
