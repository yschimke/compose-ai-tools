import * as assert from "assert";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import {
    LEGACY_HISTORY_DIRNAME,
    composeAiCacheDir,
    historyDirFor,
    historyModuleSegment,
    historyWorkspaceSlug,
} from "../historyPaths";

/**
 * Mirror of `common/io`'s `HistoryPathsTest.kt`.
 *
 * The GOLDEN vectors below are duplicated verbatim from that suite. Three implementations compute
 * this layout — Kotlin in `:common-io` (what the daemon writes through), an inlined copy in the
 * Gradle plugin (which hands the daemon `-Dcomposeai.daemon.historyDir`), and this TypeScript one
 * (which reads the archive back for the panel's FS fallback). If they drift, the daemon writes one
 * directory and the panel reads another: no crash, just a silently empty history drawer. Change
 * the layout only with both suites updated together.
 */
describe("historyPaths", () => {
    describe("workspace slug", () => {
        it("is a readable prefix plus a 12-hex digest", () => {
            assert.strictEqual(
                historyWorkspaceSlug("/home/dev/src/app"),
                "app-671822104ddc",
            );
            assert.strictEqual(
                historyWorkspaceSlug("/home/dev/src/my project"),
                "my-project-75d59f8a453b",
            );
        });

        it("ignores a trailing separator", () => {
            assert.strictEqual(
                historyWorkspaceSlug("/home/dev/src/app/"),
                historyWorkspaceSlug("/home/dev/src/app"),
            );
        });

        it("does not collide for the same basename under different parents", () => {
            assert.notStrictEqual(
                historyWorkspaceSlug("/home/dev/a/app"),
                historyWorkspaceSlug("/home/dev/b/app"),
            );
        });
    });

    describe("module segment", () => {
        it("is the workspace-relative path", () => {
            assert.strictEqual(
                historyModuleSegment("/w", "/w/auth/composables"),
                "auth/composables",
            );
        });

        it("maps the root project to _root", () => {
            assert.strictEqual(historyModuleSegment("/w", "/w"), "_root");
            assert.strictEqual(historyModuleSegment("/w", "/w/"), "_root");
        });

        it("sanitises characters outside the safe set", () => {
            // Each rewritten segment carries an 8-hex digest of its original text.
            assert.strictEqual(
                historyModuleSegment("/w", "/w/a b/c:d"),
                "a-b-c8687a08/c-d-66c7bbe2",
            );
        });

        it("keeps module names that sanitise identically distinct", () => {
            // `ui components` and `ui-components` are different modules; without the digest
            // suffix both flatten to `ui-components` and would share one history dir.
            const spaced = historyModuleSegment("/w", "/w/ui components");
            const hyphenated = historyModuleSegment("/w", "/w/ui-components");
            assert.strictEqual(spaced, "ui-components-50bae342");
            assert.strictEqual(hyphenated, "ui-components");
            assert.notStrictEqual(spaced, hyphenated);
        });

        it("leaves an already-safe segment plain", () => {
            assert.strictEqual(historyModuleSegment("/w", "/w/auth"), "auth");
        });

        it("keeps dots, underscores and hyphens", () => {
            assert.strictEqual(
                historyModuleSegment("/w", "/w/my_mod.x-1"),
                "my_mod.x-1",
            );
        });

        it("gives a module outside the workspace tree its own identity", () => {
            const a = historyModuleSegment("/w", "/elsewhere/mod");
            const b = historyModuleSegment("/w", "/other/mod");
            assert.ok(
                a.startsWith("_external-"),
                `expected an _external- segment, got '${a}'`,
            );
            assert.notStrictEqual(a, b);
        });

        it("requires a real path boundary, not a string prefix", () => {
            // "/w-other" starts with "/w" as a string but is not inside it.
            const segment = historyModuleSegment("/w", "/w-other/mod");
            assert.ok(
                segment.startsWith("_external-"),
                `expected an _external- segment, got '${segment}'`,
            );
        });
    });

    describe("historyDirFor", () => {
        let tmp: string;

        beforeEach(() => {
            tmp = fs.mkdtempSync(path.join(os.tmpdir(), "history-paths-"));
        });

        afterEach(() => {
            fs.rmSync(tmp, { recursive: true, force: true });
        });

        it("lands under the cache root", () => {
            const workspace = path.join(tmp, "ws");
            const module = path.join(workspace, "auth", "composables");
            fs.mkdirSync(module, { recursive: true });

            assert.strictEqual(
                historyDirFor(workspace, module),
                path.join(
                    composeAiCacheDir("history"),
                    historyWorkspaceSlug(workspace),
                    "auth/composables",
                ),
            );
        });

        it("never resolves inside the workspace", () => {
            const workspace = path.join(tmp, "ws");
            const module = path.join(workspace, "app");
            fs.mkdirSync(module, { recursive: true });

            const dir = historyDirFor(workspace, module);

            assert.ok(
                !dir.startsWith(workspace + path.sep),
                `history must not be written inside the workspace, got ${dir}`,
            );
        });

        it("prefers an existing legacy directory so upgrades do not strand a timeline", () => {
            const workspace = path.join(tmp, "ws");
            const module = path.join(workspace, "app");
            const legacy = path.join(module, LEGACY_HISTORY_DIRNAME);
            fs.mkdirSync(legacy, { recursive: true });

            assert.strictEqual(historyDirFor(workspace, module), legacy);
        });

        it("ignores a legacy path that is a file rather than a directory", () => {
            const workspace = path.join(tmp, "ws");
            const module = path.join(workspace, "app");
            fs.mkdirSync(module, { recursive: true });
            fs.writeFileSync(
                path.join(module, LEGACY_HISTORY_DIRNAME),
                "not a directory",
            );

            const dir = historyDirFor(workspace, module);

            assert.ok(
                !dir.startsWith(module + path.sep),
                `a stray file must not divert history back into the workspace, got ${dir}`,
            );
        });
    });
});
