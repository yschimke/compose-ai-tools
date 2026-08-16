#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { parseArgs } from "node:util";
import { servePreviewId } from "./design-references.mjs";

export const SCHEMA = "compose-preview-revision-index/v1";
export const MAX_REVISIONS = 12;

export function previewIds(catalog) {
  return [...new Set(
    (catalog?.components ?? []).flatMap((component) =>
      (component?.images ?? [])
        .map((image) => image?.path)
        .filter((path) => typeof path === "string" && /^images\/.+\.png$/.test(path))
        .map(servePreviewId),
    ),
  )].sort();
}

/**
 * Roll a branch tip's index forward. The new commit cannot name itself inside its own tree, so its
 * inventory lives under `current`; on the next publish it is promoted under the then-parent SHA.
 */
export function updateRevisionPreviewIndex(catalog, prior, parent, limit = MAX_REVISIONS) {
  const revisions = [];
  if (parent && prior?.schema === SCHEMA && Array.isArray(prior.current)) {
    revisions.push({ commit: parent, previews: [...new Set(prior.current)].sort() });
  }
  if (prior?.schema === SCHEMA && Array.isArray(prior.revisions)) {
    for (const entry of prior.revisions) {
      if (
        typeof entry?.commit !== "string" ||
        !Array.isArray(entry?.previews) ||
        revisions.some((candidate) => candidate.commit === entry.commit)
      ) continue;
      revisions.push({ commit: entry.commit, previews: [...new Set(entry.previews)].sort() });
    }
  }
  return { schema: SCHEMA, current: previewIds(catalog), revisions: revisions.slice(0, limit - 1) };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { values } = parseArgs({
    options: {
      catalog: { type: "string" },
      prior: { type: "string" },
      parent: { type: "string", default: "" },
      out: { type: "string" },
    },
  });
  if (!values.catalog || !values.out) {
    throw new Error("usage: revision-preview-index.mjs --catalog <catalog.json> [--prior <preview-index.json>] [--parent <sha>] --out <preview-index.json>");
  }
  const catalog = JSON.parse(await readFile(values.catalog, "utf8"));
  const prior = values.prior ? JSON.parse(await readFile(values.prior, "utf8")) : null;
  const index = updateRevisionPreviewIndex(catalog, prior, values.parent);
  await writeFile(values.out, `${JSON.stringify(index, null, 2)}\n`);
}
