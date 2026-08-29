// Fails when a committed bundle no longer matches a fresh build of `src/`.
//
// The bundles are committed so the Gradle build and the release chain stay
// node-free (see esbuild.mjs). The cost of that is files that can go stale
// silently — someone edits a module, forgets `npm run build`, and the server
// keeps serving the old behaviour while the source review says otherwise. This
// is what stops that: CI runs `npm run verify`, which rebuilds and then asserts
// the working tree is clean for the output paths.
//
// All outputs are checked. `serve-chrome.js` is the smaller and the easier to
// forget, and it is the one every page loads.
//
// Run after `npm run build`, from anywhere — paths resolve off this file.

import { execFileSync } from "node:child_process";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, relative, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..", "..");
const packageRoot = resolve(here, "..");
const assets = resolve(
    here,
    "..",
    "..",
    "serve/src/main/resources/ee/schimke/composeai/cli/serve/assets",
);
const tracked = [
    "serve-components.js",
    "serve-chrome.js",
    "keyboard-navigation.js",
    "report-capture.js",
    "format-compare.js",
    "known-differences.js",
    "viewer.js",
].map((name) => relative(repoRoot, resolve(assets, name)));

// The migration is atomic. Keep that property explicit so a future component
// cannot quietly restore the old runtime and make production pay for both.
const packageJson = JSON.parse(
    readFileSync(resolve(packageRoot, "package.json"), "utf8"),
);
const dependencySections = [
    packageJson.dependencies ?? {},
    packageJson.devDependencies ?? {},
];
if (dependencySections.some((section) => Object.hasOwn(section, "lit"))) {
    console.error("Lit must not be present in serve-web dependencies.");
    process.exit(1);
}

const sourceRoot = resolve(packageRoot, "src");
const sourceFiles = [];
const collectSources = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const path = resolve(directory, entry.name);
        if (entry.isDirectory()) collectSources(path);
        else if (entry.name.endsWith(".ts")) sourceFiles.push(path);
    }
};
collectSources(sourceRoot);
const litSources = sourceFiles.filter((path) => {
    const source = readFileSync(path, "utf8");
    return /(?:from|import)\s*["']lit(?:\/|["'])|\bLitElement\b/.test(source);
});
if (litSources.length) {
    console.error(
        `Lit must not be imported by serve-web source:\n${litSources
            .map((path) => relative(packageRoot, path))
            .join("\n")}`,
    );
    process.exit(1);
}

const status = execFileSync(
    "git",
    ["status", "--porcelain", "--", ...tracked],
    { cwd: repoRoot, encoding: "utf8" },
).trim();

if (status) {
    const untracked = status.split("\n").some((l) => l.trim().startsWith("??"));
    console.error(
        `\n${tracked.join(", ")} are not in sync with cli/serve-web/src.\n\n` +
            (untracked
                ? "A bundle a fresh build produced is not committed at all — `git add` it.\n"
                : "A committed bundle differs from what a fresh build produces. This run has\n" +
                  "already rebuilt it, so commit the result.\n") +
            `\ngit status said:\n${status}\n`,
    );
    process.exit(1);
}

console.log(`${tracked.join(", ")} are up to date.`);
