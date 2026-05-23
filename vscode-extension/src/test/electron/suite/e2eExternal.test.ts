import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import type { ComposePreviewTestApi } from "../../../extension";
import { RealGradleApi } from "../realGradleApi";

/**
 * External-consumer end-to-end test. Drives the VS Code extension against a
 * third-party Gradle project that resolves the Compose Preview plugin from
 * Maven Central via a version-catalog alias — the *published* coordinate
 * path, not the `includeBuild("gradle-plugin")` dev-loop the existing
 * `:samples:cmp` / `:samples:wear` e2e exercises.
 *
 * Why a separate suite: the in-repo e2e is a fast feedback loop, but it
 * misses every regression that hinges on the plugin being resolved as a
 * published artifact against a real consumer's classpath. Issues this
 * suite is positioned to catch (none of which `:samples:*` will surface):
 *
 *   - **Stale `daemon-launch.json`** after a plugin upgrade — the
 *     extension reuses the cached descriptor, the JVM spawns against a
 *     mismatched JAR, `initialize` throws.
 *   - **Classpath fingerprint mismatch** (`classpathDirty` notification)
 *     when AGP/Kotlin/Compose drift between renderer and consumer.
 *   - **First-spawn failure** memo eviction
 *     (`daemonBootstrappedModules.delete(moduleKey)`) — the "already
 *     bootstrapped this session but daemon is not ready" branch in
 *     `warmDaemonForFile`.
 *   - **Catalog-alias detection** — Confetti's modules apply the plugin
 *     via `alias(libs.plugins.composeai.preview)`. The literal
 *     `appliesPlugin` regex doesn't match catalog aliases, so the panel
 *     relies entirely on the `applied.json` marker the
 *     `composePreviewApplied` task writes during the first Gradle run.
 *     Verifies that path works end-to-end on a project that has never
 *     written the marker before.
 *
 * Gated on `COMPOSE_PREVIEW_E2E_EXTERNAL=1` (set by `npm run
 * test:e2e-external` and the matching CI workflow). Skipped silently
 * everywhere else; cold cost on a fresh CI runner is multi-tens of
 * minutes because the consumer brings its own KMP + Apollo + Firebase
 * configuration phase before any Compose Preview task runs.
 *
 * Workspace shape: `runTest.ts` opens the path from
 * `COMPOSE_PREVIEW_E2E_WORKSPACE` as the VS Code workspace. The matching
 * CI workflow materialises that path via `setup-external-e2e.sh`, which
 * clones Confetti at a pinned SHA, rewrites its catalog to point at the
 * local SNAPSHOT, and seeds `local.properties`. The Confetti pin is
 * intentional — bumping it is a deliberate "we want to test against
 * newer downstream code" decision, not an automatic update.
 *
 * Module under test: `:androidApp`. Picked because it's the simplest
 * plugin-applied module in Confetti (no KMP shared sources, no Wear OS
 * Robolectric SDK gymnastics). The `:shared` and `:wearApp` modules are
 * deliberately out of scope for the first cut — they widen the failure
 * surface to KMP commonMain visibility and Wear/Robolectric SDK
 * alignment, both of which deserve their own follow-up suite.
 */

const E2E_EXTERNAL = process.env.COMPOSE_PREVIEW_E2E_EXTERNAL === "1";
const describeExternal = E2E_EXTERNAL ? describe : describe.skip;

interface PostedMessage {
    command: string;
    [key: string]: unknown;
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

describeExternal(
    "Compose Preview external-consumer e2e (real Gradle, published plugin)",
    function () {
        // Confetti's first cold configuration alone takes 2-5 min (Apollo
        // codegen, KMP target setup, Firebase plugin), and the daemon's
        // first render adds another few. 30 minutes is the same ceiling
        // CI sets for the workflow timeout; the hook below trips first
        // on a real wedge.
        this.timeout(30 * 60_000);

        let api: ComposePreviewTestApi;
        let kotlinFile: string;
        let workspaceRoot: string;
        let renderDir: string;

        before(async function () {
            this.timeout(30 * 60_000);

            const folders = vscode.workspace.workspaceFolders;
            assert.ok(
                folders && folders.length > 0,
                "external workspace must be open — runTest.ts should have " +
                    "passed COMPOSE_PREVIEW_E2E_WORKSPACE through to VS Code",
            );
            workspaceRoot = folders[0].uri.fsPath;

            // Sanity-check the workspace shape so a misconfigured runner
            // fails loud at `before` rather than with an opaque
            // `resolveModule returned null` later. `androidApp/` and the
            // catalog entry are the two markers we keyed the suite to;
            // changing them needs to be a conscious decision.
            kotlinFile = path.join(
                workspaceRoot,
                "androidApp",
                "src",
                "main",
                "java",
                "dev",
                "johnoreilly",
                "confetti",
                "ui",
                "component",
                "Background.kt",
            );
            assert.ok(
                fs.existsSync(kotlinFile),
                `expected external workspace fixture file at ${kotlinFile} — ` +
                    "did setup-external-e2e.sh run against the pinned SHA?",
            );

            const catalogPath = path.join(
                workspaceRoot,
                "gradle",
                "libs.versions.toml",
            );
            const catalogText = fs.readFileSync(catalogPath, "utf-8");
            assert.match(
                catalogText,
                /^composeai-preview\s*=\s*"[^"]+"/m,
                `expected composeai-preview catalog entry in ${catalogPath} ` +
                    "(the catalog rewrite in setup-external-e2e.sh is the wiring " +
                    "for COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL to pick up the local SNAPSHOT)",
            );

            renderDir = path.join(
                workspaceRoot,
                "androidApp",
                "build",
                "compose-previews",
                "renders",
            );
            // Clean any prior renders so the assertion below proves this
            // run produced PNGs, not a stale artifact from a developer's
            // last local build of the same workspace path.
            if (fs.existsSync(renderDir)) {
                for (const entry of fs.readdirSync(renderDir)) {
                    fs.rmSync(path.join(renderDir, entry), {
                        recursive: true,
                        force: true,
                    });
                }
            }

            const ext = vscode.extensions.getExtension<ComposePreviewTestApi>(
                "yuri-schimke.compose-preview",
            );
            assert.ok(ext, "compose-preview extension must be present");
            const exported = await ext.activate();
            assert.ok(
                exported,
                "activate() must return ComposePreviewTestApi under COMPOSE_PREVIEW_TEST_MODE=1",
            );
            api = exported;
            api.injectGradleApi(
                new RealGradleApi(
                    workspaceRoot,
                    (line) => console.log(line),
                    // Opt the init-script into mavenLocal so the local
                    // SNAPSHOT the workflow published is the plugin
                    // Gradle resolves. Without this the catalog entry
                    // routes to Maven Central, which has the *previously
                    // released* version — defeating the point of
                    // exercising HEAD against a real consumer.
                    [],
                ),
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

        it("warms the daemon for :androidApp and renders at least one preview", async function () {
            api.resetMessages();

            // Confetti's `:androidApp` applies the compose-preview plugin
            // via `alias(libs.plugins.composeai.preview)` — the literal
            // `id(...)` text-scan in `appliesPlugin` doesn't match catalog
            // aliases, so `resolveModule` stays null until Gradle writes
            // `applied.json` for the module. The activation-time
            // `bootstrapAppliedMarkers` call is fire-and-forget AND
            // targets the original GradleService (the one `injectGradleApi`
            // above replaced), so without an explicit await the warm below
            // races the marker write and intermittently fails on cold
            // workspaces. Regression for #1362.
            await api.triggerBootstrapAppliedMarkers();

            // Drive the same activation-time path the user hits: warm
            // the daemon (composePreviewDaemonStart → JVM spawn →
            // initialize → extensions/list+enable) and then refresh.
            // Surfacing the warm result separately keeps the failure
            // message actionable when initialize fails — the daemon
            // stderr tail is already in the channel log thanks to
            // issue #1326's wrapping (`formatDaemonSpawnFailure`).
            const warmed = await api.triggerWarmDaemon(kotlinFile);
            assert.ok(
                warmed,
                "warm aborted — see daemon channel log for the spawn-failure " +
                    `tail. lastWarmDaemonError=${api.getLastWarmDaemonError() ?? "<null>"}`,
            );

            await api.triggerRefresh(kotlinFile, /* force */ true, "full");

            const previewsMessage = await waitFor(
                "non-empty setPreviews from external composePreviewRenderAll",
                this.timeout(),
                500,
                () => {
                    const msgs = api.getPostedMessages();
                    // Scan in reverse so the suite latches onto the
                    // populated payload, not the initial empty one the
                    // refresh flow emits before Gradle finishes.
                    for (let i = msgs.length - 1; i >= 0; i--) {
                        const m = msgs[i] as PostedMessage;
                        if (m.command !== "setPreviews") continue;
                        const previews = m.previews as
                            | Array<unknown>
                            | undefined;
                        if (previews && previews.length > 0) return m;
                    }
                    return undefined;
                },
            );

            const previews = previewsMessage.previews as Array<{
                id: string;
            }>;
            console.log(
                `[e2e-external] received ${previews.length} previews: ` +
                    previews
                        .map((p) => p.id)
                        .slice(0, 10)
                        .join(", ") +
                    (previews.length > 10 ? ", ..." : ""),
            );

            // The bar is intentionally low — `>= 1` proves the chain is
            // intact end-to-end. Pinning a higher number couples the
            // suite to Confetti's preview count, which drifts when
            // upstream adds or removes `@Preview`s. The smoke is "did
            // anything render through the published-plugin path."
            assert.ok(
                previews.length >= 1,
                `expected at least 1 preview from :androidApp, got ${previews.length}`,
            );

            const pngs = fs.existsSync(renderDir)
                ? fs.readdirSync(renderDir).filter((n) => n.endsWith(".png"))
                : [];
            assert.ok(
                pngs.length >= 1,
                `expected at least 1 rendered PNG in ${renderDir}, found ${pngs.length}`,
            );

            // Wait for the webview ack so a regression that posts into
            // an unresolved view (the bug e2e.test.ts already
            // regression-locks against `:samples:cmp`) also fires here
            // against the published plugin path. The matching
            // assertion shape there is what to mirror — same comment
            // applies about postedMessageLog being insufficient on its
            // own.
            const renderedSignal = await waitFor(
                "webviewPreviewsRendered from the live webview",
                this.timeout(),
                500,
                () => {
                    const inbound = api.getReceivedMessages();
                    const m = inbound.find(
                        (raw) =>
                            (raw as PostedMessage).command ===
                            "webviewPreviewsRendered",
                    ) as { command: string; count: number } | undefined;
                    if (!m || m.count <= 0) return undefined;
                    return m;
                },
            );
            assert.ok(
                renderedSignal.count >= 1,
                `webview rendered ${renderedSignal.count} cards but ${previews.length} previews were sent`,
            );
        });
    },
);
