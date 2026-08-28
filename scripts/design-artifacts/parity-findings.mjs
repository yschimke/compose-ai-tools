/**
 * Re-key a design-parity run's verdict onto the ids a preview server routes on.
 *
 * The run publishes `findings.json` on its own reporting branch
 * (`design-parity/<system>`), in the same `compose-preview-parity-findings/v1` schema the server
 * reads — but keyed by the two namespaces a RUN knows: the fully-qualified compose preview id
 * (`a.b.C.fn`) and the design-map code handle (`ui/Button.kt#Primary`). The compare page routes on
 * neither. It routes on the catalog's **sticker id** (`button-filled__ideal__default__light`),
 * which is minted at publish time and therefore cannot be known by the run.
 *
 * `buildAnnotationManifest` learned this the expensive way — its comment records that keying on
 * the preview id alone "produced a manifest the server silently ignored" — so the join is done
 * here, where both namespaces are in hand.
 *
 * Two things the run left blank get filled in on the way through, both for the same reason: only a
 * publisher knows them.
 *
 * - **`referenceId`**, from the same `planDesignReferences` records that mint `references/index.json`.
 *   This is what stops one board's token drift printing under another board's panels on a preview
 *   that carries several references. The run's own `source` stamp is what makes that resolvable at
 *   all where one code handle was diffed against several sources — it is consumed here and dropped,
 *   because once a set names a reference id the source it came from is already implied.
 * - **`reportUrl`**, from the branch the run published to plus the `reportPath` its `run.json`
 *   already records. Deliberately the repository blob URL rather than a third-party HTML renderer:
 *   the link is a promise about where the report lives, not about how it will look.
 *
 * A third thing is CORRECTED on the way through. A finding's reference-side anchor is a box in the
 * coordinate space the design tool's adapter captured, and `emit-design-references.mjs` publishes
 * that reference onto the sticker's canvas — resampling, reducing or letterboxing it on the way.
 * Where it did, the anchor and the raster describe different pictures and the highlight sits off the
 * element the finding names, which is worst on exactly the `layout` findings that anchor on both
 * panels so an offset can be SEEN (#4696). The reference step records what it did, per reference, in
 * `references/index.json`; those transforms are applied here, at the point a set is scoped to the
 * reference it is about. Candidate-side anchors are already in the render's own pixels — the frame
 * the server serves — and are never touched.
 *
 * Pure functions over already-parsed JSON — no I/O, no git, no network. The driver
 * (`emit-parity-findings.mjs`) reads the branch and writes the file.
 */

import { transformBounds } from "./reference-layout.mjs";

/** The schema the preview server validates before reading a manifest. */
export const FINDINGS_SCHEMA = "compose-preview-parity-findings/v1";

/** Where the served document lands in the bundle. */
export const FINDINGS_DIR = "parity";
export const FINDINGS_FILE = "findings.json";

/** What the run publishes on its own branch, beside `run.json`. */
export const RUN_FINDINGS_FILE = "findings.json";
export const RUN_MANIFEST_FILE = "run.json";

/**
 * Index the planned reference records by the design-map code handle the run reports against.
 *
 * A code handle can plan several records — a component rendered light and dark plans one per
 * sticker, and a variant matrix plans a `secondary` per cell. Every one of them is a comparison
 * page that should carry the verdict, so all are kept; the run's verdict is about the code, and
 * each page shows that code against one board.
 */
export function referencesByCode(records) {
  const byCode = new Map();
  for (const record of records ?? []) {
    const code = record?.source?.attributes?.code;
    if (!code || !record?.previewId || !record?.id) continue;
    if (!byCode.has(code)) byCode.set(code, []);
    byCode.get(code).push({ previewId: record.previewId, referenceId: record.id });
  }
  return byCode;
}

/**
 * `https://github.com/<owner>/<repo>/blob/<branch>/<path>`, or null when any part is missing.
 *
 * Only ever `https://`, because `ServeParityFindingStore` refuses anything else — a producer's
 * string lands in an `href` on a page the reader trusts, so the scheme is checked on both sides.
 */
export function reportUrlFor({ repoSlug, branch, reportPath }) {
  if (!repoSlug || !branch || !reportPath) return null;
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repoSlug)) return null;
  const path = String(reportPath).replace(/^\/+/, "");
  if (!path || path.includes("..")) return null;
  const encode = (segment) => segment.split("/").map(encodeURIComponent).join("/");
  return `https://github.com/${repoSlug}/blob/${encode(branch)}/${encode(path)}`;
}

/**
 * The sets a run published under one key, as a list.
 *
 * An own-property read for the same reason `previews` is prototype-free below: `previews.toString`
 * on a plain object from `JSON.parse` is inherited, not absent, and would sail past a bare
 * truthiness check.
 */
function findingsFor(runFindings, code) {
  const map = runFindings?.previews;
  if (!map || typeof map !== "object") return [];
  if (!Object.prototype.hasOwnProperty.call(map, code)) return [];
  const sets = map[code];
  return Array.isArray(sets) ? sets : [];
}

/**
 * Build the served manifest from a run's published artifacts.
 *
 * Driven by `run.json`'s entries rather than by the findings map's keys, because the entry is the
 * only place the two halves meet: it carries the `code` that indexes both the findings and the
 * planned references, and the `reportPath` that becomes the outbound link. Walking the findings map
 * instead would leave the code-handle and preview-id keys indistinguishable from one another.
 *
 * Every drop is reported rather than silently swallowed — a component whose verdict cannot reach a
 * page is exactly the thing an operator would otherwise never learn.
 *
 * The producer's own `schema` is checked before any of it is read. This driver rewrites a run's
 * records and republishes them under `FINDINGS_SCHEMA` — a claim that the sets mean what a v1
 * reader believes they mean. A producer that has moved to a v2 with different set semantics would
 * otherwise have those records relabelled as trusted v1 data by whatever driver revision an
 * external caller happens to be pinned to. Dropping the panel is the honest answer: it is an
 * enhancement, and a wrong verdict is worse than an absent one. An UNSTAMPED document is accepted,
 * because the first producer to ship this file predates the field.
 */
/**
 * One finding with its REFERENCE-side anchors moved into the published raster's pixel space.
 *
 * Returned as it stands when the export did not move that reference's pixels, so a catalog whose
 * references all published at their captured size emits byte-identical findings. An anchor on the
 * `actual` panel is left alone in every case: it was measured in the render's own pixels, which is
 * the frame the server serves.
 *
 * An anchor the placement cropped away entirely is DROPPED rather than published pointing at
 * nothing, and a finding left with none loses the field: `ServeParityFindingStore` offers a finding
 * with no anchors as prose and never as a control, which is the honest answer for a region this
 * reference does not show. The candidate-side anchor of the same finding survives, so a `layout`
 * finding keeps the half that can still be pointed at.
 */
function rebaseAnchors(finding, transform) {
  if (!transform || !Array.isArray(finding?.anchors)) return finding;
  const anchors = finding.anchors.flatMap((anchor) => {
    if (anchor?.side !== "reference" || !anchor.bounds) return [anchor];
    const bounds = transformBounds(anchor.bounds, transform, transform.canvas);
    return bounds ? [{ ...anchor, bounds }] : [];
  });
  if (anchors.length > 0) return { ...finding, anchors };
  const { anchors: dropped, ...rest } = finding;
  return rest;
}

/** One set, with every finding's reference-side anchor moved onto the reference as published. */
function rebaseSet(set, transform) {
  if (!transform || !Array.isArray(set?.findings)) return set;
  return { ...set, findings: set.findings.map((finding) => rebaseAnchors(finding, transform)) };
}

export function buildServedFindings({
  runManifest,
  runFindings,
  references,
  referenceTransforms,
  repoSlug,
  branch,
}) {
  const byCode = referencesByCode(references);
  // No prototype, because both key spaces here are producer- and catalog-controlled strings: a
  // component legitimately named `constructor` or `toString` would otherwise resolve to the
  // inherited member rather than a missing entry, and `??= []` would never fire.
  const previews = Object.create(null);
  const warnings = [];
  let mapped = 0;

  const schema = runFindings?.schema;
  if (schema != null && schema !== FINDINGS_SCHEMA) {
    return {
      document: null,
      warnings: [
        `the run publishes ${JSON.stringify(String(schema))}, not ${FINDINGS_SCHEMA}; ` +
          `its verdict is not published`,
      ],
      mapped: 0,
    };
  }

  // A structurally malformed `run.json` is a dropped panel, never a failed publish: the emitter
  // runs under `set -e`, so letting `for...of` throw on a non-array would cost the catalog its
  // render over an optional enhancement.
  const entries = runManifest?.entries;
  if (entries != null && !Array.isArray(entries)) {
    return {
      document: null,
      warnings: [`the run's entries are not a list; its verdict is not published`],
      mapped: 0,
    };
  }

  for (const entry of entries ?? []) {
    const code = entry?.code;
    if (!code) continue;
    const all = findingsFor(runFindings, code);
    if (all.length === 0) continue;

    // One code handle can be diffed against SEVERAL sources, and those results share a code handle
    // and a candidate preview id — the run tells them apart only by the `source` it stamps on each
    // set. Matching on it is what stops the Figma verdict being published under the Stitch board
    // as well. An entry with no source, or sets from a producer old enough not to stamp one, falls
    // back to every set: one source is the overwhelmingly common shape, and there the filter has
    // nothing to choose between.
    const bySource = all.filter((set) => set?.source === entry.source);
    const sets = bySource.length > 0 ? bySource : all.filter((set) => !set?.source);
    if (sets.length === 0) continue;

    const targets = byCode.get(code) ?? [];
    if (targets.length === 0) {
      // No planned reference means no comparison page: the verdict has nowhere to be shown, and
      // publishing it under a key nothing routes to would be a manifest the server ignores.
      warnings.push(`no published reference for ${code}; its verdict is not published`);
      continue;
    }

    const reportUrl = reportUrlFor({ repoSlug, branch, reportPath: entry.reportPath });
    for (const target of targets) {
      // The reference is only known HERE, and so is the transform its raster was published through.
      const transform = referenceTransforms?.get(target.referenceId);
      const scoped = sets.map(({ source, ...set }) => ({
        ...rebaseSet(set, transform),
        referenceId: target.referenceId,
        ...(reportUrl ? { reportUrl } : {}),
      }));
      (previews[target.previewId] ??= []).push(...scoped);
      mapped += 1;
    }
  }

  return {
    document: Object.keys(previews).length > 0 ? { schema: FINDINGS_SCHEMA, previews } : null,
    warnings,
    mapped,
  };
}
