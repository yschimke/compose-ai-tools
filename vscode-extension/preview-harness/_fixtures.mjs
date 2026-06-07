// Fixture discovery + loading, shared by the snapshot and contract specs.
// Synchronous on purpose: Playwright's test files register `test(...)`
// cases at module-eval time, so the fixture list has to be available
// before any `await`.

import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { readdirSync, readFileSync } from "node:fs";

const harnessDir = dirname(fileURLToPath(import.meta.url));
export const fixturesDir = resolve(harnessDir, "fixtures");

/**
 * Names (without `.json`) of every fixture, honouring the optional
 * `HARNESS_FIXTURE` env override so `HARNESS_FIXTURE=grid-default npm run
 * harness:snapshot` still narrows to one — the moral equivalent of the
 * old `--fixture` flag, which `playwright test` can't forward.
 */
export function listFixtures() {
    const only = process.env.HARNESS_FIXTURE;
    if (only) return [only];
    return readdirSync(fixturesDir)
        .filter((e) => e.endsWith(".json"))
        .map((e) => e.replace(/\.json$/, ""));
}

/** Themes to capture, honouring the optional `HARNESS_THEME` override. */
export function listThemes() {
    const only = process.env.HARNESS_THEME;
    return only ? [only] : ["dark", "light"];
}

export function loadFixture(name) {
    return JSON.parse(
        readFileSync(resolve(fixturesDir, name + ".json"), "utf8"),
    );
}
