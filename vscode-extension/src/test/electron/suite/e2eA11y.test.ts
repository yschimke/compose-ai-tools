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
 * Setup: the daemon's accessibility data-product registry is still
 * gated on `composeai.previewExtensions.a11y.enabled` (issue #1009
 * removes that gate). Until then the test passes
 * `-PcomposePreview.previewExtensions.a11y.enableAllChecks=true` via
 * `RealGradleApi.extraArgs` so the wear daemon spawns with the registry
 * registered. The focus-inspector chips themselves are gated on
 * `composePreview.earlyFeatures.enabled`; the test flips it on at
 * Global scope (isolated to `.vscode-test/user-data/`) and restores it
 * after the run.
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
                // Until #1009 removes the daemon-side flag, the
                // accessibility data-product registry only registers
                // when this property resolves true. Otherwise the
                // `data/subscribe` call succeeds but the registry has
                // no a11y kinds to attach, so no `updateA11y` ever
                // reaches the webview and the test times out.
                [
                    "-PcomposePreview.previewExtensions.a11y.enableAllChecks=true",
                ],
            ),
        );

        // One refresh bootstraps every `it`: the wear renderAllPreviews
        // cold path is the slowest piece of the suite, so paying for it
        // once and reusing the `setPreviews` payload keeps the total
        // wall-clock close to a single render plus three chip toggles.
        wearPreviews = await refreshAndGetPreviews(15 * 60_000);
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
        const target = pickPreview(
            wearPreviews,
            "ActivityListPreview",
            "Large Round",
        );
        console.log(
            `[e2e-a11y] subscribing a11y/hierarchy for ${target.functionName} (${target.params?.device})`,
        );
        api.resetMessages();

        await api.triggerSetDataExtensionEnabled(
            target.id,
            "a11y/hierarchy",
            true,
        );

        const ack = await waitFor(
            `webviewA11yState with nodes for ${target.id}`,
            this.timeout(),
            500,
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
        const target = pickPreview(wearPreviews, "BadWearButtonPreview");
        console.log(
            `[e2e-a11y] subscribing a11y/atf for ${target.functionName} (${target.params?.device})`,
        );
        api.resetMessages();

        await api.triggerSetDataExtensionEnabled(target.id, "a11y/atf", true);

        const ack = await waitFor(
            `webviewA11yState with findings for ${target.id}`,
            this.timeout(),
            500,
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
        const target = pickPreview(
            wearPreviews,
            "ActivityListPreview",
            "Large Round",
        );
        api.resetMessages();

        await api.triggerSetDataExtensionEnabled(
            target.id,
            "a11y/hierarchy",
            true,
        );
        await waitFor(
            `nodes-painted ack for ${target.id}`,
            this.timeout(),
            500,
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
            "a11y/hierarchy",
            false,
        );
        const teardown = await waitFor(
            `webviewA11yState teardown for ${target.id}`,
            this.timeout(),
            500,
            () => findA11yAck(target.id, (a) => a.nodesCount === 0),
        );
        assert.strictEqual(teardown.nodesCount, 0);
    });
});
