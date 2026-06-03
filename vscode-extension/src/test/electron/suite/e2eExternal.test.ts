import * as assert from "assert";
import { createHash } from "crypto";
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

            // Phase timings emitted as a single `[bench] {...}` JSON line at
            // the end of the suite. Captured into a `bench-result.json`
            // workflow artifact by `vscode-extension-e2e-external.yml`'s grep
            // step. Soft signal only — no assertions key off these numbers
            // (CI variance + cold Apollo/KMP config would flake any hard
            // threshold). Anchor: `t0` is suite-entry wall-clock; per-phase
            // figures are deltas from `t0` so a single timestamp suffices.
            const t0 = Date.now();
            const phases: Record<string, number> = {};
            const phaseStart = (): number => Date.now();
            const phaseEnd = (key: string, start: number): void => {
                phases[key] = Date.now() - start;
            };

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
            const tBootstrap = phaseStart();
            await api.triggerBootstrapAppliedMarkers();
            phaseEnd("bootstrapAppliedMarkersMs", tBootstrap);

            // Drive the same activation-time path the user hits: warm
            // the daemon (composePreviewDaemonStart → JVM spawn →
            // initialize → extensions/list+enable) and then refresh.
            // Surfacing the warm result separately keeps the failure
            // message actionable when initialize fails — the daemon
            // stderr tail is already in the channel log thanks to
            // issue #1326's wrapping (`formatDaemonSpawnFailure`).
            const tWarm = phaseStart();
            const warmed = await api.triggerWarmDaemon(kotlinFile);
            phaseEnd("warmDaemonMs", tWarm);
            if (!warmed) {
                // Post-mortem: surface every applied.json the workspace has on
                // disk so the CI log shows whether `composePreviewApplied` fanned
                // out to any module at all, and which `modulePath`s it wrote. A
                // null `resolveModule` with zero markers means the plugin wasn't
                // applied anywhere; a marker for `:shared` but not `:androidApp`
                // means the alias detection slipped on the test target. Limited
                // to depth 4 + skipping `.git`/`build` non-marker dirs so a KMP
                // project's `build/intermediates` tree doesn't drown the log.
                const markers: string[] = [];
                const skipTopLevel = new Set([
                    ".git",
                    "node_modules",
                    ".gradle",
                ]);
                const walk = (rel: string, depth: number): void => {
                    if (depth > 4) return;
                    let entries: fs.Dirent[];
                    try {
                        entries = fs.readdirSync(
                            path.join(workspaceRoot, rel),
                            { withFileTypes: true },
                        );
                    } catch {
                        return;
                    }
                    for (const entry of entries) {
                        if (depth === 0 && skipTopLevel.has(entry.name))
                            continue;
                        const child = rel ? `${rel}/${entry.name}` : entry.name;
                        if (entry.isFile() && entry.name === "applied.json") {
                            markers.push(child);
                            continue;
                        }
                        if (entry.isDirectory()) walk(child, depth + 1);
                    }
                };
                walk("", 0);
                console.log(
                    `[e2e-external-diag] warm aborted; ` +
                        `${markers.length} applied.json marker(s) on disk after composePreviewApplied:`,
                );
                for (const m of markers) {
                    try {
                        const body = fs.readFileSync(
                            path.join(workspaceRoot, m),
                            "utf-8",
                        );
                        console.log(
                            `[e2e-external-diag]   ${m}: ${body.trim()}`,
                        );
                    } catch (err) {
                        console.log(
                            `[e2e-external-diag]   ${m}: <read failed: ${
                                (err as Error).message
                            }>`,
                        );
                    }
                }
                // Also surface what the consumer's `:androidApp/build.gradle.kts`
                // looks like so we can tell apart "alias detection failed" from
                // "the upstream pin moved the plugin off this module entirely".
                const appBuild = path.join(
                    workspaceRoot,
                    "androidApp",
                    "build.gradle.kts",
                );
                if (fs.existsSync(appBuild)) {
                    const head = fs
                        .readFileSync(appBuild, "utf-8")
                        .split("\n")
                        .slice(0, 30)
                        .join("\n");
                    console.log(
                        `[e2e-external-diag] :androidApp/build.gradle.kts head:\n${head}`,
                    );
                } else {
                    console.log(
                        `[e2e-external-diag] :androidApp/build.gradle.kts missing — ` +
                            "upstream layout drifted",
                    );
                }
            }
            assert.ok(
                warmed,
                "warm aborted — see daemon channel log for the spawn-failure " +
                    `tail. lastWarmDaemonError=${api.getLastWarmDaemonError() ?? "<null>"}`,
            );

            const tRefresh = phaseStart();
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
            phaseEnd("firstNonEmptySetPreviewsMs", tRefresh);

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
            phaseEnd("webviewPreviewsRenderedMs", tRefresh);
            assert.ok(
                renderedSignal.count >= 1,
                `webview rendered ${renderedSignal.count} cards but ${previews.length} previews were sent`,
            );

            // Single-line JSON record for the workflow's bench-result grep.
            // The `[bench]` prefix is the contract — `vscode-extension-e2e-external.yml`
            // greps stdout for `^\[bench\] ` and writes the trailing payload
            // to `bench-result.json`. Keep all timings in milliseconds so the
            // downstream comparator doesn't have to disambiguate units.
            // Fields:
            //   scenario          — which workspace + module the numbers come from
            //   totalElapsedMs    — suite-entry to last phase, includes Gradle config
            //                       which dominates on cold runs
            //   previewCount      — guards against a regression that drops the
            //                       set; a 10x change in count makes any per-render
            //                       metric meaningless on its own
            //   renderedCount     — webview ack count; mismatch with previewCount
            //                       is the existing-assertion signal, surfaced here
            //                       too for the dashboard
            const bench = {
                scenario: "confetti-androidApp-cold",
                schemaVersion: 1,
                ...phases,
                totalElapsedMs: Date.now() - t0,
                previewCount: previews.length,
                renderedCount: renderedSignal.count,
            };
            console.log(`[bench] ${JSON.stringify(bench)}`);
        });

        // Warm edit-loop benchmark. Gated by COMPOSE_PREVIEW_E2E_EDITLOOP=1
        // (set by `npm run test:e2e-editloop`) so the default external e2e
        // stays a single cold render. Reuses the daemon + continuous-compile
        // worker the first `it` warmed. Measures the user-perceived
        // save→preview latency through the production save path
        // (`triggerSave` → dispatchSave → compile → daemon render), so it
        // reflects the `compileInProcess` / `continuousCompile` fast-compile
        // routes when those settings are enabled in the workspace's
        // `.vscode/settings.json`.
        const EDITLOOP = process.env.COMPOSE_PREVIEW_E2E_EDITLOOP === "1";
        (EDITLOOP ? it : it.skip)(
            "measures warm edit→preview latency over consecutive saves",
            async function () {
                const ITERATIONS = 4;
                // Edit 1 also pays the probe's first-image render (~130s on
                // this heavy module); edits 2+ are pure body re-renders. A
                // *changed* image that can't arrive within this window means a
                // stale render.
                const PER_EDIT_TIMEOUT_MS = 220_000;
                // Distinct, visually-obvious fills so a stale render (same
                // bytes) is provable, not just plausible.
                const marker = "ComposeAiEditLoopProbe";
                const baselineColor = "0xFFE91E63"; // pink
                const palette = [
                    "0xFF4CAF50", // green
                    "0xFF2196F3", // blue
                    "0xFFFF9800", // orange
                    "0xFF9C27B0", // purple
                ];
                const dumpDir =
                    process.env.COMPOSE_PREVIEW_EDITLOOP_DUMP ??
                    "/tmp/editloop-images";
                fs.mkdirSync(dumpDir, { recursive: true });
                const probeImageOf = (): {
                    previewId: string;
                    imageData: string;
                } | null => {
                    // Latest updateImage for the probe preview, posted since the
                    // last resetMessages.
                    const msgs = api.getPostedMessages();
                    for (let j = msgs.length - 1; j >= 0; j--) {
                        const m = msgs[j] as PostedMessage;
                        if (m.command !== "updateImage") continue;
                        const pid = String(m.previewId ?? "");
                        if (!/ComposeAiEditLoopProbe/.test(pid)) continue;
                        const data = m.imageData as string | undefined;
                        if (data && data.length > 0)
                            return { previewId: pid, imageData: data };
                    }
                    return null;
                };

                // Append a dedicated preview we can mutate deterministically,
                // independent of upstream's current Background.kt content.
                let src = fs.readFileSync(kotlinFile, "utf-8");
                if (!src.includes(marker)) {
                    src +=
                        `\n\n@androidx.compose.ui.tooling.preview.Preview(name = "${marker}")\n` +
                        `@androidx.compose.runtime.Composable\n` +
                        `fun ${marker}() {\n` +
                        `    ConfettiTheme {\n` +
                        `        ConfettiBackground(\n` +
                        `            androidx.compose.ui.Modifier.size(120.dp),\n` +
                        `            color = androidx.compose.ui.graphics.Color(${baselineColor}),\n` +
                        `            content = {},\n` +
                        `        )\n` +
                        `    }\n` +
                        `}\n`;
                    fs.writeFileSync(kotlinFile, src);
                }

                // Land the probe in the daemon's manifest before the loop via a
                // full (Gradle) refresh: it discovers + renders every preview —
                // posting setPreviews with the probe's card AND priming the
                // daemon's manifest cache — so the first loop edit is a save where
                // the probe is in-manifest and renders through the daemon. (A bare
                // triggerSave doesn't suffice: a newly-added preview's image isn't
                // rendered on the first save, and the daemon's deferred discovery
                // only runs after a render, so the card never surfaces.)
                api.resetMessages();
                await api.triggerRefresh(kotlinFile, /* force */ true, "full");
                await waitFor(
                    "probe discovered (card present)",
                    5 * 60_000,
                    300,
                    () =>
                        api.getPostedMessages().find((m) => {
                            const msg = m as PostedMessage;
                            if (msg.command !== "setPreviews") return false;
                            const previews = msg.previews as
                                | Array<{ id?: string }>
                                | undefined;
                            return previews?.some((p) =>
                                /ComposeAiEditLoopProbe/.test(
                                    String(p.id ?? ""),
                                ),
                            );
                        }),
                );

                const timingsMs: number[] = [];
                const imageHashes: string[] = [];
                // Seed prevImage with the probe's BASELINE render (its current,
                // pre-edit colour) so edit 1 must produce a *changed* image. A
                // save with no source change renders the baseline; capturing it
                // here means a baseline `updateImage` that arrives late (from the
                // prime / first daemon render) can't be accepted by edit 1 as if
                // it were edit 1's own render — which would mask a stale edit-1
                // render even while the later edits still produce distinct images
                // (Codex review on #1718).
                api.resetMessages();
                api.triggerSave(kotlinFile);
                const baseline = await waitFor(
                    "probe baseline image (pre-edit render)",
                    PER_EDIT_TIMEOUT_MS,
                    100,
                    () => probeImageOf() ?? undefined,
                );
                let prevImage: string | null = baseline.imageData;
                {
                    const buf = Buffer.from(baseline.imageData, "base64");
                    fs.writeFileSync(
                        path.join(
                            dumpDir,
                            `probe-baseline-${baselineColor.slice(2)}.png`,
                        ),
                        buf,
                    );
                    console.log(
                        `[editloop] baseline ${baselineColor} bytes=${buf.length} ` +
                            `sha=${createHash("sha256").update(buf).digest("hex").slice(0, 12)}`,
                    );
                }
                for (let i = 0; i < ITERATIONS; i++) {
                    api.resetMessages();
                    src = fs
                        .readFileSync(kotlinFile, "utf-8")
                        .replace(
                            /0xFF[0-9A-Fa-f]{6}/,
                            palette[i % palette.length],
                        );
                    fs.writeFileSync(kotlinFile, src);

                    const t0 = Date.now();
                    api.triggerSave(kotlinFile);
                    // Wait for the probe's daemon render whose pixels actually
                    // CHANGED from the previous edit. The daemon streams the PNG
                    // as base64 in `updateImage` (no disk write). Requiring a
                    // *different* image — not merely an updateImage event — is
                    // what proves live mode reflects the edit instead of
                    // re-emitting a stale render. A same-bytes render would time
                    // out here and fail the test loudly.
                    const shot = await waitFor(
                        `edit ${i + 1} → CHANGED probe image`,
                        // Bounded per-edit: a warm incremental edit that can't
                        // produce a *changed* render within this window is the
                        // stale-render bug — fail fast and loud rather than
                        // hanging until the 30-min suite timeout.
                        PER_EDIT_TIMEOUT_MS,
                        100,
                        () => {
                            const cur = probeImageOf();
                            if (!cur) return undefined;
                            if (
                                prevImage !== null &&
                                cur.imageData === prevImage
                            )
                                return undefined;
                            return cur;
                        },
                    );
                    const dt = Date.now() - t0;
                    timingsMs.push(dt);

                    const buf = Buffer.from(shot.imageData, "base64");
                    const hash = createHash("sha256")
                        .update(buf)
                        .digest("hex")
                        .slice(0, 12);
                    imageHashes.push(hash);
                    const outPath = path.join(
                        dumpDir,
                        `probe-edit-${i + 1}-${palette[i % palette.length].slice(2)}.png`,
                    );
                    fs.writeFileSync(outPath, buf);
                    console.log(
                        `[editloop] edit ${i + 1}: ${dt}ms color=${palette[i % palette.length]} ` +
                            `bytes=${buf.length} sha=${hash} -> ${outPath}`,
                    );
                    prevImage = shot.imageData;
                }

                // Every edit must have produced a distinct render — identical
                // bytes anywhere means the daemon re-served a stale image.
                assert.strictEqual(
                    new Set(imageHashes).size,
                    imageHashes.length,
                    `expected ${imageHashes.length} distinct probe renders, got hashes ${imageHashes.join(", ")} — ` +
                        "a duplicate means live mode rendered a stale image, not the edit",
                );

                const sorted = [...timingsMs].sort((a, b) => a - b);
                const median = sorted[Math.floor(sorted.length / 2)];
                console.log(
                    `[bench-editloop] ${JSON.stringify({
                        scenario: "confetti-androidApp-warm-editloop",
                        schemaVersion: 1,
                        iterations: ITERATIONS,
                        perEditMs: timingsMs,
                        medianMs: median,
                        minMs: sorted[0],
                        maxMs: sorted[sorted.length - 1],
                        continuousCompile:
                            process.env.COMPOSE_PREVIEW_EDITLOOP_LABEL ?? "",
                    })}`,
                );

                assert.ok(
                    timingsMs.every((t) => t > 0),
                    "each edit should produce a measurable render",
                );
            },
        );
    },
);
