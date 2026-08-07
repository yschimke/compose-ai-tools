/**
 * Write a published catalog's `parity/activity.json` — the feed behind the preview server's
 * **Design parity** view (`/<system>/parity`).
 *
 *     node emit-parity-activity.mjs --out <bundle dir> --repo <repo root> \
 *       [--design-map design-map.json] [--days 30] [--source-repo owner/name] [--ref main] \
 *       [--figma-file <key>] [--strict]
 *
 * `--out` is the staged bundle the workflow is about to publish to `design-artifacts/<system>`;
 * this adds one file and leaves the rest alone. Safe to run unconditionally: a repo with no git
 * history, no design map, and no Figma token simply writes nothing.
 *
 * ## Why this exists as a publish step rather than a server feature
 *
 * The serve host holds **no Figma credential** and has no checkout — see the header of
 * `parity-activity.mjs` and `docs/public-preview-server.md`. This job has both: it is already
 * running in the repo, and it already holds `FIGMA_TOKEN` to rasterize design references. So it
 * snapshots the activity here and the server only renders it.
 *
 * ## What it reads
 *
 * - **code**: `git log` over the window, `--name-only`, joined to previews through
 *   `design-map.json`'s `code` handles.
 * - **design**: `GET /v1/files/:key/versions` and `GET /v1/files/:key/comments` — two read-only
 *   endpoints, both already covered by the token the reference rasteriser uses. The file key comes
 *   from the design map's own `figma:` refs, so nothing new has to be configured.
 * - **gaps**: computed by `mappingGaps` against the published `catalog.json` and, when the token is
 *   present, `GET /v1/files/:key/components`.
 *
 * ## Failure posture
 *
 * Fail-soft, matching `emit-design-references.mjs` and the server's own reader: a lane that can't
 * be read is skipped with a `::warning::` and the rest publishes. No token ⇒ the code lane and the
 * gaps still publish, which is the normal state for a fork or a PR run. `--strict` turns any
 * skipped lane into a non-zero exit for a repo that wants its parity feed gated.
 */
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

import {
  ACTIVITY_DIR,
  ACTIVITY_FILE,
  GIT_LOG_FORMAT,
  buildActivity,
  codeEventsFrom,
  figmaCommentEventsFrom,
  figmaVersionEventsFrom,
  indexDesignMap,
  mappingGaps,
  parseGitLog,
} from "./parity-activity.mjs";

function arg(name, def = undefined) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : def;
}

const OUT = arg("out");
const REPO = path.resolve(arg("repo", "."));
const DESIGN_MAP = arg("design-map", "design-map.json");
const WINDOW_DAYS = Number(arg("days", "30"));
const SOURCE_REPO = arg("source-repo", process.env.GITHUB_REPOSITORY || "");
const SOURCE_REF = arg("ref", "");
const FIGMA_FILE = arg("figma-file", "");
const STRICT = process.argv.includes("--strict");

/** Rows kept per lane before publishing. The server caps again; this keeps the file small. */
const MAX_PER_LANE = 60;

const FIGMA_TOKEN =
  process.env.FIGMA_TOKEN || process.env.FIGMA_PAT || process.env.FIGMA_ACCESS_TOKEN || "";

if (!OUT) {
  console.error("emit-parity-activity: --out <bundle dir> is required");
  process.exit(2);
}

let skipped = 0;
const warn = (message) => {
  skipped += 1;
  console.log(`::warning::parity-activity: ${message}`);
};

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

const catalogPath = path.join(OUT, "catalog.json");
if (!fs.existsSync(catalogPath)) {
  console.error(`parity-activity: ${catalogPath} is missing — run after the catalog export`);
  process.exit(2);
}
const catalog = readJson(catalogPath);

const designMapPath = path.resolve(REPO, DESIGN_MAP);
const designMap = fs.existsSync(designMapPath) ? readJson(designMapPath) : { components: [] };
const hasDesignMap = (designMap.components ?? []).length > 0;

/**
 * `previewId -> componentId`, for naming feed rows. Built from the catalog's images, which carry
 * both the discovery `previewId` (what the design map names) and their component. The server
 * re-derives display names from the catalog it serves anyway, so this is only a fallback for rows
 * whose previews it can no longer resolve.
 */
const componentIdByPreviewId = new Map();
/**
 * The **discovery** preview ids the published catalog actually contains — the namespace a design
 * map names, and therefore what the dangling-mapping check compares against. Not the route-safe
 * serve id (`button-filled__ideal__default__light`): a design-map entry never writes one, so
 * comparing against those would report every mapping as dangling.
 */
const publishedPreviewIds = new Set();
for (const component of catalog?.components ?? []) {
  for (const image of component?.images ?? []) {
    if (typeof image?.previewId !== "string" || image.previewId === "") continue;
    componentIdByPreviewId.set(image.previewId, component.componentId);
    publishedPreviewIds.add(image.previewId);
  }
}

const index = indexDesignMap(designMap, (id) => componentIdByPreviewId.get(id) ?? null);

// ---------------------------------------------------------------------------- code lane

/**
 * The window's commits, or `[]` when this isn't a git checkout deep enough to answer.
 *
 * A shallow clone (`actions/checkout` defaults to depth 1) has no history to log, which would
 * publish an empty code lane and read as "nothing changed" — a lie. So the emptiness is warned
 * about rather than silently published.
 */
function readCommits() {
  try {
    const stdout = execFileSync(
      "git",
      [
        "-C",
        REPO,
        "log",
        `--since=${WINDOW_DAYS}.days.ago`,
        `--pretty=${GIT_LOG_FORMAT}`,
        "--name-only",
        "--no-merges",
        `--max-count=${MAX_PER_LANE}`,
      ],
      { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 },
    );
    const commits = parseGitLog(stdout);
    if (commits.length === 0) {
      const shallow = fs.existsSync(path.join(REPO, ".git", "shallow"));
      if (shallow) {
        warn("the checkout is shallow, so no code activity could be read (needs fetch-depth: 0)");
      }
    }
    return commits;
  } catch (error) {
    warn(`git log failed: ${error?.message ?? error}`);
    return [];
  }
}

const commits = readCommits();
// With a design map, a commit is interesting when it touched a mapped file. Without one there is
// nothing to join on, so every commit is kept — the feed is then a plain changelog beside the
// coverage numbers, which is still more than the page would otherwise have.
const codeEvents = codeEventsFrom(commits, index, { keepUnmapped: !hasDesignMap }).slice(
  0,
  MAX_PER_LANE,
);

// ---------------------------------------------------------------------------- design lane

/** The Figma file this catalog is specified by: `--figma-file`, else the design map's own refs. */
function resolveFigmaFileKey() {
  if (FIGMA_FILE) return FIGMA_FILE;
  for (const entry of designMap?.components ?? []) {
    const refs = Array.isArray(entry?.ref) ? entry.ref : [entry?.ref];
    for (const raw of refs) {
      const ref = typeof raw === "string" ? raw : raw?.ref;
      const match = /^figma:([^/]+)\//.exec(String(ref ?? ""));
      if (match) return match[1];
    }
  }
  return "";
}

const figmaFileKey = resolveFigmaFileKey();

/** One read-only Figma GET, or null when it fails. Never throws — a lane is optional. */
async function figmaGet(pathname) {
  const response = await fetch(`https://api.figma.com${pathname}`, {
    headers: { "X-Figma-Token": FIGMA_TOKEN },
  });
  if (!response.ok) {
    warn(`GET ${pathname} returned ${response.status}`);
    return null;
  }
  return response.json();
}

let figmaFileName = "";
let figmaVersions = [];
let figmaComments = [];
let figmaComponents = [];

if (figmaFileKey && FIGMA_TOKEN) {
  try {
    const [versions, comments, components, file] = await Promise.all([
      figmaGet(`/v1/files/${figmaFileKey}/versions?page_size=${MAX_PER_LANE}`),
      figmaGet(`/v1/files/${figmaFileKey}/comments`),
      figmaGet(`/v1/files/${figmaFileKey}/components`),
      // `depth=1` fetches the file's name without walking its whole node tree — the page labels the
      // design side with it, and "Material 3 Design Kit" reads better than a 22-character key.
      figmaGet(`/v1/files/${figmaFileKey}?depth=1`),
    ]);
    figmaFileName = typeof file?.name === "string" ? file.name : "";
    figmaVersions = figmaVersionEventsFrom(versions).slice(0, MAX_PER_LANE);
    figmaComments = figmaCommentEventsFrom(comments, index)
      // Newest first, then windowed: the API returns the whole thread history, and a comment from
      // two years ago is not "recent activity".
      .sort((a, b) => (a.at < b.at ? 1 : -1))
      .filter((c) => withinWindow(c.at))
      .slice(0, MAX_PER_LANE);
    figmaComponents = (components?.meta?.components ?? []).map((c) => ({
      node_id: c?.node_id,
      name: c?.name,
      fileKey: figmaFileKey,
    }));
  } catch (error) {
    warn(`Figma lane failed: ${error?.message ?? error}`);
  }
} else if (figmaFileKey) {
  warn("no FIGMA_TOKEN, so the design lane is empty (normal for a fork or a PR run)");
}

/** Whether an ISO-8601 instant falls inside the window. Undated ⇒ excluded. */
function withinWindow(at) {
  const stamp = Date.parse(at);
  if (Number.isNaN(stamp)) return false;
  return stamp >= Date.now() - WINDOW_DAYS * 24 * 60 * 60 * 1000;
}

// Version history is already windowed by the same rule, applied after the shape mapping so an
// unparseable date is dropped by one rule rather than two.
figmaVersions = figmaVersions.filter((v) => withinWindow(v.at));

// ---------------------------------------------------------------------------- gaps + write

const gaps = mappingGaps({
  designMap,
  catalogPreviewIds: [...publishedPreviewIds],
  figmaComponents,
});

const activity = buildActivity({
  generatedAt: new Date().toISOString(),
  windowDays: WINDOW_DAYS,
  repo: SOURCE_REPO,
  ref: SOURCE_REF || undefined,
  codeEvents,
  figmaFileKey: figmaFileKey || undefined,
  figmaFileName: figmaFileName || undefined,
  figmaVersions,
  figmaComments,
  gaps,
});

if (activity === null) {
  console.log("parity-activity: nothing to publish (no commits, no Figma activity, no gaps)");
  process.exit(STRICT && skipped > 0 ? 1 : 0);
}

const dir = path.join(OUT, ACTIVITY_DIR);
fs.mkdirSync(dir, { recursive: true });
fs.writeFileSync(path.join(dir, ACTIVITY_FILE), `${JSON.stringify(activity, null, 2)}\n`);
console.log(
  `parity-activity: wrote ${ACTIVITY_DIR}/${ACTIVITY_FILE} — ` +
    `${codeEvents.length} commit(s), ${figmaVersions.length} version(s), ` +
    `${figmaComments.length} comment(s), ${gaps.length} gap(s)`,
);

process.exit(STRICT && skipped > 0 ? 1 : 0);
