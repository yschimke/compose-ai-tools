// Editor-style webview panel for an opened preview bundle.
//
// The bundle is a `composePreviewBundle` polyglot file (PNG header
// followed by a zip). We spawn the desktop daemon JVM bound to that
// bundle's classpath via `compose-preview bundle daemon`, then surface
// the daemon's renders + data extensions in a panel that reuses the
// existing `<preview-app>` Lit element in `bundle-mode`. The daemon
// stays resident for the lifetime of the tab so focus-mode features
// (data-extension chips, a11y overlay, interactive, recording) work the
// same way as in the sidebar panel — they're just sourced from a packed
// bundle rather than a Gradle module.
//
// One panel per absolute bundle path; opening the same bundle twice
// reveals the existing tab rather than spawning a duplicate daemon.

import * as crypto from "crypto";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import {
    BundleContents,
    bundleLabel,
    BundleFormatError,
    readBundleContents,
} from "./bundleFormat";
import { androidSdkEnv, resolveAndroidSdk } from "./androidSdk";
import { BundleCliNotFoundError, locateBundleCli } from "./bundleRender";
import {
    A11Y_DATA_KINDS,
    BundleDaemonHandle,
    renderAll,
    setKindsSubscribed,
    spawnBundleDaemon,
} from "./bundleDaemonHost";
import { DaemonClient } from "./daemon/daemonClient";
import {
    DataProductAttachment,
    InteractiveInputKind,
    RecordingFormat,
    RenderFailedParams,
    RenderFinishedParams,
    StreamFrameParams,
} from "./daemon/daemonProtocol";
import type { Capture, PreviewInfo, PreviewParams } from "./types";

const CHANNEL = "[bundle-viewer]";
const CLIENT_VERSION = "compose-preview-bundle-viewer/1";

export interface BundleViewerHostDeps {
    extensionUri: vscode.Uri;
    earlyFeaturesEnabled: () => boolean;
    logLine: (message: string) => void;
}

/**
 * Webview messages this panel reads from `<preview-app>`. Same wire
 * shapes as the sidebar router, just scoped to the subset that makes
 * sense for a bundle (no module-relative file opens, no Gradle refresh).
 */
type WebviewMessage =
    | { command: "webviewReady" }
    | { command: "openExternal"; url: string }
    | { command: "setA11yOverlay"; previewId: string; enabled: boolean }
    | {
          command: "setDataExtensionEnabled";
          previewId: string;
          kinds: string[];
          enabled: boolean;
      }
    | { command: "refreshHeavy"; previewId: string }
    | { command: "previewScopeChanged"; previewId: string | null }
    | {
          command: "viewportUpdated";
          visible: string[];
          predicted: string[];
      }
    // `composestream/1` — live-mode entry/exit. The webview's
    // `LiveStateController` produces these the same way it does for the
    // sidebar panel; the bundle viewer routes them to its per-tab
    // daemon and forwards the resulting `streamFrame` events back.
    | {
          command: "requestStreamStart";
          previewId: string;
          overrides?: import("./daemon/daemonProtocol").PreviewOverrides;
      }
    | { command: "requestStreamStop"; previewId: string }
    | {
          command: "requestStreamVisibility";
          previewId: string;
          visible: boolean;
          fps?: number;
      }
    | {
          command: "recordInteractiveInput";
          previewId: string;
          kind: InteractiveInputKind;
          pixelX: number;
          pixelY: number;
          imageWidth: number;
          imageHeight: number;
          scrollDeltaY?: number;
          keyCode?: string;
          /**
           * The character a printable `keyDown` produced. `keyCode` names the
           * physical key and cannot type — the caret and Backspace work from
           * it, a character does not (issue #3491).
           */
          text?: string;
          /**
           * DOM `PointerEvent.pointerType`; absent means touch. Mouse is what
           * drags out a text selection.
           */
          pointerType?: string;
      }
    | {
          command: "setRecording";
          previewId: string;
          enabled: boolean;
          format?: RecordingFormat;
      };

export class BundleViewerPanel {
    /** Open panels keyed by absolute bundle path. */
    private static readonly active = new Map<string, BundleViewerPanel>();

    /**
     * Open (or reveal) a viewer for [bundlePath]. The file is validated
     * before the panel is created — non-bundle PNGs / unreadable files
     * surface an error toast without creating a stray empty tab.
     */
    static async open(
        bundlePath: string,
        deps: BundleViewerHostDeps,
    ): Promise<BundleViewerPanel | null> {
        const absolute = path.resolve(bundlePath);
        const existing = BundleViewerPanel.active.get(absolute);
        if (existing) {
            existing.panel.reveal(vscode.ViewColumn.Active);
            return existing;
        }
        let contents: BundleContents;
        try {
            contents = await readBundleContents(absolute);
        } catch (err) {
            const message =
                err instanceof BundleFormatError
                    ? err.message
                    : `Unable to read bundle: ${(err as Error).message}`;
            void vscode.window.showErrorMessage(
                `Compose Preview — ${path.basename(absolute)}: ${message}`,
            );
            deps.logLine(`${CHANNEL} reject ${absolute}: ${message}`);
            return null;
        }
        if (!contents.previews || contents.previews.previews.length === 0) {
            void vscode.window.showWarningMessage(
                `Compose Preview — ${path.basename(absolute)}: bundle has no previews to render.`,
            );
            return null;
        }
        const panel = vscode.window.createWebviewPanel(
            "composePreview.bundleViewer",
            `Bundle: ${bundleLabel(absolute)}`,
            vscode.ViewColumn.Active,
            {
                enableScripts: true,
                retainContextWhenHidden: true,
                localResourceRoots: [deps.extensionUri],
            },
        );
        const viewer = new BundleViewerPanel(absolute, panel, deps, contents);
        BundleViewerPanel.active.set(absolute, viewer);
        viewer.start();
        return viewer;
    }

    private disposed = false;
    private daemon: BundleDaemonHandle | null = null;
    /** Webview signalled `webviewReady`; safe to push messages after this. */
    private webviewReady = false;
    /** Queued initial `setPreviews` until the webview is ready (the panel
     *  can boot before its first `firstUpdated` if the user has the tab in
     *  the background while VS Code restores window state). */
    private pendingSetPreviews: PreviewInfo[] | null = null;
    /** Active a11y-overlay subscriptions, used so `dispose` can unwind
     *  the daemon-side state cleanly even when the panel closes
     *  abruptly. */
    private readonly a11yOverlays = new Set<string>();
    /** `(previewId, kind)` subscriptions started via the data-extension
     *  chip-bar. Mirrors the wire state so a chip toggle off (or panel
     *  disposal) unsubscribes exactly what we subscribed to. */
    private readonly dataSubscriptions = new Map<string, Set<string>>();
    /** Active `composestream/1` streams keyed by `previewId`. Value is
     *  the daemon-allocated `frameStreamId` we hand back as the
     *  `interactive/input` routing key. Mirrors
     *  `extension.ts:activeStreamFrameStreams` but scoped to this panel
     *  so each bundle tab tears down its own streams on close. */
    private readonly activeStreams = new Map<string, string>();
    /** Reverse lookup for `streamFrame` notifications — the daemon
     *  identifies the stream by `frameStreamId`, the webview wants
     *  `previewId`. */
    private readonly streamFrameIdToPreviewId = new Map<string, string>();
    /** Active `recording/*` sessions keyed by `previewId`. Value is the
     *  daemon-allocated recordingId; format lives in a sibling map so
     *  `recording/encode` on stop can pick the same container the user
     *  chose at start. Mirrors `extension.ts:activeRecordingSessions`
     *  / `activeRecordingFormats`, panel-scoped. */
    private readonly activeRecordings = new Map<string, string>();
    private readonly activeRecordingFormats = new Map<
        string,
        RecordingFormat
    >();
    /** Per-preview mutation queue so two rapid REC clicks can't race
     *  `recording/start` against `recording/stop`. Same shape as
     *  `extension.ts:recordingMutationQueues`. */
    private readonly recordingMutationQueues = new Map<string, Promise<void>>();
    /** Synthetic module id the bundle viewer surfaces to `<preview-app>`
     *  so `setInteractiveAvailability` routes through the same
     *  `LiveStateController` plumbing the sidebar uses. */
    private readonly moduleId: string;

    private constructor(
        private readonly bundlePath: string,
        private readonly panel: vscode.WebviewPanel,
        private readonly deps: BundleViewerHostDeps,
        private readonly contents: BundleContents,
    ) {
        // `bundle:<absolutePath>` matches the synthetic id we publish in
        // `setPreviews.moduleDir` and the routing key the per-panel
        // daemon advertises via `setInteractiveAvailability`.
        this.moduleId = `bundle:${bundlePath}`;
        panel.webview.html = this.buildHtml();
        panel.onDidDispose(() => this.dispose());
        panel.webview.onDidReceiveMessage((msg: WebviewMessage) => {
            void this.onWebviewMessage(msg);
        });
    }

    private dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        BundleViewerPanel.active.delete(this.bundlePath);
        // Best-effort stream/stop for any active live previews before
        // SIGTERMing the daemon — keeps the daemon's last-stderr line a
        // graceful "stream closed" instead of a mid-flush abort.
        for (const sid of this.activeStreams.values()) {
            try {
                this.daemon?.client.streamStop({ frameStreamId: sid });
            } catch {
                /* daemon may already be gone; safeKill follows */
            }
        }
        this.activeStreams.clear();
        this.streamFrameIdToPreviewId.clear();
        // Recordings need stop+encode before the daemon's frame buffer
        // disappears. Snapshot the maps, then kick off the encode
        // asynchronously — `disposeAfterRecordings` awaits the
        // recording flushes (with a timeout) before SIGTERMing the
        // daemon, so the user gets the saved videoPath toast for an
        // in-progress recording even if they closed the tab.
        const pendingRecordings = [...this.activeRecordings.entries()];
        const pendingFormats = new Map(this.activeRecordingFormats);
        this.activeRecordings.clear();
        this.activeRecordingFormats.clear();
        if (pendingRecordings.length === 0) {
            this.daemon?.dispose();
            this.daemon = null;
            return;
        }
        void this.disposeAfterRecordings(pendingRecordings, pendingFormats);
    }

    private async disposeAfterRecordings(
        pending: Array<[string, string]>,
        formats: Map<string, RecordingFormat>,
    ): Promise<void> {
        const daemon = this.daemon;
        if (!daemon) return;
        try {
            await Promise.race([
                Promise.all(
                    pending.map(async ([previewId, recordingId]) => {
                        try {
                            const stopped = await daemon.client.recordingStop({
                                recordingId,
                            });
                            const encoded = await daemon.client.recordingEncode(
                                {
                                    recordingId,
                                    format: formats.get(previewId) ?? "apng",
                                },
                            );
                            this.deps.logLine(
                                `${CHANNEL} recording flushed ${previewId}: ${encoded.videoPath} ` +
                                    `(${stopped.frameCount} frames)`,
                            );
                            void vscode.window.showInformationMessage(
                                `Compose preview recording saved: ${encoded.videoPath}`,
                            );
                        } catch (err) {
                            this.deps.logLine(
                                `${CHANNEL} recording flush ${previewId} failed: ${(err as Error).message}`,
                            );
                        }
                    }),
                ),
                // Guardrail: if the daemon hangs on encode (e.g. ffmpeg
                // shells out to a missing binary), don't pin the JVM
                // alive forever — 10s is more than enough for an APNG /
                // MP4 of a few-second recording.
                new Promise<void>((resolve) => setTimeout(resolve, 10_000)),
            ]);
        } finally {
            daemon.dispose();
            this.daemon = null;
        }
    }

    private start(): void {
        void this.spawnDaemonAndSeed();
    }

    /**
     * Env overrides for the daemon subprocess. Only an `backend="android"`
     * bundle needs them: it launches the Robolectric daemon, which resolves
     * `android.jar` from the SDK. Returns `undefined` for desktop bundles, or
     * when no SDK resolves (the daemon then falls back to its inherited env and
     * surfaces an actionable error if that has none either).
     */
    private resolveAndroidDaemonEnv(): Record<string, string> | undefined {
        if (this.contents.manifest?.backend !== "android") return undefined;
        const settingPath = vscode.workspace
            .getConfiguration("composePreview")
            .get<string>("androidSdkPath", "");
        // Prefer the workspace folder that actually contains the opened bundle so
        // its `local.properties`/`sdk.dir` is consulted in a multi-root window;
        // fall back to the first folder when the bundle lives outside any folder.
        const bundleFolder = vscode.workspace.getWorkspaceFolder(
            vscode.Uri.file(this.bundlePath),
        );
        const workspaceRoot = (
            bundleFolder ?? vscode.workspace.workspaceFolders?.[0]
        )?.uri.fsPath;
        const resolution = resolveAndroidSdk({ settingPath, workspaceRoot });
        if (!resolution) {
            this.deps.logLine(
                `${CHANNEL} android bundle: no Android SDK resolved (composePreview.androidSdkPath / ` +
                    `ANDROID_HOME / ANDROID_SDK_ROOT / local.properties); relying on inherited env.`,
            );
            return undefined;
        }
        this.deps.logLine(
            `${CHANNEL} android bundle: Android SDK from ${resolution.source}: ${resolution.sdkDir}`,
        );
        return androidSdkEnv(resolution);
    }

    /**
     * Tack an actionable hint onto an android daemon launch failure whose
     * message points at a missing SDK / sidecar, so the panel error tells the
     * user how to fix it rather than just quoting the exit code.
     */
    private augmentSpawnError(message: string): string {
        const isAndroid = this.contents.manifest?.backend === "android";
        if (
            isAndroid &&
            /android\.jar|ANDROID_HOME|ANDROID_SDK_ROOT|lib-daemon-android/i.test(
                message,
            )
        ) {
            return (
                `${message}\n\nThis is an Android bundle. Set the SDK location via the ` +
                `\`composePreview.androidSdkPath\` setting (or export ANDROID_HOME before ` +
                `launching VS Code), and ensure the compose-preview CLI ships the Android daemon.`
            );
        }
        return message;
    }

    private async spawnDaemonAndSeed(): Promise<void> {
        let cliPath: string;
        try {
            cliPath = await locateBundleCli();
        } catch (err) {
            const message =
                err instanceof BundleCliNotFoundError
                    ? err.message
                    : (err as Error).message;
            this.deps.logLine(`${CHANNEL} ${message}`);
            this.postError(
                "compose-preview CLI not found",
                "Install the CLI (scripts/install.sh) or set `composePreview.bundleCliPath`.",
            );
            return;
        }
        // An android bundle launches the Robolectric daemon, which needs
        // android.jar from a local SDK. Resolve one (setting → ANDROID_HOME →
        // ANDROID_SDK_ROOT → workspace local.properties) and forward it into the
        // daemon's env so the launch works even when VS Code was started from the
        // GUI without a shell ANDROID_HOME.
        const envOverrides = this.resolveAndroidDaemonEnv();
        let daemon: BundleDaemonHandle;
        try {
            daemon = await vscode.window.withProgress(
                {
                    location: vscode.ProgressLocation.Notification,
                    title: `Starting preview daemon for ${bundleLabel(this.bundlePath)}…`,
                    cancellable: false,
                },
                () =>
                    spawnBundleDaemon({
                        bundlePath: this.bundlePath,
                        cliPath,
                        logger: {
                            appendLine: (line) => this.deps.logLine(line),
                        },
                        clientVersion: CLIENT_VERSION,
                        envOverrides,
                        events: {
                            onRenderFinished: (params) =>
                                void this.onRenderFinished(params),
                            onRenderFailed: (params) =>
                                this.onRenderFailed(params),
                            onStreamFrame: (params) =>
                                this.onStreamFrame(params),
                        },
                    }),
            );
        } catch (err) {
            const message = (err as Error).message ?? String(err);
            this.deps.logLine(`${CHANNEL} daemon spawn failed: ${message}`);
            this.postError(
                "Bundle daemon failed to start",
                this.augmentSpawnError(message),
            );
            return;
        }
        this.daemon = daemon;
        // If the JVM dies while the panel is still open, surface it once
        // and dispose so a follow-up open spawns a fresh daemon rather
        // than re-using the dead handle.
        daemon.exited.then((code) => {
            if (this.disposed || code === null || code === 0) return;
            this.postError(
                "Bundle daemon exited",
                `JVM exit code ${code} — open the Compose Preview output channel for details.`,
            );
            this.dispose();
        });

        const previews = synthesisePreviews(this.contents);
        this.pendingSetPreviews = previews;
        if (this.webviewReady) this.flushPendingSetPreviews();

        // Tell the daemon the panel cares about every preview in the
        // bundle. `setVisible` ungates the scheduler and lets
        // subsequent `renderNow` calls actually fan out.
        const ids = previews.map((p) => p.id);
        try {
            daemon.client.setVisible({ ids });
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} setVisible failed: ${(err as Error).message}`,
            );
        }
        try {
            await renderAll(daemon.client, ids, "bundle-open");
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} initial renderNow failed: ${(err as Error).message}`,
            );
        }
    }

    private flushPendingSetPreviews(): void {
        const previews = this.pendingSetPreviews;
        if (!previews) return;
        this.pendingSetPreviews = null;
        // Signal "daemon-backed mode is live" to the webview by writing
        // a truthy moduleDir — `<preview-app>`'s bundle-mode toolbar
        // gating unhides the daemon-driven buttons once it sees a
        // non-empty module id. Bundle path serves as the synthetic id.
        this.panel.webview.postMessage({
            command: "setPreviews",
            previews,
            moduleDir: this.moduleId,
            heavyStaleIds: [],
        });
        this.panel.webview.postMessage({
            command: "bundleDaemonReady",
            bundlePath: this.bundlePath,
        });
        // `LiveStateController.setAvailability` keys daemon-readiness +
        // interactive-support per moduleId. Without this ping the LIVE
        // button stays disabled even when the bundle daemon is up — the
        // controller has no other path to learn whether
        // `composestream/1` is on offer. Mirrors the sidebar's
        // `publishInteractiveAvailability` post.
        const interactive =
            this.daemon?.initializeResult.capabilities.interactive === true;
        this.panel.webview.postMessage({
            command: "setInteractiveAvailability",
            moduleId: this.moduleId,
            ready: true,
            interactiveSupported: interactive,
        });
    }

    private async onRenderFinished(
        params: RenderFinishedParams,
    ): Promise<void> {
        if (this.disposed) return;
        try {
            const buf = await fs.promises.readFile(params.pngPath);
            this.panel.webview.postMessage({
                command: "updateImage",
                previewId: params.id,
                captureIndex: 0,
                imageData: buf.toString("base64"),
            });
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} read renderFinished png ${params.pngPath}: ${(err as Error).message}`,
            );
        }
        // Forward subscribed data-product attachments to the webview.
        // Same shape the sidebar's `updateA11y` / `updateDataProducts`
        // uses; the bundle viewer's <preview-app> consumes them
        // identically.
        if (params.dataProducts?.length) {
            this.forwardDataProducts(params.id, params.dataProducts);
        }
    }

    private onRenderFailed(params: RenderFailedParams): void {
        if (this.disposed) return;
        this.panel.webview.postMessage({
            command: "setImageError",
            previewId: params.id,
            captureIndex: 0,
            message: params.error?.message ?? "Render failed",
        });
    }

    /**
     * Split [attachments] into the a11y bucket (which the webview
     * consumes via `updateA11y` with structured findings + hierarchy
     * nodes) and the generic bucket (`updateDataProducts`). Mirrors the
     * sidebar's mapping at extension.ts:onRenderFinished.
     */
    private forwardDataProducts(
        previewId: string,
        attachments: DataProductAttachment[],
    ): void {
        let findings: unknown = undefined;
        let nodes: unknown = undefined;
        const others: { kind: string; payload: unknown }[] = [];
        for (const att of attachments) {
            if (att.kind === "a11y/atf") {
                findings = att.payload ?? null;
            } else if (att.kind === "a11y/hierarchy") {
                nodes = att.payload ?? null;
            } else {
                others.push({ kind: att.kind, payload: att.payload });
            }
        }
        if (findings !== undefined || nodes !== undefined) {
            this.panel.webview.postMessage({
                command: "updateA11y",
                previewId,
                findings,
                nodes,
            });
        }
        if (others.length > 0) {
            this.panel.webview.postMessage({
                command: "updateDataProducts",
                previewId,
                dataProducts: others,
            });
        }
    }

    private postError(title: string, detail: string): void {
        void vscode.window.showErrorMessage(
            `Compose Preview — ${path.basename(this.bundlePath)}: ${title}. ${detail}`,
        );
    }

    private async onWebviewMessage(msg: WebviewMessage): Promise<void> {
        if (this.disposed) return;
        switch (msg.command) {
            case "webviewReady":
                this.webviewReady = true;
                if (this.pendingSetPreviews) this.flushPendingSetPreviews();
                break;
            case "openExternal":
                if (msg.url && /^https?:\/\//i.test(msg.url)) {
                    void vscode.env.openExternal(vscode.Uri.parse(msg.url));
                }
                break;
            case "setA11yOverlay":
                await this.handleA11yOverlay(msg.previewId, msg.enabled);
                break;
            case "setDataExtensionEnabled":
                await this.handleDataExtensionToggle(
                    msg.previewId,
                    msg.kinds,
                    msg.enabled,
                );
                break;
            case "refreshHeavy":
                await this.requestRender([msg.previewId], "refresh-heavy");
                break;
            case "previewScopeChanged":
                // Informational — the bundle viewer ignores scope shifts
                // because every preview in the bundle is "in scope".
                break;
            case "viewportUpdated":
                this.daemon?.client.setVisible({ ids: msg.visible });
                break;
            case "requestStreamStart":
                await this.handleStreamStart(msg.previewId, msg.overrides);
                break;
            case "requestStreamStop":
                this.handleStreamStop(msg.previewId);
                break;
            case "requestStreamVisibility":
                this.handleStreamVisibility(
                    msg.previewId,
                    msg.visible,
                    msg.fps,
                );
                break;
            case "recordInteractiveInput":
                this.handleInteractiveInput(msg);
                this.forwardRecordingInput(msg);
                break;
            case "setRecording":
                this.queueRecordingMutation(
                    msg.previewId,
                    msg.enabled,
                    msg.format ?? "apng",
                );
                break;
        }
    }

    private async handleStreamStart(
        previewId: string,
        overrides?: import("./daemon/daemonProtocol").PreviewOverrides,
    ): Promise<void> {
        const client = this.daemon?.client;
        if (!client) return;
        if (this.activeStreams.has(previewId)) {
            // Idempotent — the webview's optimistic LIVE toggle may
            // re-issue start on a card that's already streaming.
            return;
        }
        try {
            const result = await client.streamStart(
                overrides ? { previewId, overrides } : { previewId },
            );
            if (this.disposed) {
                client.streamStop({ frameStreamId: result.frameStreamId });
                return;
            }
            this.activeStreams.set(previewId, result.frameStreamId);
            this.streamFrameIdToPreviewId.set(result.frameStreamId, previewId);
            this.panel.webview.postMessage({
                command: "streamStarted",
                previewId,
                frameStreamId: result.frameStreamId,
                codec: result.codec,
                heldSession: result.heldSession,
            });
            // Match the sidebar: setFocus pins the preview as the
            // interactive target so the daemon's scheduler doesn't
            // throttle it.
            client.setFocus({ ids: [previewId] });
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} stream/start ${previewId} failed: ${(err as Error).message}`,
            );
            // Roll the webview's optimistic LIVE state back so the
            // button isn't stuck "on" against a stream that never
            // started. `streamStopped` alone only detaches the
            // `<canvas>` painter — `clearInteractive` is what unwinds
            // `LiveStateController`'s live-set so the toolbar gating
            // and pointer-event masking revert. The sidebar's
            // `handleRequestStreamStart` falls back to a legacy
            // interactive path here that keeps the card LIVE; bundle
            // mode has no such fallback.
            this.panel.webview.postMessage({
                command: "clearInteractive",
                previewId,
            });
            this.panel.webview.postMessage({
                command: "streamStopped",
                previewId,
            });
        }
    }

    private handleStreamStop(previewId: string): void {
        const sid = this.activeStreams.get(previewId);
        if (!sid) return;
        this.activeStreams.delete(previewId);
        this.streamFrameIdToPreviewId.delete(sid);
        try {
            this.daemon?.client.streamStop({ frameStreamId: sid });
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} stream/stop ${previewId}: ${(err as Error).message}`,
            );
        }
        if (!this.disposed) {
            this.panel.webview.postMessage({
                command: "streamStopped",
                previewId,
            });
        }
    }

    private handleStreamVisibility(
        previewId: string,
        visible: boolean,
        fps?: number,
    ): void {
        const sid = this.activeStreams.get(previewId);
        if (!sid) return;
        try {
            this.daemon?.client.streamVisibility({
                frameStreamId: sid,
                visible,
                fps,
            });
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} stream/visibility ${previewId}: ${(err as Error).message}`,
            );
        }
    }

    private handleInteractiveInput(
        msg: Extract<WebviewMessage, { command: "recordInteractiveInput" }>,
    ): void {
        const sid = this.activeStreams.get(msg.previewId);
        if (!sid) return;
        try {
            this.daemon?.client.interactiveInput({
                frameStreamId: sid,
                kind: msg.kind,
                pixelX: msg.pixelX,
                pixelY: msg.pixelY,
                scrollDeltaY: msg.scrollDeltaY,
                keyCode: msg.keyCode,
                text: msg.text,
                pointerType: msg.pointerType,
            });
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} interactive/input ${msg.previewId}: ${(err as Error).message}`,
            );
        }
    }

    /**
     * When a recording is active on this preview, fan out the same
     * pointer/key event to `recording/input` so the recorded script
     * captures it alongside the live frame. Mirrors `forwardRecordingInput`
     * in the sidebar's `extension.ts`.
     */
    private forwardRecordingInput(
        msg: Extract<WebviewMessage, { command: "recordInteractiveInput" }>,
    ): void {
        const recordingId = this.activeRecordings.get(msg.previewId);
        if (!recordingId) return;
        try {
            this.daemon?.client.recordingInput({
                recordingId,
                kind: msg.kind,
                pixelX: msg.pixelX,
                pixelY: msg.pixelY,
                scrollDeltaY: msg.scrollDeltaY,
                keyCode: msg.keyCode,
                text: msg.text,
                pointerType: msg.pointerType,
            });
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} recording/input ${msg.previewId}: ${(err as Error).message}`,
            );
        }
    }

    /**
     * Serialise REC start/stop toggles per previewId so a rapid double-
     * click can't fire `recording/start` while a `recording/stop` is in
     * flight (or vice versa). Same shape as
     * `extension.ts:queueRecordingMutation`.
     */
    private queueRecordingMutation(
        previewId: string,
        enabled: boolean,
        format: RecordingFormat,
    ): void {
        const previous =
            this.recordingMutationQueues.get(previewId) ?? Promise.resolve();
        const next = previous
            .catch(() => {})
            .then(() => this.handleSetRecording(previewId, enabled, format))
            .catch((err) => {
                this.deps.logLine(
                    `${CHANNEL} setRecording ${previewId} failed: ${(err as Error).message}`,
                );
                if (!this.disposed) {
                    this.panel.webview.postMessage({
                        command: "clearRecording",
                        previewId,
                    });
                }
            })
            .finally(() => {
                if (this.recordingMutationQueues.get(previewId) === next) {
                    this.recordingMutationQueues.delete(previewId);
                }
            });
        this.recordingMutationQueues.set(previewId, next);
    }

    private async handleSetRecording(
        previewId: string,
        enabled: boolean,
        format: RecordingFormat,
    ): Promise<void> {
        const client = this.daemon?.client;
        if (!client) {
            if (!this.disposed) {
                this.panel.webview.postMessage({
                    command: "clearRecording",
                    previewId,
                });
            }
            return;
        }
        if (!enabled) {
            const recordingId = this.activeRecordings.get(previewId);
            if (!recordingId) {
                if (!this.disposed) {
                    this.panel.webview.postMessage({
                        command: "clearRecording",
                        previewId,
                    });
                }
                return;
            }
            this.activeRecordings.delete(previewId);
            const encodeFormat =
                this.activeRecordingFormats.get(previewId) ?? format;
            this.activeRecordingFormats.delete(previewId);
            try {
                const stopped = await client.recordingStop({ recordingId });
                const encoded = await client.recordingEncode({
                    recordingId,
                    format: encodeFormat,
                });
                this.deps.logLine(
                    `${CHANNEL} recording saved ${previewId}: ${encoded.videoPath} ` +
                        `(${stopped.frameCount} frames, ${stopped.durationMs}ms)`,
                );
                void vscode.window.showInformationMessage(
                    `Compose preview recording saved: ${encoded.videoPath}`,
                );
            } catch (err) {
                this.deps.logLine(
                    `${CHANNEL} recording/stop ${previewId} failed: ${(err as Error).message}`,
                );
                void vscode.window.showErrorMessage(
                    `Compose preview recording failed: ${(err as Error).message}`,
                );
            } finally {
                if (!this.disposed) {
                    this.panel.webview.postMessage({
                        command: "clearRecording",
                        previewId,
                    });
                }
            }
            return;
        }

        if (this.activeRecordings.has(previewId)) {
            // Idempotent on the optimistic-toggle path.
            return;
        }
        try {
            const result = await client.recordingStart({
                previewId,
                fps: 30,
                scale: 1.0,
                live: true,
            });
            this.activeRecordings.set(previewId, result.recordingId);
            this.activeRecordingFormats.set(previewId, format);
            client.setFocus({ ids: [previewId] });
            this.deps.logLine(
                `${CHANNEL} recording on ${previewId} (recordingId=${result.recordingId})`,
            );
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} recording/start ${previewId} failed: ${(err as Error).message}`,
            );
            if (!this.disposed) {
                this.panel.webview.postMessage({
                    command: "clearRecording",
                    previewId,
                });
            }
            void vscode.window.showErrorMessage(
                `Compose preview recording failed: ${(err as Error).message}`,
            );
        }
    }

    private onStreamFrame(params: StreamFrameParams): void {
        if (this.disposed) return;
        const previewId = this.streamFrameIdToPreviewId.get(
            params.frameStreamId,
        );
        if (!previewId) return;
        this.panel.webview.postMessage({
            command: "streamFrame",
            previewId,
            frameStreamId: params.frameStreamId,
            seq: params.seq,
            ptsMillis: params.ptsMillis,
            widthPx: params.widthPx,
            heightPx: params.heightPx,
            codec: params.codec,
            keyframe: params.keyframe,
            final: params.final,
            payloadBase64: params.payloadBase64,
        });
        if (params.final) {
            this.activeStreams.delete(previewId);
            this.streamFrameIdToPreviewId.delete(params.frameStreamId);
        }
    }

    private async handleA11yOverlay(
        previewId: string,
        enabled: boolean,
    ): Promise<void> {
        if (!this.daemon) return;
        if (enabled) {
            this.a11yOverlays.add(previewId);
        } else {
            this.a11yOverlays.delete(previewId);
        }
        await setKindsSubscribed(
            this.daemon.client,
            previewId,
            A11Y_DATA_KINDS,
            enabled,
            (kind, err) =>
                this.deps.logLine(
                    `${CHANNEL} data/${enabled ? "subscribe" : "unsubscribe"} ${kind} ${previewId} failed: ${err.message}`,
                ),
        );
        if (!enabled) {
            // Tear down panel-side cache the same way the sidebar does —
            // an empty `updateA11y` clears the overlay without waiting
            // for a fresh renderFinished.
            this.panel.webview.postMessage({
                command: "updateA11y",
                previewId,
                findings: null,
                nodes: null,
            });
        } else {
            await this.requestRender([previewId], "a11y-overlay-on");
        }
    }

    private async handleDataExtensionToggle(
        previewId: string,
        kinds: string[],
        enabled: boolean,
    ): Promise<void> {
        if (!this.daemon) return;
        const bucket =
            this.dataSubscriptions.get(previewId) ?? new Set<string>();
        for (const kind of kinds) {
            if (enabled) bucket.add(kind);
            else bucket.delete(kind);
        }
        if (bucket.size === 0) this.dataSubscriptions.delete(previewId);
        else this.dataSubscriptions.set(previewId, bucket);
        await setKindsSubscribed(
            this.daemon.client,
            previewId,
            kinds,
            enabled,
            (kind, err) =>
                this.deps.logLine(
                    `${CHANNEL} data ${enabled ? "subscribe" : "unsubscribe"} ${kind} ${previewId}: ${err.message}`,
                ),
        );
        if (enabled) {
            // Trigger a render so the daemon attaches the newly-subscribed
            // kinds; mirrors what the sidebar does after a chip toggle.
            await this.requestRender([previewId], "extension-on");
        }
    }

    private async requestRender(
        previews: string[],
        reason: string,
    ): Promise<void> {
        const client: DaemonClient | undefined = this.daemon?.client;
        if (!client) return;
        try {
            await client.renderNow({ previews, tier: "full", reason });
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} renderNow ${previews.join(",")} (${reason}) failed: ${(err as Error).message}`,
            );
        }
    }

    private buildHtml(): string {
        const webview = this.panel.webview;
        const nonce = crypto.randomBytes(16).toString("hex");
        const styleUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.deps.extensionUri, "media", "preview.css"),
        );
        const codiconUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.deps.extensionUri, "media", "codicon.css"),
        );
        const scriptUri = webview.asWebviewUri(
            vscode.Uri.joinPath(
                this.deps.extensionUri,
                "media",
                "webview",
                "preview.js",
            ),
        );
        const early = this.deps.earlyFeaturesEnabled() ? "true" : "false";
        return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; img-src data:; font-src ${webview.cspSource} https://fonts.gstatic.com; style-src ${webview.cspSource} https://fonts.googleapis.com 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
    <link href="${codiconUri}" rel="stylesheet">
    <link href="${styleUri}" rel="stylesheet">
    <link href="https://fonts.googleapis.com/css2?family=Roboto:ital,wght@0,300;0,400;0,500;0,700;1,400&family=Roboto+Serif:ital,wght@0,400;0,500;0,700;1,400&family=Roboto+Mono:ital,wght@0,400;0,500;0,700&family=Caveat:wght@400;700&display=swap" rel="stylesheet">
</head>
<body>
    <preview-app
        data-early-features="${early}"
        data-minimal-mode="false"
        data-bundle-mode="true"
    ></preview-app>
    <script nonce="${nonce}" src="${scriptUri}"></script>
</body>
</html>`;
    }
}

/**
 * Map the bundle's discovery-time `previews.json` into the `PreviewInfo`
 * shape `<preview-app>` consumes via `setPreviews`. Images arrive later
 * via daemon `renderFinished` notifications → host `updateImage`
 * postMessages, so the synthetic `Capture[]` here carries a placeholder
 * `renderOutput` string that the panel only uses as a key.
 */
function synthesisePreviews(contents: BundleContents): PreviewInfo[] {
    return (contents.previews?.previews ?? []).map((entry) => {
        const params: PreviewParams = {
            name: entry.params.name ?? null,
            device: entry.params.device ?? null,
            widthDp: entry.params.widthDp ?? null,
            heightDp: entry.params.heightDp ?? null,
            fontScale: entry.params.fontScale ?? 1,
            showSystemUi: false,
            showBackground: entry.params.showBackground ?? false,
            backgroundColor: entry.params.backgroundColor ?? 0,
            uiMode: entry.params.uiMode ?? 0,
            locale: entry.params.locale ?? null,
            group: entry.params.group ?? null,
        };
        const captures: Capture[] = [
            {
                advanceTimeMillis: null,
                scroll: null,
                renderOutput: `${entry.id}.png`,
            },
        ];
        return {
            id: entry.id,
            functionName: entry.functionName,
            className: entry.className,
            sourceFile: entry.sourceFile ?? null,
            params,
            captures,
        };
    });
}
