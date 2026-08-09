import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import type { ComposePreviewTestApi } from "../../../extension";
import { RealGradleApi } from "../realGradleApi";
import { assertRefreshRendered } from "./refreshOutcome";

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

/**
 * Preview-name filter (`-PcomposePreview.filter`, issue #2066) handed to
 * every `gradlew` invocation this suite drives. Names exactly the previews
 * the scenarios below display and assert on.
 *
 * Without it the disk assertions can't hold on CI hardware. `triggerRefresh`
 * runs the production `composePreviewRenderAll`, which renders the *whole*
 * module, and `gradleService` caps every Gradle task at `TASK_TIMEOUT_MS`
 * — `:samples:android` alone carries 160+ `@Preview`s behind
 * Robolectric, so a cold full-module render is killed at the cap long before
 * it reaches `TypographyGallery.kt`. The extension then behaves exactly as
 * designed (`renderWithDiskFallback` paints the on-disk manifest and records
 * a partial-render failure), which leaves scenario A's "every capture
 * resolves to a PNG on disk" invariant a lottery over how far the truncated
 * render happened to get — it lost 4 of 5 consecutive `main` runs.
 *
 * Filtering leaves the loop under test intact — real plugin, real
 * Robolectric, real manifest, real panel — and only shrinks the render to
 * something that finishes inside the cap, so the disk checks mean what they
 * say. Only *rendering* is narrowed: `composePreviewDiscover` still writes
 * the module's complete `previews.json`, so the id-set invariants (A2/A4)
 * keep seeing every preview in the module, and unfiltered previews keep
 * whatever PNGs they already had on disk. Full-module render coverage lives
 * in the `cmp-smoke` shard (`e2e.test.ts`), which renders `:samples:cmp`
 * unfiltered.
 *
 * Patterns are package-qualified so one can't substring-match a same-named
 * preview elsewhere in the repo. A non-empty filter that matches *nothing*
 * fails the render task outright, so every module this suite renders
 * (`:samples:cmp`, `:samples:android`) must keep at least one entry here.
 *
 * **Narrowing the render requires `-PcomposePreview.missingRenders=warn`**
 * alongside it — see where these patterns are handed to `RealGradleApi`.
 * `composePreviewRenderAll` finishes with a completeness check across the
 * whole manifest and the policy defaults to `fail`, so a filtered render
 * fails the task on every preview the filter excluded. Without the pairing
 * this suite never once reached the happy path.
 */
const FIXTURE_PREVIEW_FILTER = [
    // `samples/cmp/.../Previews.kt` — the cmp fixture every scenario
    // refreshes on. The glob also covers scenario E's rename of
    // `RedBoxPreview` → `RedBoxPreviewRenamed`.
    "com.example.samplecmp.RedBoxPreview*",
    "com.example.samplecmp.BlueBoxPreview",
    "com.example.samplecmp.AppPreview",
    "com.example.samplecmp.WallpaperDemoPreview",
    "com.example.samplecmp.Pixel8SystemUiPreview",
    // Scenario B appends `fun Interactive<timestamp>()` to that same file.
    "com.example.samplecmp.Interactive*",
    // `samples/android/.../TypographyGallery.kt` — the android fixture
    // scenarios A/D/E switch to.
    "com.example.sampleandroid.TypographySpecimenPreview",
    "com.example.sampleandroid.FontFamilySpecimenPreview",
    "com.example.sampleandroid.FallbackCoverageSpecimenPreview",
];

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

/**
 * Budget for "the panel should have published this by now" waits.
 *
 * Deliberately NOT `this.timeout()`. Passing the Mocha ceiling as a wait
 * budget means a condition that never becomes true consumes the entire test
 * and reports a bare `Timeout of 1800000ms exceeded` — no indication of which
 * invariant failed, and 30 minutes of runner time per occurrence. Scenario E
 * did exactly that on `main` (run 31306997859): 26 minutes of silence after
 * `composePreviewDiscover`, then a timeout that named nothing.
 *
 * These waits follow an *awaited* `triggerRefresh`, and `refresh` posts
 * `setPreviews` before it resolves — so in a healthy run the value is already
 * there on the first poll (measured at 44ms in the cmp-smoke shard). Three
 * minutes is far past generous; it exists only so a daemon-path repaint that
 * lands late still counts. The Mocha ceiling stays as the backstop.
 */
const PANEL_UPDATE_BUDGET_MS = 3 * 60_000;

/**
 * Budget for the awaited `triggerRefresh` itself.
 *
 * {@link PANEL_UPDATE_BUDGET_MS} only bounds the *poll after* the refresh
 * resolves — it does nothing for a refresh that never resolves, because the
 * timer has not started yet. That is not hypothetical: the first recorded
 * scenario-E failure (run 31306997859) went silent right after
 * `composePreviewDiscover` and sat there for 26 minutes with no Gradle
 * output, no task-cap cancellation, and nothing for the poll to observe.
 *
 * Sized above `gradleService`'s 10-minute per-task cap so a genuinely slow
 * cold render is never cut short — this fires only when something is wedged
 * past the point the cap itself should have handled.
 */
const REFRESH_BUDGET_MS = 12 * 60_000;

/** Head room so a diagnostic always reports before Mocha's own ceiling. */
const DIAGNOSTIC_SLACK_MS = 30_000;

/**
 * Clamp a diagnostic budget to what is left of the test's Mocha budget.
 *
 * A fixed budget is not enough on its own: scenario E runs four bounded
 * refreshes inside one 30-minute test, and 4 × {@link REFRESH_BUDGET_MS} is
 * 48 minutes. A wedge in a *late* refresh would blow the Mocha ceiling before
 * its own timer fired, reinstating exactly the bare, unattributed timeout
 * these budgets exist to remove. Deriving each deadline from the remaining
 * time guarantees the diagnostic wins the race.
 */
function budgetWithin(deadlineAt: number, max: number): number {
    return Math.max(
        1_000,
        Math.min(max, deadlineAt - Date.now() - DIAGNOSTIC_SLACK_MS),
    );
}

/**
 * Await a refresh under `budgetMs` — normally
 * `budgetWithin(deadlineAt, REFRESH_BUDGET_MS)` — reporting panel state if it
 * never resolves.
 *
 * The pending refresh is abandoned, not cancelled: there is no test-API hook
 * to abort one, and the extension host keeps running after a failed test, so
 * the abandoned refresh's `pendingRefresh` can still be in flight when the
 * *next* scenario starts. That is why every scenario downstream of a bounded
 * refresh bounds its own waits too — an inherited wedge then costs one
 * `PANEL_UPDATE_BUDGET_MS` per wait instead of a full Mocha ceiling per test.
 */
async function refreshWithinBudget<T>(
    label: string,
    budgetMs: number,
    refresh: Promise<T>,
    describeState: () => string,
): Promise<T> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const budget = new Promise<never>((_, reject) => {
        timer = setTimeout(
            () =>
                reject(
                    new Error(
                        `${label}: refresh did not resolve within ${
                            budgetMs / 1000
                        }s — it is wedged, not slow.\n  ${describeState()}`,
                    ),
                ),
            budgetMs,
        );
    });
    timer?.unref?.();
    try {
        return await Promise.race([refresh, budget]);
    } finally {
        clearTimeout(timer);
    }
}

async function waitFor<T>(
    description: string,
    timeoutMs: number,
    pollMs: number,
    probe: () => T | undefined,
    /**
     * Rendered into the timeout message. A wait that fails should say what it
     * actually observed — otherwise the next occurrence is as opaque as the
     * last one.
     */
    describeState?: () => string,
): Promise<T> {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
        const value = probe();
        if (value !== undefined) {
            return value;
        }
        await new Promise((r) => setTimeout(r, pollMs));
    }
    let state = "";
    if (describeState) {
        try {
            state = `\n  ${describeState()}`;
        } catch (err) {
            state = `\n  <describeState threw: ${String(err)}>`;
        }
    }
    throw new Error(
        `Timed out after ${timeoutMs}ms waiting for: ${description}${state}`,
    );
}

/**
 * Text of a panel empty-state message, if the refresh posted one. `refresh`
 * has two such branches, and BOTH are terminal for a test waiting on
 * `setPreviews`: each posts `clearAll` + a `showMessage`, posts no
 * `setPreviews`, and still returns `'completed'` — so `assertRefreshRendered`
 * cannot see them either.
 *
 *   * "No @Preview functions in this file (N in other files in this module)."
 *     — the module has previews, the active file does not.
 *   * "No @Preview functions found in this module"
 *     — the whole module came back empty.
 *
 * Matching only the first would leave the module-wide case waiting out the
 * full budget for a `setPreviews` that is never coming.
 */
const EMPTY_STATE_PREFIXES = [
    "No @Preview functions in this file",
    "No @Preview functions found in this module",
];

function emptyStateText(messages: unknown[]): string | undefined {
    for (let i = messages.length - 1; i >= 0; i--) {
        const m = messages[i] as PostedMessage;
        if (m.command !== "showMessage" || typeof m.text !== "string") continue;
        const text = m.text;
        if (EMPTY_STATE_PREFIXES.some((prefix) => text.startsWith(prefix))) {
            return text;
        }
    }
    return undefined;
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

/**
 * Generic timeout diagnostic: what the panel last published for a module,
 * plus the Gradle tail the extension log would otherwise swallow.
 *
 * Scenario E builds a richer one that also reports its old/new rename ids;
 * this is the version the scenarios with no id-level expectation share.
 */
function describeModulePanelState(
    api: ComposePreviewTestApi,
    moduleDirNeedle: string,
): () => string {
    return () => {
        const msgs = api.getPostedMessages();
        const latest = latestSetPreviewsMatching(msgs, (m) =>
            m.moduleDir.includes(moduleDirNeedle),
        );
        const ids = latest
            ? latest.previews.map((p) => p.id).sort()
            : undefined;
        return [
            `latest ${moduleDirNeedle} setPreviews: ${
                ids
                    ? `${ids.length} preview(s) [${ids.join(", ")}]`
                    : "<none posted>"
            }`,
            `empty state: ${emptyStateText(msgs) ?? "<none>"}`,
            `lastWarmDaemonError: ${api.getLastWarmDaemonError() ?? "<none>"}`,
            `Compose Preview output tail:\n    ${api
                .getOutputChannelTail(40)
                .join("\n    ")}`,
        ].join("\n  ");
    };
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
            new RealGradleApi(repoRoot, (line) => console.log(line), [
                `-PcomposePreview.filter=${FIXTURE_PREVIEW_FILTER.join(",")}`,
                // Mandatory companion to the filter above, and its absence was
                // a real bug. `composePreviewRenderAll` ends with a
                // completeness check over the *whole* manifest, and
                // `composePreview.missingRenders` defaults to `fail` — so
                // narrowing the render to 6 of the module's 66 previews made
                // the task fail every single time:
                //
                //   composePreviewRenderAll: render produced no output file
                //   for 59 of 66 preview(s)
                //
                // Every refresh in this suite was therefore landing on
                // `renderWithDiskFallback` instead of the happy path, so the
                // scenarios were only ever exercising the degraded route — and
                // scenario E's post-rename refresh then saw a manifest of 3
                // resource-only previews and resolved to the panel's empty
                // state. `ci.yml`'s bundle job pairs a narrowed render with
                // `missingRenders=warn` for exactly this reason.
                "-PcomposePreview.missingRenders=warn",
            ]),
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
        assertRefreshRendered(
            api,
            await api.triggerRefresh(cmpFile, true, "full"),
            ":samples:cmp render",
        );
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
        assertRefreshRendered(
            api,
            await api.triggerRefresh(androidFile, true, "full"),
            ":samples:android render",
        );
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
        // :samples:android's project dir and exists on disk. The
        // "missing on disk" half only holds because `FIXTURE_PREVIEW_FILTER`
        // keeps this module's render small enough to finish inside
        // `gradleService`'s 5-minute task cap — if the whole set turns up
        // missing, suspect a render killed at that cap (the log carries a
        // `cancel :samples:android:composePreviewRenderAll` line ~300s after
        // it started) before suspecting the panel.
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
        assertRefreshRendered(
            api,
            await api.triggerRefresh(cmpFile, true, "full"),
            ":samples:cmp render",
        );
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

        assertRefreshRendered(
            api,
            await api.triggerRefresh(cmpFile, true, "full"),
            ":samples:cmp render",
        );
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
            assertRefreshRendered(
                api,
                await api.triggerRefresh(cmpFile, true, "full"),
                ":samples:cmp render",
            );
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
        assertRefreshRendered(
            api,
            await api.triggerRefresh(cmpFile, true, "full"),
            ":samples:cmp render",
        );
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
        assertRefreshRendered(
            api,
            await api.triggerRefresh(androidFile, true, "full"),
            ":samples:android render",
        );
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
        // Every budget below is clamped against this so the scenario's own
        // waits can never sum past the Mocha ceiling: four full refreshes at
        // REFRESH_BUDGET_MS each already exceed it, and a bare Mocha timeout
        // reports nothing. Clamping means the *last* wait to overrun is the
        // one that fails, and it fails with `describeCmpPanelState` attached.
        const deadlineAt = Date.now() + this.timeout();
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

        // Every wait in this scenario reports through this on timeout. The
        // three ways the rename flow can stall are indistinguishable from a
        // bare Mocha timeout but obvious from here: no `setPreviews` posted at
        // all (the refresh never republished), the new id absent (discovery
        // did not see the rename), or the old id still present (the module
        // index was not pruned). The output-channel tail carries the Gradle
        // lines — `> <task> completed` / `FAILED` / `cancelled` — that the
        // in-repo e2e otherwise routes to the extension log and discards.
        const describeCmpPanelState = (): string => {
            const latest = latestSetPreviewsMatching(
                api.getPostedMessages(),
                (m) => m.moduleDir.includes(cmpNeedle),
            );
            const ids = latest
                ? latest.previews.map((p) => p.id).sort()
                : undefined;
            const tail = api.getOutputChannelTail(40).join("\n    ");
            return [
                `latest cmp setPreviews: ${
                    ids
                        ? `${ids.length} preview(s) [${ids.join(", ")}]`
                        : "<none posted>"
                }`,
                `old id present: ${ids ? ids.some((id) => OLD.test(id)) : "n/a"}`,
                `new id present: ${ids ? ids.some((id) => NEW.test(id)) : "n/a"}`,
                `lastWarmDaemonError: ${api.getLastWarmDaemonError() ?? "<none>"}`,
                `Compose Preview output tail:\n    ${tail}`,
            ].join("\n  ");
        };

        // --- Baseline: the pre-rename manifest must contain RedBoxPreview ---
        assertRefreshRendered(
            api,
            await refreshWithinBudget(
                ":samples:cmp render",
                budgetWithin(deadlineAt, REFRESH_BUDGET_MS),
                api.triggerRefresh(cmpFile, true, "full"),
                describeCmpPanelState,
            ),
            ":samples:cmp render",
        );
        const baseline = await waitFor(
            "cmp baseline setPreviews (with RedBoxPreview)",
            budgetWithin(deadlineAt, PANEL_UPDATE_BUDGET_MS),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(msgs, (m) => {
                    if (!m.moduleDir.includes(cmpNeedle)) return false;
                    return m.previews.some((p) => OLD.test(p.id));
                });
            },
            describeCmpPanelState,
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
            assertRefreshRendered(
                api,
                await refreshWithinBudget(
                    ":samples:cmp render",
                    budgetWithin(deadlineAt, REFRESH_BUDGET_MS),
                    api.triggerRefresh(cmpFile, true, "full"),
                    describeCmpPanelState,
                ),
                ":samples:cmp render",
            );
            const afterRename = await waitFor(
                "setPreviews after rename (new id present, old id gone)",
                budgetWithin(deadlineAt, PANEL_UPDATE_BUDGET_MS),
                500,
                () => {
                    const msgs = api.getPostedMessages();
                    // A refresh that finds nothing to show in the active file
                    // settles into the panel's empty state — `clearAll` plus
                    // "No @Preview functions in this file" — and returns
                    // 'completed' having posted no `setPreviews` at all. That
                    // slips past `assertRefreshRendered`, which only catches
                    // 'failed'. It is also terminal: nothing re-triggers a
                    // refresh afterwards, so waiting out the budget just
                    // delays the failure and throws away the reason.
                    //
                    // Observed on this suite's own PR run (job 93235209013):
                    // the post-rename `composePreviewRenderAll` died with
                    // `composePreviewRender --preview matched no previews`
                    // and the refresh reported "0 visible previews in
                    // Previews.kt, module has 3".
                    const emptyState = emptyStateText(msgs);
                    if (emptyState) {
                        throw new Error(
                            "after the rename the panel resolved to its empty " +
                                `state instead of republishing: "${emptyState}"\n  ` +
                                describeCmpPanelState(),
                        );
                    }
                    return latestSetPreviewsMatching(msgs, (m) => {
                        if (m.previews.length === 0) return false;
                        if (!m.moduleDir.includes(cmpNeedle)) return false;
                        return (
                            m.previews.some((p) => NEW.test(p.id)) &&
                            !m.previews.some((p) => OLD.test(p.id))
                        );
                    });
                },
                describeCmpPanelState,
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
                budgetWithin(deadlineAt, PANEL_UPDATE_BUDGET_MS),
                500,
                () =>
                    newRenderOutputs.every((p) => fs.existsSync(p))
                        ? true
                        : undefined,
                describeCmpPanelState,
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
            assertRefreshRendered(
                api,
                await refreshWithinBudget(
                    ":samples:cmp render",
                    budgetWithin(deadlineAt, REFRESH_BUDGET_MS),
                    api.triggerRefresh(cmpFile, true, "full"),
                    describeCmpPanelState,
                ),
                ":samples:cmp render",
            );
            const reRefreshed = await waitFor(
                "second post-rename cmp setPreviews",
                budgetWithin(deadlineAt, PANEL_UPDATE_BUDGET_MS),
                500,
                () => {
                    const msgs = api.getPostedMessages();
                    return latestSetPreviewsMatching(
                        msgs,
                        nonEmptyForModule(cmpNeedle),
                    );
                },
                describeCmpPanelState,
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
        assertRefreshRendered(
            api,
            await refreshWithinBudget(
                ":samples:cmp render",
                budgetWithin(deadlineAt, REFRESH_BUDGET_MS),
                api.triggerRefresh(cmpFile, true, "full"),
                describeCmpPanelState,
            ),
            ":samples:cmp render",
        );
        const afterRevert = await waitFor(
            "setPreviews after reverting the rename",
            budgetWithin(deadlineAt, PANEL_UPDATE_BUDGET_MS),
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
            describeCmpPanelState,
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
        // F and G inherit whatever state a failing E left behind. E abandons
        // its refresh on timeout rather than cancelling it, so the extension's
        // `pendingRefresh` can still be in flight when F starts — and F's
        // waits would then poll a buffer nothing is writing to for the full
        // Mocha ceiling, turning one wedged render into three 30-minute
        // timeouts. Bounding these the same way E bounds its own caps the
        // cascade and makes each failure say what it saw.
        const deadlineAt = Date.now() + this.timeout();
        const observations: Record<string, unknown> = {};
        const cmpNeedle = path.join("samples", "cmp");
        const describeState = describeModulePanelState(api, cmpNeedle);

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
        assertRefreshRendered(
            api,
            await refreshWithinBudget(
                ":samples:cmp render",
                budgetWithin(deadlineAt, REFRESH_BUDGET_MS),
                api.triggerRefresh(cmpFile, true, "full"),
                describeState,
            ),
            ":samples:cmp render",
        );
        const loaded = await waitFor(
            "cmp setPreviews before dropping scope",
            budgetWithin(deadlineAt, PANEL_UPDATE_BUDGET_MS),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(cmpNeedle),
                );
            },
            describeState,
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
            budgetWithin(deadlineAt, PANEL_UPDATE_BUDGET_MS),
            200,
            () => {
                const msgs = api.getPostedMessages() as PostedMessage[];
                return msgs.find(
                    (m) => m.command === "showMessage" && m.text === EMPTY_TEXT,
                );
            },
            describeState,
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
        assertRefreshRendered(
            api,
            await refreshWithinBudget(
                ":samples:cmp render",
                budgetWithin(deadlineAt, REFRESH_BUDGET_MS),
                api.triggerRefresh(cmpFile, true, "full"),
                describeState,
            ),
            ":samples:cmp render",
        );
    });

    it("G. compile error surfaces a banner without wiping cards; fix clears it", async function () {
        api.resetMessages();
        // Same reasoning as F: bound every wait against this test's own Mocha
        // budget so an upstream wedge fails here with a diagnostic instead of
        // a bare 30-minute timeout.
        const deadlineAt = Date.now() + this.timeout();
        const observations: Record<string, unknown> = {};
        const cmpNeedle = path.join("samples", "cmp");
        const describeState = describeModulePanelState(api, cmpNeedle);

        // --- Baseline: a clean render so there are cards to keep ---
        assertRefreshRendered(
            api,
            await refreshWithinBudget(
                ":samples:cmp render",
                budgetWithin(deadlineAt, REFRESH_BUDGET_MS),
                api.triggerRefresh(cmpFile, true, "full"),
                describeState,
            ),
            ":samples:cmp render",
        );
        const baseline = await waitFor(
            "cmp baseline setPreviews before injecting the error",
            budgetWithin(deadlineAt, PANEL_UPDATE_BUDGET_MS),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(cmpNeedle),
                );
            },
            describeState,
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
            // Not `assertRefreshRendered` — this refresh is *expected* to
            // report 'failed'; the compile error is the thing under test.
            await refreshWithinBudget(
                ":samples:cmp render (with injected syntax error)",
                budgetWithin(deadlineAt, REFRESH_BUDGET_MS),
                api.triggerRefresh(cmpFile, true, "full"),
                describeState,
            );
            const errBanner = await waitFor(
                "setCompileErrors after introducing the syntax error",
                budgetWithin(deadlineAt, PANEL_UPDATE_BUDGET_MS),
                500,
                () => {
                    const msgs = api.getPostedMessages() as PostedMessage[];
                    return msgs.find((m) => {
                        if (m.command !== "setCompileErrors") return false;
                        const errors = m.errors as unknown[] | undefined;
                        return Array.isArray(errors) && errors.length > 0;
                    });
                },
                describeState,
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
        assertRefreshRendered(
            api,
            await refreshWithinBudget(
                ":samples:cmp render",
                budgetWithin(deadlineAt, REFRESH_BUDGET_MS),
                api.triggerRefresh(cmpFile, true, "full"),
                describeState,
            ),
            ":samples:cmp render",
        );
        const recovered = await waitFor(
            "clean cmp setPreviews after fixing the error",
            budgetWithin(deadlineAt, PANEL_UPDATE_BUDGET_MS),
            500,
            () => {
                const msgs = api.getPostedMessages();
                return latestSetPreviewsMatching(
                    msgs,
                    nonEmptyForModule(cmpNeedle),
                );
            },
            describeState,
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
