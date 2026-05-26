import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import type { ComposePreviewTestApi } from "../../../extension";
import { RealGradleApi } from "../realGradleApi";

/**
 * End-to-end test for the subscription-driven accessibility chain
 * (issue #1006, follow-up to PR #1007). Drives the extension against a
 * real Gradle build of `:samples:wear` so the full chip → daemon →
 * attachment → webview chain is exercised: a focus-inspector chip
 * toggle posts `setDataExtensionEnabled`, the daemon rerenders in
 * `mode=a11y`, ships `a11y/atf` / `a11y/hierarchy` attachments via
 * `renderFinished`, and the webview applies the resulting overlays.
 *
 * Gated on `COMPOSE_PREVIEW_E2E=1` alongside the existing real-Gradle
 * suite in `e2e.test.ts`. Skipped silently in the fast suite (cold
 * Gradle + first daemon spawn for wear runs multiple minutes).
 *
 * The verification surface is the `webviewA11yState` ack
 * (webview→extension) the panel emits after `applyA11yUpdate`. Reading
 * the webview's DOM directly is awkward across the extension-host /
 * webview boundary, and asserting on the host-side `updateA11y` post
 * alone wouldn't catch a webview-side regression in the cache write or
 * overlay paint. Mirrors the `webviewPreviewsRendered` ack pattern the
 * cmp e2e already uses.
 *
 * Setup: the daemon's accessibility data-product registry registers
 * unconditionally now — a11y is driven entirely by per-preview
 * `data/subscribe` calls coming in from the chip toggle (see
 * `handleSetDataExtensionEnabled`). The focus-inspector chips
 * themselves are gated on `composePreview.earlyFeatures.enabled`; the
 * test flips it on at Global scope (isolated to
 * `.vscode-test/user-data/`) and restores it after the run.
 */

const E2E = process.env.COMPOSE_PREVIEW_E2E === "1";
const describeE2E = E2E ? describe : describe.skip;

interface PostedMessage {
    command: string;
    [key: string]: unknown;
}

interface A11yStateAck extends PostedMessage {
    command: "webviewA11yState";
    previewId: string;
    findingsCount: number | null;
    nodesCount: number | null;
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

/**
 * Dumps the daemon output channel tail + the latest host-side message
 * traffic to the workflow log when a `waitFor` for a webview ack times
 * out. The wear a11y suite has historically failed silently (60s mocha
 * cap with no actionable detail) because the scheduler swallows
 * `dataSubscribe` / post-subscribe `renderNow` rejections — only the
 * `Compose Preview` output channel records the reason, and that channel
 * isn't persisted by `@vscode/test-electron`'s log uploads. Reading the
 * tail via the test API surfaces it on stdout where the workflow log
 * captures it.
 *
 * `tailLines` is a rough bound on signal-to-noise: ~120 lines covers
 * the latest renderAll + warm + subscribe sequence comfortably without
 * burying the recent failure under cmp suite history.
 */
function dumpA11yFailureDiagnostics(
    api: ComposePreviewTestApi,
    label: string,
    previewId: string,
    tailLines = 120,
): void {
    const tail = api.getOutputChannelTail(tailLines);
    const posted = api.getPostedMessages();
    const received = api.getReceivedMessages();
    console.log(
        `[e2e-a11y-diag] ${label} previewId=${previewId} ` +
            `posted=${posted.length} received=${received.length} ` +
            `outputChannelTail=${tail.length}line(s)`,
    );
    for (const line of tail) {
        console.log(`[e2e-a11y-diag/channel] ${line}`);
    }
    // Compact the chatty `setProgress`/`clearProgress` stream so they don't
    // crowd out the actually-interesting `updateA11y` / `webviewA11yState` /
    // `updateDataProducts` lines when the buffer holds dozens of progress
    // frames per render. Each summarised group counts the run; the first
    // non-progress message ends the group.
    interface Counted {
        kind: "single" | "progress-group";
        text: string;
    }
    const compact = (msgs: unknown[]): Counted[] => {
        const out: Counted[] = [];
        let groupCmd: string | null = null;
        let groupCount = 0;
        const flush = () => {
            if (groupCmd && groupCount > 0) {
                out.push({
                    kind: "progress-group",
                    text: `${groupCmd} ×${groupCount}`,
                });
            }
            groupCmd = null;
            groupCount = 0;
        };
        for (const raw of msgs) {
            const m = raw as {
                command?: string;
                previewId?: string;
                nodesCount?: unknown;
                findingsCount?: unknown;
                kinds?: unknown;
                findings?: unknown[];
                nodes?: unknown[];
                dataProducts?: unknown[];
            };
            const cmd = m?.command ?? "<no-command>";
            if (cmd === "setProgress" || cmd === "clearProgress") {
                if (cmd === groupCmd) {
                    groupCount++;
                } else {
                    flush();
                    groupCmd = cmd;
                    groupCount = 1;
                }
                continue;
            }
            flush();
            const fields: string[] = [];
            if (typeof m?.previewId === "string") {
                fields.push(`previewId=${m.previewId}`);
            }
            // Detail fields useful for the wear-a11y diagnosis: full
            // count surface of webviewA11yState, plus the upstream
            // updateA11y shape that drives it (findings/nodes arrays
            // on host posts). `updateDataProducts` carries the daemon's
            // attached payload kinds, which is what we ultimately need
            // to know "did the daemon emit a11y data this render?".
            if ("nodesCount" in m) fields.push(`nodesCount=${m.nodesCount}`);
            if ("findingsCount" in m)
                fields.push(`findingsCount=${m.findingsCount}`);
            if (Array.isArray(m?.findings))
                fields.push(`findings=${m.findings.length}`);
            if (Array.isArray(m?.nodes)) fields.push(`nodes=${m.nodes.length}`);
            if (Array.isArray(m?.dataProducts)) {
                const kinds = (m.dataProducts as Array<{ kind?: string }>).map(
                    (dp) => dp?.kind ?? "<?>",
                );
                fields.push(`dataProducts=[${kinds.join(",")}]`);
            }
            if (Array.isArray(m?.kinds)) {
                fields.push(`kinds=[${(m.kinds as unknown[]).join(",")}]`);
            }
            out.push({
                kind: "single",
                text: `${cmd}${fields.length ? " " + fields.join(" ") : ""}`,
            });
        }
        flush();
        return out;
    };
    // Cap the tail we dump so a long suite doesn't drown the workflow log,
    // but keep enough room (60) for renderStarted + per-card setProgress
    // burst + renderFinished + updateA11y + ack from a fresh render to
    // fit comfortably.
    for (const entry of compact(posted).slice(-60)) {
        console.log(`[e2e-a11y-diag/posted] ${entry.text}`);
    }
    for (const entry of compact(received).slice(-60)) {
        console.log(`[e2e-a11y-diag/received] ${entry.text}`);
    }
}

async function waitForA11yAck<T>(
    api: ComposePreviewTestApi,
    label: string,
    previewId: string,
    timeoutMs: number,
    probe: () => T | undefined,
): Promise<T> {
    try {
        return await waitFor(label, timeoutMs, 500, probe);
    } catch (err) {
        dumpA11yFailureDiagnostics(api, label, previewId);
        throw err;
    }
}

interface PreviewSummary {
    id: string;
    functionName: string;
    params?: { device?: string | null; name?: string | null };
}

/**
 * `@WearPreviewDevices` expands into multiple variants (one per round
 * size). The plan calls out the "Large Round" variant for the hierarchy
 * scenario, so prefer that; fall back to any variant if the device
 * label naming changes upstream.
 */
function pickPreview(
    previews: readonly PreviewSummary[],
    functionName: string,
    preferDeviceContains?: string,
): PreviewSummary {
    const candidates = previews.filter((p) => p.functionName === functionName);
    assert.ok(
        candidates.length > 0,
        `expected at least one preview named ${functionName}; got ` +
            previews
                .map((p) => `${p.functionName}@${p.params?.device}`)
                .join(", "),
    );
    if (preferDeviceContains) {
        const preferred = candidates.find((p) =>
            (p.params?.device ?? "").includes(preferDeviceContains),
        );
        if (preferred) return preferred;
    }
    return candidates[0];
}

describeE2E("Compose Preview a11y subscription e2e (wear)", function () {
    // The wear daemon's first cold render is the slowest path in the
    // build: Robolectric + Wear material first-launch, plus an extra
    // a11y-mode render after the chip subscribes. 15 minutes leaves
    // head room on a fresh CI runner.
    this.timeout(15 * 60_000);

    let api: ComposePreviewTestApi;
    let wearKotlinFile: string;
    let priorEarlyFeatures: boolean | undefined;
    let wearPreviews: PreviewSummary[];
    // PreviewIds the suite has subscribed a11y data products on. `afterEach`
    // walks this and unsubscribes both kinds so each `it()` starts with a
    // clean daemon-side subscription state. Required because
    // `LiveDaemonScheduler.setDataProductSubscription` is idempotent
    // (daemonScheduler.ts:499 skips when `enabled === already`): re-issuing
    // a subscribe for an already-subscribed pair produces no fresh
    // `updateA11y` and therefore no `webviewA11yState` ack, so a test that
    // assumes "subscribe → ack" would hang against the per-test 60s cap.
    const touchedPreviewIds = new Set<string>();

    before(async function () {
        // Cold first-render bootstrap; each `it` reuses `wearPreviews`
        // so the slow Gradle/daemon spin-up only happens once. Hook
        // timeout matches the suite ceiling so the bootstrap render
        // isn't truncated.
        this.timeout(15 * 60_000);

        const folders = vscode.workspace.workspaceFolders;
        assert.ok(folders && folders.length > 0, "workspace must be open");
        const repoRoot = folders[0].uri.fsPath;
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
            fs.existsSync(wearKotlinFile),
            `expected wear sample file at ${wearKotlinFile}`,
        );

        // Focus-inspector chips + webview overlay painting are gated on
        // earlyFeatures. Use Global scope so `@vscode/test-electron`'s
        // isolated `.vscode-test/user-data/` holds the value and the
        // repo's `.vscode/` stays clean. Restored in after().
        const config = vscode.workspace.getConfiguration("composePreview");
        priorEarlyFeatures = config.get<boolean>("earlyFeatures.enabled");
        await config.update(
            "earlyFeatures.enabled",
            true,
            vscode.ConfigurationTarget.Global,
        );

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
                repoRoot,
                (line) => console.log(line),
                // No extra Gradle args — the daemon registers the a11y
                // data-product registry unconditionally now, so the
                // chip-driven `data/subscribe` is the only enablement
                // signal the daemon needs.
                [],
            ),
        );

        // Resolve the webview view. The `webviewA11yState` ack the chip
        // tests wait for only fires from a live webview script, and
        // without explicit focus `panel.view` stays undefined — every
        // host `postMessage` no-ops. See the matching note in
        // `e2e.test.ts`. Idempotent if the cmp suite already opened it.
        await vscode.commands.executeCommand("composePreview.panel.focus");
        // `webviewReady` is a one-shot signal: the webview emits it once
        // on resolve and never again. When the cmp suite ran first it
        // already drove that signal, and `resetMessages()` inside the
        // cmp `it()` cleared the inbound buffer — so scanning
        // `getReceivedMessages()` for `webviewReady` here would loop
        // forever. Use the persistent latch instead; fall back to the
        // inbound scan only when the wear suite is the first to focus
        // the panel (e.g. `e2e*.test.js` runs in isolation).
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

        // Mirror the e2eExternal sequencing: run the activation-time
        // `composePreviewApplied` marker bootstrap explicitly before warming
        // the daemon. `:samples:wear` is detected via literal-id text-scan
        // even without markers, so this isn't strictly required for
        // `resolveModule`, but post-#1438 `runDaemonBootstrap` bundles
        // `composePreviewApplied` + `composePreviewDaemonStart` +
        // `composePreviewDiscover` into one cold-start invocation — fronting
        // it with an explicit `bootstrapAppliedMarkers` lets the bundle's
        // coalescing skip the redundant `composePreviewApplied` head, and
        // keeps the markers fresh before the test interacts with the
        // daemon scheduler.
        await api.triggerBootstrapAppliedMarkers();

        // Render first, warm second. The daemon JVM is launched with
        // `-Dcomposeai.harness.previewsManifest=…/previews.json` baked into
        // its `daemon-launch.json`; `PreviewManifestRouter.loadManifest`
        // throws at startup when the file isn't on disk, taking the JVM
        // down with exit code 1 before the channel handshake even begins.
        // `composePreviewDaemonStart` (what `runDaemonBootstrap` invokes)
        // writes the launch descriptor but NOT the manifest, so it has to
        // run after the renderAll that writes `previews.json`. This matches
        // production `runActivationRefresh` ordering: refresh → warm.
        //
        // One refresh bootstraps every `it`: the wear composePreviewRenderAll
        // cold path is the slowest piece of the suite, so paying for it
        // once and reusing the `setPreviews` payload keeps the total
        // wall-clock close to a single render plus three chip toggles.
        wearPreviews = await refreshAndGetPreviews(15 * 60_000);

        // The chip toggles below send `data/subscribe` through the daemon
        // scheduler, which requires a live daemon and therefore a
        // `composePreviewDaemonStart`-produced launch descriptor. Activation
        // does this automatically in production via `runActivationRefresh`,
        // but COMPOSE_PREVIEW_TEST_MODE=1 skips that auto-refresh — without
        // an explicit warm here the first `triggerSetDataExtensionEnabled`
        // throws `[daemon] no launch descriptor for :samples:wear`.
        const warmed = await api.triggerWarmDaemon(wearKotlinFile);
        // Format the cause on its own line so a multi-line
        // `formatDaemonSpawnFailure` tail (issue #1326's wrapping — wraps
        // the daemon stderr tail into the failure reason) is preserved in
        // the Mocha spec reporter output. Mocha keeps the full message in
        // `err.message` but renders it after the diff; folding the cause
        // string into a single-line concatenation hid the stderr tail
        // behind the `+ expected - actual` block in CI output.
        assert.ok(
            warmed,
            `wear daemon must warm before chip subscriptions\ncause: ${api.getLastWarmDaemonError() ?? "<unknown>"}`,
        );
    });

    afterEach(async () => {
        // Drop every a11y subscription this test created so the next
        // `it()` re-issuing a subscribe sees a fresh transition (and
        // therefore a fresh `webviewA11yState` ack). Unsubscribing a
        // never-subscribed pair is a no-op in
        // `setDataProductSubscription`, so passing both kinds for every
        // touched preview is safe and avoids per-test bookkeeping.
        if (!api) return;
        for (const previewId of touchedPreviewIds) {
            await api.triggerSetDataExtensionEnabled(
                previewId,
                ["a11y/hierarchy", "a11y/atf"],
                false,
            );
        }
        touchedPreviewIds.clear();
    });

    after(async () => {
        const config = vscode.workspace.getConfiguration("composePreview");
        await config.update(
            "earlyFeatures.enabled",
            priorEarlyFeatures,
            vscode.ConfigurationTarget.Global,
        );
    });

    async function refreshAndGetPreviews(
        timeoutMs: number,
    ): Promise<PreviewSummary[]> {
        api.resetMessages();
        await api.triggerRefresh(wearKotlinFile, /* force */ true, "full");
        const setPreviews = await waitFor(
            "non-empty setPreviews for samples/wear",
            timeoutMs,
            500,
            () => {
                // Scan in reverse for the latest non-empty setPreviews:
                // the refresh flow can emit an initial empty/stale payload
                // (cached-manifest replay) before the rendered manifest
                // lands, and `Array.prototype.find` would lock onto the
                // empty one forever.
                const msgs = api.getPostedMessages();
                for (let i = msgs.length - 1; i >= 0; i--) {
                    const m = msgs[i] as PostedMessage;
                    if (m.command !== "setPreviews") continue;
                    const previews = m.previews as PreviewSummary[] | undefined;
                    if (previews && previews.length > 0) return m;
                }
                return undefined;
            },
        );
        const previews = setPreviews.previews as PreviewSummary[];
        console.log(
            `[e2e-a11y] wear setPreviews carried ${previews.length} previews`,
        );
        return previews;
    }

    function findA11yAck(
        previewId: string,
        match: (ack: A11yStateAck) => boolean,
    ): A11yStateAck | undefined {
        const inbound = api.getReceivedMessages();
        return inbound.find((raw) => {
            const m = raw as PostedMessage;
            return (
                m.command === "webviewA11yState" &&
                (m as A11yStateAck).previewId === previewId &&
                match(m as A11yStateAck)
            );
        }) as A11yStateAck | undefined;
    }

    it("paints hierarchy overlay when the chip toggles ON for ActivityListPreview", async function () {
        // Per-test cap. The wear daemon is already warm by `before()`; chip
        // toggles are sub-second on a warm runner (observed 2-3s including
        // daemon-side a11y render). 60s leaves 20× headroom while letting
        // a stuck waitFor surface in a minute instead of inheriting the
        // suite-level 15 min ceiling (which previously hid a hang in the
        // post-#1461 run for the whole 60-min workflow budget).
        this.timeout(60_000);
        const target = pickPreview(
            wearPreviews,
            "ActivityListPreview",
            "Large Round",
        );
        console.log(
            `[e2e-a11y] subscribing a11y/hierarchy for ${target.functionName} (${target.params?.device})`,
        );
        touchedPreviewIds.add(target.id);
        api.resetMessages();

        await api.triggerSetDataExtensionEnabled(
            target.id,
            ["a11y/hierarchy"],
            true,
        );

        const ack = await waitForA11yAck(
            api,
            `webviewA11yState with nodes for ${target.id}`,
            target.id,
            this.timeout(),
            () =>
                findA11yAck(
                    target.id,
                    (a) => typeof a.nodesCount === "number" && a.nodesCount > 0,
                ),
        );
        // The plan called out 12 nodes for ActivityListPreview at the time
        // of PR #1007's manual verification. Don't pin the literal — text
        // content and Material defaults shift across compose-bom updates,
        // and any positive count proves the chain is intact.
        assert.ok(
            (ack.nodesCount ?? 0) > 0,
            `expected nodesCount > 0, got ${ack.nodesCount}`,
        );
        assert.strictEqual(
            ack.findingsCount,
            null,
            "hierarchy-only update should leave findingsCount=null",
        );
    });

    it("paints findings legend when the chip toggles ON for BadWearButtonPreview", async function () {
        this.timeout(60_000);
        const target = pickPreview(wearPreviews, "BadWearButtonPreview");
        console.log(
            `[e2e-a11y] subscribing a11y/atf for ${target.functionName} (${target.params?.device})`,
        );
        touchedPreviewIds.add(target.id);
        api.resetMessages();

        await api.triggerSetDataExtensionEnabled(target.id, ["a11y/atf"], true);

        const ack = await waitForA11yAck(
            api,
            `webviewA11yState with findings for ${target.id}`,
            target.id,
            this.timeout(),
            () =>
                findA11yAck(
                    target.id,
                    (a) =>
                        typeof a.findingsCount === "number" &&
                        a.findingsCount > 0,
                ),
        );
        // `BadWearButtonPreview` is engineered to produce exactly one ATF
        // finding (level=ERROR, type=SpeakableTextPresentCheck — the
        // empty-label `Button`). Pin to 1 so a regression that produces
        // *zero* findings (the chain silently dropping the attachment) or
        // *multiple* (Material defaults changing what ATF flags) is loud.
        assert.strictEqual(
            ack.findingsCount,
            1,
            `expected exactly 1 finding for BadWearButtonPreview, got ${ack.findingsCount}`,
        );
        assert.strictEqual(
            ack.nodesCount,
            null,
            "atf-only update should leave nodesCount=null",
        );
    });

    it("tears down the hierarchy overlay when the chip toggles OFF", async function () {
        this.timeout(60_000);
        const target = pickPreview(
            wearPreviews,
            "ActivityListPreview",
            "Large Round",
        );
        touchedPreviewIds.add(target.id);
        api.resetMessages();

        await api.triggerSetDataExtensionEnabled(
            target.id,
            ["a11y/hierarchy"],
            true,
        );
        await waitForA11yAck(
            api,
            `nodes-painted ack for ${target.id}`,
            target.id,
            this.timeout(),
            () =>
                findA11yAck(
                    target.id,
                    (a) => typeof a.nodesCount === "number" && a.nodesCount > 0,
                ),
        );

        // Reset so the teardown ack isn't confused with the prior paint.
        api.resetMessages();

        await api.triggerSetDataExtensionEnabled(
            target.id,
            ["a11y/hierarchy"],
            false,
        );
        const teardown = await waitForA11yAck(
            api,
            `webviewA11yState teardown for ${target.id}`,
            target.id,
            this.timeout(),
            () => findA11yAck(target.id, (a) => a.nodesCount === 0),
        );
        assert.strictEqual(teardown.nodesCount, 0);
    });
});
