#!/usr/bin/env node
/** Extract the deterministic module list from `compose-preview list --json`. */
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { parseArgs } from "node:util";

export function previewModuleRecords(response, preferred) {
  const byModule = new Map();
  for (const preview of response?.previews ?? []) {
    if (!preview?.module) continue;
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

export function previewModules(response, preferred) {
  return previewModuleRecords(response, preferred).map((record) => record.module);
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
    },
  });
  if (!values.input || !values.output) {
    console.error(
      "usage: preview-modules.mjs --input <list.json> --output <modules.txt> " +
        "[--sources-output <project-directories.txt>] [--preferred <:module>]",
    );
    process.exit(2);
  }
  const response = JSON.parse(await readFile(values.input, "utf8"));
  const records = previewModuleRecords(response, values.preferred);
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
