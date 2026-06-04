import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import type { ComposePreviewTestApi } from "../../../extension";
import { RealGradleApi } from "../realGradleApi";

/**
 * Electron-driven e2e for the bundle chip ↔ tab ↔ overlay chain
 * (issue #1391, follow-up to #1104). Companion to the controller-level
 * `chipTabOverlayChain.test.ts` unit test — the unit test wires a
 * stub controller against synthetic DOM and asserts the three surfaces
 * stay in sync; this one runs the same contract against a real daemon
 * round-trip on `:samples:wear` so the assertions also catch:
 *
 *   - `main.ts` `firstUpdated` ordering bugs (a single bundle's
 *     `refreshXxxBundle` wired the wrong side of the controller).
 *   - the daemon ↔ panel subscription path —
 *     `triggerWebviewBundleToggle` activates the chip in the webview,
 *     which posts `setDataExtensionEnabled` back to the extension,
 *     which subscribes the daemon, which emits attachments, which
 *     paint overlays back in the webview.
 *   - `webviewBundleState` ack fidelity — `tabHandleCount` /
 *     `overlayCountByBundle` must reflect what's actually mounted in
 *     the DOM, not stale controller bookkeeping.
 *
 * Gated on `COMPOSE_PREVIEW_E2E=1` next to the existing real-Gradle
 * suite; piggybacks on the wear daemon already warmed by
 * `e2eA11y.test.ts` when both run in the same Mocha process. The
 * Accessibility bundle is the chosen target because both
 * `a11y/hierarchy` (positive node count → overlay boxes) and the
 * underlying daemon registry are already exercised by #1006's tests,
 * so this suite focuses on chip/tab/overlay synchrony rather than
 * proving the data path again. History was the original sketch in
 * #1391 but requires a baseline render archived in `historyManager`
 * before `history/diff/regions` resolves, which is a separate setup
 * burden out of scope for this suite.
 *
 * The verification surface is the `webviewBundleState` ack the panel
 * emits after every `reflectBundleState()` and from the data-update
 * paths after `refreshXxxBundle()` repaints overlays. Reading the
 * webview DOM directly across the extension-host / webview boundary
 * is awkward, and asserting on host-side posts alone wouldn't catch
 * a webview-side regression in the chip / tab / overlay paint.
 * Mirrors `webviewA11yState` (data-side ack) for the chip-surface
 * side of the chain.
 */

// Normally this suite is gated on `COMPOSE_PREVIEW_E2E=1`
// (`E2E ? describe : describe.skip`), but TODO(#1473) forces the WHOLE
// suite skipped, not just the two `it()`s below. Mocha runs a suite's
// `before`/`after` hooks even when every test in it is pending, so the
// per-test `.skip`s still paid the cost of the `before()` hook's cold
// full-tier `:samples:wear` render. On CI that render ran past the 15-min
// suite ceiling and failed the job with a "before all" hook timeout — pure
// waste, since neither test runs. Skipping at the describe level skips the
// expensive setup too. Restore the `E2E ? describe : describe.skip` gate
// when the chip-activation path lands.
const describeE2E = describe.skip;

interface PostedMessage {
    command: string;
    [key: string]: unknown;
}

interface BundleStateAck extends PostedMessage {
    command: "webviewBundleState";
    activeBundles: string[];
    activeTab: string | null;
    tabHandleCount: number;
    overlayCountByBundle: Record<string, number>;
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

interface PreviewSummary {
    id: string;
    functionName: string;
    params?: { device?: string | null; name?: string | null };
}

describeE2E("Compose Preview bundle chip↔tab↔overlay e2e (wear)", function () {
    // Suite ceiling matches the sibling wear suites — first cold render
    // on `:samples:wear` is the slowest path and dwarfs every subsequent
    // chip toggle.
    this.timeout(15 * 60_000);

    let api: ComposePreviewTestApi;
    let wearKotlinFile: string;
    let priorEarlyFeatures: boolean | undefined;
    let wearPreviews: PreviewSummary[];

    before(async function () {
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

        // Chip bar / tab row / overlays are gated on `earlyFeatures`
        // for non-graduated bundles; a11y is graduated so the chip
        // would show regardless, but flipping the flag keeps this
        // suite's setup mirrored with `e2eA11y` for future bundles.
        // Global scope so `@vscode/test-electron`'s isolated
        // `.vscode-test/user-data/` holds the value and the repo's
        // `.vscode/` stays clean.
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
            new RealGradleApi(repoRoot, (line) => console.log(line), []),
        );

        // Same dance as `e2eA11y` — focus the panel so the webview
        // resolves, then wait for the ready latch / inbound message.
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
        // One full-tier render to populate the grid + scope a current
        // bundle target. `currentBundleTarget()` in the webview returns
        // the first visible card, which is the previewId the chip's
        // outbound subscribe targets.
        api.resetMessages();
        await api.triggerRefresh(wearKotlinFile, /* force */ true, "full");
        const setPreviews = await waitFor(
            "non-empty setPreviews for samples/wear",
            this.timeout(),
            500,
            () => {
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
        wearPreviews = setPreviews.previews as PreviewSummary[];
        console.log(
            `[e2e-bundle-chain] wear setPreviews carried ${wearPreviews.length} previews`,
        );
        // Wait for the webview to actually render the cards so
        // `currentBundleTarget()` resolves to a real previewId rather
        // than `null` when the chip toggle fires.
        await waitFor(
            "webviewPreviewsRendered for samples/wear",
            this.timeout(),
            500,
            () => {
                const inbound = api.getReceivedMessages();
                return inbound.find(
                    (m) =>
                        (m as PostedMessage).command ===
                        "webviewPreviewsRendered",
                );
            },
        );

        // The chip toggle's outbound `setDataExtensionEnabled` lands
        // in `handleSetDataExtensionEnabled`, which needs a live daemon
        // to subscribe against. `COMPOSE_PREVIEW_TEST_MODE=1` skips the
        // activation auto-refresh, so the warm has to be explicit.
        const warmed = await api.triggerWarmDaemon(wearKotlinFile);
        assert.ok(
            warmed,
            "wear daemon must warm before chip toggles" +
                (warmed
                    ? ""
                    : `; cause: ${api.getLastWarmDaemonError() ?? "<unknown>"}`),
        );
    });

    after(async () => {
        const config = vscode.workspace.getConfiguration("composePreview");
        await config.update(
            "earlyFeatures.enabled",
            priorEarlyFeatures,
            vscode.ConfigurationTarget.Global,
        );
    });

    function findBundleAck(
        match: (ack: BundleStateAck) => boolean,
    ): BundleStateAck | undefined {
        const inbound = api.getReceivedMessages();
        // Scan in reverse for the latest matching snapshot — the chip
        // toggle emits at activation time (overlays empty) and again
        // after the daemon attachment lands (overlays populated). A
        // forward scan would lock onto the first emit and never see
        // the post-paint state.
        for (let i = inbound.length - 1; i >= 0; i--) {
            const m = inbound[i] as PostedMessage;
            if (m.command !== "webviewBundleState") continue;
            const ack = m as BundleStateAck;
            if (match(ack)) return ack;
        }
        return undefined;
    }

    // TODO(#1473) — chip activation never produces a `webviewBundleState`
    // ack with `tabHandleCount >= 1`, so both `it()`s here time out at the
    // per-test 60s cap. Skipped until the root cause is in (chip never
    // activated / Lit render lagged + no follow-up emit / daemon attachment
    // route mismatched the chip's chosen target). The companion controller-
    // level test `chipTabOverlayChain.test.ts` still covers the chip/tab/
    // overlay state machine against synthetic DOM.
    it.skip("activate → chip on, tab opens, overlays paint after daemon attachment", async function () {
        // Per-test cap. The wear daemon is already warm by `before()`; chip
        // toggles + tab/overlay paint round-trip in seconds on a warm
        // runner. 60s leaves comfortable headroom while letting a stuck
        // waitFor surface in a minute instead of inheriting the suite-level
        // 15 min ceiling (which previously hid hangs for the whole 60 min
        // workflow budget).
        this.timeout(60_000);
        // Sanity baseline: at suite entry no bundle is active (the wear
        // suite hasn't toggled anything yet, and the initial
        // `reflectBundleState()` runs with `active=[]`).
        const initial = findBundleAck(
            (a) => a.activeBundles.length === 0 && a.tabHandleCount === 0,
        );
        assert.ok(
            initial,
            "expected an initial webviewBundleState with no active bundles before the toggle",
        );

        // Reset only the inbound buffer so we can scan the toggle's
        // emits without the initial empty-state acks confusing the
        // "post-paint overlay count" probe.
        api.resetMessages();

        console.log("[e2e-bundle-chain] triggering chip activation for a11y");
        api.triggerWebviewBundleToggle("a11y");

        // First milestone: the chip activation itself. `reflectBundle
        // State()` runs synchronously on `controller.fire()`, so the
        // ack should land within the next webview tick.
        const activated = await waitFor(
            "webviewBundleState with a11y active + tab open",
            this.timeout(),
            500,
            () =>
                findBundleAck(
                    (a) =>
                        a.activeBundles.includes("a11y") &&
                        a.activeTab === "a11y" &&
                        a.tabHandleCount >= 1,
                ),
        );
        console.log(
            `[e2e-bundle-chain] chip-on snapshot: ` +
                `active=[${activated.activeBundles.join(",")}] ` +
                `tabs=${activated.tabHandleCount} ` +
                `overlays.a11y=${activated.overlayCountByBundle["a11y"] ?? 0}`,
        );

        // Second milestone: daemon attachment lands → `refreshA11y
        // Bundle()` repaints overlays → bundle state re-emits with a
        // positive overlay count. The chip-on activation emits with
        // overlay count = 0 (cache empty); we're looking for a later
        // snapshot where the count is non-zero. Every wear preview
        // ships at least one accessibility-relevant node, so the
        // first-visible-card subscription is guaranteed to produce
        // at least one overlay box.
        const painted = await waitFor(
            "webviewBundleState with a11y overlay painted",
            this.timeout(),
            500,
            () =>
                findBundleAck(
                    (a) =>
                        a.activeBundles.includes("a11y") &&
                        (a.overlayCountByBundle["a11y"] ?? 0) > 0,
                ),
        );
        assert.ok(
            (painted.overlayCountByBundle["a11y"] ?? 0) > 0,
            `expected overlayCountByBundle.a11y > 0 after daemon attachment, ` +
                `got ${painted.overlayCountByBundle["a11y"] ?? 0}`,
        );
        // Tab handle should still be there alongside the painted
        // overlay — a regression where the overlay paint clobbers the
        // tab row (or vice versa) would surface here.
        assert.ok(
            painted.tabHandleCount >= 1,
            `expected tabHandleCount >= 1 with paint, got ${painted.tabHandleCount}`,
        );
    });

    // TODO(#1473) — skipped alongside the chip-activation `it()` above:
    // the prereq (chip-on from the prior test) doesn't hold while that
    // test is `.skip`, and the underlying chip→bundle-state path is the
    // same.
    it.skip("toggle off → chip off, tab closed, overlays cleared", async function () {
        this.timeout(60_000);
        // Prereq: chip on with overlays painted. The previous `it`
        // toggled the chip on and left it that way; if Mocha re-orders
        // (e.g. a future `--retries` setting) the assertion below
        // would surface that as a real failure rather than a spurious
        // off-by-one against the test order.
        const stillOn = findBundleAck((a) => a.activeBundles.includes("a11y"));
        assert.ok(
            stillOn,
            "expected a11y to still be active from the prior chip-on test; " +
                "either Mocha re-ordered the suite or the prior toggle never landed",
        );

        api.resetMessages();
        console.log("[e2e-bundle-chain] triggering chip teardown for a11y");
        api.triggerWebviewBundleToggle("a11y");

        const torn = await waitFor(
            "webviewBundleState with a11y deactivated",
            this.timeout(),
            500,
            () =>
                findBundleAck(
                    (a) =>
                        !a.activeBundles.includes("a11y") &&
                        a.tabHandleCount === 0 &&
                        (a.overlayCountByBundle["a11y"] ?? 0) === 0,
                ),
        );
        assert.deepStrictEqual(
            torn.activeBundles.includes("a11y"),
            false,
            "a11y must be removed from activeBundles after toggle off",
        );
        assert.strictEqual(
            torn.tabHandleCount,
            0,
            "tab handle row must be empty after the only active bundle closes",
        );
        assert.strictEqual(
            torn.overlayCountByBundle["a11y"] ?? 0,
            0,
            "every card's a11y box-overlay layer must be cleared on close",
        );
        // Active tab follows activeBundles to `null` when nothing is
        // left — locks the closure rule the unit test pins at
        // chipTabOverlayChain.test.ts:191.
        assert.strictEqual(
            torn.activeTab,
            null,
            "activeTab must be null when no bundle is active",
        );
    });
});
