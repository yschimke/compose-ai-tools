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
 * Follow-ups worth adding (need either small test-API extensions or extra
 * sample state):
 *
 *   E. Rename an existing `@Preview` (e.g. `RedBoxPreview` → `RedBoxPreviewRenamed`),
 *      refresh. Assert the old id is gone from `setPreviews` AND no stale
 *      `RedBoxPreview_Red_Box.png` survives on disk (the gradle-level repro
 *      confirmed the renderer cleans these up, but the panel layer can
 *      still surface a stale id if `previewModuleIndex` isn't pruned).
 *
 *   F. Close the file the panel was scoped to (editor focus → undefined)
 *      and confirm the panel resolves to empty state, not stale bytes.
 *      Regression for #1566 ("after closing last editor, left seeing stale previews").
 *
 *   G. Compile-error path: introduce a deliberate Kotlin syntax error,
 *      refresh, expect the errors panel to populate AND the previous
 *      `setPreviews` to remain visually marked as stale rather than wiped.
 *      Then fix the error, refresh, expect clean state.
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
});
