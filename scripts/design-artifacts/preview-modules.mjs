#!/usr/bin/env node
/** Extract the deterministic module list from `compose-preview list --json`. */
import { readFile, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { parseArgs } from "node:util";

export function previewModules(response, preferred) {
  const modules = [...new Set((response?.previews ?? []).map((p) => p?.module).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b));
  const normalizedPreferred = preferred?.replace(/^:/, "");
  const preferredIndex = modules.findIndex(
    (module) => module.replace(/^:/, "") === normalizedPreferred,
  );
  if (normalizedPreferred && preferredIndex >= 0) {
    modules.unshift(...modules.splice(preferredIndex, 1));
  }
  return modules;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { values } = parseArgs({
    options: {
      input: { type: "string" },
      output: { type: "string" },
      preferred: { type: "string" },
    },
  });
  if (!values.input || !values.output) {
    console.error("usage: preview-modules.mjs --input <list.json> --output <modules.txt> [--preferred <:module>]");
    process.exit(2);
  }
  const modules = previewModules(JSON.parse(await readFile(values.input, "utf8")), values.preferred);
  if (modules.length === 0) {
    console.error("preview-modules: discovery returned no preview-enabled modules");
    process.exit(1);
  }
  await writeFile(values.output, `${modules.join("\n")}\n`, "utf8");
}
