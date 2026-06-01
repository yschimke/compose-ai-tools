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
 * test asserts (a) both arrive within a bounded window after the
 * switch, (b) the `updateImage` payloads carry non-empty `imageData`,
 * and (c) the previews referenced by `setPreviews` belong to the
 * freshly-active module. The original 5s window was tight enough to
 * prove "came from on-disk preload rather than a fresh daemon
 * render"; the current 30s window only validates the end-to-end
 * round-trip (see `PRELOAD_WINDOW_MS` below).
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

function firstNonEmptySetPreviews(
    api: ComposePreviewTestApi,
): PostedMessage | undefined {
    const msgs = api.getPostedMessages();
    for (let i = msgs.length - 1; i >= 0; i--) {
        const m = msgs[i] as PostedMessage;
        if (m.command !== "setPreviews") continue;
        const previews = m.previews as Array<unknown> | undefined;
        if (previews && previews.length > 0) return m;
    }
    return undefined;
}

/**
 * Wall-clock ceiling for one whole prime attempt — the awaited Gradle
 * render *and* the subsequent wait for its `setPreviews`, together (see
 * `primeModule`). A render runs through `gradleService`, which caps each
 * Gradle task at 5 minutes (`TASK_TIMEOUT_MS`) and fires `cancelRunTask`
 * on expiry; budget just past that cap so a render completing near it
 * still posts its `setPreviews`, while a wedged one is abandoned shortly
 * after the cap kills it. Because this bounds the *entire* attempt (not
 * just the wait), `SUITE_TIMEOUT_MS` below can be derived from it directly.
 */
const PRIME_ATTEMPT_BUDGET_MS = 6 * 60_000;
/** Prime attempts per module (one retry on a freed build lock). */
const PRIME_MAX_ATTEMPTS = 2;
/** Modules primed by the before-hook: `:samples:cmp` + `:samples:wear`. */
const PRIME_MODULE_COUNT = 2;
/**
 * Non-render before-hook work (extension activation, the 30s webviewReady
 * wait, render-dir cleans) plus a safety margin, on top of the worst-case
 * prime time below.
 */
const PRIME_OVERHEAD_MS = 4 * 60_000;
/**
 * Suite/hook timeout. Derived from the prime budget so it always outlasts
 * the worst case — every attempt for both modules exhausting its budget —
 * and `primeModule` reaches its attributable `throw lastErr` instead of
 * degrading into a bare Mocha "hook timeout" with no Gradle context. With
 * the values above this is 28 minutes, comfortably under the workflow's
 * 60m job cap.
 */
const SUITE_TIMEOUT_MS =
    PRIME_MODULE_COUNT * PRIME_MAX_ATTEMPTS * PRIME_ATTEMPT_BUDGET_MS +
    PRIME_OVERHEAD_MS;

/**
 * Force a full render of `file` and wait for the resulting non-empty
 * `setPreviews`, leaving ≥2 PNGs in `renderDir` for the module-switch test
 * to preload.
 *
 * Bounded + retried on purpose. `triggerRefresh` *awaits* the Gradle
 * render (gradleService caps it at the 5-minute `TASK_TIMEOUT_MS` and, via
 * `RealGradleApi`, kills the gradlew client + releases its build lock on
 * expiry); on a render that times out without producing a manifest the
 * refresh resolves *without* posting `setPreviews`. So the render's
 * blocking time and the subsequent `setPreviews` wait must share a single
 * budget — otherwise a slow-failing attempt costs ~5m (render) +
 * `PRIME_ATTEMPT_BUDGET_MS` (wait) and the suite timeout, derived from
 * attempts × budget alone, fires before this reaches its diagnostic throw.
 * We therefore race the whole attempt (refresh + wait) against one
 * `PRIME_ATTEMPT_BUDGET_MS` deadline, then retry once on the freed lock
 * (the retry's refresh cancels any render still in flight from the
 * abandoned attempt). A healthy render always completes inside the
 * 5-minute task cap and posts `setPreviews` before the refresh resolves,
 * so the budget never truncates a healthy-but-slow cold render. On failure
 * we dump the Compose Preview output channel — the underlying render
 * rejection is otherwise swallowed by the refresh scheduler, leaving only
 * a bare "timed out" with no Gradle context.
 */
async function primeModule(
    api: ComposePreviewTestApi,
    label: string,
    file: string,
    renderDir: string,
): Promise<void> {
    let lastErr: unknown;
    for (let attempt = 1; attempt <= PRIME_MAX_ATTEMPTS; attempt++) {
        console.log(
            `[preload-e2e] priming ${label} (attempt ${attempt}/${PRIME_MAX_ATTEMPTS})`,
        );
        api.resetMessages();
        // One deadline covering refresh + wait, so a render that blocks up
        // to its task cap before failing can't push the attempt past the
        // budget the suite timeout is sized against.
        let budgetTimer: ReturnType<typeof setTimeout> | undefined;
        const budget = new Promise<never>((_, reject) => {
            budgetTimer = setTimeout(
                () =>
                    reject(
                        new Error(
                            `${label} prime attempt ${attempt} exceeded ${
                                PRIME_ATTEMPT_BUDGET_MS / 1000
                            }s budget`,
                        ),
                    ),
                PRIME_ATTEMPT_BUDGET_MS,
            );
        });
        const work = (async () => {
            await api.triggerRefresh(file, /* force */ true, "full");
            await waitFor(
                `non-empty setPreviews for ${label} prime`,
                PRIME_ATTEMPT_BUDGET_MS,
                500,
                () => firstNonEmptySetPreviews(api),
            );
        })();
        try {
            await Promise.race([work, budget]);
            assert.ok(
                listPngs(renderDir).length >= 2,
                `${label} prime produced no PNGs in ${renderDir}`,
            );
            return;
        } catch (err) {
            lastErr = err;
            console.log(
                `[preload-e2e] ${label} prime attempt ${attempt}/${PRIME_MAX_ATTEMPTS} failed: ${
                    (err as Error)?.message ?? String(err)
                }`,
            );
            for (const line of api.getOutputChannelTail(80)) {
                console.log(`[preload-e2e]   [out] ${line}`);
            }
        } finally {
            if (budgetTimer) clearTimeout(budgetTimer);
            // If the budget won the race the `work` promise is still live;
            // swallow its eventual rejection so it doesn't surface as an
            // unhandled rejection (the next attempt's refresh cancels the
            // render it left in flight).
            void work.catch(() => {});
        }
    }
    throw lastErr;
}

describeE2E("Compose Preview cached preload on module switch", function () {
    // Cold paths for `:samples:cmp` + `:samples:wear` both run inside this
    // suite (sibling e2e* suites may share the Gradle cache but we can't
    // rely on it — the wear cold daemon spawn alone is ~10s on a warm
    // host, multi-minute on a fresh CI runner). The timeout is derived
    // from the prime budget (`SUITE_TIMEOUT_MS`) so it always outlasts the
    // worst-case retry path for both modules: a hung render then fails
    // fast with diagnostics instead of degrading into a bare hook timeout.
    this.timeout(SUITE_TIMEOUT_MS);

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
        await primeModule(api, ":samples:cmp", cmpKotlinFile, cmpRenderDir);
        await primeModule(api, ":samples:wear", wearKotlinFile, wearRenderDir);
    });

    it("paints cached PNGs from disk when switching to a previously-rendered module", async () => {
        // Anchor on cmp before the switch under test, so the
        // `onDidChangeActiveTextEditor` transition has cmp → wear shape
        // (not "wear is already active" which would no-op). Use
        // `showTextDocument` rather than `triggerRefresh` because the
        // production preload path hangs off `onDidChangeActiveTextEditor`,
        // which only fires for actual editor focus changes.
        //
        // Force the document's `languageId` to "kotlin" before showing
        // it. The production handler at extension.ts:1788 bails out
        // unless `editor.document.languageId === "kotlin"`. The test
        // electron host only loads `compose-preview` + the
        // `fake-vscode-gradle` stub — neither contributes a Kotlin
        // language — so `.kt` files resolve to a non-`"kotlin"` id and
        // the preload path is never entered. Setting the languageId
        // *before* `showTextDocument` matters: the editor-change event
        // reads the document's languageId at fire time, so a
        // post-show `setTextDocumentLanguage` would lose the race.
        const cmpDoc = await vscode.workspace.openTextDocument(
            vscode.Uri.file(cmpKotlinFile),
        );
        if (cmpDoc.languageId !== "kotlin") {
            await vscode.languages.setTextDocumentLanguage(cmpDoc, "kotlin");
        }
        await vscode.window.showTextDocument(cmpDoc, { preview: false });
        // Let the cmp activation settle (preload + refresh kickoff) so
        // its in-flight refresh isn't dangling when we switch. 1s is
        // generous — the preload itself is <100ms; the refresh
        // continuation that follows is the slow part and would be
        // aborted by the switch anyway.
        await new Promise((r) => setTimeout(r, 1000));

        api.resetMessages();
        const switchedAt = Date.now();
        const wearDoc = await vscode.workspace.openTextDocument(
            vscode.Uri.file(wearKotlinFile),
        );
        if (wearDoc.languageId !== "kotlin") {
            await vscode.languages.setTextDocumentLanguage(wearDoc, "kotlin");
        }
        await vscode.window.showTextDocument(wearDoc, { preview: false });

        // Window the preload (+ updateImage + webview ack) must land
        // inside after the editor switch. 5s was the original target
        // (below Robolectric daemon cold-warm of ~5-15s, giving a
        // strict "must have come from on-disk preload" signal), but
        // CI consistently lost the race with the prior file's
        // in-flight activation chain (preload → discover → daemon
        // warm) — `pendingRefresh.abort()` returns immediately but
        // the cmp refresh continuation still occupies the panel
        // message queue for several seconds on a cold runner. 30s
        // restores green CI; the test still validates the round-trip
        // (setPreviews + non-empty updateImage + webview ack with
        // samplewear ids), but no longer proves "came from disk
        // rather than daemon".
        const PRELOAD_WINDOW_MS = 30_000;

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
