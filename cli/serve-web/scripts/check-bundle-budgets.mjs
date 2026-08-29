// Pins the browser cost of each representative server surface. The budgets include every generated
// asset that page needs for its controls, not CSS or the small site-wide chrome/keyboard scripts.

import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const here = dirname(fileURLToPath(import.meta.url));
const assets = resolve(
    here,
    "../../serve/src/main/resources/ee/schimke/composeai/cli/serve/assets",
);
const runtime = "vue-runtime.js";
const pages = {
    catalog: [runtime, "catalog-components.js"],
    "compare wall": [runtime, "compare-components.js", "format-compare.js"],
    "focused comparison": [
        runtime,
        "compare-components.js",
        "format-compare.js",
        "known-differences.js",
    ],
    "design page": [runtime, "design-components.js", "format-compare.js"],
    parity: [
        runtime,
        "parity-components.js",
        "format-compare.js",
        "known-differences.js",
    ],
    viewer: [runtime, "viewer-components.js", "format-compare.js", "viewer.js"],
};
const limits = {
    catalog: 30_000,
    "compare wall": 50_000,
    "focused comparison": 70_000,
    "design page": 44_000,
    parity: 52_000,
    viewer: 68_000,
};

const gzipBytes = new Map();
for (const names of Object.values(pages)) {
    for (const name of names) {
        if (!gzipBytes.has(name)) {
            gzipBytes.set(
                name,
                gzipSync(readFileSync(resolve(assets, name)), { level: 9 })
                    .length,
            );
        }
    }
}

let failed = false;
console.log("\nserve-web page bundles (gzip):");
for (const [page, names] of Object.entries(pages)) {
    const bytes = names.reduce((sum, name) => sum + gzipBytes.get(name), 0);
    const limit = limits[page];
    const status = bytes <= limit ? "ok" : "OVER";
    console.log(
        `${page.padEnd(20)} ${String(bytes).padStart(7)} / ${String(limit).padStart(7)} bytes  ${status}`,
    );
    failed ||= bytes > limit;
}

if (failed) {
    console.error(
        "\nA page bundle exceeded its budget. Split or trim the responsible surface, or update the " +
            "budget with measured justification.",
    );
    process.exit(1);
}
