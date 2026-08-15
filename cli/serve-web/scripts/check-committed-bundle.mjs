// Fails when a committed bundle no longer matches a fresh build of `src/`.
//
// The bundles are committed so the Gradle build and the release chain stay
// node-free (see esbuild.mjs). The cost of that is files that can go stale
// silently — someone edits a module, forgets `npm run build`, and the server
// keeps serving the old behaviour while the source review says otherwise. This
// is what stops that: CI runs `npm run verify`, which rebuilds and then asserts
// the working tree is clean for the output paths.
//
// BOTH outputs are checked. `serve-chrome.js` is the smaller and the easier to
// forget, and it is the one every page loads.
//
// Run after `npm run build`, from anywhere — paths resolve off this file.

import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, relative, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..", "..");
const assets = resolve(
    here,
    "..",
    "..",
    "src/main/resources/ee/schimke/composeai/cli/serve/assets",
);
const tracked = ["serve-components.js", "serve-chrome.js"].map((name) =>
    relative(repoRoot, resolve(assets, name)),
);

const status = execFileSync(
    "git",
    ["status", "--porcelain", "--", ...tracked],
    { cwd: repoRoot, encoding: "utf8" },
).trim();

if (status) {
    const untracked = status.split("\n").some((l) => l.trim().startsWith("??"));
    console.error(
        `\n${tracked.join(" and ")} is not in sync with cli/serve-web/src.\n\n` +
            (untracked
                ? "A bundle a fresh build produced is not committed at all — `git add` it.\n"
                : "A committed bundle differs from what a fresh build produces. This run has\n" +
                  "already rebuilt it, so commit the result.\n") +
            `\ngit status said:\n${status}\n`,
    );
    process.exit(1);
}

console.log(`${tracked.join(", ")} up to date.`);
