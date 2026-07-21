// Filesystem helpers for the catalog-spec CLIs (validate / init). Kept out of
// catalog-spec.mjs so that module stays fs-free and unit-testable without disk.

import { readdir, readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, isAbsolute, resolve, dirname } from "node:path";

/** Convert a Gradle module path (`:app`, `samples:design-catalog-m3`) to a
 *  repo-relative filesystem directory (`app`, `samples/design-catalog-m3`). */
export function moduleToDir(gradlePath) {
  return gradlePath.replace(/^:/, "").split(":").join("/");
}

/**
 * Resolve the source directories to scan for a spec. Preference order:
 *   1. explicit `srcDirs` (from --src / --module-dir),
 *   2. else derive from `spec.module`, resolved against the spec's own dir then cwd.
 * Returns absolute directories that exist. Empty ⇒ caller runs structural-only.
 */
export function resolveSourceDirs({ srcDirs, spec, specPath }) {
  const out = [];
  const add = (p) => {
    const abs = isAbsolute(p) ? p : resolve(p);
    if (existsSync(abs) && !out.includes(abs)) out.push(abs);
  };
  if (srcDirs && srcDirs.length) {
    srcDirs.forEach(add);
    return out;
  }
  if (spec?.module) {
    const rel = moduleToDir(spec.module);
    const bases = specPath ? [dirname(resolve(specPath)), process.cwd()] : [process.cwd()];
    for (const base of bases) {
      const cand = join(base, rel);
      if (existsSync(cand)) {
        add(cand);
        break;
      }
    }
  }
  return out;
}

/** Recursively collect `.kt` file contents under the given directories.
 *  Skips `build/` and hidden dirs. */
export async function collectKotlinSources(dirs) {
  const sources = [];
  const seen = new Set();
  async function walk(dir) {
    let entries;
    try {
      entries = await readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const full = join(dir, e.name);
      if (e.isDirectory()) {
        if (e.name === "build" || e.name.startsWith(".")) continue;
        await walk(full);
      } else if (e.isFile() && e.name.endsWith(".kt") && !seen.has(full)) {
        seen.add(full);
        sources.push(await readFile(full, "utf8"));
      }
    }
  }
  for (const d of dirs) await walk(d);
  return sources;
}
