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
// Data products (phase D1) — see DATA-PRODUCTS.md § "Error codes".
export const ERROR_DATA_PRODUCT_UNKNOWN = -32020;
export const ERROR_DATA_PRODUCT_NOT_AVAILABLE = -32021;
export const ERROR_DATA_PRODUCT_FETCH_FAILED = -32022;
export const ERROR_DATA_PRODUCT_BUDGET_EXCEEDED = -32023;

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
        /**
         * Data-product kinds the client wants attached to *every* render of
         * *every* preview, ambient. Reserved for genuinely cheap, always-on
         * kinds (today: `a11y/atf` only). Most clients leave this empty and
         * use `data/subscribe` for sticky-while-visible attachment instead.
         * See `docs/daemon/DATA-PRODUCTS.md` § "Wire surface".
         */
        attachDataProducts?: string[];
        /**
         * Per-render `host.submit(...)` timeout (ms) the daemon enforces for this client's
         * session. Defaults to 5 minutes (`5 * 60_000`) — generous enough for Robolectric
         * cold-sandbox bootstrap plus any single render. Bump for CI-style runs that render
         * many heavy previews and want headroom; lower for interactive sessions that prefer
         * a fast failure over a long hang. Values ≤ 0 fall back to the default.
         */
        maxRenderMs?: number;
        /**
         * Optional history pruning defaults for this daemon session. Present values override the
         * matching daemon JVM sysprop/default at initialize time; absent/null values preserve the
         * daemon-configured value. Values ≤ 0 disable that pruning knob.
         */
        historyPrune?: {
            maxEntriesPerPreview?: number;
            maxAgeDays?: number;
            maxTotalSizeBytes?: number;
            autoIntervalMs?: number;
        };
    };
}

/**
 * One advertised data-product kind on the daemon side. Mirrors
 * `DataProductCapability` in `daemon/core/.../Messages.kt`; see
 * `docs/daemon/DATA-PRODUCTS.md` § "The primitive" for semantics.
 *
 * `transport`: `'inline'` means the payload travels as JSON in the response;
 * `'path'` means the daemon writes a sibling file and returns its absolute
 * path; `'both'` lets the caller pick via `data/fetch.inline`.
 *
 * `attachable` / `fetchable` describe which surfaces support the kind —
 * a kind that's only producible by re-rendering may be `fetchable: true,
 * attachable: false`. `requiresRerender: true` warns the client that a
 * `data/fetch` against a never-rendered preview will pay a render cost.
 */
export interface DataProductCapability {
    kind: string;
    schemaVersion: number;
    transport: 'inline' | 'path' | 'both';
    attachable: boolean;
    fetchable: boolean;
    requiresRerender: boolean;
}

export interface InitializeResult {
    protocolVersion: number;
    daemonVersion: string;
    pid: number;
    capabilities: {
        incrementalDiscovery: boolean;
        sandboxRecycle: boolean;
        leakDetection: ('light' | 'heavy')[];
        /**
         * Phase D1 — kinds the daemon can produce. Empty list means the
         * daemon doesn't speak the data-product surface (pre-D1 daemons).
         * Additive: clients ignore unknown kinds, daemons reject unknown
         * kinds in subscribe/fetch with `DataProductUnknown` (-32020).
         */
        dataProducts: DataProductCapability[];
        /**
         * INTERACTIVE.md § 9 — `true` when the daemon's host can dispatch
         * `interactive/input` events into a held composition (v2 — clicks
         * mutate `remember { mutableStateOf(...) }` state). `false` means
         * `interactive/start` still succeeds but inputs trigger a stateless
         * re-render (v1 fallback). Defaulted for pre-#425 daemons that
         * predate the capability — clients treat absent and `false`
         * identically. Today: `true` for desktop hosts, `false` for the
         * Robolectric / Android backends.
         */
        interactive?: boolean;
        /**
         * The `@Preview(device = ...)` ids the daemon's catalog recognises, paired with
         * resolved geometry. Lets clients build a "render this preview at..." picker without
         * re-bundling the catalog. Empty list = pre-feature daemon (treat absent and `[]`
         * identically). The `spec:width=…,height=…,dpi=…` grammar is not enumerable —
         * clients pass it as a free-form `device` override and the daemon parses it at
         * resolve-time.
         */
        knownDevices?: KnownDevice[];
        /**
         * The `PreviewOverrides` field names this daemon's host actually applies (see
         * PROTOCOL.md § 5 `renderNow.overrides`). Names match the JSON spelling on the
         * wire: `widthPx`, `heightPx`, `density`, `localeTag`, `fontScale`, `uiMode`,
         * `orientation`, `device`. Lets clients grey out unsupported sliders. Empty list
         * = pre-feature daemon (clients treat absent and `[]` identically and assume any
         * field they pass might be ignored).
         *
         * Today: Robolectric advertises all eight; Desktop omits `localeTag` (no
         * `LocalLocale` CompositionLocal) and `orientation` (no rotation concept on
         * `ImageComposeScene`).
         */
        supportedOverrides?: string[];
        /**
         * Identifier for the renderer backend behind this daemon. Lets clients render
         * backend-specific UI hints (e.g. "Wear preview not supported on desktop") without
         * per-call probing. Today: `'desktop'` for the Compose Desktop / Skiko backend,
         * `'android'` for the Robolectric backend. Absent / `null` on hosts that haven't
         * classified themselves (e.g. test fakes); clients should treat both as "unknown".
         */
        backend?: 'desktop' | 'android' | null;
        /**
         * Fixed Android SDK level this daemon renders against. Present on Robolectric /
         * Android backends, absent or null on Desktop and other non-Android backends.
         */
        androidSdk?: number | null;
    };
    classpathFingerprint: string;
    manifest: { path: string; previewCount: number };
}

/**
 * One entry in `ServerCapabilities.knownDevices`. The `id` is the string a caller passes via
 * `renderNow.overrides.device` (or `@Preview(device = ...)` at discovery time); the geometry
 * fields let a UI label the device ("Pixel 5 — 393×851 dp @ 2.75x") without re-resolving.
 * `isRound` marks circular Wear-style displays.
 */
export interface KnownDevice {
    id: string;
    widthDp: number;
    heightDp: number;
    density: number;
    isRound?: boolean;
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

/**
 * Per-render display-property overrides — see PROTOCOL.md § 5 (`renderNow.overrides`).
 * Backends that don't model a particular field (e.g. desktop has no `uiMode` resource qualifier)
 * ignore it.
 */
export interface PreviewOverrides {
    widthPx?: number;
    heightPx?: number;
    density?: number;
    localeTag?: string;
    fontScale?: number;
    uiMode?: 'light' | 'dark';
    orientation?: 'portrait' | 'landscape';
    /**
     * `@Preview(device = ...)` string — `id:pixel_5`, `id:wearos_small_round`, `id:tv_1080p`,
     * or a full `spec:width=400dp,height=800dp,dpi=320` grammar. Resolved by the daemon's
     * built-in catalog into widthPx/heightPx/density; explicit width/height/density overrides
     * on this same object take precedence.
     */
    device?: string;
    /**
     * Paused-clock advance (ms) before capture — Android-only today. Default ≈ 32ms (≈ 2
     * Choreographer frames); bump for animation-heavy previews that need longer to settle.
     * Values ≤ 0 fall back to the default. Desktop ignores it.
     */
    captureAdvanceMs?: number;
    /**
     * Per-render LocalInspectionMode override. Undefined preserves normal preview behaviour.
     */
    inspectionMode?: boolean;
}

export interface RenderNowParams {
    previews: string[];
    tier: RenderTier;
    reason?: string;
    overrides?: PreviewOverrides;
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

/**
 * One additional non-JSON output a producer wrote alongside its primary
 * payload — typically a derived image such as the Paparazzi-style a11y
 * overlay PNG. Pointer-only on the wire; the client reads the file
 * directly. See docs/daemon/DATA-PRODUCTS.md § "Image processors and
 * extras".
 */
export interface DataProductExtra {
    name: string;
    path: string;
    mediaType?: string;
    sizeBytes?: number;
}

/**
 * One data-product attachment riding on a `renderFinished` notification.
 * `payload` is per-kind JSON when `transport='inline'`; `path` is an
 * absolute path to a sibling file when `transport='path'`. Exactly one of
 * the two is set per entry. `extras` is the producer's derived non-JSON
 * outputs (PNGs etc.); absent / empty are interchangeable.
 */
export interface DataProductAttachment {
    kind: string;
    schemaVersion: number;
    payload?: unknown;
    path?: string;
    extras?: DataProductExtra[];
}

export interface RenderFinishedParams {
    id: string;
    pngPath: string;
    tookMs: number;
    metrics?: RenderMetrics;
    /**
     * Phase D1 — populated only with the `(id, kind)` pairs the client has
     * subscribed to via `data/subscribe`, plus everything in
     * `initialize.options.attachDataProducts`. Absent / `[]` mean "no
     * attachments"; clients MUST treat the two interchangeably.
     */
    dataProducts?: DataProductAttachment[];
    /**
     * Interactive-mode dedup signal — see docs/daemon/INTERACTIVE.md § 5.
     * `true` means the daemon already determined the rendered bytes are
     * byte-identical to the last frame for this preview id, so the client
     * can short-circuit the read-PNG → base64 → postMessage hop and leave
     * the on-screen card untouched. `undefined` (the wire-side default
     * when the daemon omits the field) means "client must paint".
     */
    unchanged?: boolean;
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

// Data products (phase D1) — see docs/daemon/DATA-PRODUCTS.md.
//
// Three methods:
// - `data/fetch`     — pull-on-demand. Returns a payload for one
//                      `(previewId, kind)` pair against the latest render;
//                      may trigger a re-render if the kind needs it.
// - `data/subscribe` — sticky attach. While subscribed, every
//                      `renderFinished` for `previewId` carries the kind
//                      in its `dataProducts` field. Drops automatically
//                      when the preview leaves `setVisible`.
// - `data/unsubscribe` — opposite. Idempotent.

export interface DataFetchParams {
    previewId: string;
    kind: string;
    /** Per-kind options. Documented alongside each kind. */
    params?: Record<string, unknown>;
    /**
     * `true` → daemon inlines the payload (or `bytes` for blob kinds).
     * `false` (default) → daemon writes JSON to disk and returns `path`,
     * matching the cheaper local-client path used by `history/read`.
     */
    inline?: boolean;
}

export interface DataFetchResult {
    kind: string;
    schemaVersion: number;
    payload?: unknown;
    path?: string;
    /** Base64 — set only when caller passed `inline: true` and the kind's
     *  transport is blob-shaped. Reserved for non-local clients. */
    bytes?: string;
    /** Derived non-JSON outputs the producer wrote alongside (e.g. a11y
     *  overlay PNG). Absent / empty are interchangeable on the wire. */
    extras?: DataProductExtra[];
}

export interface DataSubscribeParams {
    previewId: string;
    kind: string;
}

/** Acknowledgement-only result; the response shape is intentionally
 *  trivial so adding fields stays additive. */
export interface DataSubscribeResult {
    ok: true;
}

// Interactive mode (reserved — see docs/daemon/INTERACTIVE.md § 7).
//
// The wire shapes below are documented but **not** spoken by today's daemon.
// They live here so a future protocol implementation lands without a schema
// reshuffle, and so the TypeScript client can grow input-emission code in
// lockstep with the daemon work. Adding these methods is additive per
// PROTOCOL.md § 7 — no `protocolVersion` bump required.

export interface InteractiveStartParams { previewId: string; inspectionMode?: boolean }
export interface InteractiveStartResult {
    /** Opaque correlation id; the client passes it back on every input
     *  notification so the daemon can route inputs to the right warm
     *  sandbox even if the user toggles between previews. */
    frameStreamId: string;
    /** True when this stream is backed by a held composition rather than stateless fallback. */
    heldSession: boolean;
    /** Human-readable reason for stateless fallback, when the daemon reports one. */
    fallbackReason?: string;
}

export interface InteractiveStopParams { frameStreamId: string }

export type InteractiveInputKind =
    | 'click'
    | 'pointerDown'
    | 'pointerMove'
    | 'pointerUp'
    | 'rotaryScroll'
    | 'keyDown'
    | 'keyUp';

export interface InteractiveInputParams {
    frameStreamId: string;
    kind: InteractiveInputKind;
    /** Image-natural pixel coordinates. Daemon resolves to dp using the
     *  last-render density. Omit for keyboard events. */
    pixelX?: number;
    pixelY?: number;
    /** Browser wheel delta for `rotaryScroll`; positive means wheel-down. */
    scrollDeltaY?: number;
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
