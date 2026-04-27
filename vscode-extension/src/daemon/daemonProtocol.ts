// ---------------------------------------------------------------------------
// Preview daemon — IPC protocol message types (TypeScript mirror).
//
// Source of truth: docs/daemon/PROTOCOL.md (v1, locked). Field names match
// the JSON shapes in that document exactly. The Kotlin counterpart lives in
// renderer-android-daemon/src/main/kotlin/ee/schimke/composeai/daemon/protocol/
// Messages.kt — keep both in lockstep on every protocol change (PROTOCOL.md
// § 7).
//
// Both suites round-trip the JSON fixtures under docs/daemon/protocol-
// fixtures/ as a shared corpus (PROTOCOL.md § 9). The Stream C side of that
// is exercised by `src/test/daemon/daemonProtocol.test.ts`.
//
// We deliberately use string literal unions instead of TypeScript `enum`
// declarations: enums emit runtime objects, which we don't need (the wire
// values are already strings per @SerialName on the Kotlin side), and the
// literal-union form composes more cleanly with structural typing when the
// daemon adds new variants in a backwards-compatible way (PROTOCOL.md § 7
// — "ignore unknown fields and unknown notifications").
// ---------------------------------------------------------------------------

// ===========================================================================
// 1. Protocol-level constants
// ===========================================================================

/** PROTOCOL.md § 7 — must equal Messages.kt::PROTOCOL_VERSION on the daemon. */
export const PROTOCOL_VERSION = 1 as const;

/**
 * JSON-RPC error codes per PROTOCOL.md § 2. Kept as a plain `const` map
 * (not a TypeScript `enum`) so consumers can `import { JSON_RPC_ERROR_CODES }`
 * and read both ways: by name (`JSON_RPC_ERROR_CODES.ClasspathDirty`) and by
 * value (using the inverse-lookup helper below).
 */
export const JSON_RPC_ERROR_CODES = {
    // Standard JSON-RPC 2.0
    ParseError: -32700,
    InvalidRequest: -32600,
    MethodNotFound: -32601,
    InvalidParams: -32602,
    InternalError: -32603,
    // Daemon-specific extensions (-32000..-32099)
    NotInitialized: -32001,
    ClasspathDirty: -32002,
    SandboxRecycling: -32003,
    UnknownPreview: -32004,
    RenderFailed: -32005,
} as const;

export type JsonRpcErrorCodeName = keyof typeof JSON_RPC_ERROR_CODES;
export type JsonRpcErrorCodeValue = typeof JSON_RPC_ERROR_CODES[JsonRpcErrorCodeName];

// ===========================================================================
// 2. JSON-RPC envelope (PROTOCOL.md § 2)
//
// Three envelope kinds. Notifications have no `id`; responses carry result XOR
// error. `params`, `result`, and `error.data` are typed loosely (`unknown`)
// because the dispatch layer narrows them to the message-specific shapes
// below — exactly the pattern Messages.kt uses with JsonElement.
// ===========================================================================

export interface JsonRpcRequest<P = unknown> {
    jsonrpc: '2.0';
    id: number;
    method: string;
    params?: P;
}

export interface JsonRpcResponse<R = unknown> {
    jsonrpc: '2.0';
    /**
     * `null` is permitted by JSON-RPC 2.0 § 5 when an error is reported for a
     * request whose own id couldn't be parsed (e.g. ParseError). All other
     * responses MUST echo the request's `id` (always a positive integer for
     * this daemon — see PROTOCOL.md § 2).
     */
    id: number | null;
    result?: R;
    error?: JsonRpcError;
}

export interface JsonRpcNotification<P = unknown> {
    jsonrpc: '2.0';
    method: string;
    params?: P;
}

export interface JsonRpcError {
    /** One of {@link JSON_RPC_ERROR_CODES} values, or another integer. */
    code: number;
    message: string;
    /**
     * Daemon-specific errors include `data.kind: string` for machine-routable
     * subcategories (PROTOCOL.md § 2). Other shapes are tolerated.
     */
    data?: { kind?: string;[key: string]: unknown };
}

// ===========================================================================
// 3. initialize (PROTOCOL.md § 3)
// ===========================================================================

export interface ClientCapabilities {
    visibility: boolean;
    metrics: boolean;
}

/** Mirrors Messages.kt::DetectLeaks. */
export type DetectLeaksMode = 'off' | 'light' | 'heavy';

export interface InitializeOptions {
    maxHeapMb?: number;
    warmSpare?: boolean;
    detectLeaks?: DetectLeaksMode;
    foreground?: boolean;
}

export interface InitializeParams {
    protocolVersion: number;
    clientVersion: string;
    workspaceRoot: string;
    moduleId: string;
    moduleProjectDir: string;
    capabilities: ClientCapabilities;
    options?: InitializeOptions;
}

/** Subset of {@link DetectLeaksMode}. PROTOCOL.md § 3 — server capability. */
export type LeakDetectionMode = 'light' | 'heavy';

export interface ServerCapabilities {
    incrementalDiscovery: boolean;
    sandboxRecycle: boolean;
    leakDetection: LeakDetectionMode[];
}

export interface Manifest {
    /** Absolute path to the daemon's working previews.json. */
    path: string;
    previewCount: number;
}

export interface InitializeResult {
    protocolVersion: number;
    daemonVersion: string;
    pid: number;
    capabilities: ServerCapabilities;
    /** SHA-256 hex of the resolved test classpath. */
    classpathFingerprint: string;
    manifest: Manifest;
}

// ===========================================================================
// 4. Client → daemon notifications (PROTOCOL.md § 4)
// ===========================================================================

export interface SetVisibleParams {
    ids: string[];
}

export interface SetFocusParams {
    ids: string[];
}

export type FileChangeKind = 'source' | 'resource' | 'classpath';
export type FileChangeType = 'modified' | 'created' | 'deleted';

export interface FileChangedParams {
    /** Absolute path. */
    path: string;
    kind: FileChangeKind;
    changeType: FileChangeType;
}

// ===========================================================================
// 5. Client → daemon requests (PROTOCOL.md § 5)
// ===========================================================================

export type RenderTier = 'fast' | 'full';

export interface RenderNowParams {
    /** Preview IDs; empty = render all visible-and-stale. */
    previews: string[];
    tier: RenderTier;
    /** Free-form, surfaces in logs (e.g. "user clicked refresh"). */
    reason?: string;
}

export interface RejectedRender {
    id: string;
    reason: string;
}

export interface RenderNowResult {
    queued: string[];
    rejected: RejectedRender[];
}

// ===========================================================================
// 6. Daemon → client notifications (PROTOCOL.md § 6)
// ===========================================================================

/**
 * `PreviewInfo` mirrors the JSON shape emitted by `DiscoverPreviewsTask` plus
 * the `sourceFile` field added in P0.2. The canonical shape lives in the
 * Gradle plugin (PreviewData.kt) and the existing TypeScript mirror is in
 * `src/types.ts`. We carry it here as `unknown` for the same reason
 * Messages.kt uses `JsonElement`: the protocol envelope is decoupled from
 * the Gradle-plugin-owned schema, so a non-breaking PreviewInfo addition
 * doesn't ripple through PROTOCOL.md.
 */
export type DiscoveredPreview = unknown;

export interface DiscoveryUpdatedParams {
    added: DiscoveredPreview[];
    /** Removed preview IDs. */
    removed: string[];
    /** Same shape as added; ID present, metadata differs. */
    changed: DiscoveredPreview[];
    totalPreviews: number;
}

export interface RenderStartedParams {
    id: string;
    /** Wall-clock between accept and start. */
    queuedMs: number;
}

export interface RenderMetrics {
    heapAfterGcMb: number;
    nativeHeapMb: number;
    sandboxAgeRenders: number;
    sandboxAgeMs: number;
}

export interface RenderFinishedParams {
    id: string;
    /** Absolute path; existing render strategy decides directory. */
    pngPath: string;
    /** Wall-clock for the render body. */
    tookMs: number;
    /** Present iff the client set `capabilities.metrics: true` in initialize. */
    metrics?: RenderMetrics;
}

export type RenderErrorKind =
    | 'compile'
    | 'runtime'
    | 'capture'
    | 'timeout'
    | 'internal';

export interface RenderError {
    kind: RenderErrorKind;
    message: string;
    /** Present for kind='runtime' | 'internal'. */
    stackTrace?: string;
}

export interface RenderFailedParams {
    id: string;
    error: RenderError;
}

export type ClasspathDirtyReason =
    | 'fingerprintMismatch'
    | 'fileChanged'
    | 'manifestMissing';

export interface ClasspathDirtyParams {
    reason: ClasspathDirtyReason;
    /** Human-readable. */
    detail: string;
    changedPaths?: string[];
}

export type SandboxRecycleReason =
    | 'heapCeiling'
    | 'heapDrift'
    | 'renderTimeDrift'
    | 'histogramDrift'
    | 'renderCount'
    | 'leakSuspected'
    | 'manual';

export interface SandboxRecycleParams {
    reason: SandboxRecycleReason;
    ageMs: number;
    renderCount: number;
    /** false → next render blocks; expect `daemonWarming`. */
    warmSpareReady: boolean;
}

export interface DaemonWarmingParams {
    /** Best-effort estimate; client shows spinner. */
    etaMs: number;
}

/**
 * `daemonReady` carries no fields; the wire shape is `{}`. Modelled as an
 * empty object type so `JSON.parse(...) as DaemonReadyParams` compiles, with
 * an index signature that allows for additive PROTOCOL.md § 7 fields without
 * a breaking change.
 */
export type DaemonReadyParams = Record<string, never>;

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

export interface LogParams {
    level: LogLevel;
    message: string;
    category?: string;
    context?: Record<string, unknown>;
}

// ===========================================================================
// 7. Method-name constants
//
// Kept as a `const` map so call sites (the daemonClient in C1.3) read like
// `sendNotification(METHODS.SetVisible, ...)` rather than littering the file
// with stringly-typed method names that drift from PROTOCOL.md.
// ===========================================================================

export const METHODS = {
    // Lifecycle (PROTOCOL.md § 3)
    Initialize: 'initialize',
    Initialized: 'initialized',
    Shutdown: 'shutdown',
    Exit: 'exit',
    // Client → daemon notifications (§ 4)
    SetVisible: 'setVisible',
    SetFocus: 'setFocus',
    FileChanged: 'fileChanged',
    // Client → daemon requests (§ 5)
    RenderNow: 'renderNow',
    // Daemon → client notifications (§ 6)
    DiscoveryUpdated: 'discoveryUpdated',
    RenderStarted: 'renderStarted',
    RenderFinished: 'renderFinished',
    RenderFailed: 'renderFailed',
    ClasspathDirty: 'classpathDirty',
    SandboxRecycle: 'sandboxRecycle',
    DaemonWarming: 'daemonWarming',
    DaemonReady: 'daemonReady',
    Log: 'log',
} as const;

export type MethodName = typeof METHODS[keyof typeof METHODS];
