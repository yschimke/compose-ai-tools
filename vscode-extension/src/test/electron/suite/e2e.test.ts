import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import type { ComposePreviewTestApi } from "../../../extension";
import { RealGradleApi } from "../realGradleApi";

/**
 * End-to-end test. Drives the extension against a real Gradle build of
 * `:samples:cmp` so the *full* loop is exercised: `renderAllPreviews`
 * runs through the locally-built plugin, the renderer subprocesses fork,
 * PNGs land under `samples/cmp/build/compose-previews/renders/`, and the
 * extension panel receives the populated `setPreviews` message.
 *
 * Gated on `COMPOSE_PREVIEW_E2E=1`. Skipped silently in the fast suite
 * because (a) cold Gradle + Compose Multiplatform setup is multi-minute,
 * (b) flake potential is non-trivial. The CI workflow at
 * `.github/workflows/vscode-extension-e2e.yml` runs it on `main` after
 * CI + Integration both pass, plus on `workflow_dispatch`.
 *
 * Why the existing `samples/cmp` module rather than a fresh fixture: the
 * repo's settings.gradle.kts already wires `includeBuild("gradle-plugin")`,
 * so the test exercises the locally-built plugin without any version-
 * pinning or `publishToMavenLocal` setup. This is the prototype shape;
 * a self-contained fixture is a follow-up if/when we want to insulate
 * the test from sample evolution.
 */

const E2E = process.env.COMPOSE_PREVIEW_E2E === "1";
const describeE2E = E2E ? describe : describe.skip;

interface PostedMessage {
    command: string;
    [key: string]: unknown;
}

function findMessage(
    messages: unknown[],
    command: string,
): PostedMessage | undefined {
    return messages.find((m) => (m as PostedMessage).command === command) as
        | PostedMessage
        | undefined;
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

describeE2E("Compose Preview e2e (real Gradle)", function () {
    // Cold renderAllPreviews on `:samples:cmp` is ~30-90s on a warm machine
    // and can spike higher on a fresh CI runner. 10 minutes is the same
    // ceiling the gradle-plugin functional tests use.
    this.timeout(10 * 60_000);

    let api: ComposePreviewTestApi;
    let kotlinFile: string;
    let repoRoot: string;
    let renderDir: string;

    before(async () => {
        const folders = vscode.workspace.workspaceFolders;
        assert.ok(folders && folders.length > 0, "workspace must be open");
        repoRoot = folders[0].uri.fsPath;
        kotlinFile = path.join(
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
        assert.ok(
            fs.existsSync(kotlinFile),
            `expected fixture file at ${kotlinFile}`,
        );
        renderDir = path.join(
            repoRoot,
            "samples",
            "cmp",
            "build",
            "compose-previews",
            "renders",
        );
        // Clean previous renders so the assertion below verifies *this* run
        // produced PNGs, not a stale artifact from a developer's last build.
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
            new RealGradleApi(repoRoot, (line) => console.log(line)),
        );
    });

    it("renders previews for samples/cmp through the real Gradle plugin", async () => {
        api.resetMessages();
        await api.triggerRefresh(kotlinFile, /* force */ true, "full");

        // The panel may emit several setPreviews on its way through the
        // refresh flow; the last one is the one carrying the rendered
        // metadata, so wait for any non-empty message.
        const previewsMessage = await waitFor(
            "non-empty setPreviews from real renderAllPreviews",
            this.timeout(),
            500,
            () => {
                const msgs = api.getPostedMessages();
                const m = findMessage(msgs, "setPreviews");
                if (!m) return undefined;
                const previews = m.previews as Array<unknown> | undefined;
                if (!previews || previews.length === 0) return undefined;
                return m;
            },
        );

        const previews = previewsMessage.previews as Array<{
            id: string;
            renderPath?: string;
        }>;
        console.log(
            `[e2e] received ${previews.length} previews: ` +
                previews.map((p) => p.id).join(", "),
        );
        assert.ok(
            previews.length >= 2,
            `expected at least 2 previews, got ${previews.length}`,
        );

        // Confirm the renders actually landed on disk — the panel-message
        // path could in theory be satisfied by a cached previews.json
        // without fresh PNGs, and we want to catch that regression here.
        const pngs = fs.existsSync(renderDir)
            ? fs.readdirSync(renderDir).filter((n) => n.endsWith(".png"))
            : [];
        assert.ok(
            pngs.length >= 2,
            `expected at least 2 rendered PNGs in ${renderDir}, found ${pngs.length}`,
        );

        // `postedMessageLog` only proves the host *attempted* to post. Wait
        // for `webviewPreviewsRendered` from the resolved webview to confirm
        // the message landed and `renderPreviews` actually painted cards.
        // Regression-locks the empty-grid bug where a host post into an
        // unresolved view was silently dropped — assertions on
        // `postedMessageLog` alone passed cleanly while users saw an empty
        // panel.
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
            renderedSignal.count >= previews.length,
            `webview rendered ${renderedSignal.count} cards but ${previews.length} previews were sent`,
        );
    });
});
