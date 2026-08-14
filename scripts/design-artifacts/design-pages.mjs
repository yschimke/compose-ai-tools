/**
 * Project a repo's **design-page import** onto a published catalog — the producer for the preview
 * server's `/{system}/pages/` surface.
 *
 * A repo imports whole pages of its design file as SVG (m3-catalog's `scripts/import-figma-pages.mjs`
 * is the reference implementation) and commits them under `design/pages/`: one `pages.json` naming
 * the component nodes on each page, and one `<id>.svg` per page exported with `data-node-id` on
 * every element. The server inlines that SVG, finds a node by its id, hides the design's own drawing
 * of it, and puts the catalog's render in the hole — which is why the ids are load-bearing and the
 * export cannot be a raster.
 *
 * The catch is the same one `design-references.mjs` exists to solve: the id on a node is the repo's
 * own **discovery** preview id, and a published catalog keys everything on the route-safe **serve**
 * preview id (`chat-contact__ideal__default__dark__compact`). Handing the manifest to the server
 * unchanged would give it ids that render nothing. So this module re-keys each node, reusing that
 * module's indexes rather than restating the join:
 *
 * 1. **By discovery preview id** ([imagesByPreviewId]) — exact, and the id a design-map entry
 *    already carries to disambiguate light from dark. Handles both id namespaces (raw vs the
 *    sanitised in-bundle form), which is why it is tried first.
 * 2. **By `@Preview` function name** ([imagesByPreviewFunction], via the `#Member` of the code
 *    handle) — the fallback for a spec-led catalog whose manifest entry named no preview id.
 *
 * A node that resolves to neither keeps its `code` and its `link`: the mapping is still true and
 * the outline still names the file, it just can't be drawn with a render. Dropping it instead would
 * silently understate the page's coverage, which is the one number this surface exists to report.
 *
 * Pure and dependency-free (no I/O) so it unit-tests without an `npm ci`, like its siblings
 * `design-references.mjs` / `catalog-variants.mjs`. The I/O half — read the repo's import, copy the
 * SVGs into the bundle — lives in `emit-design-pages.mjs`.
 */

import {
  functionNameOf,
  imagesByPreviewFunction,
  imagesByPreviewId,
  matchesForPreviewId,
  servePreviewId,
} from "./design-references.mjs";

/** Directory (bundle-relative) the manifest and its cached SVGs are published under. */
export const PAGES_DIR = "pages";

/** The manifest file the server reads (`ServeDesignPageStore.INDEX_FILE`). */
export const PAGES_INDEX = "index.json";

/** The `DESIGN_PAGES_VERSION` this producer emits. */
export const PAGES_VERSION = 2;

/** `ServeDesignPageStore.SAFE_ID` — a page id is a URL path segment on `/{system}/pages/{id}`. */
const SAFE_ID = /^[A-Za-z0-9._-]{1,160}$/;

/**
 * `.svg` is reserved: the server serves a page's export off the same route as its view with that
 * suffix, so a page id'd `shape.svg` would be unreachable behind the export of the page `shape`.
 * The server refuses one too ([ServeDesignPageStore]); refusing it here as well means the delivery
 * branch never carries a page the consumer will silently drop.
 */
const RESERVED_ID_SUFFIX = /\.svg$/i;

/**
 * `.` and `..` match the id alphabet but are **path segments**, not names: a browser normalises
 * `/pages/..` to `/` before the request is even sent, so such a page could never be opened even
 * though its export published. Refused alongside the reserved suffix.
 */
const DOT_SEGMENT = /^\.{1,2}$/;

/** The contract's `confidence` values. Anything else is dropped rather than republished. */
const CONFIDENCE_VALUES = new Set(["high", "low"]);

const LINK_METHODS = new Set(["code-connect", "manifest", "convention", "unlinked"]);

/** A finite number greater than zero — every dimension the server draws with. */
function isPositive(value) {
  return typeof value === "number" && Number.isFinite(value) && value > 0;
}

/**
 * The published export path for a page: always `<id>.svg`, never the producer's own file name.
 *
 * The server re-paths these again when it stages a catalog, so this is belt-and-braces — but it
 * also means the bundle is self-describing: a reader of the delivery branch can tell which export
 * belongs to which page without parsing the manifest.
 */
export function pageImageName(pageId) {
  return `${pageId}.svg`;
}

/**
 * The serve preview id for one node, or null when the catalog publishes no sticker for it.
 *
 * `candidates` are the catalog images that matched; the first is taken deliberately rather than
 * merged. A specimen sheet shows a component in exactly one state, and the catalog may publish that
 * component in several (light and dark, three sizes) — any of them renders the right component, and
 * a stable choice keeps the published manifest diffable across regenerations. Light-mode stickers
 * sort first in a catalog's own image order, which is also the better default under a design sheet
 * exported in light mode.
 */
function resolveServePreviewId(node, { byPreviewId, byFunction }) {
  const declared = typeof node?.previewId === "string" ? node.previewId : "";
  if (declared !== "") {
    // Terminal: a declared id that resolves to nothing must NOT fall through to the function name.
    // [matchesForPreviewId] returns empty for a *sanitised bundle-id collision* — a family where an
    // apparent exact hit can belong to the colliding sibling — and that emptiness is a refusal, not
    // a miss. Falling back would then pick the first image of a `@Preview` function that may cover
    // several themes or states, overlaying a sticker the producer explicitly declined to name.
    const matches = matchesForPreviewId(byPreviewId, declared);
    return matches.length > 0 ? servePreviewId(matches[0].image?.path) : null;
  }
  const fn = functionNameOf(node?.code);
  if (fn) {
    const matches = byFunction.get(fn) ?? [];
    // Refuse an AMBIGUOUS fallback. `byFunction` is keyed by the bare member name, so two
    // components whose previews are both called `DefaultPreview` share a bucket — and taking the
    // first would put component A's render inside component B's outline, which is worse than
    // showing no render at all. Same posture as `matchesForPreviewId`'s collision guard: decline,
    // warn, and leave the outline with its mapping.
    const componentIds = new Set(matches.map((m) => m.componentId));
    if (matches.length > 0 && componentIds.size === 1) {
      return servePreviewId(matches[0].image?.path);
    }
  }
  return null;
}

/**
 * Whether a node is complete enough for the server to draw. Mirrors the server's own test.
 *
 * The node id is the *only* handle this contract carries — there is no recorded rectangle, because
 * the SVG is the geometry — so a node without one names nothing in the export and could never be
 * outlined, hidden or swapped.
 */
function isDrawableNode(node) {
  return typeof node?.nodeId === "string" && node.nodeId.trim() !== "";
}

/**
 * Plan the published `pages/index.json` for `manifest`.
 *
 * Returns `{ manifest, images, warnings }` — the bundle-shaped manifest, the `[{ pageId, from }]`
 * pairs the caller must copy (`from` is the producer's own export path, relative to its manifest),
 * and human-readable warnings for anything dropped or left unrenderable.
 */
export function planDesignPages({ manifest, spec, catalog }) {
  const warnings = [];
  if (!manifest || typeof manifest !== "object") {
    return { manifest: null, images: [], warnings };
  }
  const version = manifest.version;
  if (version !== PAGES_VERSION) {
    warnings.push(
      `design-pages manifest version ${String(version)} is not one this catalog can publish ` +
        `(supported: ${PAGES_VERSION})`,
    );
    return { manifest: null, images: [], warnings };
  }

  const byFunction = imagesByPreviewFunction(spec, catalog);
  const byPreviewId = imagesByPreviewId(catalog);

  const images = [];
  const seen = new Set();
  const pages = [];
  // `Array.isArray`, not `?? []`: a structurally malformed manifest — `"pages": {}` from a bad
  // edit — is syntactically valid JSON, so it survives the parse and would throw "object is not
  // iterable" here, out of the emitter and into the workflow's `set -e`. The whole point of this
  // lane is that it cannot cost a catalog its publish.
  if (!Array.isArray(manifest.pages)) {
    warnings.push("design-pages manifest declares no usable pages array");
    return { manifest: null, images: [], warnings };
  }
  for (const page of manifest.pages) {
    const id = typeof page?.id === "string" ? page.id : "";
    if (!SAFE_ID.test(id) || RESERVED_ID_SUFFIX.test(id) || DOT_SEGMENT.test(id)) {
      warnings.push(`page ${JSON.stringify(page?.id ?? null)} has no route-safe id; skipped`);
      continue;
    }
    if (seen.has(id)) {
      warnings.push(`page ${id} is declared twice; keeping the first`);
      continue;
    }
    // The frame is the export's own viewBox, and the server lays the stage out with its ratio. A
    // page without one would render as a zero-height box with the sheet squashed into nothing.
    if (!isPositive(page?.frame?.width) || !isPositive(page?.frame?.height)) {
      warnings.push(`page ${id} declares no usable frame size; skipped`);
      continue;
    }
    const format = typeof page?.image?.format === "string" ? page.image.format : "svg";
    if (format.toLowerCase() !== "svg") {
      // Refused rather than republished. The surface's whole capability is addressing nodes inside
      // the export; a raster is a picture, and a page the server can only stare at is worse than a
      // page it never advertises.
      warnings.push(`page ${id} exports as ${format}, not svg; skipped`);
      continue;
    }
    const from = typeof page?.image?.uri === "string" ? page.image.uri : "";
    if (from === "") {
      warnings.push(`page ${id} names no export; skipped`);
      continue;
    }
    seen.add(id);

    let unresolved = 0;
    const nodes = [];
    for (const node of Array.isArray(page.nodes) ? page.nodes : []) {
      if (!isDrawableNode(node)) continue;
      const link = LINK_METHODS.has(node?.link) ? node.link : "unlinked";
      const previewId =
        link === "unlinked" ? null : resolveServePreviewId(node, { byPreviewId, byFunction });
      if (link !== "unlinked" && previewId === null) unresolved += 1;
      nodes.push({
        nodeId: String(node.nodeId),
        name: String(node?.name ?? ""),
        // Range-checked, not just integer-checked. The consumer decodes `depth` as a Kotlin Int,
        // so republishing `2147483648` — which `Number.isInteger` happily accepts — fails the parse
        // for the WHOLE manifest and hides every page. Same failure shape as an unsupported
        // `confidence`, and depth is only a nesting hint, so an out-of-range one becomes 0.
        depth:
          Number.isInteger(node?.depth) && node.depth >= 0 && node.depth <= 2147483647
            ? node.depth
            : 0,
        // The node's type in the design file, republished so the consumer can tell a container from
        // the components inside it EXACTLY rather than by inference. `DesignPage.coverageGaps`
        // falls back to "a node followed by a deeper one has children" when no type is stated, and
        // that fallback is only as good as the walk: an import lists components only, so an unlisted
        // frame between two of them lets a shallower node be followed by a deeper one that is not
        // inside it — and the shallower one then drops out of the count as furniture, understating
        // the gaps. Stripping the field here meant every delivery branch took that fallback, however
        // carefully the importer had stated the type.
        //
        // Free text, like the consumer's own field, so a design tool growing a type does not fail
        // the parse — but only a non-empty string, since `""` is not a type and would read as one.
        ...(typeof node?.type === "string" && node.type.trim() !== ""
          ? { type: node.type.trim() }
          : {}),
        ...(node?.ref ? { ref: String(node.ref) } : {}),
        link,
        ...(node?.code ? { code: String(node.code) } : {}),
        ...(previewId ? { previewId } : {}),
        // Validated, not passed through. The consumer decodes this into a strict enum, so an
        // unrecognised value there is a parse failure for the WHOLE manifest — one bad string in
        // one node would hide every page the catalog publishes. Dropping the field costs only a
        // styling hint.
        ...(CONFIDENCE_VALUES.has(node?.confidence) ? { confidence: node.confidence } : {}),
        // A grouping whose contents are listed below it — a COMPONENT_SET, whose children are the
        // variants. Nothing implements a set (a reference names one of its variants), so the
        // consumer draws it as structure and leaves it out of the coverage count. Dropping it here
        // is not cosmetic: the set comes back as a component nobody implemented, and a page whose
        // every component is done reports missing work that no code could ever clear.
        //
        // Literal `true` only, in the same spirit as `confidence` above — this decodes into a
        // Kotlin Boolean, and a truthy string is a parse failure for the whole manifest.
        ...(node?.container === true ? { container: true } : {}),
      });
    }
    if (unresolved > 0) {
      warnings.push(
        `page ${id}: ${unresolved} linked node(s) map to no published sticker, so they show as ` +
          `outlines without a render`,
      );
    }

    images.push({ pageId: id, from });
    pages.push({
      id,
      name: String(page?.name ?? id),
      nodeId: String(page?.nodeId ?? ""),
      frame: { width: page.frame.width, height: page.frame.height },
      image: { uri: pageImageName(id), format: "svg" },
      nodes,
    });
  }

  if (pages.length === 0) return { manifest: null, images: [], warnings };
  return {
    manifest: {
      version: PAGES_VERSION,
      source: "figma",
      fileKey: String(manifest.fileKey ?? ""),
      pages,
    },
    images,
    warnings,
  };
}
