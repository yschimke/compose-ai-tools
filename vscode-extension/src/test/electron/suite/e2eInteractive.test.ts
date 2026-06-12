import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import type { ComposePreviewTestApi } from "../../../extension";
import { RealGradleApi } from "../realGradleApi";

/**
 * Exploratory scenario driver targeting the failure modes reported as
 * "inconsistencies when moving between modules, weird preview states,
 * edits not updating". Sits alongside `e2e.test.ts` (single render-and-assert
 * smoke) and exercises the production loop the user actually hits.
 *
 * Implemented scenarios (this file):
 *
 *   A. Module switch: render `:samples:cmp`, then `:samples:android`, then back.
 *      Asserts that successive `setPreviews` messages don't leak previews
 *      from the previous module and that referenced render PNGs exist on disk.
 *      Catches: "wrong-module previews after switch" and dangling renderOutputs.
 *
 *   B. Edit-save-render: add a `@Preview` to a Kotlin source, refresh, expect
 *      the new id in the next setPreviews; revert, refresh, expect it gone.
 *      Catches: "edits not updating" at the panel layer — Gradle-level
 *      reverts go FROM-CACHE / UP-TO-DATE, so the bug usually lives in the
 *      extension's manifest re-read or webview diff, not the renderer.
 *
 *   C. Rapid refresh coalescing: fire several `triggerRefresh` calls in
 *      quick succession against the same module and confirm convergence
 *      and that every final-state capture resolves to an on-disk PNG.
 *
 *   D. Race: start a render, immediately switch modules. The final state
 *      must belong to the new module — no leftover stale previews from
 *      the cancelled refresh.
 *
 *   E. Rename an existing `@Preview` (`RedBoxPreview` → `RedBoxPreviewRenamed`),
 *      refresh. Asserts the old function-name id is gone from `setPreviews`,
 *      the new one is present, the new render PNG is on disk and the old one
 *      isn't, and a follow-up refresh + module round-trip never resurrects the
 *      stale id (proving `previewModuleIndex` was pruned, not just hidden).
 *
 *   F. Drop the editor scope to nothing (the panel's sticky `.kt` is no longer
 *      open) and confirm the panel resolves to its empty state — `clearAll`
 *      plus the "Open a Kotlin source file…" message — rather than holding the
 *      previous module's stale cards. Regression for #1566 ("after closing the
 *      last editor, left seeing stale previews"). Driven through the
 *      `triggerEditorScopeChange(null)` test hook, which runs the same
 *      focus-lost teardown the production focus handlers do.
 *
 *   G. Compile-error path: introduce a deliberate Kotlin syntax error in
 *      `Previews.kt`, refresh, expect the compile-error banner to populate
 *      (`setCompileErrors`) AND the previous cards to survive (no `clearAll` —
 *      they stay visible, just marked loading/stale). Then fix the error,
 *      refresh, expect `clearCompileErrors` + a clean `setPreviews` back at the
 *      pre-edit baseline with no stuck error overlay.
 *
 * Follow-ups worth adding (need either small test-API extensions or extra
 * sample state):
 *
 *   H. Override merge (regression for closed #1441 (a)): seed a remote-compose
 *      override with two named values, edit one through the live panel,
 *      assert the other still resolves. Requires a `triggerSetOverride`
 *      hook on the test API.
 *
 *   I. Daemon-down recovery: kill the daemon JVM mid-session (writes to
 *      `daemon-launch.json` are sticky), trigger refresh, expect the panel
 *      to re-spawn rather than wedge. Catches the "stale daemon-launch
 *      descriptor" risk the e2eExternal suite documents but never provokes.
 *
 *   J. Kotlin IC stuck state (issue #1493): provoke
 *      "Storage for [...] is already registered" by overlapping a terminal
 *      `./gradlew :samples:cmp:composePreviewCompile` with an extension
 *      refresh, then confirm whether the panel reflects the latest edit
 *      or a stale class. This is the only scenario that requires forking
 *      an out-of-band `gradlew`, which the test API doesn't expose; the
 *      bundled `scripts/repro-panel-state.sh` is the dev-loop tool today.
 *
 * Every scenario also dumps a JSON transcript under
 * `<repoRoot>/build/interactive-e2e/<scenarioName>.json` for post-mortems.
 *
 * Gated on `COMPOSE_PREVIEW_E2E=1` (same gate as `e2e.test.ts`).
 */

const E2E = process.env.COMPOSE_PREVIEW_E2E === "1";
const describeE2E = E2E ? describe : describe.skip;

interface PostedMessage {
    command: string;
    [key: string]: unknown;
}

interface CaptureRecord {
    renderOutput: string;
    [key: string]: unknown;
}

interface PreviewRecord {
    id: string;
    sourceFile?: string | null;
    captures?: CaptureRecord[];
    referenced?: boolean;
    [key: string]: unknown;
}

interface SetPreviewsMessage {
    previews: PreviewRecord[];
    moduleDir: string;
}

async function waitFor<T>(
    description: string,
    timeoutMs: number,
    pollMs: number,
    probe: () => T | undefined,
): Promise<T> {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
        const value = probe();
        if (value !== undefined) {
            return value;
        }
        await new Promise((r) => setTimeout(r, pollMs));
    }
    throw new Error(
        `Timed out after ${timeoutMs}ms waiting for: ${description}`,
    );
}

function latestSetPreviewsMatching(
    messages: unknown[],
    predicate: (msg: SetPreviewsMessage) => boolean,
): SetPreviewsMessage | undefined {
    for (let i = messages.length - 1; i >= 0; i--) {
        const m = messages[i] as PostedMessage;
        if (m.command !== "setPreviews") continue;
        const previews = m.previews as PreviewRecord[] | undefined;
        if (!previews) continue;
        const candidate: SetPreviewsMessage = {
            previews,
            moduleDir: String(m.moduleDir ?? ""),
        };
        if (predicate(candidate)) return candidate;
    }
    return undefined;
}

function nonEmptyForModule(
    moduleDirNeedle: string,
): (msg: SetPreviewsMessage) => boolean {
    return (msg) =>
        msg.previews.length > 0 && msg.moduleDir.includes(moduleDirNeedle);
}

function fullRenderPath(
    repoRoot: string,
    moduleDir: string,
    output: string,
): string {
    if (path.isAbsolute(output)) return output;
    // `moduleDir` (the `setPreviews.moduleDir` field) is the module's
    // `projectDir` — a path *relative to the workspace root*, e.g.
    // `samples/cmp`. For multi-module aggregations it's a comma-joined list;
    // the test never hits that path because we trigger refreshes against a
    // single file.
    const first = moduleDir.split(",")[0] ?? moduleDir;
    // `capture.renderOutput` is relative to the module's
    // `build/compose-previews/` dir (e.g. `renders/Foo.png`). Resolve it the
    // same way the extension does in `GradleService.readPreviewImage`:
    // `<workspaceRoot>/<projectDir>/build/compose-previews/<renderOutput>`.
    return path.join(repoRoot, first, "build", "compose-previews", output);
}

function dumpTranscript(
    repoRoot: string,
    scenarioName: string,
    body: unknown,
): void {
    const dir = path.join(repoRoot, "build", "interactive-e2e");
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(
        path.join(dir, `${scenarioName}.json`),
        JSON.stringify(body, null, 2),
    );
}

describeE2E("Compose Preview interactive scenarios (real Gradle)", function () {
    this.timeout(30 * 60_000);

    let api: ComposePreviewTestApi;
    let repoRoot: string;
    let cmpFile: string;
    let androidFile: string;

    before(async () => {
        const folders = vscode.workspace.workspaceFolders;
        assert.ok(folders && folders.length > 0, "workspace must be open");
        repoRoot = folders[0].uri.fsPath;
        cmpFile = path.join(
            repoRoot,
            "samples",
            "cmp",
            "src",
            "main",
            "kotlin",
            "com",
            "example",
            "samplecmp",
            "Previews.kt",
        );
        androidFile = path.join(
            repoRoot,
            "samples",
            "android",
            "src",
            "main",
            "kotlin",
            "com",
            "example",
            "sampleandroid",
            "TypographyGallery.kt",
        );
        assert.ok(
            fs.existsSync(cmpFile),
            `expected cmp fixture file at ${cmpFile}`,
        );
        assert.ok(
            fs.existsSync(androidFile),
            `expected android fixture file at ${androidFile}`,
        );

        const ext = vscode.extensions.getExtension<ComposePreviewTestApi>(
            "yuri-schimke.compose-preview",
        );
        assert.ok(ext, "compose-preview extension must be present");
        const exported = await ext.activate();
        assert.ok(exported, "activate() must return ComposePreviewTestApi");
        api = exported;
        api.injectGradleApi(
            new RealGradleApi(repoRoot, (line) => console.log(line)),
        );

        await vscode.commands.executeCommand("composePreview.panel.focus");
        await waitFor(
            "webviewReady from the resolved webview",
            30_000,
            100,
            () => {
                if (api.isWebviewReady()) return true;
                const inbound = api.getReceivedMessages();
                return inbound.find(
                    (m) => (m as PostedMessage).command === "webviewReady",
                );
            },
        );
    });

    it("A. cold cmp → switch to android → switch back to cmp", async function () {
        api.resetMessages();
        const observations: Record<string, unknown> = {};
        const cmpNeedle = path.join("samples", "cmp");
        const androidNeedle = path.join("samples", "android");

        // --- Cmp cold ---
        const cmpStart = Date.now();
        await api.triggerRefresh(cmpFile, true, "full");
        const cmpFirst = await waitFor(
            "first cmp setPreviews",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(cmpNeedle),
                );
            },
        );
        observations.cmpFirst = {
            wallMs: Date.now() - cmpStart,
            previewCount: cmpFirst.previews.length,
            moduleDir: cmpFirst.moduleDir,
            ids: cmpFirst.previews.map((p) => p.id).sort(),
            sourceFiles: [
                ...new Set(
                    cmpFirst.previews
                        .map((p) => p.sourceFile ?? "<null>")
                        .map((f) => path.basename(String(f))),
                ),
            ].sort(),
        };

        // Invariant A1: every preview in the cmp panel is sourced from `Previews.kt`
        // (the file we refreshed on) — otherwise the file-scoped filter is leaking.
        const cmpForeignSources = cmpFirst.previews
            .filter((p) => !p.referenced)
            .filter(
                (p) =>
                    typeof p.sourceFile === "string" &&
                    path.basename(p.sourceFile) !== "Previews.kt",
            );
        assert.deepStrictEqual(
            cmpForeignSources.map((p) => ({
                id: p.id,
                sourceFile: p.sourceFile,
            })),
            [],
            "cmp setPreviews carries non-referenced previews from files other than Previews.kt",
        );

        // --- Switch to android ---
        api.resetMessages();
        const androidStart = Date.now();
        await api.triggerRefresh(androidFile, true, "full");
        const androidFirst = await waitFor(
            "first android setPreviews",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(androidNeedle),
                );
            },
        );
        observations.androidFirst = {
            wallMs: Date.now() - androidStart,
            previewCount: androidFirst.previews.length,
            moduleDir: androidFirst.moduleDir,
            ids: androidFirst.previews.map((p) => p.id).sort(),
            sourceFiles: [
                ...new Set(
                    androidFirst.previews
                        .map((p) => p.sourceFile ?? "<null>")
                        .map((f) => path.basename(String(f))),
                ),
            ].sort(),
        };

        // Invariant A2: no cmp ids in the android-targeted setPreviews.
        const cmpIds = new Set(cmpFirst.previews.map((p) => p.id));
        const leakedFromCmp = androidFirst.previews
            .map((p) => p.id)
            .filter((id) => cmpIds.has(id));
        assert.deepStrictEqual(
            leakedFromCmp,
            [],
            `cmp preview ids leaked into android setPreviews: ${leakedFromCmp.join(", ")}`,
        );

        // Invariant A3: every capture renderOutput resolves to a file under
        // :samples:android's project dir and exists on disk.
        const androidBadCaptures: Array<{
            id: string;
            renderOutput: string;
            resolved: string;
        }> = [];
        for (const p of androidFirst.previews) {
            for (const c of p.captures ?? []) {
                const resolved = fullRenderPath(
                    repoRoot,
                    androidFirst.moduleDir,
                    c.renderOutput,
                );
                if (
                    !resolved.includes(androidNeedle) ||
                    !fs.existsSync(resolved)
                ) {
                    androidBadCaptures.push({
                        id: p.id,
                        renderOutput: c.renderOutput,
                        resolved,
                    });
                }
            }
        }
        observations.androidBadCaptures = androidBadCaptures;
        assert.deepStrictEqual(
            androidBadCaptures,
            [],
            `android setPreviews referenced renderOutputs outside :samples:android or missing on disk`,
        );

        // --- Switch back to cmp ---
        api.resetMessages();
        const cmpReStart = Date.now();
        await api.triggerRefresh(cmpFile, true, "full");
        const cmpSecond = await waitFor(
            "second cmp setPreviews",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(cmpNeedle),
                );
            },
        );
        observations.cmpSecond = {
            wallMs: Date.now() - cmpReStart,
            previewCount: cmpSecond.previews.length,
            ids: cmpSecond.previews.map((p) => p.id).sort(),
        };

        // Invariant A4: cmp-after-roundtrip should produce the same id set as
        // cmp-cold (deterministic per file). A discrepancy here is the
        // "wrong-module leak after switch" symptom.
        const cmpFirstSorted = (
            observations.cmpFirst as { ids: string[] }
        ).ids.slice();
        const cmpSecondSorted = (
            observations.cmpSecond as { ids: string[] }
        ).ids.slice();
        assert.deepStrictEqual(
            cmpSecondSorted,
            cmpFirstSorted,
            "preview-id set diverged after cmp → android → cmp round-trip",
        );

        dumpTranscript(repoRoot, "scenario-A-module-switch", observations);
    });

    it("B. edit a @Preview source, refresh, expect new id; revert, expect it gone", async function () {
        api.resetMessages();
        const observations: Record<string, unknown> = {};
        const cmpNeedle = path.join("samples", "cmp");

        await api.triggerRefresh(cmpFile, true, "full");
        const baseline = await waitFor(
            "cmp baseline setPreviews",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(cmpNeedle),
                );
            },
        );
        const baselineIds = new Set(baseline.previews.map((p) => p.id));
        observations.baselineIds = [...baselineIds].sort();

        const tag = `Interactive${Date.now()}`;
        const original = fs.readFileSync(cmpFile, "utf-8");
        // FQN-only — Previews.kt's existing imports already cover androidx.compose.ui.tooling.preview.Preview,
        // but the appended block bypasses imports so spell the annotation in full to match what the
        // ClassGraph discoverer scans for. Picking the wrong package (e.g. `androidx.compose.desktop...`)
        // silently compiles but is not discovered, which makes the assertion below time out instead of
        // failing fast with a useful message.
        const addition =
            `\n\n@androidx.compose.ui.tooling.preview.Preview\n` +
            `@androidx.compose.runtime.Composable\n` +
            `fun ${tag}() {\n` +
            `    androidx.compose.material3.Text(text = "${tag}")\n` +
            `}\n`;
        fs.writeFileSync(cmpFile, original + addition, "utf-8");
        let editPhaseError: Error | null = null;
        try {
            api.resetMessages();
            await api.triggerRefresh(cmpFile, true, "full");
            const afterAdd = await waitFor(
                `setPreviews after adding ${tag}`,
                this.timeout(),
                500,
                () => {
                    const msgs = api.getPostedMessages();
                    return latestSetPreviewsMatching(msgs, (m) => {
                        if (m.previews.length === 0) return false;
                        if (!m.moduleDir.includes(cmpNeedle)) return false;
                        return m.previews.some((p) => p.id.endsWith(tag));
                    });
                },
            );
            const matched = afterAdd.previews.filter((p) => p.id.endsWith(tag));
            observations.afterAdd = {
                previewCount: afterAdd.previews.length,
                matched: matched.map((p) => ({
                    id: p.id,
                    captureCount: (p.captures ?? []).length,
                    firstRenderOutput: p.captures?.[0]?.renderOutput,
                })),
                idsSorted: afterAdd.previews.map((p) => p.id).sort(),
            };

            // Invariant B1: new id appears with at least one capture pointing
            // to an on-disk PNG. This is the heart of "edits not updating" —
            // if the panel says the preview exists but the PNG isn't there,
            // the user sees a placeholder card forever.
            for (const m of matched) {
                const captures = m.captures ?? [];
                assert.ok(
                    captures.length > 0,
                    `preview ${m.id} arrived with 0 captures`,
                );
                for (const c of captures) {
                    const resolved = fullRenderPath(
                        repoRoot,
                        afterAdd.moduleDir,
                        c.renderOutput,
                    );
                    assert.ok(
                        fs.existsSync(resolved),
                        `preview ${m.id} capture ${c.renderOutput} not on disk at ${resolved}`,
                    );
                }
            }
        } catch (err) {
            editPhaseError = err as Error;
        } finally {
            fs.writeFileSync(cmpFile, original, "utf-8");
        }
        if (editPhaseError) throw editPhaseError;

        api.resetMessages();
        await api.triggerRefresh(cmpFile, true, "full");
        const afterRevert = await waitFor(
            `setPreviews after reverting ${tag}`,
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(msgs, (m) => {
                    if (m.previews.length === 0) return false;
                    if (!m.moduleDir.includes(cmpNeedle)) return false;
                    return !m.previews.some((p) => p.id.endsWith(tag));
                });
            },
        );
        observations.afterRevert = {
            previewCount: afterRevert.previews.length,
            idsSorted: afterRevert.previews.map((p) => p.id).sort(),
        };

        // Invariant B2: post-revert id set matches the pre-edit baseline.
        assert.deepStrictEqual(
            afterRevert.previews.map((p) => p.id).sort(),
            [...baselineIds].sort(),
            "id set after revert diverged from pre-edit baseline",
        );

        dumpTranscript(repoRoot, "scenario-B-edit-revert", observations);
    });

    it("C. rapid force refreshes converge on the latest target", async function () {
        api.resetMessages();
        const observations: Record<string, unknown> = {};
        const cmpNeedle = path.join("samples", "cmp");

        const p1 = api.triggerRefresh(cmpFile, true, "full");
        const p2 = api.triggerRefresh(cmpFile, true, "full");
        const p3 = api.triggerRefresh(cmpFile, true, "full");
        await Promise.all([p1, p2, p3]);

        const settled = await waitFor(
            "settled cmp setPreviews after rapid burst",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(cmpNeedle),
                );
            },
        );

        const allPosts = api.getPostedMessages();
        observations.totalSetPreviewsPosts = allPosts.filter(
            (m) => (m as PostedMessage).command === "setPreviews",
        ).length;
        observations.finalIds = settled.previews.map((p) => p.id).sort();
        observations.finalModuleDir = settled.moduleDir;

        assert.ok(
            settled.moduleDir.includes(cmpNeedle),
            `coalesced final setPreviews points at ${settled.moduleDir}, expected :samples:cmp`,
        );
        // Invariant C1: every capture in the final post resolves to an on-disk PNG.
        // A coalescing bug that drops the imageJobs pass on the in-flight refresh
        // would leave the panel showing PreviewInfo entries pointing at PNGs that
        // a later overwrite has clobbered.
        const missing: string[] = [];
        for (const p of settled.previews) {
            for (const c of p.captures ?? []) {
                const resolved = fullRenderPath(
                    repoRoot,
                    settled.moduleDir,
                    c.renderOutput,
                );
                if (!fs.existsSync(resolved))
                    missing.push(`${p.id} → ${resolved}`);
            }
        }
        assert.deepStrictEqual(
            missing,
            [],
            `captures pointing at missing PNGs after rapid burst: ${missing.join(", ")}`,
        );

        dumpTranscript(repoRoot, "scenario-C-rapid-refresh", observations);
    });

    it("D. start cmp render, mid-flight switch to android", async function () {
        api.resetMessages();
        const observations: Record<string, unknown> = {};
        const cmpNeedle = path.join("samples", "cmp");
        const androidNeedle = path.join("samples", "android");

        const racing = api.triggerRefresh(cmpFile, true, "full");
        // Give the cmp refresh enough wall time to enter Gradle.
        await new Promise((r) => setTimeout(r, 250));
        await api.triggerRefresh(androidFile, true, "full");
        await racing.catch(() => {
            /* cancellation expected */
        });

        const settled = await waitFor(
            "settled android setPreviews after race",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(androidNeedle),
                );
            },
        );

        observations.finalModuleDir = settled.moduleDir;
        observations.finalIds = settled.previews.map((p) => p.id).sort();

        const cmpFixtureRoot = path.join(repoRoot, "samples", "cmp") + path.sep;
        const leakedCaptures: string[] = [];
        for (const p of settled.previews) {
            for (const c of p.captures ?? []) {
                const resolved = fullRenderPath(
                    repoRoot,
                    settled.moduleDir,
                    c.renderOutput,
                );
                if (resolved.startsWith(cmpFixtureRoot)) {
                    leakedCaptures.push(`${p.id} → ${resolved}`);
                }
            }
        }
        observations.leakedCmpCaptures = leakedCaptures;
        assert.deepStrictEqual(
            leakedCaptures,
            [],
            `cmp captures leaked into post-race android setPreviews: ${leakedCaptures.join(", ")}`,
        );

        // Sanity: also verify no cmp moduleDir mentions slipped into the
        // *latest* panel state. A bug where the late android refresh raced
        // through but the panel's selectedModule never updated would show up
        // here as moduleDir=cmp despite the previews coming from android.
        assert.ok(
            !settled.moduleDir.includes(cmpNeedle),
            `post-race panel moduleDir still points at cmp: ${settled.moduleDir}`,
        );

        dumpTranscript(repoRoot, "scenario-D-switch-mid-flight", observations);
    });

    it("E. rename a @Preview function; old id + PNG drop, new id surfaces, index pruned", async function () {
        api.resetMessages();
        const observations: Record<string, unknown> = {};
        const cmpNeedle = path.join("samples", "cmp");
        // The id format is `<class>.<functionName>_<previewName>` (e.g.
        // `com.example.samplecmp.PreviewsKt.RedBoxPreview_Red Box`). Anchoring
        // on `.RedBoxPreview_` (function name + the `_` name separator) keeps
        // the old matcher from also matching the renamed id, since the renamed
        // id reads `…RedBoxPreviewRenamed_Red Box` — there's no `_` directly
        // after `RedBoxPreview` there.
        const OLD = /\.RedBoxPreview_/;
        const NEW = /\.RedBoxPreviewRenamed_/;

        // --- Baseline: the pre-rename manifest must contain RedBoxPreview ---
        await api.triggerRefresh(cmpFile, true, "full");
        const baseline = await waitFor(
            "cmp baseline setPreviews (with RedBoxPreview)",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(msgs, (m) => {
                    if (!m.moduleDir.includes(cmpNeedle)) return false;
                    return m.previews.some((p) => OLD.test(p.id));
                });
            },
        );
        const baselineIds = baseline.previews.map((p) => p.id).sort();
        observations.baselineIds = baselineIds;

        // Stash the old preview's on-disk render path so we can assert it's
        // gone after the rename. Resolve it the same way the panel does.
        const oldPreview = baseline.previews.find((p) => OLD.test(p.id));
        assert.ok(oldPreview, "baseline missing RedBoxPreview");
        const oldRenderOutputs = (oldPreview.captures ?? []).map((c) =>
            fullRenderPath(repoRoot, baseline.moduleDir, c.renderOutput),
        );
        observations.oldRenderOutputs = oldRenderOutputs;
        for (const p of oldRenderOutputs) {
            assert.ok(
                fs.existsSync(p),
                `baseline RedBoxPreview render PNG missing on disk at ${p}`,
            );
        }

        const original = fs.readFileSync(cmpFile, "utf-8");
        assert.ok(
            original.includes("fun RedBoxPreview()"),
            "fixture drift: expected `fun RedBoxPreview()` in Previews.kt",
        );
        const renamed = original.replace(
            "fun RedBoxPreview()",
            "fun RedBoxPreviewRenamed()",
        );
        fs.writeFileSync(cmpFile, renamed, "utf-8");

        let renamePhaseError: Error | null = null;
        try {
            api.resetMessages();
            await api.triggerRefresh(cmpFile, true, "full");
            const afterRename = await waitFor(
                "setPreviews after rename (new id present, old id gone)",
                this.timeout(),
                500,
                () => {
                    const msgs = api.getPostedMessages();
                    return latestSetPreviewsMatching(msgs, (m) => {
                        if (m.previews.length === 0) return false;
                        if (!m.moduleDir.includes(cmpNeedle)) return false;
                        return (
                            m.previews.some((p) => NEW.test(p.id)) &&
                            !m.previews.some((p) => OLD.test(p.id))
                        );
                    });
                },
            );
            observations.afterRename = {
                idsSorted: afterRename.previews.map((p) => p.id).sort(),
            };

            // Invariant E1: the renamed id is present, the old id is gone.
            const renamedPreview = afterRename.previews.find((p) =>
                NEW.test(p.id),
            );
            assert.ok(
                renamedPreview,
                "renamed preview id never appeared in setPreviews",
            );
            assert.deepStrictEqual(
                afterRename.previews
                    .filter((p) => OLD.test(p.id))
                    .map((p) => p.id),
                [],
                "stale RedBoxPreview id survived the rename in setPreviews",
            );

            // Invariant E2: the new render PNG is on disk.
            const newRenderOutputs = (renamedPreview.captures ?? []).map((c) =>
                fullRenderPath(repoRoot, afterRename.moduleDir, c.renderOutput),
            );
            observations.newRenderOutputs = newRenderOutputs;
            assert.ok(
                newRenderOutputs.length > 0,
                "renamed preview arrived with 0 captures",
            );
            // E2 is the one disk check in this suite that races the render
            // pipeline: the renamed preview is rendered fresh, so its
            // `setPreviews` metadata (already carrying the capture's
            // `renderOutput` path) can be published a beat before the PNG bytes
            // are flushed to disk. Poll for the file rather than asserting once
            // — every other PNG check here reads a pre-existing render, so only
            // this freshly-rendered one needs the wait. A genuine "never
            // written" regression still fails, just via waitFor's timeout.
            await waitFor(
                `renamed preview render PNG(s) on disk: ${newRenderOutputs.join(", ")}`,
                this.timeout(),
                500,
                () =>
                    newRenderOutputs.every((p) => fs.existsSync(p))
                        ? true
                        : undefined,
            );

            // Invariant E3: the stale PNG no longer exists on disk. The
            // gradle-level repro proved the renderer sanitises these; this
            // pins the behaviour at the panel-driven layer.
            const survivingOld = oldRenderOutputs.filter((p) =>
                fs.existsSync(p),
            );
            observations.survivingOldRenderOutputs = survivingOld;
            assert.deepStrictEqual(
                survivingOld,
                [],
                `stale RedBoxPreview PNG(s) survived on disk after rename: ${survivingOld.join(", ")}`,
            );

            // Invariant E4: the pruned id stays pruned. A second refresh plus a
            // module round-trip must not resurrect RedBoxPreview from a stale
            // `previewModuleIndex` entry.
            api.resetMessages();
            await api.triggerRefresh(cmpFile, true, "full");
            const reRefreshed = await waitFor(
                "second post-rename cmp setPreviews",
                this.timeout(),
                500,
                () => {
                    const msgs = api.getPostedMessages();
                    return latestSetPreviewsMatching(
                        msgs,
                        nonEmptyForModule(cmpNeedle),
                    );
                },
            );
            assert.deepStrictEqual(
                reRefreshed.previews
                    .filter((p) => OLD.test(p.id))
                    .map((p) => p.id),
                [],
                "RedBoxPreview resurfaced on a second refresh — previewModuleIndex not pruned",
            );
        } catch (err) {
            renamePhaseError = err as Error;
        } finally {
            fs.writeFileSync(cmpFile, original, "utf-8");
        }
        if (renamePhaseError) throw renamePhaseError;

        // --- Revert: the original RedBoxPreview must come back, renamed gone ---
        api.resetMessages();
        await api.triggerRefresh(cmpFile, true, "full");
        const afterRevert = await waitFor(
            "setPreviews after reverting the rename",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(msgs, (m) => {
                    if (m.previews.length === 0) return false;
                    if (!m.moduleDir.includes(cmpNeedle)) return false;
                    return (
                        m.previews.some((p) => OLD.test(p.id)) &&
                        !m.previews.some((p) => NEW.test(p.id))
                    );
                });
            },
        );
        observations.afterRevertIds = afterRevert.previews
            .map((p) => p.id)
            .sort();
        assert.deepStrictEqual(
            afterRevert.previews.map((p) => p.id).sort(),
            baselineIds,
            "id set after reverting the rename diverged from the pre-edit baseline",
        );

        dumpTranscript(repoRoot, "scenario-E-rename-preview", observations);
    });

    it("F. dropping the editor scope resolves to empty state, not stale cards (#1566)", async function () {
        api.resetMessages();
        const observations: Record<string, unknown> = {};
        const cmpNeedle = path.join("samples", "cmp");

        // Unlike the other scenarios, F drives `triggerEditorScopeChange(null)`,
        // which routes through `refresh(false)` with NO caller file — so
        // `resolveScopeFile()` falls back to `vscode.window.visibleTextEditors`.
        // Earlier e2e suites (`e2eCachedPreloadOnSwitch`, which sorts before
        // this file under the `e2e*.test.js` loader) open preview-source `.kt`
        // editors via `showTextDocument` and never close them. A leftover
        // visible editor would make `refresh(false)` re-scope to that file and
        // render it instead of resolving to empty, so F would pass/fail on
        // prior-suite state rather than on #1566. Close every editor and wait
        // for the visible set to actually drain before we start.
        await vscode.commands.executeCommand(
            "workbench.action.closeAllEditors",
        );
        await waitFor(
            "all text editors closed before dropping scope",
            30_000,
            100,
            () =>
                vscode.window.visibleTextEditors.length === 0
                    ? true
                    : undefined,
        );

        // Warm cmp so there are real cards on screen — the buggy behaviour is
        // "stale cards stay after the last editor closes", so we have to start
        // from a loaded panel for the assertion to mean anything. The caller
        // path (`triggerRefresh(cmpFile, …)`) scopes by argument, not by a
        // visible editor, so the warm stays deterministic without reopening one.
        await api.triggerRefresh(cmpFile, true, "full");
        const loaded = await waitFor(
            "cmp setPreviews before dropping scope",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(cmpNeedle),
                );
            },
        );
        observations.loadedIds = loaded.previews.map((p) => p.id).sort();
        assert.ok(
            loaded.previews.length > 0,
            "precondition: panel must have cards loaded before dropping scope",
        );

        // Drop the scope to nothing — the production "last preview editor
        // closed / focus lost with no visible .kt" teardown.
        api.resetMessages();
        await api.triggerEditorScopeChange(null);

        const EMPTY_TEXT = "Open a Kotlin source file with @Preview functions.";
        await waitFor(
            "empty-state showMessage after dropping scope",
            this.timeout(),
            200,
            () => {
                const msgs = api.getPostedMessages() as PostedMessage[];
                return msgs.find(
                    (m) => m.command === "showMessage" && m.text === EMPTY_TEXT,
                );
            },
        );

        const post = api.getPostedMessages() as PostedMessage[];
        observations.postScopeChangeCommands = post.map((m) => m.command);

        // Invariant F1: the panel was explicitly cleared.
        assert.ok(
            post.some((m) => m.command === "clearAll"),
            "expected a clearAll when the scope dropped to nothing",
        );

        // Invariant F2: no stale cards were re-pushed — the panel must NOT
        // receive a non-empty setPreviews after the scope went away. This is
        // the exact #1566 failure: the panel kept showing the previous
        // module's previews after its backing editor closed.
        const stalePushes = post
            .filter((m) => m.command === "setPreviews")
            .filter((m) => {
                const previews = m.previews as PreviewRecord[] | undefined;
                return Array.isArray(previews) && previews.length > 0;
            });
        assert.deepStrictEqual(
            stalePushes.map((m) => (m.previews as PreviewRecord[]).length),
            [],
            "stale non-empty setPreviews pushed after the editor scope was dropped",
        );

        dumpTranscript(
            repoRoot,
            "scenario-F-empty-on-scope-drop",
            observations,
        );

        // Restore a real scope so a later test in the suite doesn't inherit
        // the empty state.
        await api.triggerRefresh(cmpFile, true, "full");
    });

    it("G. compile error surfaces a banner without wiping cards; fix clears it", async function () {
        api.resetMessages();
        const observations: Record<string, unknown> = {};
        const cmpNeedle = path.join("samples", "cmp");

        // --- Baseline: a clean render so there are cards to keep ---
        await api.triggerRefresh(cmpFile, true, "full");
        const baseline = await waitFor(
            "cmp baseline setPreviews before injecting the error",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(cmpNeedle),
                );
            },
        );
        const baselineIds = baseline.previews.map((p) => p.id).sort();
        observations.baselineIds = baselineIds;

        const original = fs.readFileSync(cmpFile, "utf-8");
        // Append a deliberately broken declaration. `val broken: Int =` with
        // no right-hand side is a syntax error kotlinc reports as an
        // `e: <file>:<line>:<col> Expecting an expression` line — exactly the
        // shape KotlinCompileErrorDetector parses into the banner. Picking a
        // *syntax* error (vs. an unresolved reference) keeps the failure from
        // depending on classpath resolution order.
        const broken =
            `${original}\n\n@androidx.compose.runtime.Composable\n` +
            `fun CompileErrorProbe() {\n    val broken: Int =\n}\n`;
        fs.writeFileSync(cmpFile, broken, "utf-8");

        let errorPhaseError: Error | null = null;
        try {
            api.resetMessages();
            await api.triggerRefresh(cmpFile, true, "full");
            const errBanner = await waitFor(
                "setCompileErrors after introducing the syntax error",
                this.timeout(),
                500,
                () => {
                    const msgs = api.getPostedMessages() as PostedMessage[];
                    return msgs.find((m) => {
                        if (m.command !== "setCompileErrors") return false;
                        const errors = m.errors as unknown[] | undefined;
                        return Array.isArray(errors) && errors.length > 0;
                    });
                },
            );
            const errors = errBanner.errors as Array<Record<string, unknown>>;
            observations.compileErrors = errors.map((e) => ({
                file: e.file,
                line: e.line,
                message: e.message,
            }));

            // Invariant G1: the banner carries at least one structured error.
            assert.ok(
                errors.length > 0,
                "setCompileErrors arrived with an empty errors array",
            );

            // Invariant G2: the previous cards were NOT wiped — the compile
            // failure must not post `clearAll` for a same-module refresh.
            // The cards stay visible (just marked loading/stale) so the user
            // keeps a reference while fixing the error.
            const errPhase = api.getPostedMessages() as PostedMessage[];
            observations.errorPhaseCommands = errPhase.map((m) => m.command);
            assert.ok(
                !errPhase.some((m) => m.command === "clearAll"),
                "compile error wiped the panel (clearAll) instead of keeping stale cards",
            );
        } catch (err) {
            errorPhaseError = err as Error;
        } finally {
            fs.writeFileSync(cmpFile, original, "utf-8");
        }
        if (errorPhaseError) throw errorPhaseError;

        // --- Fix: the banner clears and a clean manifest returns ---
        api.resetMessages();
        await api.triggerRefresh(cmpFile, true, "full");
        const recovered = await waitFor(
            "clean cmp setPreviews after fixing the error",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(cmpNeedle),
                );
            },
        );
        const recoverPhase = api.getPostedMessages() as PostedMessage[];
        observations.recoverPhaseCommands = recoverPhase.map((m) => m.command);
        observations.recoveredIds = recovered.previews.map((p) => p.id).sort();

        // Invariant G3: the error banner was explicitly cleared on the way to
        // the clean render — no stuck overlay.
        assert.ok(
            recoverPhase.some((m) => m.command === "clearCompileErrors"),
            "expected clearCompileErrors on the recovery refresh",
        );
        // Invariant G4: no compile-error banner lingers after the clean render.
        const lastCompileMsgIdx = recoverPhase
            .map((m) => m.command)
            .lastIndexOf("setCompileErrors");
        assert.strictEqual(
            lastCompileMsgIdx,
            -1,
            "a setCompileErrors banner re-appeared on the recovery refresh",
        );
        // Invariant G5: the recovered id set matches the pre-error baseline.
        assert.deepStrictEqual(
            recovered.previews.map((p) => p.id).sort(),
            baselineIds,
            "id set after fixing the error diverged from the pre-error baseline",
        );

        dumpTranscript(repoRoot, "scenario-G-compile-error", observations);
    });
});
