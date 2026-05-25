import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import type { ComposePreviewTestApi } from "../../../extension";
import { RealGradleApi } from "../realGradleApi";

/**
 * End-to-end coverage for the "switching modules shows cached PNGs
 * immediately" path (issues that motivated PRs #1471 and #1481). After
 * `:samples:cmp` and `:samples:wear` have each rendered at least once,
 * activating the other module's `Previews.kt` must repaint the panel
 * from `build/compose-previews/renders/` *before* the daemon re-warms.
 *
 * Failure mode in production: `onDidChangeActiveTextEditor` either
 * skips `preloadCachedPreviews` outright, races an in-flight refresh
 * that overwrites the preloaded state, or posts the messages into an
 * unresolved webview. The panel stays on the loading skeleton for the
 * 5-15s daemon warm even though the PNGs are already on disk.
 *
 * Verification surface: the `setPreviews` + per-capture `updateImage`
 * posts logged by the production code path on a real activation. The
 * test asserts (a) both arrive within a tight 5s window — the daemon
 * warm is multi-second so anything sub-5s came from
 * `preloadCachedPreviews`'s on-disk read, (b) the `updateImage`
 * payloads carry non-empty `imageData`, and (c) the previews referenced
 * by `setPreviews` belong to the freshly-active module.
 *
 * Gated on `COMPOSE_PREVIEW_E2E=1`. The prime phases call
 * `triggerRefresh` against each module which pays the full cold-Gradle
 * + cold-daemon cost — this suite is part of the slow `npm run
 * test:e2e` bucket, not the fast unit-test loop. Cold render for both
 * `:samples:cmp` and `:samples:wear` is the dominant time sink.
 *
 * Module pair: `:samples:cmp` + `:samples:wear` matches the original
 * manual repro the user filed and exercises both renderer paths
 * (desktop + Android/Robolectric).
 */

const E2E = process.env.COMPOSE_PREVIEW_E2E === "1";
const describeE2E = E2E ? describe : describe.skip;

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

function cleanRenderDir(renderDir: string): void {
    if (!fs.existsSync(renderDir)) return;
    for (const entry of fs.readdirSync(renderDir)) {
        fs.rmSync(path.join(renderDir, entry), {
            recursive: true,
            force: true,
        });
    }
}

function listPngs(renderDir: string): string[] {
    if (!fs.existsSync(renderDir)) return [];
    return fs.readdirSync(renderDir).filter((n) => n.endsWith(".png"));
}

describeE2E("Compose Preview cached preload on module switch", function () {
    // Cold paths for `:samples:cmp` + `:samples:wear` both run inside
    // this suite (sibling e2e* suites may share the Gradle cache but we
    // can't rely on it — the wear cold daemon spawn alone is ~10s on a
    // warm host, multi-minute on a fresh CI runner). 20 minutes matches
    // the combined ceiling of the cmp + wear e2e suites and leaves head
    // room under the workflow's 60m cap.
    this.timeout(20 * 60_000);

    let api: ComposePreviewTestApi;
    let repoRoot: string;
    let cmpKotlinFile: string;
    let wearKotlinFile: string;
    let cmpRenderDir: string;
    let wearRenderDir: string;

    before(async () => {
        const folders = vscode.workspace.workspaceFolders;
        assert.ok(folders && folders.length > 0, "workspace must be open");
        repoRoot = folders[0].uri.fsPath;

        cmpKotlinFile = path.join(
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
        wearKotlinFile = path.join(
            repoRoot,
            "samples",
            "wear",
            "src",
            "main",
            "kotlin",
            "com",
            "example",
            "samplewear",
            "Previews.kt",
        );
        assert.ok(
            fs.existsSync(cmpKotlinFile),
            `expected cmp fixture at ${cmpKotlinFile}`,
        );
        assert.ok(
            fs.existsSync(wearKotlinFile),
            `expected wear fixture at ${wearKotlinFile}`,
        );

        cmpRenderDir = path.join(
            repoRoot,
            "samples",
            "cmp",
            "build",
            "compose-previews",
            "renders",
        );
        wearRenderDir = path.join(
            repoRoot,
            "samples",
            "wear",
            "build",
            "compose-previews",
            "renders",
        );
        // The "preload reads fresh PNGs" assertion below must verify
        // *this run's* renders, not an artifact from a developer's last
        // build. Clean both before priming.
        cleanRenderDir(cmpRenderDir);
        cleanRenderDir(wearRenderDir);

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
            new RealGradleApi(repoRoot, (line) => console.log(line)),
        );

        // Resolve the webview view. Without this, `panel.view` stays
        // undefined and every `panel.postMessage` is silently dropped —
        // `preloadCachedPreviews`'s `setPreviews` / `updateImage` posts
        // never reach a webview that could ack them, and the
        // `getPostedMessages()` scan below would still see them in the
        // host's log (the log captures attempts), so the test could
        // false-pass while real users still saw an empty panel. See the
        // matching note in `e2e.test.ts`.
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

        // Prime both modules in fixed order: cmp first (faster cold
        // path), then wear. Each render leaves PNGs on disk that the
        // module-switch test below reads via `preloadCachedPreviews`.
        // Use `triggerRefresh` rather than the production
        // `showTextDocument` path because the prime phase wants to
        // *force* the render-all (no force flag on the activation
        // path), and the message log doesn't need to be tidy yet.
        console.log("[preload-e2e] priming :samples:cmp");
        api.resetMessages();
        await api.triggerRefresh(cmpKotlinFile, /* force */ true, "full");
        await waitFor(
            "non-empty setPreviews for samples/cmp prime",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                for (let i = msgs.length - 1; i >= 0; i--) {
                    const m = msgs[i] as PostedMessage;
                    if (m.command !== "setPreviews") continue;
                    const previews = m.previews as Array<unknown> | undefined;
                    if (previews && previews.length > 0) return m;
                }
                return undefined;
            },
        );
        assert.ok(
            listPngs(cmpRenderDir).length >= 2,
            `cmp prime produced no PNGs in ${cmpRenderDir}`,
        );

        console.log("[preload-e2e] priming :samples:wear");
        api.resetMessages();
        await api.triggerRefresh(wearKotlinFile, /* force */ true, "full");
        await waitFor(
            "non-empty setPreviews for samples/wear prime",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                for (let i = msgs.length - 1; i >= 0; i--) {
                    const m = msgs[i] as PostedMessage;
                    if (m.command !== "setPreviews") continue;
                    const previews = m.previews as Array<unknown> | undefined;
                    if (previews && previews.length > 0) return m;
                }
                return undefined;
            },
        );
        assert.ok(
            listPngs(wearRenderDir).length >= 2,
            `wear prime produced no PNGs in ${wearRenderDir}`,
        );
    });

    it("paints cached PNGs from disk when switching to a previously-rendered module", async () => {
        // Anchor on cmp before the switch under test, so the
        // `onDidChangeActiveTextEditor` transition has cmp → wear shape
        // (not "wear is already active" which would no-op). Use
        // `showTextDocument` rather than `triggerRefresh` because the
        // production preload path hangs off `onDidChangeActiveTextEditor`,
        // which only fires for actual editor focus changes.
        await vscode.window.showTextDocument(vscode.Uri.file(cmpKotlinFile), {
            preview: false,
        });
        // Let the cmp activation settle (preload + refresh kickoff) so
        // its in-flight refresh isn't dangling when we switch. 1s is
        // generous — the preload itself is <100ms; the refresh
        // continuation that follows is the slow part and would be
        // aborted by the switch anyway.
        await new Promise((r) => setTimeout(r, 1000));

        api.resetMessages();
        const switchedAt = Date.now();
        await vscode.window.showTextDocument(vscode.Uri.file(wearKotlinFile), {
            preview: false,
        });

        // 5 seconds is below any plausible daemon cold-warm time
        // (Robolectric init alone runs ~5-15s on the user's hardware
        // and CI shows the same shape). A `setPreviews` arriving in
        // this window cannot have come from a fresh daemon render —
        // it must be from `preloadCachedPreviews` reading
        // `previews.json` + PNGs straight off disk.
        const PRELOAD_WINDOW_MS = 5_000;

        const preloadSetPreviews = await waitFor<PostedMessage>(
            "preload-source setPreviews for wear",
            PRELOAD_WINDOW_MS,
            50,
            () => {
                const msgs = api.getPostedMessages();
                for (const raw of msgs) {
                    const m = raw as PostedMessage;
                    if (m.command !== "setPreviews") continue;
                    const previews = m.previews as
                        | Array<{ id?: string }>
                        | undefined;
                    if (!previews || previews.length === 0) continue;
                    // Bind to wear by checking the previews' fully-qualified
                    // names — `preloadCachedPreviews` filters by file path,
                    // so anything else means the wrong module's manifest
                    // got loaded.
                    if (
                        previews.some(
                            (p) =>
                                typeof p.id === "string" &&
                                p.id.includes("samplewear"),
                        )
                    ) {
                        return m;
                    }
                }
                return undefined;
            },
        );
        const previewsArrivedMs = Date.now() - switchedAt;
        console.log(
            `[preload-e2e] preload setPreviews arrived ${previewsArrivedMs}ms after switch ` +
                `with ${(preloadSetPreviews.previews as Array<unknown>).length} preview(s)`,
        );
        assert.ok(
            previewsArrivedMs < PRELOAD_WINDOW_MS,
            `setPreviews took ${previewsArrivedMs}ms — outside preload window, must have come from daemon`,
        );

        // Per-capture `updateImage` carrying real PNG bytes (the cached
        // renders) is the second half of `preloadCachedPreviews`'s
        // output. Without it the grid paints empty cards with the right
        // metadata — exactly the broken UX the user has been hitting.
        const updateImage = await waitFor<PostedMessage>(
            "preload-source updateImage with non-empty imageData",
            PRELOAD_WINDOW_MS,
            50,
            () => {
                const msgs = api.getPostedMessages();
                for (const raw of msgs) {
                    const m = raw as PostedMessage;
                    if (m.command !== "updateImage") continue;
                    const imageData = m.imageData as string | undefined;
                    if (typeof imageData !== "string") continue;
                    if (imageData.length === 0) continue;
                    return m;
                }
                return undefined;
            },
        );
        const imagesArrivedMs = Date.now() - switchedAt;
        console.log(
            `[preload-e2e] preload updateImage arrived ${imagesArrivedMs}ms after switch`,
        );
        assert.ok(
            imagesArrivedMs < PRELOAD_WINDOW_MS,
            `updateImage took ${imagesArrivedMs}ms — outside preload window`,
        );
        // `previewId` should reference a wear preview — anything else
        // means an in-flight cmp refresh slipped its updateImage in
        // ahead of wear's preload, which is the original race.
        const previewId = updateImage.previewId as string | undefined;
        assert.ok(
            typeof previewId === "string" && previewId.includes("samplewear"),
            `updateImage previewId="${previewId ?? "<missing>"}" — expected a samplewear id`,
        );

        // Round-trip sanity: the webview must actually paint the cards
        // (not just the host posting into the void). `webviewPreviewsRendered`
        // fires from the live webview's render path and is the same
        // ack the existing e2e suites use to lock down "host post →
        // webview consumed". A preload that loses its message in the
        // resolved-webview boundary would fail here.
        const renderedAck = await waitFor<{ command: string; count: number }>(
            "webviewPreviewsRendered ack after preload",
            PRELOAD_WINDOW_MS,
            50,
            () => {
                const inbound = api.getReceivedMessages();
                for (let i = inbound.length - 1; i >= 0; i--) {
                    const m = inbound[i] as {
                        command: string;
                        count?: number;
                    };
                    if (m.command !== "webviewPreviewsRendered") continue;
                    if (!m.count || m.count <= 0) continue;
                    return m as { command: string; count: number };
                }
                return undefined;
            },
        );
        assert.ok(
            renderedAck.count > 0,
            `webview rendered 0 cards from preload`,
        );
    });
});
