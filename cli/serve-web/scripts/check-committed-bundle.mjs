// Fails when the committed bundle no longer matches a fresh build of `src/`.
//
// The bundle is committed so the Gradle build and the release chain stay
// node-free (see esbuild.mjs). The cost of that is a file that can go stale
// silently — someone edits a component, forgets `npm run build`, and the server
// keeps serving the old behaviour while the source review says otherwise. This is
// what stops that: CI runs `npm run verify`, which rebuilds and then asserts the
// working tree is clean for the output path.
//
// Run after `npm run build`, from anywhere — paths resolve off this file.

import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, relative, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..", "..");
const bundle = resolve(
    here,
    "..",
    "..",
    "src/main/resources/ee/schimke/composeai/cli/serve/assets/serve-components.js",
);
const tracked = relative(repoRoot, bundle);

const status = execFileSync("git", ["status", "--porcelain", "--", tracked], {
    cwd: repoRoot,
    encoding: "utf8",
}).trim();

if (status) {
    const untracked = status.startsWith("??");
    console.error(
        `\n${tracked} is not in sync with cli/serve-web/src.\n\n` +
            (untracked
                ? "The bundle a fresh build produced is not committed at all — `git add` it.\n"
                : "The committed bundle differs from what a fresh build produces. This run has\n" +
                  "already rebuilt it, so commit the result.\n") +
            `\ngit status said: ${status}\n`,
    );
    process.exit(1);
}

console.log(`${tracked} is up to date.`);
