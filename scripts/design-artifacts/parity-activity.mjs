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
 * Index a design map for both join directions:
 *
 * - `byPath`: source file path → `{ previewIds, components }` — for the code lane.
 * - `byNode`: Figma node id (in BOTH `73:6` and `73-6` spellings, because the API returns one and
 *   the design map writes the other) → `{ previewIds, components }` — for the design lane.
 *
 * `components` are catalog component ids when [componentIdFor] can resolve one for a preview id,
 * else the design-map handle's `#Member`. Display-only; the server prefers its own live spelling.
 */
export function indexDesignMap(designMap, componentIdFor = () => null) {
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
    const previewIds = previewIdsOf(entry);
    const member = String(entry?.code ?? "").split("#")[1] ?? null;
    const component = previewIds.map((id) => componentIdFor(id)).find(Boolean) ?? member;
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
 */
export function mappingGaps({
  designMap,
  catalogPreviewIds = [],
  publishedReferenceNodeIds = [],
  droppedReferences = [],
  figmaComponents = [],
} = {}) {
  const gaps = [];
  const live = new Set(catalogPreviewIds);

  for (const entry of designMap?.components ?? []) {
    const previewIds = previewIdsOf(entry);
    const missing = previewIds.filter((id) => !live.has(id));
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
