#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

function die(message) {
  console.error(`error: ${message}`);
  process.exit(1);
}

function filesWith(dir, extension) {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(extension))
    .map((entry) => entry.name.slice(0, -extension.length))
    .sort();
}

function difference(left, right) {
  const other = new Set(right);
  return left.filter((value) => !other.has(value));
}

function manifestIds(inputDir) {
  const manifestPath = path.join(inputDir, "manifest.json");
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  } catch (error) {
    die(`cannot read ${manifestPath}: ${error.message}`);
  }
  if (!Array.isArray(manifest) || manifest.length === 0)
    die("manifest.json must contain at least one document");
  const ids = manifest.map((entry) => entry?.id);
  if (ids.some((id) => typeof id !== "string" || id.length === 0))
    die("every manifest entry must have a non-empty string id");
  if (new Set(ids).size !== ids.length)
    die("manifest.json contains duplicate ids");
  return ids.sort();
}

function validateInputs(inputDir, ids) {
  const inputs = filesWith(inputDir, ".rc");
  const missing = difference(ids, inputs);
  const extra = difference(inputs, ids);
  if (missing.length || extra.length) {
    die(
      `staged .rc files do not match manifest` +
        (missing.length ? `; missing: ${missing.join(", ")}` : "") +
        (extra.length ? `; extra: ${extra.join(", ")}` : ""),
    );
  }
  console.log(`inputs: ${ids.length}/${ids.length} manifest documents`);
}

function validateLane(lanesDir, lane, ids) {
  const dir = path.join(lanesDir, lane);
  const pngs = filesWith(dir, ".png");
  const errors = filesWith(dir, ".error");
  const known = new Set(ids);
  const extras = [...pngs, ...errors].filter((id) => !known.has(id));
  if (extras.length)
    die(`${lane} has results outside the manifest: ${extras.join(", ")}`);

  for (const id of ids) {
    const hasPng = pngs.includes(id);
    const hasError = errors.includes(id);
    if (lane === "view" && hasError) die(`view failed to render ${id}`);
    if (Number(hasPng) + Number(hasError) !== 1)
      die(
        `${lane} must have exactly one result for ${id}; found ` +
          `${hasPng ? "png" : "no png"} and ${hasError ? "error" : "no error"}`,
      );
  }
  console.log(
    `    ${lane}: ${pngs.length}/${ids.length} png, ${errors.length} error`,
  );
}

const [mode, inputDir, lanesDir] = process.argv.slice(2);
if (
  (mode !== "inputs" && mode !== "results") ||
  !inputDir ||
  (mode === "results" && !lanesDir)
) {
  die(
    "usage: validate-stage.mjs inputs <input-dir> | results <input-dir> <lanes-dir>",
  );
}
const ids = manifestIds(inputDir);
validateInputs(inputDir, ids);
if (mode === "results") {
  validateLane(lanesDir, "view", ids);
  validateLane(lanesDir, "embedded", ids);
}
