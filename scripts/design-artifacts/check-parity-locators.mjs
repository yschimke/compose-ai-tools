#!/usr/bin/env node

/**
 * Does every parity locator still name a cell the catalog draws?
 *
 * `parity-issues.mjs` validates a locator's SHAPE — required fields, non-empty values, no component
 * or preview claimed twice in one body. Nothing has ever validated what the `preview` field points
 * AT, because the producer runs against an issue list and has no catalog to resolve it with. So a
 * renamed cell turns an issue's locator into a dangling pointer and every check stays green:
 * `buildIssueIndex` emits the row, the delivery branch carries it, and the compare page simply
 * never shows it, because no preview on any page has that id any more.
 *
 * That is the same place an issue with NO locator lands — skipped, silently, correctly — which is
 * what makes it invisible. It has happened twice on one repository from one rename
 * ([wear-m3-catalog#305](https://github.com/yschimke/wear-m3-catalog/issues/305)), and the second
 * one was found only because somebody distrusted a clean result from the first audit.
 *
 * ## What it resolves against, and why not the preview id
 *
 * A locator's `preview` is a SERVED id (`appcard__ideal__outlined__compact`) minted by the render
 * pipeline out of `catalog.json` — see `revision-preview-index.mjs`. A pull request has no
 * `catalog.json`: it has the discovered `previews.json`, whose ids are fully-qualified Kotlin
 * (`…CatalogPreviewsKt.AppCardRemote_width=227dp,…_VARIANT_outlined`). Reconstructing one from the
 * other would mean re-implementing the pipeline's naming in a checker, which is exactly the kind of
 * fork that lets a validator and its producer drift apart.
 *
 * So this resolves the pair the two forms genuinely share: the **component id** and the **cell**.
 * `component:` is a locator field, and the cell is the served id's third segment — the same segment
 * the sheet spells `_VARIANT_<cell>` on the manifest side. A rename moves the cell on both sides at
 * once, which is the whole failure this catches, and neither side has to know how the other builds
 * a string.
 *
 * ## Why a baseline, rather than "every locator must resolve"
 *
 * A blanket assertion fails pull requests that had nothing to do with the breakage: one issue
 * edited to a bad id and every subsequent PR is red, with the fix nowhere in its diff. Given
 * `--published` — the delivery branch's `preview-index.json`, which is what the catalog last
 * actually served — an unresolvable id splits in two:
 *
 * - it **was** served and is not drawn now: this change broke it. A failure, and the PR that
 *   renamed the cell is the one holding it.
 * - it was never served: a typo, or breakage that predates the baseline. Reported as a warning,
 *   because failing here would punish the wrong commit.
 *
 * Without `--published` every unresolvable id is a failure, which is what a scheduled or
 * publish-side run wants: there, nothing is "somebody else's PR".
 */

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { parseLocators } from "./parity-issues.mjs";

/** The cell a served preview id names, or null when the id carries no cell segment. */
export function cellOfPreviewId(previewId) {
  const segments = String(previewId ?? "").split("__");
  // `<component-slug>__<theme>__<cell>[__<breakpoint>]`. Two segments is a themed component with no
  // cell axis at all, which no locator should carry — it would name the component, not a variant.
  return segments.length >= 3 && segments[2] ? segments[2] : null;
}

/**
 * `componentId -> Set<cell>` for one discovered `previews.json`.
 *
 * A preview with no `_VARIANT_` suffix is the component's base render, which every sheet spells
 * `default` in a served id — so the two vocabularies meet on that word rather than on an absence.
 */
export function cellsFromManifest(manifest) {
  const cells = new Map();
  for (const preview of manifest?.previews ?? []) {
    const componentId = preview?.catalog?.componentId;
    if (!componentId) continue;
    const id = String(preview.id ?? "");
    const marker = id.indexOf("_VARIANT_");
    const cell = marker < 0 ? "default" : id.slice(marker + "_VARIANT_".length);
    if (!cells.has(componentId)) cells.set(componentId, new Set());
    cells.get(componentId).add(cell);
  }
  return cells;
}

/** Levenshtein distance, capped — only ever run against one component's cell list. */
function distance(a, b) {
  const rows = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)]);
  for (let j = 0; j <= b.length; j++) rows[0][j] = j;
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      rows[i][j] = Math.min(
        rows[i - 1][j] + 1,
        rows[i][j - 1] + 1,
        rows[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1),
      );
    }
  }
  return rows[a.length][b.length];
}

/** Jaccard overlap of two cells' hyphen-separated segments. */
function overlap(a, b) {
  const left = new Set(a.split("-"));
  const right = new Set(b.split("-"));
  const shared = [...left].filter((token) => right.has(token)).length;
  return shared / (left.size + right.size - shared);
}

/**
 * The cells this one was most likely renamed from, nearest first.
 *
 * A failure has two very different fixes — a rename means edit the issue, a typo means fix the id —
 * and the message can only tell them apart by showing what the component DOES draw.
 *
 * TWO measures, because the renames that break locators come in two shapes and neither measure
 * sees both. A respelling (`outline` -> `outlined`) is one edit away and nothing like it in tokens.
 * A REORDERING (`outline-icon-gallery-2` -> `icon-outlined-gallery-2`, which is the rename that
 * actually broke two issues) shares three segments of four and is ten edits away — well past any
 * edit-distance cap loose enough to be useful. Scoring either as near catches both; requiring both
 * would catch neither.
 */
export function nearestCells(cell, candidates, limit = 2) {
  const cap = Math.max(2, Math.ceil(cell.length / 3));
  return [...candidates]
    .map((candidate) => ({
      candidate,
      edits: distance(cell, candidate),
      shared: overlap(cell, candidate),
    }))
    .filter(({ edits, shared }) => edits <= cap || shared >= 0.5)
    .sort(
      (a, b) => b.shared - a.shared || a.edits - b.edits || a.candidate.localeCompare(b.candidate),
    )
    .slice(0, limit)
    .map(({ candidate }) => candidate);
}

/**
 * Resolve every locator in [issues] against the per-system cell inventories in [manifests].
 *
 * [published] is optional and keyed the same way; see the note on the baseline above. A locator
 * naming a system with no manifest is counted in `skipped` rather than assumed good: one repository
 * can file issues against a system another repository builds.
 */
export function checkLocators({ issues = [], manifests = new Map(), published = new Map() } = {}) {
  const failures = [];
  const warnings = [];
  let checked = 0;
  let skipped = 0;
  let unverified = 0;
  for (const issue of issues) {
    const parsed = parseLocators(issue?.body);
    // A body with no locator, or a damaged one, is `emit-parity-issues.mjs`'s to report. Saying it
    // twice in two jobs trains readers to ignore both.
    if (!parsed.ok) continue;
    for (const locator of parsed.locators) {
      const cells = manifests.get(locator.system);
      if (!cells) {
        skipped++;
        continue;
      }
      const servedBefore = published.get(locator.system);
      for (const [field, id] of [
        ["preview", locator.previewId],
        ["reference", locator.referenceId],
      ]) {
        // The two are the same string on every locator written by the report form, so checking both
        // costs nothing and covers a hand-written body that pins them apart.
        if (field === "reference" && id === locator.previewId) continue;
        const cell = cellOfPreviewId(id);
        const drawn = cells.get(locator.component);
        const row = {
          number: issue?.number ?? null,
          system: locator.system,
          component: locator.component,
          field,
          id,
        };
        // A component this manifest has never heard of is UNVERIFIABLE here, not broken. A sheet can
        // publish a component from one source set and not another — `:remote-catalog` builds
        // `CheckboxButton` and `EdgeButton` on its snapshot lane only — and a run discovers one lane
        // at a time, so the released-lane manifest is legitimately missing components the delivery
        // branch serves. Failing on that would redden every pull request for a lane it did not
        // build. Said out loud rather than dropped, because the same shape covers a component that
        // really was deleted.
        if (!drawn) {
          unverified++;
          warnings.push({
            ...row,
            message:
              `no component "${locator.component}" in the ${locator.system} manifest — not built ` +
              "on the discovered lane, or removed",
          });
          continue;
        }
        checked++;
        if (cell && drawn.has(cell)) continue;
        const near = cell ? nearestCells(cell, drawn) : [];
        const hint = near.length ? ` — did you mean ${near.map((c) => `"${c}"`).join(" or ")}?` : "";
        const why = cell
          ? `${locator.component} draws no cell "${cell}"`
          : `"${id}" carries no cell segment`;
        // Served once and not drawn now: whatever is in this working tree removed it.
        if (!servedBefore || servedBefore.has(id)) failures.push({ ...row, message: `${why}${hint}` });
        else warnings.push({ ...row, message: `${why}${hint}` });
      }
    }
  }
  return { failures, warnings, checked, skipped, unverified };
}

/** `--flag system=path` pairs, as a Map. */
function pairs(values, load) {
  const map = new Map();
  for (const value of values ?? []) {
    const split = value.indexOf("=");
    if (split <= 0) throw new Error(`expected <system>=<path>, got "${value}"`);
    map.set(value.slice(0, split), load(value.slice(split + 1)));
  }
  return map;
}

const readJson = (path) => JSON.parse(readFileSync(path, "utf8"));

if (import.meta.url === `file://${process.argv[1]}`) {
  const { values } = parseArgs({
    options: {
      repo: { type: "string", multiple: true },
      manifest: { type: "string", multiple: true },
      published: { type: "string", multiple: true },
    },
  });
  if (!values.repo?.length || !values.manifest?.length) {
    console.error(
      "usage: check-parity-locators.mjs --repo <owner/name> [--repo ...]\n" +
        "         --manifest <system>=<previews.json> [--manifest ...]\n" +
        "         [--published <system>=<preview-index.json> ...]",
    );
    process.exit(2);
  }
  const manifests = pairs(values.manifest, (path) => cellsFromManifest(readJson(path)));
  const published = pairs(values.published, (path) => new Set(readJson(path)?.current ?? []));

  const issues = [];
  for (const repo of values.repo) {
    const raw = execFileSync(
      "gh",
      ["api", "--paginate", "--slurp", `repos/${repo}/issues?state=all&per_page=100`],
      { encoding: "utf8", maxBuffer: 25 * 1024 * 1024 },
    );
    issues.push(...JSON.parse(raw).flat().filter((issue) => !issue.pull_request));
  }

  const { failures, warnings, checked, skipped, unverified } = checkLocators({ issues, manifests, published });
  for (const row of warnings) {
    console.error(
      `::warning::parity-locators: #${row.number}: ${row.field} ${row.id} does not resolve, and was ` +
        `not served at the baseline either — ${row.message}`,
    );
  }
  for (const row of failures) {
    console.error(`::error::parity-locators: #${row.number}: ${row.field} ${row.id} — ${row.message}`);
  }
  console.log(
    `parity-locators: checked ${checked} id(s); ${failures.length} broken, ` +
      `${warnings.length - unverified} pre-existing, ${unverified} on components this lane does ` +
      `not build, ${skipped} for systems not built here`,
  );
  if (failures.length) {
    console.error(
      "\nA locator names a cell this catalog no longer draws. If the cell was RENAMED, update the " +
        "`preview` and `reference` fields (and the `variant` line) in the issue body to the new " +
        "name; the issue is otherwise dropped from the compare page in silence.",
    );
    process.exitCode = 1;
  }
}
