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
import { variantStateFromId } from "./variant-state.mjs";

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

const LINK_METHODS = new Set([
  "code-connect",
  "manifest",
  "convention",
  "unlinked",
]);

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
function resolveServePreviewId(
  node,
  { byPreviewId, byFunction, byReference, classes, directories },
) {
  const declared = typeof node?.previewId === "string" ? node.previewId : "";
  // A declared id is only a refusal when it is OURS. The terminal rule below reads an empty match
  // as "the producer declined to name a sticker", which is true of an id in this catalog's own
  // namespace and false of a sibling module's — that one never named anything of ours to begin
  // with, so treating it as a refusal would suppress the reference join for every node on a shared
  // import. See [catalogOwnsNode].
  const ours = catalogOwnsNode(node, classes ?? new Set(), directories);
  if (declared !== "" && ours) {
    // Terminal: a declared id that resolves to nothing must NOT fall through to the function name.
    // [matchesForPreviewId] returns empty for a *sanitised bundle-id collision* — a family where an
    // apparent exact hit can belong to the colliding sibling — and that emptiness is a refusal, not
    // a miss. Falling back would then pick the first image of a `@Preview` function that may cover
    // several themes or states, overlaying a sticker the producer explicitly declined to name.
    const matches = matchesForPreviewId(byPreviewId, declared);
    return matches.length > 0 ? servePreviewId(matches[0].image?.path) : null;
  }
  // The function join is in the PRODUCING MODULE's namespace, so it is only offered for a node this
  // catalog owns. A foreign node's `#Member` can collide with an unrelated local preview — a bare
  // `DefaultPreview` is the obvious case — and taking it would pair one component's code (rewritten
  // from the reference) with another component's render, corrupting the very score this surface
  // publishes. A foreign node goes straight to the reference join.
  const fn = ours ? functionNameOf(node?.code) : null;
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
  // Last, the design node itself. Both joins above are in the PRODUCING MODULE's namespace, so a
  // repo publishing two catalogs off one shared page import resolves them for the module the import
  // was written against and for neither sibling. The reference handle is in the design file's
  // namespace, which both share — see [componentsByReference].
  const referenced = componentForNodeReference(node, byReference ?? new Map());
  if (referenced) {
    const image = (referenced.images ?? []).find(
      (candidate) => candidate?.path,
    );
    if (image) return servePreviewId(image.path);
  }
  return null;
}

/**
 * The `code` handle to publish for a node: this catalog's own, whenever it can say so.
 *
 * A node's incoming `code` is the REPO's claim — true of the repo, and true of exactly one of its
 * modules. Republishing it verbatim into a sibling catalog states that the sibling implements a
 * file it does not contain, which is how `remote-m3` came to list 75 components under
 * `catalog/…/IconButtons.kt`. Three outcomes:
 *
 *  * the reference join found OUR component ⇒ restate the handle from it, so the row names the file
 *    that actually draws the sticker beside it;
 *  * the node is OURS ([catalogOwnsNode]) ⇒ keep the incoming handle, unchanged. The manifest was
 *    written for this module, and an unresolved-but-ours claim is still true — which is the
 *    coverage the surface exists to report;
 *  * neither ⇒ null. The claim is demonstrably another module's, and publishing it would overstate
 *    this catalog's coverage with work it could never do.
 */
export function codeForNode(node, { byReference, classes, directories }) {
  const incoming =
    typeof node?.code === "string" && node.code !== ""
      ? String(node.code)
      : null;
  // OURS first, and kept verbatim. The incoming handle is repo-relative and already correct for the
  // module the import was written against, so a catalog that owns the node publishes exactly what
  // it published before — no rewrite, no chance of moving a working source link.
  if (incoming && catalogOwnsNode(node, classes ?? new Set(), directories))
    return incoming;
  // Not ours, but we reproduce the same design node: restate the handle from OUR component, using
  // only what the producer CARRIED. `sourceDirectory` and `sourceFunction` are stamped by
  // `apply-source-files.mjs` from the bundle's own record; neither is derivable from what the
  // catalog published before, and both were previously guessed — the directory from the logical
  // Gradle path (wrong for all 100 remapped projects in this repository) and the function by
  // splitting a preview id that an arbitrary `@Preview(name = …)` makes unsplittable.
  //
  // Absent ⇒ no handle. That costs the row its source text on a catalog published before the fields
  // existed; it does NOT cost the render, which [resolveServePreviewId] establishes from the design
  // node itself.
  const referenced = componentForNodeReference(node, byReference ?? new Map());
  if (!referenced) return null;
  const file =
    typeof referenced.sourceFile === "string"
      ? referenced.sourceFile.trim()
      : "";
  const dir =
    typeof referenced.sourceDirectory === "string"
      ? referenced.sourceDirectory.trim().replace(/^\/+|\/+$/g, "")
      : null;
  if (file === "" || dir === null) return null;
  const path = dir === "" ? file : `${dir}/${file}`;
  const fn =
    typeof referenced.sourceFunction === "string"
      ? referenced.sourceFunction.trim()
      : "";
  return fn !== "" ? `${path}#${fn}` : path;
}

/**
 * This catalog's components indexed by the design node each one REPRODUCES (`reference`).
 *
 * The third join, and the only one that works across modules. The other two ask "which of my
 * previews is this node?" through an id or a `@Preview` function name — both of which live in the
 * producing module's namespace. A design-page import is a REPO-level artifact: one `pages.json`
 * describes the whole design file, and a repo publishing two catalogs from it hands the same
 * function names to both. The sibling's names resolve in neither.
 *
 * A `reference` handle does not have that problem. It names a node in the design file, which is the
 * one namespace both sides already share — so "the component that reproduces this node" is
 * answerable by any catalog whose components declare their references, without knowing anything
 * about the other module.
 *
 * Measured on the catalogs this was written for: `remote-m3` publishes 51 components, 10 of which
 * declare a reference, and all 10 land on a node of the kit sheet its previews reproduce. Before
 * this, its pages resolved zero renders — the whole sheet was the sibling's function names.
 *
 * @returns Map of reference handle → the componentIds claiming it. A handle claimed by more than
 *   one component stays in the map with all of them, so the caller can refuse it rather than guess.
 */
export function componentsByReference(catalog) {
  const byReference = new Map();
  for (const component of catalog?.components ?? []) {
    const reference =
      typeof component?.reference === "string"
        ? component.reference.trim()
        : "";
    if (reference === "") continue;
    const componentId =
      typeof component?.componentId === "string"
        ? component.componentId.trim()
        : "";
    if (componentId === "") continue;
    const claims = byReference.get(reference) ?? [];
    claims.push(component);
    byReference.set(reference, claims);
  }
  return byReference;
}

/**
 * The component this catalog publishes for the design node [node] draws, or null.
 *
 * Refuses an AMBIGUOUS claim, the same posture the id and function joins take: `Card` and
 * `TitleCard` both reference one kit node in the catalog that motivated this, and putting one
 * component's render inside the other's outline is worse than drawing no render at all.
 */
export function componentForNodeReference(node, byReference) {
  const ref = typeof node?.ref === "string" ? node.ref.trim() : "";
  if (ref === "") return null;
  const claims = byReference.get(ref) ?? [];
  return claims.length === 1 ? claims[0] : null;
}

/**
 * The **declaring classes** this catalog publishes previews from — `…sections.ButtonsKt` — taken
 * from its own images' discovery ids.
 *
 * The reliable way to ask "is this node's claim mine?". The obvious alternatives both fail:
 * `imagesByPreviewFunction` is EMPTY for an annotation-derived catalog (it is built from spec
 * entries, and `wear-m3-catalog` declares its inventory on annotations), and an exact previewId
 * match drops a legitimate claim whose particular variant this catalog did not bake — measured, one
 * of `wear-m3-catalog`'s 185.
 *
 * The class is the right granularity because it names the FILE the preview is declared in, which is
 * exactly what a `code` handle claims. A catalog either publishes previews out of that file or it
 * does not, and no ambiguity or bake-time choice can change the answer.
 */
/**
 * The declaring class inside a discovery id — `ee.app.sections.ButtonsKt` out of
 * `ee.app.sections.ButtonsKt.TextAction`.
 *
 * NOT the last dot. `buildVariantSuffix` appends `@Preview(name = …)` / `group` through
 * `sanitizeForPath`, which deliberately leaves DOTS INTACT so an id stays lossless — a preview
 * named `Phone.v2` ends `…FooKt.Render_Phone.v2`. Splitting at the last dot would read that class
 * as `…FooKt.Render_Phone`, putting two variants of ONE function in different "classes" and making
 * a legitimate node read as foreign.
 *
 * The boundary is structural instead: a Kotlin package is lowercase by convention and the file
 * class is the first segment that is not, so the class is the prefix up to and including the first
 * capitalised segment. An id with no capitalised segment falls back to the last dot — the previous
 * behaviour, and no worse than it.
 */
export function declaringClassOf(previewId) {
  if (typeof previewId !== "string" || !previewId.includes(".")) return "";
  const segments = previewId.split(".");
  const classAt = segments.findIndex((segment) => /^[A-Z]/.test(segment));
  if (classAt > 0) return segments.slice(0, classAt + 1).join(".");
  return previewId.slice(0, previewId.lastIndexOf("."));
}

export function declaringClasses(catalog) {
  const classes = new Set();
  for (const component of catalog?.components ?? []) {
    for (const image of component?.images ?? []) {
      const declaring = declaringClassOf(image?.previewId);
      if (declaring !== "") classes.add(declaring);
    }
  }
  return classes;
}

/**
 * Whether [node]'s claim can be this catalog's at all.
 *
 * Deliberately weaker than "does it resolve": a declared id that is ours but AMBIGUOUS, or a
 * variant this catalog chose not to bake, is still our claim, and the resolver declines those on
 * purpose — a refusal to guess which sticker, not a statement that the code is somebody else's.
 * Only a preview declared in a file this catalog publishes nothing from says that.
 *
 * Measured on the shared import that motivated this: of 185 coded nodes, `wear-m3-catalog` owns 185
 * and `remote-m3` owns 0. So this separates "the manifest was written for me" from "the manifest was
 * written for my sibling" exactly, and is a no-op for every single-catalog repo.
 */
export function catalogOwnsNode(node, classes, directories) {
  // A catalog whose images carry NO discovery ids at all — everything folded in through the
  // generator's `--extra-renders`, which deliberately publishes none (see
  // [narrowToMappedPreviewId]) — cannot place any claim. That is ignorance, not evidence of
  // foreignness: judging on it would unlink every node such a catalog has, which is the whole page
  // surface. Unable to judge ⇒ keep, exactly as before this test existed.
  if (!classes || classes.size === 0) return true;
  const declaring = declaringClassOf(node?.previewId);
  if (declaring === "") {
    // No declared id to place. Nothing here can prove the claim foreign, so it is kept — the
    // pre-existing behaviour for every manifest that names no preview ids at all.
    return true;
  }
  if (!classes.has(declaring)) return false;
  // The class matches, which is not the same as the claim being ours. A discovery id is
  // `classInfo.name` + `method.name` and carries NO module, while two sibling Gradle modules may
  // legally compile the same fully-qualified class — nothing stops `:app` and `:feature` each
  // having an `ee/app/sections/Buttons.kt`. On a shared design-page import that reads the
  // sibling's node as ours, and then either pairs our render with its code (when the member names
  // coincide) or drops a render the reference join would have resolved (when they differ).
  //
  // So when BOTH sides name a module, they have to agree. Fail-open otherwise, like everything
  // above: a bundle too old to carry `sourceDirectory` and a node with no code handle each leave
  // the class match standing on its own, which is the behaviour that shipped.
  return nodeIsUnderOurModules(node, directories, declaring);
}

/**
 * Whether [node]'s code handle names a source file this catalog publishes [declaring] from.
 *
 * The module half of ownership, and an EXACT test rather than a containment one. Directory
 * containment reads a nested Gradle project as its parent's: `:app` at `app/` lexically contains
 * `:app:feature` at `app/feature/`, and the root project's `""` contains the entire repository, so
 * a prefix test hands the parent every node its children declare — precisely the collision this is
 * here to reject, in the layout where it is most likely.
 *
 * Exact repository paths have no such ambiguity. `codeForNode` builds a handle as the component's
 * `sourceDirectory` joined to its module-relative `sourceFile`, so the set of handles this catalog
 * could have published IS the set of those joins, and a node either names one or does not.
 *
 * Scoped PER DECLARING CLASS, which is what keeps an exact test safe on a partly-stamped catalog.
 * `applySourceFiles` stamps identity per component and only when discovery resolved that
 * component's preview function, so a catalog may carry it for some and not others. Judging every
 * node against one catalog-wide set would then call an unstamped component's own node foreign the
 * moment any sibling was stamped — a regression against the class test alone. A class we hold no
 * identity for is one we cannot place, so it keeps the answer the class test gave.
 */
function nodeIsUnderOurModules(node, sourcePaths, declaring) {
  if (!sourcePaths || sourcePaths.size === 0) return true;
  const known = sourcePaths.get(declaring);
  if (!known || known.size === 0) return true;
  const code = typeof node?.code === "string" ? node.code.trim() : "";
  if (code === "") return true;
  const file = code.split("#", 1)[0].replace(/^\/+/, "");
  if (file === "") return true;
  return known.has(file);
}

/**
 * The repository-relative source files this catalog publishes, indexed by declaring class.
 *
 * `sourceDirectory` is the producing project's repository-relative directory as the bundle recorded
 * it and `sourceFile` is module-relative, so joining them is the same path `codeForNode` emits.
 * The root project stamps `""` — a real answer meaning "already repository-relative" — and joins to
 * the bare file.
 *
 * A component contributes only when it carries BOTH fields, so the map's keys are exactly the
 * classes this catalog can place. Empty for a bundle predating them, which is what makes
 * [catalogOwnsNode]'s module test fail open for one.
 *
 * @returns Map of declaring class → the repository paths this catalog publishes it from.
 */
export function publishingSourcePaths(catalog) {
  const byClass = new Map();
  for (const component of catalog?.components ?? []) {
    // `""` is the root project and is a usable answer; `undefined` is a bundle that never recorded
    // the field and must not be read as one.
    if (typeof component?.sourceDirectory !== "string") continue;
    if (typeof component?.sourceFile !== "string") continue;
    const dir = component.sourceDirectory.trim().replace(/^\/+|\/+$/g, "");
    const file = component.sourceFile.trim().replace(/^\/+/, "");
    if (file === "") continue;
    const path = dir === "" ? file : `${dir}/${file}`;
    for (const image of component?.images ?? []) {
      const declaring = declaringClassOf(image?.previewId);
      if (declaring === "") continue;
      const paths = byClass.get(declaring) ?? new Set();
      paths.add(path);
      byClass.set(declaring, paths);
    }
  }
  return byClass;
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
 * Whether this node is drawn by an **override cell** — a `_VARIANT_<name>` capture of another
 * preview with knobs seeded — rather than by a `@Preview` written for it.
 *
 * Read off the id the design-map DECLARED, not off the resolved serve id: the serve id is derived
 * from the published image path ([servePreviewId]) and carries no guarantee of keeping discovery's
 * suffix. `variantStateFromId` is the same parse the catalog fold uses, so the page and the fold
 * cannot disagree about what a cell is.
 *
 * Independent of whether the catalog publishes a sticker for it. The claim is about the MAPPING —
 * "the thing behind this node is a variant of something else" — which is true whether or not the
 * render made it into the bundle, and a linked node keeps its outline either way.
 */
function isCellNode(node) {
  const declared = typeof node?.previewId === "string" ? node.previewId : "";
  return declared !== "" && variantStateFromId(declared) !== null;
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
  // The cross-module join. Empty for a catalog whose components declare no design references, which
  // leaves both the resolver and the code handle exactly as they were.
  const byReference = componentsByReference(catalog);
  // Which files this catalog actually publishes previews from — the test for whether an incoming
  // `code` claim can be ours at all.
  const classes = declaringClasses(catalog);
  // …and out of which files, at full repository paths. A class name alone cannot tell two
  // modules apart, and a directory cannot tell a project from its own nested ones; see
  // [catalogOwnsNode].
  const directories = publishingSourcePaths(catalog);

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
    if (
      !SAFE_ID.test(id) ||
      RESERVED_ID_SUFFIX.test(id) ||
      DOT_SEGMENT.test(id)
    ) {
      warnings.push(
        `page ${JSON.stringify(page?.id ?? null)} has no route-safe id; skipped`,
      );
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
    const format =
      typeof page?.image?.format === "string" ? page.image.format : "svg";
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
    let foreign = 0;
    const nodes = [];
    for (const node of Array.isArray(page.nodes) ? page.nodes : []) {
      if (!isDrawableNode(node)) continue;
      const declaredLink = LINK_METHODS.has(node?.link)
        ? node.link
        : "unlinked";
      const ours = catalogOwnsNode(node, classes, directories);
      const code =
        declaredLink === "unlinked"
          ? null
          : codeForNode(node, { byReference, classes, directories });
      // A reference match is an IDENTITY — this catalog's component names the very design node the
      // page is drawing — so it stands on its own. The code handle is a label over the top of it and
      // may be missing (a catalog published before the producer carried `sourceDirectory` /
      // `sourceFunction`); losing the label must not cost the render, which is the whole point of
      // the surface.
      const referencedHere =
        componentForNodeReference(node, byReference) !== null;
      // Whether the reference join REPLACED this node's mapping rather than inheriting it. None of
      // the owner's provenance survives that swap: its `confidence` grades a link we did not make,
      // and its `cell` says the id it declared is an override capture — a claim about a preview id
      // in the sibling's namespace, not about ours. Republishing either would label our component
      // with the other catalog's bookkeeping.
      const rewritten =
        declaredLink !== "unlinked" &&
        !ours &&
        (code !== null || referencedHere);
      // A claim this catalog cannot substantiate is not a link. Dropping the handle while keeping
      // `link` would publish a linked node with nothing behind it — the contradiction
      // `renderablePreviewId` already refuses to draw — so the two move together.
      const link =
        declaredLink !== "unlinked" && code === null && !referencedHere
          ? "unlinked"
          : rewritten
            ? // Our own catalog metadata tied this node to a component — the `reference` handle on
              // it — so the method is `manifest` however the owner came by its link.
              "manifest"
            : declaredLink;
      if (declaredLink !== "unlinked" && link === "unlinked") foreign += 1;
      const previewId =
        link === "unlinked"
          ? null
          : resolveServePreviewId(node, {
              byPreviewId,
              byFunction,
              byReference,
              classes,
              directories,
            });
      if (link !== "unlinked" && previewId === null) unresolved += 1;
      nodes.push({
        nodeId: String(node.nodeId),
        name: String(node?.name ?? ""),
        // Range-checked, not just integer-checked. The consumer decodes `depth` as a Kotlin Int,
        // so republishing `2147483648` — which `Number.isInteger` happily accepts — fails the parse
        // for the WHOLE manifest and hides every page. Same failure shape as an unsupported
        // `confidence`, and depth is only a nesting hint, so an out-of-range one becomes 0.
        depth:
          Number.isInteger(node?.depth) &&
          node.depth >= 0 &&
          node.depth <= 2147483647
            ? node.depth
            : 0,
        // The node's type in the design file, republished so the consumer can tell a container from
        // the components inside it exactly. In particular, a COMPONENT_SET is the grid/column that
        // arranges its concrete variants; treating it as another component produces one enormous
        // missing-work outline over all of the useful, specific component hotspots.
        //
        // Free text, like the consumer's own field, so a design tool growing a type does not fail
        // the parse — but only a non-empty string, since `""` is not a type and would read as one.
        ...(typeof node?.type === "string" && node.type.trim() !== ""
          ? { type: node.type.trim() }
          : {}),
        ...(node?.ref ? { ref: String(node.ref) } : {}),
        link,
        ...(code ? { code } : {}),
        ...(previewId ? { previewId } : {}),
        // Validated, not passed through. The consumer decodes this into a strict enum, so an
        // unrecognised value there is a parse failure for the WHOLE manifest — one bad string in
        // one node would hide every page the catalog publishes. Dropping the field costs only a
        // styling hint.
        ...(!rewritten && CONFIDENCE_VALUES.has(node?.confidence)
          ? { confidence: node.confidence }
          : {}),
        // A grouping whose contents are listed below it — a COMPONENT_SET, whose children are the
        // variants. Nothing implements a set (a reference names one of its variants), so the
        // consumer draws it as structure and leaves it out of the coverage count. Dropping it here
        // is not cosmetic: the set comes back as a component nobody implemented, and a page whose
        // every component is done reports missing work that no code could ever clear.
        //
        // Literal `true` only, in the same spirit as `confidence` above — this decodes into a
        // Kotlin Boolean, and a truthy string is a parse failure for the whole manifest.
        ...(node?.container === true ? { container: true } : {}),
        // The kit's own base parts — `Base / SelectionControl / Switch`, `Base / Loading Icon` —
        // which no catalog owes an implementation and which `kit-sets.json` already excludes from
        // the kit walk. Stated by the importer, which has the real tree and therefore knows which
        // set a bare `Selected=Yes, Disabled=No` variant came out of; this flat list does not, so
        // nothing is inferred here.
        //
        // Literal `false` only, and emitted only then: the consumer defaults it to `true`, so a
        // manifest published before the field existed counts exactly what it counted before, and a
        // truthy string would fail the parse for every page.
        ...(node?.inventory === false ? { inventory: false } : {}),
        // Drawn by an override cell rather than by a preview of its own. Only for a node that is
        // actually linked: an unlinked node carries no claim about what is behind it, and a stale
        // `previewId` left beside `link: unlinked` is exactly the contradiction
        // `renderablePreviewId` refuses to draw.
        ...(link !== "unlinked" && !rewritten && isCellNode(node)
          ? { cell: true }
          : {}),
      });
    }
    if (foreign > 0) {
      warnings.push(
        `page ${id}: ${foreign} node(s) name code this catalog does not publish — a shared design ` +
          `import describing a sibling module — so they publish as unlinked rather than as this ` +
          `catalog's work`,
      );
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
      // A sheet that is not a component inventory — the kit's 499-node icon page. Same literal
      // `false` rule as the node field: absent means `true`, which is what every page published
      // before this field existed means.
      ...(page?.inventory === false ? { inventory: false } : {}),
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
