/**
 * Build a published catalog's `parity/activity.json` — the design-parity dashboard's feed.
 *
 * The preview server renders `/<system>/parity`: coverage, a merged code ↔ Figma activity feed, and
 * the mapping gaps. It computes the **coverage** half itself, from the previews and design
 * references it is already serving. It cannot compute the other half, and deliberately does not
 * try: the serve host has no checkout to `git log` and — the load-bearing constraint — **no Figma
 * credential and no Figma egress at all** (`ServeFigmaSpec`, `docs/public-preview-server.md`). A
 * dashboard that called Figma per request would put a token on a public box and make page loads
 * depend on someone else's rate limit.
 *
 * The publish job has both. So it snapshots the feed here, at publish time, into a file on the
 * delivery branch — which also makes the page reproducible (every visitor sees the same feed) and
 * diffable (the branch keeps its history).
 *
 * ## The join
 *
 * Both lanes have to land on the same components as the catalog's stickers, or the page is two
 * unrelated changelogs. `design-map.json` is the pivot, in both directions:
 *
 * - **code**: a changed file path → the design-map entries whose `code` handle starts with that
 *   path → their `previewId`s.
 * - **design**: a Figma comment's pinned `client_meta.node_id` → the design-map entries whose `ref`
 *   is `figma:<key>/<node>` → their `previewId`s.
 *
 * The server then filters those preview ids against the catalog it is actually serving, so an id
 * that outlived a rename degrades to a row with no inbound link rather than a dead one.
 *
 * Pure and dependency-free (no `@design-parity/*`, no I/O, no network) so it unit-tests without an
 * `npm ci`, like its siblings `design-references.mjs` / `catalog-variants.mjs`. The I/O half — run
 * `git log`, call the two Figma REST endpoints, write the file — lives in
 * `emit-parity-activity.mjs`, which drives this.
 */

import { sanitizeBundleEntryId, servePreviewId } from "./design-references.mjs";

/** The `schema` token `ServeParityActivityStore` requires; anything else is ignored wholesale. */
export const ACTIVITY_SCHEMA = "compose-preview-activity/v1";

/** Directory (bundle-relative) the feed lives in. Mirrors `ParityActivity.DIRECTORY`. */
export const ACTIVITY_DIR = "parity";

/** Filename inside [ACTIVITY_DIR]. Mirrors `ParityActivity.FILE`. */
export const ACTIVITY_FILE = "activity.json";

/** Gap kinds the server renders. A kind outside this set is dropped there, so don't emit one. */
export const GAP_KINDS = {
  DANGLING_MAPPING: "dangling-mapping",
  UNRENDERED_REFERENCE: "unrendered-reference",
  UNMAPPED_DESIGN_NODE: "unmapped-design-node",
};

/** `figma:<fileKey>/<nodeId>` — the handle `design-map.json` uses. */
const FIGMA_HANDLE = /^figma:([^/]+)\/(.+)$/;

/** The file path of a design-map `code` handle (`ui/Foo.kt#Bar` → `ui/Foo.kt`). */
function pathOf(code) {
  const value = String(code ?? "");
  const hash = value.indexOf("#");
  return hash <= 0 ? value : value.slice(0, hash);
}

/** Every `previewId` a design-map entry names — the field is a string OR a per-axis array. */
function previewIdsOf(entry) {
  const raw = entry?.previewId;
  if (typeof raw === "string") return raw === "" ? [] : [raw];
  if (!Array.isArray(raw)) return [];
  return raw.map((r) => (typeof r === "string" ? r : r?.previewId)).filter((id) => typeof id === "string" && id !== "");
}

/** Every Figma `<key>/<node>` handle a design-map entry names — same string-or-array shape. */
function figmaRefsOf(entry) {
  const raw = entry?.ref;
  const refs = typeof raw === "string" ? [raw] : Array.isArray(raw) ? raw.map((r) => (typeof r === "string" ? r : r?.ref)) : [];
  return refs
    .filter((ref) => typeof ref === "string")
    .map((ref) => FIGMA_HANDLE.exec(ref))
    .filter(Boolean)
    .map((m) => ({ fileKey: m[1], nodeId: m[2] }));
}

/**
 * `discovery previewId -> route-safe serve id`, for every image the catalog published.
 *
 * **This is the join the feed's inbound links live or die on, and the two sides are written in
 * different alphabets.** A design map names the *discovery* id the daemon keys renders on
 * (`sections.ButtonsKt.FilledButton_Light`); the serve host keys a preview by the *route-safe* id
 * derived from the image path (`button-filled__ideal__default__light` — `ServeCatalogStore
 * .previewIdFor`, restated as [servePreviewId]). `ServeParityDashboard` filters every event's
 * preview ids against the live route ids, so emitting discovery ids means every row silently loses
 * its link to the comparison — the page degrades to two changelogs, which is the one thing it
 * exists not to be.
 *
 * Keyed by both the exact and the [sanitizeBundleEntryId] projection for the same reason
 * `imagesByPreviewId` is: the design map carries the RAW discovery id while a catalog image carries
 * the sanitised in-bundle form, so a `@Preview(name = "Small Round")` is `…_Small Round` on one
 * side and `…_Small_Round` on the other. Catalogs whose names need no sanitising match either way,
 * which is exactly why this gap survives casual testing.
 *
 * ## Ambiguous sanitised keys are dropped, not guessed
 *
 * Sanitising is lossy, so two distinct previews can land on one key (`"A B"` and `"A/B"` both
 * become `A_B`). `assignBundleEntryIds` in the plugin resolves that by letting the first claimant
 * keep the base form and suffixing the rest `_1`, `_2`, … — meaning the raw→sanitised direction is
 * genuinely *not* invertible from the catalog alone.
 *
 * Two ways out, and the cheap-looking one is wrong: reproducing the plugin's suffix assignment here
 * would restate a *third* Kotlin derivation in JavaScript, with nothing checking the restatement —
 * and if it drifted, the failure would be a link that quietly points at the wrong component. A
 * misdirected link is worse than an absent one: absent, the reader knows to go look; misdirected,
 * they compare the wrong pair and trust the answer.
 *
 * So an ambiguous key resolves to **nothing**. The affected rows keep their text and lose only
 * their inbound link, exactly as they do for a preview the catalog dropped.
 */
export function catalogRouteIds(catalog) {
  const exact = new Map();
  const sanitised = new Map();
  /** Sanitised keys claimed by more than one image — see the ambiguity note above. */
  const ambiguous = new Set();
  for (const component of catalog?.components ?? []) {
    for (const image of component?.images ?? []) {
      if (typeof image?.previewId !== "string" || image.previewId === "") continue;
      if (typeof image?.path !== "string" || image.path === "") continue;
      const routeId = servePreviewId(image.path);
      if (!exact.has(image.previewId)) exact.set(image.previewId, routeId);
      const key = sanitizeBundleEntryId(image.previewId);
      const claimed = sanitised.get(key);
      // Only a *different* route is a collision: one preview rendered into several catalog images
      // legitimately repeats its own id, and that is not ambiguity.
      if (claimed !== undefined && claimed !== routeId) ambiguous.add(key);
      else if (claimed === undefined) sanitised.set(key, routeId);
    }
  }
  for (const key of ambiguous) sanitised.delete(key);
  return { exact, sanitised, ambiguous };
}

/**
 * A `discoveryId -> routeId` resolver over [catalogRouteIds], returning null for an id the catalog
 * doesn't publish — or one whose sanitised form is ambiguous. Null rather than the input, and null
 * rather than a guess: emitting an id the server can't match loses a link, while emitting the wrong
 * one sends the reader to the wrong comparison. Both are bugs; only the second is silent.
 *
 * The exact map is consulted first, so an id needing no sanitising is never affected by a collision
 * elsewhere in the catalog.
 */
export function routeIdResolver(catalog) {
  const { exact, sanitised } = catalogRouteIds(catalog);
  return (discoveryId) =>
    exact.get(discoveryId) ??
    sanitised.get(sanitizeBundleEntryId(String(discoveryId ?? ""))) ??
    null;
}

/**
 * Index a design map for both join directions:
 *
 * - `byPath`: source file path → `{ previewIds, components }` — for the code lane.
 * - `byNode`: Figma node id (in BOTH `73:6` and `73-6` spellings, because the API returns one and
 *   the design map writes the other) → `{ previewIds, components }` — for the design lane.
 *
 * The recorded `previewIds` are **route ids** once [routeIdFor] is supplied — the namespace the
 * serve host keys previews by, and the only one its inbound links resolve in (see
 * [catalogRouteIds]). An entry [routeIdFor] cannot resolve contributes no preview id rather than an
 * unmatchable one. The default identity resolver is for tests that work in one namespace; the
 * driver always passes a real one.
 *
 * `components` are catalog component ids when [componentIdFor] can resolve one for a *discovery*
 * id, else the design-map handle's `#Member`. Display-only; the server prefers its own live
 * spelling.
 */
export function indexDesignMap(
  designMap,
  { componentIdFor = () => null, routeIdFor = (id) => id } = {},
) {
  const byPath = new Map();
  const byNode = new Map();

  const record = (map, key, previewIds, component) => {
    if (!key) return;
    if (!map.has(key)) map.set(key, { previewIds: new Set(), components: new Set() });
    const bucket = map.get(key);
    previewIds.forEach((id) => bucket.previewIds.add(id));
    if (component) bucket.components.add(component);
  };

  for (const entry of designMap?.components ?? []) {
    const discoveryIds = previewIdsOf(entry);
    const member = String(entry?.code ?? "").split("#")[1] ?? null;
    const component = discoveryIds.map((id) => componentIdFor(id)).find(Boolean) ?? member;
    const previewIds = discoveryIds.map((id) => routeIdFor(id)).filter(Boolean);
    record(byPath, pathOf(entry?.code), previewIds, component);
    for (const { nodeId } of figmaRefsOf(entry)) {
      record(byNode, nodeId, previewIds, component);
      // Figma's URL/API spellings differ by one character; index both so neither side has to know.
      record(byNode, nodeId.replace(/:/g, "-"), previewIds, component);
      record(byNode, nodeId.replace(/-/g, ":"), previewIds, component);
    }
  }

  const freeze = (map) =>
    new Map(
      [...map].map(([key, value]) => [
        key,
        { previewIds: [...value.previewIds], components: [...value.components] },
      ]),
    );
  return { byPath: freeze(byPath), byNode: freeze(byNode) };
}

/**
 * Turn parsed `git log` commits into code-lane events.
 *
 * A commit is kept when at least one of its changed files is one the design map maps, OR when
 * [keepUnmapped] is set — the latter is how a catalog with no design map still gets a code feed.
 * Files are matched by **prefix**, so a design-map handle written repo-relative
 * (`catalog/src/.../Buttons.kt#FilledButton`) matches the same path `git log --name-only` reports.
 */
export function codeEventsFrom(commits, index, { keepUnmapped = false } = {}) {
  const events = [];
  for (const commit of commits ?? []) {
    if (typeof commit?.sha !== "string" || typeof commit?.at !== "string") continue;
    const previewIds = new Set();
    const components = new Set();
    for (const file of commit.files ?? []) {
      const hit = index?.byPath?.get(file);
      if (!hit) continue;
      hit.previewIds.forEach((id) => previewIds.add(id));
      hit.components.forEach((c) => components.add(c));
    }
    if (previewIds.size === 0 && !keepUnmapped) continue;
    events.push({
      sha: commit.sha,
      subject: commit.subject ?? "",
      at: commit.at,
      ...(commit.author ? { author: commit.author } : {}),
      previewIds: [...previewIds],
      components: [...components],
    });
  }
  return events;
}

/**
 * Turn a Figma `GET /v1/files/:key/comments` payload into comment events.
 *
 * Two things are deliberately dropped rather than published:
 *
 * - **the commenter's email / handle beyond a display name.** The feed lands on a public preview
 *   server; a display name is what a reviewer needs to know who to ask, and anything more is
 *   personal data this page has no use for.
 * - **replies.** Figma threads a reply by `parent_id`; a dashboard showing every reply as its own
 *   row buries the threads that matter. Only thread openers are kept.
 */
export function figmaCommentEventsFrom(payload, index) {
  const events = [];
  for (const comment of payload?.comments ?? []) {
    if (comment?.parent_id) continue;
    const message = typeof comment?.message === "string" ? comment.message.trim() : "";
    const at = comment?.created_at;
    if (message === "" || typeof at !== "string") continue;
    const nodeId = comment?.client_meta?.node_id;
    const hit = typeof nodeId === "string" ? index?.byNode?.get(nodeId) : undefined;
    events.push({
      id: String(comment.id ?? ""),
      at,
      message,
      ...(comment?.user?.handle ? { author: String(comment.user.handle) } : {}),
      resolved: Boolean(comment?.resolved_at),
      ...(typeof nodeId === "string" ? { nodeId } : {}),
      previewIds: hit?.previewIds ?? [],
      components: hit?.components ?? [],
    });
  }
  return events;
}

/** Turn a Figma `GET /v1/files/:key/versions` payload into version events. */
export function figmaVersionEventsFrom(payload) {
  const events = [];
  for (const version of payload?.versions ?? []) {
    const at = version?.created_at;
    if (typeof at !== "string") continue;
    events.push({
      id: String(version.id ?? ""),
      at,
      ...(version?.label ? { label: String(version.label) } : {}),
      ...(version?.description ? { description: String(version.description) } : {}),
      ...(version?.user?.handle ? { author: String(version.user.handle) } : {}),
    });
  }
  return events;
}

/**
 * The mapping gaps only this job can see.
 *
 * Deliberately NOT including "this preview has no design reference": the server derives that live
 * from the catalog it is serving, and publishing it here would let a stale feed contradict the
 * catalog in front of the reader. What is emitted is exactly the set that needs the design file or
 * the checkout:
 *
 * - a design-map entry naming a preview id the published catalog doesn't contain (**dangling**);
 * - a mapped Figma node whose reference raster failed to publish (**unrendered**);
 * - a component published in the Figma file that nothing maps to (**unmapped design node**).
 *
 * **`catalogPreviewIds` and the design map's ids are compared through [sanitizeBundleEntryId], not
 * verbatim.** A design map carries the RAW discovery id while a catalog image carries the sanitised
 * in-bundle form, so `pkg.FooKt.Bar_Small Round` and `pkg.FooKt.Bar_Small_Round` are the same
 * preview written two ways. Comparing them literally reports a perfectly healthy mapping as
 * dangling *and* suppresses the unrendered-reference check for it — one false finding and one
 * missed one, from the same mismatch. Sanitising is idempotent, so normalising both sides is safe
 * whichever form a caller happens to pass.
 */
export function mappingGaps({
  designMap,
  catalogPreviewIds = [],
  publishedReferenceNodeIds = [],
  droppedReferences = [],
  figmaComponents = [],
  referenceManifest = null,
} = {}) {
  const gaps = [];
  const live = new Set(catalogPreviewIds.map((id) => sanitizeBundleEntryId(String(id ?? ""))));
  const isPublished = (id) => live.has(sanitizeBundleEntryId(String(id ?? "")));

  for (const entry of designMap?.components ?? []) {
    const previewIds = previewIdsOf(entry);
    // Reported verbatim as the design map spells it — the normalisation is for *comparison*; a
    // reader fixing the map needs to see the id they actually wrote.
    const missing = previewIds.filter((id) => !isPublished(id));
    if (missing.length > 0 && previewIds.length > 0) {
      gaps.push({
        kind: GAP_KINDS.DANGLING_MAPPING,
        detail: `design-map names ${missing.join(", ")}, which this catalog does not publish.`,
        code: entry?.code,
        previewId: missing[0],
      });
    }
  }

  for (const dropped of droppedReferences) {
    gaps.push({
      kind: GAP_KINDS.UNRENDERED_REFERENCE,
      detail: dropped?.reason ?? "The reference raster could not be published.",
      ...(dropped?.code ? { code: dropped.code } : {}),
      ...(dropped?.ref ? { ref: dropped.ref } : {}),
      ...(dropped?.previewId ? { previewId: dropped.previewId } : {}),
    });
  }

  // The same gap, *derived* rather than reported. The reference step drops an entry it can't
  // rasterize with a `::warning::` and moves on, and those warnings are gone by the time this runs
  // — so relying on a caller to hand them over would leave `unrendered-reference` a documented kind
  // nothing ever emits. The published manifest is the durable evidence: it records the design-map
  // `code` handle of every reference that made it (`emit-design-references.mjs` puts it in
  // `source.attributes.code`), so a mapped entry that is missing from it is precisely one that
  // didn't rasterize.
  //
  // Scoped to entries whose preview the catalog DOES publish — an entry mapping to nothing at all
  // is a different finding, and one the dangling-mapping check above already reports. Skipped
  // entirely when no manifest was passed, because "no manifest" and "an empty manifest" are
  // different claims and only the second means every reference failed.
  if (referenceManifest) {
    const published = new Set(
      (referenceManifest.references ?? [])
        // Only a PRIMARY answers "does this component have its reference?". Every record of an
        // entry — the default binding and each of its size/state cells — carries the same `code`
        // handle, so counting secondaries would let a surviving `size=l` cell mask a missing
        // default render: the gap would go quiet precisely when the binding it exists to protect
        // is the one that failed. A manifest written before tiers existed carries no `tier`, and
        // every record in it was a primary.
        .filter((r) => (r?.tier ?? "primary") === "primary")
        .map((r) => r?.source?.attributes?.code)
        .filter((code) => typeof code === "string"),
    );
    for (const entry of designMap?.components ?? []) {
      if (typeof entry?.code !== "string" || published.has(entry.code)) continue;
      if (figmaRefsOf(entry).length === 0) continue;
      const previewIds = previewIdsOf(entry);
      if (previewIds.length === 0 || previewIds.some((id) => !isPublished(id))) continue;
      gaps.push({
        kind: GAP_KINDS.UNRENDERED_REFERENCE,
        detail:
          "Mapped to a Figma node, but no reference raster was published for it — the render " +
          "step could not produce one, so nothing can score this preview against its spec.",
        code: entry.code,
        ...(typeof entry.ref === "string" ? { ref: entry.ref } : {}),
        previewId: previewIds[0],
      });
    }
  }

  // A published component the map never names. Compared in both node-id spellings for the same
  // reason `indexDesignMap` indexes both.
  const mapped = new Set();
  for (const entry of designMap?.components ?? []) {
    for (const { nodeId } of figmaRefsOf(entry)) {
      mapped.add(nodeId);
      mapped.add(nodeId.replace(/:/g, "-"));
      mapped.add(nodeId.replace(/-/g, ":"));
    }
  }
  for (const id of publishedReferenceNodeIds) mapped.add(id);
  for (const component of figmaComponents) {
    const nodeId = component?.node_id ?? component?.nodeId;
    if (typeof nodeId !== "string" || mapped.has(nodeId)) continue;
    gaps.push({
      kind: GAP_KINDS.UNMAPPED_DESIGN_NODE,
      detail: "Published in the design file, but no design-map entry names it.",
      ...(component?.fileKey ? { ref: `figma:${component.fileKey}/${nodeId}` } : {}),
      ...(component?.name ? { component: String(component.name) } : {}),
    });
  }

  return gaps;
}

/**
 * Assemble the served document. Returns null when there is nothing worth publishing — the server
 * treats an empty feed as no feed, so writing one would only add a file that changes nothing.
 */
export function buildActivity({
  generatedAt,
  windowDays,
  repo,
  ref,
  codeEvents = [],
  figmaFileKey,
  figmaFileName,
  figmaVersions = [],
  figmaComments = [],
  gaps = [],
} = {}) {
  const hasCode = codeEvents.length > 0;
  const hasFigma = figmaVersions.length > 0 || figmaComments.length > 0;
  if (!hasCode && !hasFigma && gaps.length === 0) return null;
  return {
    schema: ACTIVITY_SCHEMA,
    ...(generatedAt ? { generatedAt } : {}),
    ...(windowDays ? { windowDays } : {}),
    ...(hasCode
      ? {
          code: {
            ...(repo ? { repo } : {}),
            ...(ref ? { ref } : {}),
            events: codeEvents,
          },
        }
      : {}),
    ...(hasFigma
      ? {
          figma: {
            ...(figmaFileKey ? { fileKey: figmaFileKey } : {}),
            ...(figmaFileName ? { fileName: figmaFileName } : {}),
            versions: figmaVersions,
            comments: figmaComments,
          },
        }
      : {}),
    ...(gaps.length > 0 ? { gaps } : {}),
  };
}

/**
 * Parse `git log --name-only` output in the exact format [GIT_LOG_FORMAT] asks for. Kept here
 * (rather than in the driver) so the parse is unit-tested against real-shaped output.
 *
 * The record separator is `\x1e` and the field separator `\x1f` — control characters that cannot
 * appear in a commit subject, unlike any printable delimiter a subject could legitimately contain.
 */
export function parseGitLog(stdout) {
  const commits = [];
  for (const record of String(stdout ?? "").split("\x1e")) {
    const trimmed = record.replace(/^\n+/, "");
    if (trimmed.trim() === "") continue;
    const [header, ...fileLines] = trimmed.split("\n");
    const [sha, at, author, ...subjectParts] = header.split("\x1f");
    if (!sha || !at) continue;
    commits.push({
      sha,
      at,
      author: author || undefined,
      subject: subjectParts.join("\x1f"),
      files: fileLines.map((line) => line.trim()).filter((line) => line !== ""),
    });
  }
  return commits;
}

/** The `--pretty` format [parseGitLog] expects. */
export const GIT_LOG_FORMAT = "%x1e%H%x1f%aI%x1f%an%x1f%s";
