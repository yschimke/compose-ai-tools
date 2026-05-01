/**
 * TypeScript mirrors of the locked v1 protocol from
 * `daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/protocol/Messages.kt`.
 * Kept hand-rolled (no generator) so the round-trip golden fixtures under
 * `docs/daemon/protocol-fixtures/` are the only authority both sides depend on.
 *
 * Spec: docs/daemon/PROTOCOL.md (v1).
 */

export const PROTOCOL_VERSION = 1;

// JSON-RPC envelopes (PROTOCOL.md § 2)

export interface JsonRpcRequest<P = unknown> {
    jsonrpc: '2.0';
    id: number;
    method: string;
    params?: P;
}

export interface JsonRpcResponse<R = unknown> {
    jsonrpc: '2.0';
    id: number;
    result?: R;
    error?: JsonRpcError;
}

export interface JsonRpcNotification<P = unknown> {
    jsonrpc: '2.0';
    method: string;
    params?: P;
}

export interface JsonRpcError {
    code: number;
    message: string;
    data?: { kind?: string } & Record<string, unknown>;
}

export const ERROR_PARSE = -32700;
export const ERROR_INVALID_REQUEST = -32600;
export const ERROR_METHOD_NOT_FOUND = -32601;
export const ERROR_INVALID_PARAMS = -32602;
export const ERROR_INTERNAL = -32603;
export const ERROR_NOT_INITIALIZED = -32001;
export const ERROR_CLASSPATH_DIRTY = -32002;
export const ERROR_SANDBOX_RECYCLING = -32003;
export const ERROR_UNKNOWN_PREVIEW = -32004;
export const ERROR_RENDER_FAILED = -32005;
// History (phase H2 / H3) — see PROTOCOL.md § 5 (history/list, history/read,
// history/diff) and HISTORY.md § "Layer 2 — JSON-RPC API".
export const ERROR_HISTORY_ENTRY_NOT_FOUND = -32010;
export const ERROR_HISTORY_DIFF_MISMATCH = -32011;
export const ERROR_HISTORY_PIXEL_NOT_IMPLEMENTED = -32012;

// initialize (PROTOCOL.md § 3)

export interface InitializeParams {
    protocolVersion: number;
    clientVersion: string;
    workspaceRoot: string;
    moduleId: string;
    moduleProjectDir: string;
    capabilities: { visibility: boolean; metrics: boolean };
    options?: {
        maxHeapMb?: number;
        warmSpare?: boolean;
        detectLeaks?: 'off' | 'light' | 'heavy';
        foreground?: boolean;
    };
}

export interface InitializeResult {
    protocolVersion: number;
    daemonVersion: string;
    pid: number;
    capabilities: {
        incrementalDiscovery: boolean;
        sandboxRecycle: boolean;
        leakDetection: ('light' | 'heavy')[];
    };
    classpathFingerprint: string;
    manifest: { path: string; previewCount: number };
}

// Client → daemon notifications (PROTOCOL.md § 4)

export interface SetVisibleParams { ids: string[] }
export interface SetFocusParams { ids: string[] }

export type FileKind = 'source' | 'resource' | 'classpath';
export type FileChangeType = 'modified' | 'created' | 'deleted';

export interface FileChangedParams {
    path: string;
    kind: FileKind;
    changeType: FileChangeType;
}

// Client → daemon requests (PROTOCOL.md § 5)

export type RenderTier = 'fast' | 'full';

export interface RenderNowParams {
    previews: string[];
    tier: RenderTier;
    reason?: string;
}

export interface RenderNowResult {
    queued: string[];
    rejected: { id: string; reason: string }[];
}

// Daemon → client notifications (PROTOCOL.md § 6)

/**
 * Per-preview shape inside `discoveryUpdated.added` / `.changed`. Mirrors the
 * `PreviewInfoDto` JSON serialised by `daemon/core/.../PreviewIndex.kt` —
 * which uses `@SerialName("functionName")` so the wire field is `functionName`,
 * not `methodName`.
 */
export interface DiscoveryPreviewInfo {
    id: string;
    className: string;
    functionName: string;
    sourceFile?: string | null;
    displayName?: string | null;
    group?: string | null;
}

export interface DiscoveryUpdatedParams {
    added: DiscoveryPreviewInfo[];
    removed: string[];
    changed: DiscoveryPreviewInfo[];
    totalPreviews: number;
}

export interface RenderStartedParams { id: string; queuedMs: number }

export interface RenderMetrics {
    heapAfterGcMb: number;
    nativeHeapMb: number;
    sandboxAgeRenders: number;
    sandboxAgeMs: number;
}

export interface RenderFinishedParams {
    id: string;
    pngPath: string;
    tookMs: number;
    metrics?: RenderMetrics;
}

export interface RenderError {
    kind: 'compile' | 'runtime' | 'capture' | 'timeout' | 'internal';
    message: string;
    stackTrace?: string;
}

export interface RenderFailedParams { id: string; error: RenderError }

export interface ClasspathDirtyParams {
    reason: 'fingerprintMismatch' | 'fileChanged' | 'manifestMissing';
    detail: string;
    changedPaths?: string[];
}

export interface SandboxRecycleParams {
    reason: 'heapCeiling' | 'heapDrift' | 'renderTimeDrift' | 'histogramDrift'
          | 'renderCount' | 'leakSuspected' | 'manual';
    ageMs: number;
    renderCount: number;
    warmSpareReady: boolean;
}

export interface DaemonWarmingParams { etaMs: number }

export interface LogParams {
    level: 'debug' | 'info' | 'warn' | 'error';
    message: string;
    category?: string;
    context?: Record<string, unknown>;
}

// History (phase H1+H2+H3) — see PROTOCOL.md § 5 / § 6 and HISTORY.md
// "Layer 2 — JSON-RPC API" + "Sidecar metadata schema" for the canonical
// shapes. The Kotlin counterpart lives in `Messages.kt` and carries the
// payload fields as `JsonElement` to avoid pulling history-package types
// onto the dispatch surface; we mirror that with `unknown` here so the
// types stay schema-agnostic against future additive fields.

export type HistorySourceKind = 'fs' | 'git' | 'http';

export interface HistoryListParams {
    previewId?: string;
    since?: string;                   // ISO 8601 lower bound
    until?: string;                   // ISO 8601 upper bound
    limit?: number;                   // daemon defaults to 50, max 500
    cursor?: string;                  // opaque token from a previous response
    branch?: string;
    branchPattern?: string;           // regex
    commit?: string;                  // long or short SHA
    worktreePath?: string;
    agentId?: string;
    sourceKind?: HistorySourceKind;
    sourceId?: string;
}

export interface HistoryListResult {
    /** Sidecar JSON shape per HISTORY.md § "Sidecar metadata schema". */
    entries: unknown[];
    /** Present iff more entries match — feed back as `cursor` to paginate. */
    nextCursor?: string;
    totalCount: number;
}

export interface HistoryReadParams {
    id: string;
    /** When true, daemon returns base64 PNG bytes inline (`pngBytes`). When
     *  false, the client reads `pngPath` from disk — preferred for local
     *  same-host clients (VS Code) to avoid the wire round-trip. */
    inline?: boolean;
}

export interface HistoryReadResult {
    /** Sidecar JSON. */
    entry: unknown;
    /** PreviewMetadataSnapshot — frozen at render time. May be null when
     *  the originating manifest is gone. */
    previewMetadata?: unknown;
    /** Absolute path; the client reads bytes from here when `inline=false`. */
    pngPath: string;
    /** Base64 PNG; populated only when the request set `inline: true`. */
    pngBytes?: string;
}

export type HistoryDiffMode = 'metadata' | 'pixel';

export interface HistoryDiffParams {
    from: string;
    to: string;
    mode?: HistoryDiffMode;           // default 'metadata'
}

/**
 * `mode = 'metadata'` (default) is cheap: daemon hashes both PNGs and
 * returns the sidecars. Pixel-mode fields are reserved for phase H5; in
 * METADATA mode they are always undefined/null. A caller asking for
 * `mode = 'pixel'` against a current daemon receives error
 * `HistoryPixelNotImplemented` (-32012).
 */
export interface HistoryDiffResult {
    pngHashChanged: boolean;
    fromMetadata: unknown;
    toMetadata: unknown;
    diffPx?: number;
    ssim?: number;
    diffPngPath?: string;
}

export interface HistoryAddedParams {
    /** Sidecar JSON of the newly-written entry. */
    entry: unknown;
}

// Interactive mode (reserved — see docs/daemon/INTERACTIVE.md § 7).
//
// The wire shapes below are documented but **not** spoken by today's daemon.
// They live here so a future protocol implementation lands without a schema
// reshuffle, and so the TypeScript client can grow input-emission code in
// lockstep with the daemon work. Adding these methods is additive per
// PROTOCOL.md § 7 — no `protocolVersion` bump required.

export interface InteractiveStartParams { previewId: string }
export interface InteractiveStartResult {
    /** Opaque correlation id; the client passes it back on every input
     *  notification so the daemon can route inputs to the right warm
     *  sandbox even if the user toggles between previews. */
    frameStreamId: string;
}

export interface InteractiveStopParams { frameStreamId: string }

export type InteractiveInputKind =
    | 'click'
    | 'pointerDown'
    | 'pointerUp'
    | 'keyDown'
    | 'keyUp';

export interface InteractiveInputParams {
    frameStreamId: string;
    kind: InteractiveInputKind;
    /** Image-natural pixel coordinates. Daemon resolves to dp using the
     *  last-render density. Omit for keyboard events. */
    pixelX?: number;
    pixelY?: number;
    /** For `keyDown` / `keyUp`. */
    keyCode?: string;
}

/**
 * Wire format of `<module>/build/compose-previews/daemon-launch.json`,
 * authored by `DaemonBootstrapTask`. See
 * `gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/daemon/DaemonClasspathDescriptor.kt`.
 */
export interface DaemonLaunchDescriptor {
    schemaVersion: number;
    modulePath: string;
    variant: string;
    enabled: boolean;
    mainClass: string;
    javaLauncher: string | null;
    classpath: string[];
    jvmArgs: string[];
    systemProperties: Record<string, string>;
    workingDirectory: string;
    manifestPath: string;
}

export const DAEMON_DESCRIPTOR_SCHEMA_VERSION = 1;
