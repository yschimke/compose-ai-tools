/**
 * Project a repo's **page-backdrop manifest** onto a published catalog — the producer for the
 * preview server's whole-screen `/{system}/pages/` surface.
 *
 * design-parity's page importer writes `design/pages/pages.json`: one key screen from the design
 * file, plus the rectangle of every component instance on it, each linked back to a code component
 * through the repo's `design-map.json`. That file is already a stated wire contract — its schema
 * names "a preview server" as a consumer, and carries `previewId` on every placement *specifically*
 * so a server can render the component without re-deriving the mapping.
 *
 * The catch is the same one `design-references.mjs` exists to solve: the id on a placement is the
 * repo's own **discovery** preview id, and a published catalog keys everything on the route-safe
 * **serve** preview id (`chat-contact__ideal__default__dark__compact`). Handing the manifest to the
 * server unchanged would give it ids that render nothing. So this module re-keys each placement,
 * reusing that module's indexes rather than restating the join:
 *
 * 1. **By discovery preview id** ([imagesByPreviewId]) — exact, and the id a design-map entry
 *    already carries to disambiguate light from dark. Handles both id namespaces (raw vs the
 *    sanitised in-bundle form), which is why it is tried first.
 * 2. **By `@Preview` function name** ([imagesByPreviewFunction], via the `#Member` of the code
 *    handle) — the fallback for a spec-led catalog whose manifest entry named no preview id.
 *
 * A placement that resolves to neither keeps its `code` and its `link`: the mapping is still true
 * and the hotspot still names the file, it just can't be drawn with a render. Dropping it instead
 * would silently understate the screen's coverage, which is the one number this surface exists to
 * report.
 *
 * Pure and dependency-free (no `@design-parity/*`, no I/O) so it unit-tests without an `npm ci`,
 * like its siblings `design-references.mjs` / `catalog-variants.mjs`. The I/O half — read the
 * repo's manifest, copy the backdrop PNGs into the bundle — lives in `emit-design-pages.mjs`.
 */

import {
  functionNameOf,
  imagesByPreviewFunction,
  imagesByPreviewId,
  matchesForPreviewId,
  servePreviewId,
} from "./design-references.mjs";

/** Directory (bundle-relative) the manifest and its backdrop PNGs are published under. */
export const PAGES_DIR = "pages";

/** The manifest file the server reads (`ServePageBackdropStore.INDEX_FILE`). */
export const PAGES_INDEX = "index.json";

/** The newest `PAGE_BACKDROP_VERSION` this producer emits. */
export const PAGES_VERSION = 1;

/** `ServePageBackdropStore.SAFE_ID` — a page id is a URL path segment on `/{system}/pages/{id}`. */
const SAFE_ID = /^[A-Za-z0-9._-]{1,160}$/;

/**
 * `.png` is reserved: the server serves a page's backdrop off the same route as its view with that
 * suffix, so a page id'd `home.png` would be unreachable behind the image of the page `home`. The
 * server refuses one too ([ServePageBackdropStore]); refusing it here as well means the delivery
 * branch never carries a page the consumer will silently drop.
 */
const RESERVED_ID_SUFFIX = /\.png$/i;

/**
 * `.` and `..` match the id alphabet but are **path segments**, not names: a browser normalises
 * `/pages/..` to `/` before the request is even sent, so such a page could never be opened even
 * though its image published. Refused alongside the reserved suffix.
 */
const DOT_SEGMENT = /^\.{1,2}$/;

/** The contract's `confidence` values. Anything else is dropped rather than republished. */
const CONFIDENCE_VALUES = new Set(["high", "low"]);

const LINK_METHODS = new Set(["code-connect", "manifest", "convention", "unlinked"]);

/** A finite number greater than zero — every dimension the server draws with. */
function isPositive(value) {
  return typeof value === "number" && Number.isFinite(value) && value > 0;
}

function isFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

/**
 * The published image path for a page: always `<id>.png`, never the producer's own file name.
 *
 * The server re-paths these again when it stages a catalog, so this is belt-and-braces — but it
 * also means the bundle is self-describing: a reader of the delivery branch can tell which PNG
 * belongs to which screen without parsing the manifest.
 */
export function pageImageName(pageId) {
  return `${pageId}.png`;
}

/**
 * The serve preview id for one placement, or null when the catalog publishes no sticker for it.
 *
 * `candidates` are the catalog images that matched; the first is taken deliberately rather than
 * merged. A screen shows a component in exactly one state, and the catalog may publish that
 * component in several (light and dark, three sizes) — any of them renders the right component, and
 * a stable choice keeps the published manifest diffable across regenerations. Light-mode stickers
 * sort first in a catalog's own image order, which is also the better default under a design
 * screenshot exported in light mode.
 */
function resolveServePreviewId(placement, { byPreviewId, byFunction }) {
  const declared = typeof placement?.previewId === "string" ? placement.previewId : "";
  if (declared !== "") {
    // Terminal: a declared id that resolves to nothing must NOT fall through to the function name.
    // [matchesForPreviewId] returns empty for a *sanitised bundle-id collision* — a family where an
    // apparent exact hit can belong to the colliding sibling — and that emptiness is a refusal, not
    // a miss. Falling back would then pick the first image of a `@Preview` function that may cover
    // several themes or states, overlaying a sticker the producer explicitly declined to name.
    const matches = matchesForPreviewId(byPreviewId, declared);
    return matches.length > 0 ? servePreviewId(matches[0].image?.path) : null;
  }
  const fn = functionNameOf(placement?.code);
  if (fn) {
    const matches = byFunction.get(fn) ?? [];
    // Refuse an AMBIGUOUS fallback. `byFunction` is keyed by the bare member name, so two
    // components whose previews are both called `DefaultPreview` share a bucket — and taking the
    // first would overlay component A inside component B's rectangle, which is worse than showing
    // no render at all. Same posture as `matchesForPreviewId`'s collision guard: decline, warn, and
    // leave the hotspot with its mapping.
    const componentIds = new Set(matches.map((m) => m.componentId));
    if (matches.length > 0 && componentIds.size === 1) {
      return servePreviewId(matches[0].image?.path);
    }
  }
  return null;
}

/** Whether a placement is complete enough for the server to draw. Mirrors the server's own test. */
function isDrawablePlacement(placement) {
  const bounds = placement?.bounds;
  return (
    !!bounds &&
    isFiniteNumber(bounds.x) &&
    isFiniteNumber(bounds.y) &&
    isPositive(bounds.width) &&
    isPositive(bounds.height)
  );
}

/**
 * Plan the published `pages/index.json` for `manifest`.
 *
 * Returns `{ manifest, images, warnings }` — the bundle-shaped manifest, the `[{ pageId, from }]`
 * pairs the caller must copy (`from` is the producer's own image path, relative to its manifest),
 * and human-readable warnings for anything dropped or left unrenderable.
 */
export function planPageBackdrops({ manifest, spec, catalog }) {
  const warnings = [];
  if (!manifest || typeof manifest !== "object") {
    return { manifest: null, images: [], warnings };
  }
  const version = manifest.version;
  if (typeof version !== "number" || version < 1 || version > PAGES_VERSION) {
    warnings.push(
      `page-backdrop manifest version ${String(version)} is not one this catalog can publish ` +
        `(supported: 1..${PAGES_VERSION})`,
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
    warnings.push("page-backdrop manifest declares no usable pages array");
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
    if (!isPositive(page?.frame?.width) || !isPositive(page?.frame?.height)) {
      warnings.push(`page ${id} declares no usable frame size; skipped`);
      continue;
    }
    const from = typeof page?.image?.uri === "string" ? page.image.uri : "";
    if (from === "") {
      warnings.push(`page ${id} names no backdrop image; skipped`);
      continue;
    }
    seen.add(id);

    let unresolved = 0;
    const placements = [];
    for (const placement of Array.isArray(page.placements) ? page.placements : []) {
      if (!isDrawablePlacement(placement)) continue;
      const link = LINK_METHODS.has(placement?.link) ? placement.link : "unlinked";
      const previewId =
        link === "unlinked" ? null : resolveServePreviewId(placement, { byPreviewId, byFunction });
      if (link !== "unlinked" && previewId === null) unresolved += 1;
      placements.push({
        nodeId: String(placement?.nodeId ?? ""),
        name: String(placement?.name ?? ""),
        bounds: {
          x: placement.bounds.x,
          y: placement.bounds.y,
          width: placement.bounds.width,
          height: placement.bounds.height,
        },
        // Range-checked, not just integer-checked. The consumer decodes `depth` as a Kotlin Int,
        // so republishing `2147483648` — which `Number.isInteger` happily accepts — fails the parse
        // for the WHOLE manifest and hides every screen. Same failure shape as an unsupported
        // `confidence`, and depth is only a nesting hint, so an out-of-range one becomes 0.
        depth:
          Number.isInteger(placement?.depth) && placement.depth >= 0 && placement.depth <= 2147483647
            ? placement.depth
            : 0,
        ref: String(placement?.ref ?? ""),
        link,
        ...(placement?.code ? { code: String(placement.code) } : {}),
        ...(previewId ? { previewId } : {}),
        // Validated, not passed through. The consumer decodes this into a strict enum, so an
        // unrecognised value there is a parse failure for the WHOLE manifest — one bad string in
        // one placement would hide every screen the catalog publishes. Dropping the field costs
        // only a styling hint.
        ...(CONFIDENCE_VALUES.has(placement?.confidence)
          ? { confidence: placement.confidence }
          : {}),
        ...(placement?.matchedRef ? { matchedRef: String(placement.matchedRef) } : {}),
      });
    }
    if (unresolved > 0) {
      warnings.push(
        `page ${id}: ${unresolved} linked placement(s) map to no published sticker, so they show ` +
          `as hotspots without a render`,
      );
    }

    images.push({ pageId: id, from });
    pages.push({
      id,
      name: String(page?.name ?? id),
      nodeId: String(page?.nodeId ?? ""),
      frame: { width: page.frame.width, height: page.frame.height },
      image: {
        uri: pageImageName(id),
        scale: isPositive(page?.image?.scale) ? page.image.scale : 1,
      },
      placements,
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
