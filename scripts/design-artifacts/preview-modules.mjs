#!/usr/bin/env node
/** Extract the deterministic module list from `compose-preview list --json`. */
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { parseArgs } from "node:util";

/**
 * Ids a module can hold without counting as preview-enabled.
 *
 * An IMPORT renders composables, never the app, so the synthetic activity / app-tour previews are
 * excluded at render time. A module whose previews are ALL synthetic then has its entire set
 * excluded, and `excludePreviewIds` throws rather than render nothing — sinking the whole sweep on
 * a module nobody wanted. That shape is the common one, not a corner: an Android application
 * module with activities but no authored `@Preview` is what you get whenever the previews live in
 * feature or design modules.
 *
 * So such a module must not reach the render at all. Prefixes rather than globs: these ids are
 * built by `AppTourDiscovery` as `"activity__" + simpleName` and `"apptour__" + name`, and this is
 * our own code rather than the CLI's glob flag, so it can match exactly.
 *
 * Empty (the first-party default) keeps every module, including activity-only ones — their hero
 * captures are the point there.
 */
export function previewModuleRecords(response, preferred, ignoreIdPrefixes = []) {
  const ignored = ignoreIdPrefixes.filter((prefix) => prefix.length > 0);
  const byModule = new Map();
  for (const preview of response?.previews ?? []) {
    if (!preview?.module) continue;
    if (ignored.length > 0 && ignored.some((prefix) => String(preview.id ?? "").startsWith(prefix))) {
      continue;
    }
    const existing = byModule.get(preview.module);
    if (!existing?.projectDirectory || preview.projectDirectory) {
      byModule.set(preview.module, {
        module: preview.module,
        projectDirectory: preview.projectDirectory,
      });
    }
  }
  const records = [...byModule.values()].sort((a, b) => a.module.localeCompare(b.module));
  const normalizedPreferred = preferred?.replace(/^:/, "");
  const preferredIndex = records.findIndex(
    (record) => record.module.replace(/^:/, "") === normalizedPreferred,
  );
  if (normalizedPreferred && preferredIndex >= 0) {
    records.unshift(...records.splice(preferredIndex, 1));
  }
  return records;
}

export function previewModules(response, preferred, ignoreIdPrefixes = []) {
  return previewModuleRecords(response, preferred, ignoreIdPrefixes).map((record) => record.module);
}

export function previewModuleSources(records, baseDirectory = process.cwd()) {
  return records.map((record) =>
    resolve(
      baseDirectory,
      record.projectDirectory ?? record.module.replace(/^:/, "").replaceAll(":", "/"),
    ),
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { values } = parseArgs({
    options: {
      input: { type: "string" },
      output: { type: "string" },
      "sources-output": { type: "string" },
      preferred: { type: "string" },
      "ignore-id-prefix": { type: "string" },
    },
  });
  if (!values.input || !values.output) {
    console.error(
      "usage: preview-modules.mjs --input <list.json> --output <modules.txt> " +
        "[--sources-output <project-directories.txt>] [--preferred <:module>] " +
        "[--ignore-id-prefix <prefix,prefix>]",
    );
    process.exit(2);
  }
  const response = JSON.parse(await readFile(values.input, "utf8"));
  const ignoreIdPrefixes = (values["ignore-id-prefix"] ?? "")
    .split(",")
    .map((prefix) => prefix.trim())
    .filter((prefix) => prefix.length > 0);
  const records = previewModuleRecords(response, values.preferred, ignoreIdPrefixes);
  const modules = records.map((record) => record.module);
  if (modules.length === 0) {
    console.error("preview-modules: discovery returned no preview-enabled modules");
    process.exit(1);
  }
  await writeFile(values.output, `${modules.join("\n")}\n`, "utf8");
  if (values["sources-output"]) {
    const missing = records.filter((record) => !record.projectDirectory);
    if (missing.length > 0) {
      console.warn(
        `preview-modules: discovery omitted projectDirectory for ${missing.map((r) => r.module).join(", ")}; ` +
          "falling back to the conventional Gradle-path directory until the caller upgrades its CLI",
      );
    }
    await writeFile(
      values["sources-output"],
      `${previewModuleSources(records).join("\n")}\n`,
      "utf8",
    );
  }
}
