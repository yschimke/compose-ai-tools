import { Readable, Writable } from "stream";
import { FrameDecoder, encodeFrame } from "./daemonFraming";
import {
    ClasspathDirtyParams,
    DaemonWarmingParams,
    DataFetchParams,
    DataFetchResult,
    DataSubscribeParams,
    DataSubscribeResult,
    DiscoveryUpdatedParams,
    FileChangedParams,
    HistoryAddedParams,
    HistoryPrunedParams,
    HistoryDiffParams,
    HistoryDiffResult,
    HistoryListParams,
    HistoryListResult,
    HistoryReadParams,
    HistoryReadResult,
    ExtensionsDisableParams,
    ExtensionsDisableResult,
    ExtensionsEnableParams,
    ExtensionsEnableResult,
    ExtensionsListResult,
    InitializeParams,
    InitializeResult,
    InteractiveInputParams,
    InteractiveStartParams,
    InteractiveStartResult,
    InteractiveStopParams,
    JsonRpcError,
    JsonRpcNotification,
    JsonRpcRequest,
    JsonRpcResponse,
    LogParams,
    PROTOCOL_VERSION,
    RecordingEncodeParams,
    RecordingEncodeResult,
    RecordingInputParams,
    RecordingStartParams,
    RecordingStartResult,
    RecordingStopParams,
    RecordingStopResult,
    RenderFailedParams,
    RenderFinishedParams,
    RenderNowParams,
    RenderNowResult,
    RenderStartedParams,
    SandboxRecycleParams,
    SetFocusParams,
    SetVisibleParams,
    StreamFrameParams,
    StreamStartParams,
    StreamStartResult,
    StreamStopParams,
    StreamVisibilityParams,
} from "./daemonProtocol";

export interface DaemonClientLogger {
    appendLine(value: string): void;
}

const nullLogger: DaemonClientLogger = {
    appendLine() {
        /* noop */
    },
};

/**
 * Daemon → client notification handlers. All optional; ignore what you don't
 * care about. Unknown notifications from the daemon are dropped silently per
 * PROTOCOL.md § 7 (additive notifications must not break old clients).
 */
export interface DaemonClientEvents {
    onDiscoveryUpdated?: (params: DiscoveryUpdatedParams) => void;
    onRenderStarted?: (params: RenderStartedParams) => void;
    onRenderFinished?: (params: RenderFinishedParams) => void;
    onRenderFailed?: (params: RenderFailedParams) => void;
    onClasspathDirty?: (params: ClasspathDirtyParams) => void;
    onSandboxRecycle?: (params: SandboxRecycleParams) => void;
    onDaemonWarming?: (params: DaemonWarmingParams) => void;
    onDaemonReady?: () => void;
    onLog?: (params: LogParams) => void;
    /** Phase H2 — daemon emits this after writing each render's sidecar +
     *  index entry. Subscribers avoid polling `history/list`. */
    onHistoryAdded?: (params: HistoryAddedParams) => void;
    /** Phase H4 — daemon emits this after a non-empty auto- or manual-prune
     *  pass. Subscribers drop the removed IDs from any in-memory caches. */
    onHistoryPruned?: (params: HistoryPrunedParams) => void;
    /** `composestream/1` — one frame on a live stream. See
     *  docs/daemon/STREAMING.md. The client routes these to the active
     *  webview's painter via the StreamClient + canvas pipeline. */
    onStreamFrame?: (params: StreamFrameParams) => void;
    /** Stream end / framing collapse. After this fires, the client is dead. */
    onChannelClosed?: (err?: Error) => void;
}

export class DaemonRpcError extends Error {
    constructor(public readonly rpc: JsonRpcError) {
        super(`${rpc.code} ${rpc.message}`);
        this.name = "DaemonRpcError";
    }
}

interface PendingRequest {
    resolve: (value: unknown) => void;
    reject: (err: Error) => void;
    method: string;
}

/**
 * JSON-RPC 2.0 client with LSP-style `Content-Length` framing, speaking to a
 * preview-daemon JVM over its stdin/stdout. See `daemonProcess.ts` for the
 * spawn-and-supervise side; this class is transport-agnostic so unit tests
 * can drive it from in-memory streams.
 */
export class DaemonClient {
    private nextId = 1;
    private readonly pending = new Map<number, PendingRequest>();
    private readonly decoder: FrameDecoder;
    private closed = false;

    constructor(
        private readonly stdin: Writable,
        stdout: Readable,
        private readonly events: DaemonClientEvents,
        private readonly logger: DaemonClientLogger = nullLogger,
    ) {
        this.decoder = new FrameDecoder({
            onMessage: (json) => this.handleIncoming(json),
            onError: (err) => this.fail(err),
        });

        stdout.on("data", (chunk: Buffer | string) => {
            const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
            this.decoder.push(buf);
        });
        stdout.on("end", () => this.fail());
        stdout.on("error", (err: Error) => this.fail(err));
        stdin.on("error", (err: Error) => this.fail(err));
    }

    initialize(
        params: Omit<InitializeParams, "protocolVersion">,
    ): Promise<InitializeResult> {
        return this.request<InitializeResult>("initialize", {
            ...params,
            protocolVersion: PROTOCOL_VERSION,
        });
    }

    initialized(): void {
        this.notify("initialized", undefined);
    }

    /**
     * `extensions/list` (PROTOCOL.md § 3a). Snapshot of every registered extension and
     * whether it's currently public or pulled in as a dependency. Cheap; the panel calls
     * this once after `initialize` to learn what's available before deciding what to enable.
     */
    extensionsList(): Promise<ExtensionsListResult> {
        return this.request<ExtensionsListResult>("extensions/list", undefined);
    }

    /**
     * `extensions/enable {ids}`. Returns the new public capability snapshot in the
     * response so callers don't need a follow-up `extensions/list`.
     */
    extensionsEnable(
        params: ExtensionsEnableParams,
    ): Promise<ExtensionsEnableResult> {
        return this.request<ExtensionsEnableResult>(
            "extensions/enable",
            params,
        );
    }

    /** `extensions/disable {ids}`. */
    extensionsDisable(
        params: ExtensionsDisableParams,
    ): Promise<ExtensionsDisableResult> {
        return this.request<ExtensionsDisableResult>(
            "extensions/disable",
            params,
        );
    }

    setVisible(params: SetVisibleParams): void {
        this.notify("setVisible", params);
    }

    setFocus(params: SetFocusParams): void {
        this.notify("setFocus", params);
    }

    fileChanged(params: FileChangedParams): void {
        this.notify("fileChanged", params);
    }

    renderNow(params: RenderNowParams): Promise<RenderNowResult> {
        return this.request<RenderNowResult>("renderNow", params);
    }

    /** Phase H2. Returns recent history entries newest-first; pass back
     *  `result.nextCursor` as `params.cursor` to paginate. Filter fields are
     *  cumulative (AND semantics). See PROTOCOL.md § 5 (history/list). */
    historyList(params: HistoryListParams = {}): Promise<HistoryListResult> {
        return this.request<HistoryListResult>("history/list", params);
    }

    /** Phase H2. `inline: false` (default) returns `pngPath` only —
     *  preferred for local same-host clients; the bytes never traverse the
     *  wire. `inline: true` returns base64 `pngBytes`. See PROTOCOL.md § 5. */
    historyRead(params: HistoryReadParams): Promise<HistoryReadResult> {
        return this.request<HistoryReadResult>("history/read", params);
    }

    /** Phase H3 (metadata mode). `mode: 'pixel'` is reserved for H5 and
     *  rejects with `HistoryPixelNotImplemented` until then. See
     *  PROTOCOL.md § 5 (history/diff).
     *
     *  Experimental in 1.0: production daemons gate the handler behind
     *  `composeai.experimental.historyDiff`, so this call rejects with
     *  `MethodNotFound` (-32601) unless that sysprop is set. Callers should
     *  treat `MethodNotFound` as "diff unavailable" and fall back to a
     *  metadata-only side-by-side. TODO(1.1): once the daemon flips the
     *  default, drop the fallback path on the call sites. */
    historyDiff(params: HistoryDiffParams): Promise<HistoryDiffResult> {
        return this.request<HistoryDiffResult>("history/diff", params);
    }

    /**
     * D1 — pull-on-demand data product fetch. See
     * `docs/daemon/DATA-PRODUCTS.md` § "Wire surface". The call resolves
     * against the latest render of the preview; the daemon may trigger a
     * re-render if the kind wasn't computed in the last pass and is
     * marked `requiresRerender: true` in capabilities. Pre-D2 daemons
     * that haven't wired any producer reject with
     * `DataProductUnknown (-32020)`.
     */
    dataFetch(params: DataFetchParams): Promise<DataFetchResult> {
        return this.request<DataFetchResult>("data/fetch", params);
    }

    /**
     * D1 — sticky `(previewId, kind)` subscription. While subscribed, every
     * `renderFinished` for `previewId` carries the kind in its
     * `dataProducts` field. Idempotent. Drops automatically when
     * `previewId` leaves the most recent `setVisible` set, so the panel
     * UI is invited to re-subscribe when the preview returns to view.
     */
    dataSubscribe(params: DataSubscribeParams): Promise<DataSubscribeResult> {
        return this.request<DataSubscribeResult>("data/subscribe", params);
    }

    /** D1 — opposite of {@link dataSubscribe}. Idempotent — unsubscribing
     *  a kind that was never subscribed succeeds silently. */
    dataUnsubscribe(params: DataSubscribeParams): Promise<DataSubscribeResult> {
        return this.request<DataSubscribeResult>("data/unsubscribe", params);
    }

    /**
     * Interactive mode (reserved — see docs/daemon/INTERACTIVE.md § 7).
     *
     * Tells the daemon "this preview is the user's interactive target." Pins
     * a warm sandbox to it and returns an opaque stream id used to correlate
     * later `interactiveInput` notifications. **Not yet implemented by any
     * shipped daemon** — calling against a v1.0 daemon rejects with
     * `MethodNotFound (-32601)`. The wire shape is locked so a future
     * lockstep landing in `daemon/core` doesn't reshuffle the client.
     */
    interactiveStart(
        params: InteractiveStartParams,
    ): Promise<InteractiveStartResult> {
        return this.request<InteractiveStartResult>(
            "interactive/start",
            params,
        );
    }

    /** Symmetric to {@link interactiveStart}. Idempotent — extra stops are
     *  no-ops. Notification, not request: drop-and-go semantics. */
    interactiveStop(params: InteractiveStopParams): void {
        this.notify("interactive/stop", params);
    }

    /** Notification (drop-and-go). The daemon dispatches the input into the
     *  active composition and emits a fresh `renderFinished` once it
     *  settles. Backpressure is the caller's job — don't issue a new input
     *  before the prior frame arrives. */
    interactiveInput(params: InteractiveInputParams): void {
        this.notify("interactive/input", params);
    }

    /**
     * `composestream/1` — opens a live frame stream against a held
     * interactive session. The daemon emits `streamFrame` notifications
     * carrying inline base64 payload (or unchanged-heartbeats) on every
     * renderFinished for the target preview until `stream/stop` arrives.
     *
     * Daemons older than the protocol reject with `MethodNotFound (-32601)`;
     * callers should fall back to `interactiveStart` + the legacy
     * `renderFinished` PNG-on-disk path. See docs/daemon/STREAMING.md.
     */
    streamStart(params: StreamStartParams): Promise<StreamStartResult> {
        return this.request<StreamStartResult>("stream/start", params);
    }

    /** Symmetric to {@link streamStart}. Idempotent — extra stops are
     *  no-ops. Notification, drop-and-go semantics. The daemon emits one
     *  final `streamFrame` carrying `final: true` so the painter can
     *  release decoder state. */
    streamStop(params: StreamStopParams): void {
        this.notify("stream/stop", params);
    }

    /** Throttle / un-throttle a stream when the preview card scrolls out
     *  of (or back into) viewport. Idempotent and silent on unknown stream
     *  ids. Replaces the legacy "stop on scroll-out" semantics — the held
     *  session stays warm while throttled. */
    streamVisibility(params: StreamVisibilityParams): void {
        this.notify("stream/visibility", params);
    }

    recordingStart(
        params: RecordingStartParams,
    ): Promise<RecordingStartResult> {
        return this.request<RecordingStartResult>("recording/start", params);
    }

    recordingInput(params: RecordingInputParams): void {
        this.notify("recording/input", params);
    }

    recordingStop(params: RecordingStopParams): Promise<RecordingStopResult> {
        return this.request<RecordingStopResult>("recording/stop", params);
    }

    recordingEncode(
        params: RecordingEncodeParams,
    ): Promise<RecordingEncodeResult> {
        return this.request<RecordingEncodeResult>("recording/encode", params);
    }

    /** Drains in-flight renders, then resolves. Daemon will not exit until `exit` fires. */
    shutdown(): Promise<null> {
        return this.request<null>("shutdown", undefined);
    }

    /** Daemon exits after this. Best-effort; the channel may be closed already. */
    exit(): void {
        try {
            this.notify("exit", undefined);
        } catch {
            /* channel already gone */
        }
    }

    isClosed(): boolean {
        return this.closed;
    }

    private request<T>(method: string, params: unknown): Promise<T> {
        if (this.closed) {
            return Promise.reject(
                new Error(`Daemon channel closed; cannot send ${method}`),
            );
        }
        const id = this.nextId++;
        const envelope: JsonRpcRequest = {
            jsonrpc: "2.0",
            id,
            method,
            params: params ?? null,
        };
        return new Promise<T>((resolve, reject) => {
            this.pending.set(id, {
                resolve: (v) => resolve(v as T),
                reject,
                method,
            });
            try {
                this.stdin.write(encodeFrame(envelope));
            } catch (err) {
                this.pending.delete(id);
                reject(err instanceof Error ? err : new Error(String(err)));
            }
        });
    }

    private notify(method: string, params: unknown): void {
        if (this.closed) {
            return;
        }
        const envelope: JsonRpcNotification = {
            jsonrpc: "2.0",
            method,
            params: params === undefined ? null : params,
        };
        try {
            this.stdin.write(encodeFrame(envelope));
        } catch (err) {
            this.fail(err instanceof Error ? err : new Error(String(err)));
        }
    }

    private handleIncoming(json: string): void {
        let msg: JsonRpcResponse | JsonRpcNotification;
        try {
            msg = JSON.parse(json);
        } catch (err) {
            this.logger.appendLine(
                `[daemon] dropped non-JSON message: ${(err as Error).message}`,
            );
            return;
        }

        if (typeof (msg as JsonRpcResponse).id === "number") {
            const response = msg as JsonRpcResponse;
            const pending = this.pending.get(response.id);
            if (!pending) {
                this.logger.appendLine(
                    `[daemon] response with no pending request: id=${response.id}`,
                );
                return;
            }
            this.pending.delete(response.id);
            if (response.error) {
                pending.reject(new DaemonRpcError(response.error));
            } else {
                pending.resolve(response.result ?? null);
            }
            return;
        }

        const notification = msg as JsonRpcNotification;
        this.dispatchNotification(notification.method, notification.params);
    }

    private dispatchNotification(method: string, params: unknown): void {
        switch (method) {
            case "discoveryUpdated":
                this.events.onDiscoveryUpdated?.(
                    params as DiscoveryUpdatedParams,
                );
                break;
            case "renderStarted":
                this.events.onRenderStarted?.(params as RenderStartedParams);
                break;
            case "renderFinished":
                this.events.onRenderFinished?.(params as RenderFinishedParams);
                break;
            case "renderFailed":
                this.events.onRenderFailed?.(params as RenderFailedParams);
                break;
            case "classpathDirty":
                this.events.onClasspathDirty?.(params as ClasspathDirtyParams);
                break;
            case "sandboxRecycle":
                this.events.onSandboxRecycle?.(params as SandboxRecycleParams);
                break;
            case "daemonWarming":
                this.events.onDaemonWarming?.(params as DaemonWarmingParams);
                break;
            case "daemonReady":
                this.events.onDaemonReady?.();
                break;
            case "log":
                this.events.onLog?.(params as LogParams);
                break;
            case "historyAdded":
                this.events.onHistoryAdded?.(params as HistoryAddedParams);
                break;
            case "historyPruned":
                this.events.onHistoryPruned?.(params as HistoryPrunedParams);
                break;
            case "streamFrame":
                this.events.onStreamFrame?.(params as StreamFrameParams);
                break;
            default:
                this.logger.appendLine(
                    `[daemon] ignoring unknown notification: ${method}`,
                );
                break;
        }
    }

    private fail(err?: Error): void {
        if (this.closed) {
            return;
        }
        this.closed = true;
        const reason = err ?? new Error("Daemon channel closed");
        for (const [, pending] of this.pending) {
            pending.reject(reason);
        }
        this.pending.clear();
        this.events.onChannelClosed?.(err);
    }
}
