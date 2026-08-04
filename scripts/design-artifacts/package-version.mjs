import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";

/**
 * Resolves an installed package's version without requiring it to export
 * `./package.json`.
 *
 * Modern packages commonly expose only their public entry points. Resolve that
 * entry point first, then walk up to the owning package manifest instead of
 * depending on a package-json subpath that its exports map may reject.
 */
export function installedPackageVersion(packageName, fromUrl = import.meta.url) {
  try {
    const require = createRequire(fromUrl);
    let current = dirname(require.resolve(packageName));

    while (true) {
      try {
        const manifest = JSON.parse(
          readFileSync(join(current, "package.json"), "utf8"),
        );
        if (manifest.name === packageName) {
          return manifest.version || undefined;
        }
      } catch {
        // Keep walking: package entry points often live below dist/ or lib/.
      }

      const parent = dirname(current);
      if (parent === current) break;
      current = parent;
    }
  } catch {
    // Provenance is best-effort and must not sink catalog generation.
  }

  return undefined;
}
