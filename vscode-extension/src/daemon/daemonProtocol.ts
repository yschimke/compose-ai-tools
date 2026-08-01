/**
 * TypeScript mirrors of the locked v2 protocol from
 * `daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/protocol/Messages.kt`.
 * Kept hand-rolled (no generator) so the round-trip golden fixtures under
 * `docs/daemon/protocol-fixtures/` are the only authority both sides depend on.
 *
 * Spec: docs/daemon/PROTOCOL.md (v2). v2 introduced `extensions/{list,enable,disable}`
 * and emptied the default `initialize.capabilities.{dataProducts,dataExtensions,
 * previewExtensions}` lists — clients call `extensions/enable` after the handshake to
 * opt in to the contributions they actually use.
 */

export const PROTOCOL_VERSION = 2;

// JSON-RPC envelopes (PROTOCOL.md § 2)

export interface JsonRpcRequest<P = unknown> {
    jsonrpc: "2.0";
    id: number;
    method: string;
    params?: P;
}

export interface JsonRpcResponse<R = unknown> {
    jsonrpc: "2.0";
    id: number;
    result?: R;
    error?: JsonRpcError;
}

export interface JsonRpcNotification<P = unknown> {
    jsonrpc: "2.0";
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
        detectLeaks?: "off" | "light" | "heavy";
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
    transport: "inline" | "path" | "both";
    attachable: boolean;
    fetchable: boolean;
    requiresRerender: boolean;
    displayName?: string;
    facets?: DataProductFacet[];
    mediaTypes?: string[];
    sampling?: SamplingPolicy;
}

export type DataProductFacet =
    | "structured"
    | "artifact"
    | "image"
    | "animation"
    | "overlay"
    | "check"
    | "diagnostic"
    | "profile"
    | "interactive";

export type SamplingPolicy =
    "Start" | "End" | "EachFrame" | "OnDemand" | "Aggregate" | "Failure";

export interface PreviewExtensionDescriptor {
    id: string;
    displayName?: string;
    usageModes?: PreviewExtensionUsageMode[];
    componentExtensionIds?: string[];
    cliCommands?: PreviewExtensionCliCommand[];
    steps?: PreviewPipelineStep[];
}

export interface PreviewExtensionCliCommand {
    id: string;
    displayName?: string;
    summary?: string;
    command: string[];
    agentRecommended?: boolean;
    requiresDaemon?: boolean;
    usageModes?: PreviewExtensionUsageMode[];
    productKinds?: string[];
}

export interface DataExtensionDescriptor {
    id: string;
    displayName?: string;
    recordingScriptEvents?: RecordingScriptEventDescriptor[];
    /**
     * Issue #1203 — when `true`, every dispatch path under this extension requires a held
     * interactive composition. The panel auto-enters live mode for the preview when toggling
     * the extension on; absent / `false` on pre-#1203 daemons.
     */
    requiresInteractive?: boolean;
}

export interface RecordingScriptEventDescriptor {
    id: string;
    displayName?: string;
    summary?: string;
    supported?: boolean;
}

export interface PreviewPipelineStep {
    id: string;
    displayName?: string;
    productKinds?: string[];
    annotationFqns?: string[];
    usageModes?: PreviewExtensionUsageMode[];
    traits?: PipelineStepTrait[];
    requires?: PipelineCapability[];
    provides?: PipelineCapability[];
    conflictsWith?: PipelineStepTrait[];
    sampling?: SamplingPolicy | null;
    extraction?: ExtractionSpec | null;
}

export type PreviewExtensionUsageMode =
    "ExplicitEffect" | "SuggestedExtraPreview";

export type PipelineStepTrait =
    | "ScenarioDriver"
    | "InteractiveDriver"
    | "AnnotationInspector"
    | "ExtraPreviewSuggester"
    | "FrameProcessor"
    | "FinalArtifactProcessor"
    | "DataExtractor"
    | "Check"
    | "Encoder"
    | "Profiler";

export type PipelineCapability =
    | "Frames"
    | "SingleFrame"
    | "MultipleFrames"
    | "PreviewFunctionAnnotations"
    | "SuggestedPreviews"
    | "DeviceGeometry"
    | "DeviceClip"
    | "ScrollState"
    | "SemanticsSnapshot"
    | "AccessibilityNodes"
    | "AccessibilityFindings"
    | "OverlayAnnotations"
    | "ImageArtifact"
    | "AnnotatedImageArtifact"
    | "AnimatedArtifact"
    | "InteractiveSession"
    | "TraceEvents";

export interface ExtractionSpec {
    kind: string;
    sampling: SamplingPolicy;
    requiresImage?: boolean;
    requiresSemantics?: boolean;
    aggregate?: boolean;
}

export interface InitializeResult {
    protocolVersion: number;
    daemonVersion: string;
    pid: number;
    capabilities: {
        incrementalDiscovery: boolean;
        sandboxRecycle: boolean;
        leakDetection: ("light" | "heavy")[];
        /**
         * Phase D1 — kinds the daemon can produce. Empty list means the
         * daemon doesn't speak the data-product surface (pre-D1 daemons).
         * Additive: clients ignore unknown kinds, daemons reject unknown
         * kinds in subscribe/fetch with `DataProductUnknown` (-32020).
         */
        dataProducts: DataProductCapability[];
        dataExtensions?: DataExtensionDescriptor[];
        previewExtensions?: PreviewExtensionDescriptor[];
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
        backend?: "desktop" | "android" | null;
        /**
         * Fixed Android SDK level this daemon renders against. Present on Robolectric /
         * Android backends, absent or null on Desktop and other non-Android backends.
         */
        androidSdk?: number | null;
        /**
         * Issue #1203 — interactive input kinds (beyond pointer) this host can dispatch into a
         * held composition. Wire-spelled to match {@link InteractiveInputKind} on the Kotlin
         * side (`'keyDown'`, `'keyUp'`, `'rotaryScroll'`). Empty / absent on pre-#1203 daemons;
         * panel treats both as "only pointer input is dispatchable" and skips the keyboard /
         * rotary controls.
         */
        interactiveControlKinds?: string[];
    };
    classpathFingerprint: string;
    manifest: { path: string; previewCount: number };
}

// extensions/{list,enable,disable} (PROTOCOL.md § 3a)
//
// Daemons boot with every extension registered as inactive. Capability lists in
// `InitializeResult.capabilities.{dataProducts,dataExtensions,previewExtensions}` start
// empty; clients opt in via `extensions/enable`. Dependencies declared by an extension
// are pulled in transitively but stay invisible to direct client RPC.

export interface ExtensionInfo {
    id: string;
    displayName: string;
    // Daemon serializes with `encodeDefaults = false`; empty lists/false-defaults drop off the
    // wire. Treat list fields as optional + default at access sites.
    dependencies?: string[];
    publiclyEnabled?: boolean;
    /** True iff publicly enabled OR pulled in as a dependency of another public extension. */
    active?: boolean;
    dataProductKinds?: string[];
    dataExtensionIds?: string[];
    previewExtensionIds?: string[];
}

export interface ExtensionsListResult {
    extensions: ExtensionInfo[];
}

export interface ExtensionsEnableParams {
    ids: string[];
}

export interface ExtensionsEnableResult {
    // Daemon serializes with `encodeDefaults = false`; empty lists drop off the wire entirely.
    // Treat every field as optional and default to `[]` at access sites.
    newlyEnabled?: string[];
    pulledIn?: string[];
    alreadyEnabled?: string[];
    unknown?: string[];
    /**
     * Updated public capability snapshots — same shape as the `initialize.capabilities`
     * fields. Saves a follow-up `extensions/list` round-trip after enabling.
     */
    dataProducts?: DataProductCapability[];
    dataExtensions?: DataExtensionDescriptor[];
    previewExtensions?: PreviewExtensionDescriptor[];
}

export interface ExtensionsDisableParams {
    ids: string[];
}

export interface ExtensionsDisableResult {
    disabled: string[];
    deactivated: string[];
    stillActiveAsDependency: string[];
    notEnabled: string[];
    unknown: string[];
    dataProducts: DataProductCapability[];
    dataExtensions: DataExtensionDescriptor[];
    previewExtensions: PreviewExtensionDescriptor[];
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

export interface SetVisibleParams {
    ids: string[];
}
export interface SetFocusParams {
    ids: string[];
}

export type FileKind = "source" | "resource" | "classpath";
export type FileChangeType = "modified" | "created" | "deleted";

export interface FileChangedParams {
    path: string;
    kind: FileKind;
    changeType: FileChangeType;
}

/**
 * Stage-2 in-process compile.
 *
 * Client → daemon request: "compile these sources via the BTA host inside the daemon JVM and
 * swap the user classloader once the new `.class` files are on disk". The daemon side does
 * the same `host.swapUserClassLoaders()` that a `fileChanged({kind:"source"})` notification
 * would, then returns synchronously. Render dispatch happens via the existing per-preview
 * mechanism; this request handles the compile leg only.
 */
export interface CompileSourcesParams {
    sources: string[];
    /** When the editor knows the dirty set, pass it; otherwise null/undefined and the
     *  daemon recalculates from BTA's IC cache. */
    changes?: SourceChangeSet | null;
}

export interface SourceChangeSet {
    modified: string[];
    removed: string[];
}

export type CompileResultKind = "ok" | "compileError" | "fallback";

export interface CompileSourcesResult {
    result: CompileResultKind;
    /** Populated when `result === "compileError"`. Empty otherwise. */
    errors?: CompileErrorDetail[];
    durationMs: number;
}

export interface CompileErrorDetail {
    file: string;
    line: number;
    column: number;
    message: string;
}

// Client → daemon requests (PROTOCOL.md § 5)

export type RenderTier = "fast" | "full";

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
    uiMode?: "light" | "dark";
    orientation?: "portrait" | "landscape";
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
    /**
     * Per-render cleared-background ("crisp outline") toggle. `true` forces a transparent harness
     * background (overriding `@Preview(showBackground=…)` / `backgroundColor`) and provides
     * `LocalPreviewBackgroundCleared = true` so a composable drawing its own opaque fill (a Material 3
     * `Surface`) can drop it. Undefined/false preserves the discovery-time background. The panel's
     * background chip flips this; both daemon backends honour it.
     */
    clearBackground?: boolean;
    /**
     * Opt-in touch-event visualization for live / recording sessions. `true` installs the
     * `TouchOverlayExtension` `AroundComposable` so the captured / streamed frames carry
     * cyan rings at every pressed pointer plus short-lived expanding pulses on down/up —
     * Android's "Show touches" developer-mode toggle, but agent-pixel-true. The panel's
     * focus-bar touch-overlay button (`#btn-touch-overlay`) flips this. Defaults
     * to `null` (off) so existing pixel-exact tests stay byte-identical.
     */
    touchOverlay?: boolean;
    /**
     * Soft-keyboard (IME) band override. `visible = true` forces the daemon's `data/keyboard`
     * extension's Gboard-shaped band onto the capture regardless of what the app's
     * `LocalSoftwareKeyboardController` / focus state would naturally do; `pressedKey`
     * highlights a specific key cap (lower-case letter or `"space"` / `"enter"` /
     * `"shift"` / `"backspace"` / `"sym"`). Driven by the panel's focus-bar keyboard-band
     * toggle button (`#btn-keyboard-band`). Sending the field with all nullable
     * sub-fields null is a no-op — the connector's always-active planner still observes the
     * app's natural IME signals.
     */
    keyboard?: KeyboardOverride;
    /**
     * Remote Compose override. Carries the daemon-requested platform
     * profile (`RcPlatformProfiles` mirror), seeded named values (typed
     * sum: float / dp / int / string / bool / color), and an optional
     * accept-list filter for captured `HostAction` events. Drives the
     * connector-side `RemoteComposeOverrideExtension` so user code inside
     * a `RemotePreview { ... }` block can read seeded values via
     * `LocalRemoteComposeHost.current.namedFloat(...)` and report fired
     * actions via `reportHostAction(...)`. Sending a fresh `remoteCompose`
     * on a subsequent `renderNow` replaces the named-value map + profile
     * (host-action buffer persists across overrides until session reset).
     * Android-only; desktop ignores.
     */
    remoteCompose?: RemoteComposeOverride;
    /**
     * Android runtime-permissions override. Drives the connector-side
     * `PermissionsOverrideExtension` (see `:data-permissions-connector`). Mirrors
     * `PermissionsOverride` in `Messages.kt` — the around-composable seeds
     * Robolectric's `ShadowApplication` grant state from {@link PermissionsOverride.grants}
     * so consumer code reading the standard Android permission APIs
     * (`ContextCompat.checkSelfPermission`, `Activity.checkSelfPermission`,
     * `Context.checkPermission`, accompanist's `rememberPermissionState`) sees the
     * requested value. The panel's permissions tab body builds this bag from the user's
     * Grant / Deny / Clear button clicks and pushes a fresh `renderNow` so the held
     * preview re-renders with the new grants. Android-only; desktop ignores.
     */
    permissions?: PermissionsOverride;
    /**
     * Launcher-widget container-size override. Drives the connector-side
     * `LauncherWidgetExtension` (see `:data-launcher-widget-connector`) so a held preview can
     * be laid out at a specific whole-cell size on the host's launcher grid — `cells = (4, 2)`
     * at the default `72dp` cell size resolves to a `4*72 + 3*8 = 312.dp` wide by
     * `2*72 + 1*8 = 152.dp` tall container. Value is clamped into `minCells`..`maxCells`
     * (defaulting to `1×1`..`5×5`) before reaching the layout pass. A single `renderNow` snaps
     * to the target. The daemon's `compose/launcher-widget` data product surfaces the
     * resolved (post-clamp) cells + dp footprint on `data/fetch` for the panel's
     * "size: 4×2 (312×152 dp)" badge.
     */
    launcherWidget?: LauncherWidgetOverride;
    /**
     * Lottie timeline override. A non-null `progress` (0..1) re-renders a `kind=LOTTIE` preview at
     * that timeline position — the interactive scrub the panel's Lottie slider drives. The desktop
     * renderer provides it as `LocalLottieProgress`, winning over the composable's authored
     * progress. Desktop-only; other backends ignore it.
     */
    lottie?: LottieOverride;
}

/** Lottie timeline override. See `PreviewOverrides.lottie`. */
export interface LottieOverride {
    /** Timeline position in `0..1` (`0` = first frame, `1` = last). */
    progress?: number | null;
}

/**
 * Wire shape of the `animation/lottie` data product (see
 * `LottieTimelineDataProductRegistry`). Read off the asset with no render, so it's available
 * before the first frame lands; the panel's scrubber uses it to label the slider (frame N / total)
 * and size its range.
 */
export interface LottieTimelineMetadata {
    totalFrames: number;
    frameRate: number;
    durationMillis: number;
    width: number;
    height: number;
}

/** Whole-cell size on a launcher's grid, expressed as integer cell counts. */
export interface LauncherWidgetSize {
    /** Cell count along the width axis. Negative values are rejected; `0` is "below min". */
    width: number;
    /** Cell count along the height axis. Negative values are rejected; `0` is "below min". */
    height: number;
}

/**
 * How an orchestrator walks per-axis steps when animating the launcher-widget container between
 * two whole-cell sizes. Real Android launcher widgets have edge handles, not corner handles —
 * the user grabs one edge and drags it, so width and height never change simultaneously in a
 * single gesture. `"widthFirst"` and `"heightFirst"` mirror that two-gesture path; `"diagonal"`
 * is the relaxed mode that advances both axes in lock-step. The single-shot around-composable
 * connector ignores this field; a future daemon-side stepping loop reads it.
 */
export type LauncherResizeOrder = "diagonal" | "widthFirst" | "heightFirst";

/** Launcher-widget container-size override. See `PreviewOverrides.launcherWidget`. */
export interface LauncherWidgetOverride {
    /** Target whole-cell size on the grid. Clamped into `minCells`..`maxCells`. */
    cells: LauncherWidgetSize;
    /**
     * One cell's edge length in dp. `null` falls back to the connector's default (`72`),
     * matching a Pixel launcher's `5×5` grid on a 411dp screen.
     */
    cellSizeDp?: number;
    /** Gap between adjacent cells in dp. `null` falls back to the connector default (`8`). */
    cellSpacingDp?: number;
    /** Inclusive lower bound on the cell count (per axis). `null` falls back to `1×1`. */
    minCells?: LauncherWidgetSize;
    /** Inclusive upper bound on the cell count (per axis). `null` falls back to `5×5`. */
    maxCells?: LauncherWidgetSize;
    /**
     * Hint for a future daemon-side resize-loop orchestrator on how to walk intermediate stops
     * between two sizes. The single-shot around-composable ignores this field — it always snaps
     * to `cells`.
     */
    resizeOrder?: LauncherResizeOrder;
}

/**
 * Captured launcher-widget state for a preview, returned by
 * `data/fetch?kind=compose/launcher-widget`. Carries the resolved (post-clamp) cells + dp
 * footprint the renderer actually applied — distinct from the request shape
 * (`LauncherWidgetOverride`) which may exceed `maxCells` or use `null`-default knobs.
 */
export interface LauncherWidgetPayload {
    /** Resolved (post-clamp) cell count the renderer applied. */
    cells: LauncherWidgetSize;
    /** Resolved per-cell edge length in dp. */
    cellSizeDp: number;
    /** Resolved inter-cell spacing in dp. */
    cellSpacingDp: number;
    /** Computed container footprint in dp — `cellSizeDp * cells.width + cellSpacingDp * (cells.width - 1)`. */
    widthDp: number;
    /** Computed container footprint in dp — `cellSizeDp * cells.height + cellSpacingDp * (cells.height - 1)`. */
    heightDp: number;
    /** Echo of the resize-order hint from the request, plumbed for a future orchestrator. */
    resizeOrder?: LauncherResizeOrder;
    /**
     * Set of cell sizes the underlying widget declared support for. Populated by render-side
     * metadata sources (today: Glance's `previewSizeMode` reflection; future:
     * `appwidget-provider/...xml` auto-discovery). `null` means "no constraint surfaced"
     * (picker falls back to a default rectangle). Empty array means "no resizing" (Glance
     * `SizeMode.Single`, `resizeMode="none"`).
     */
    supportedCells?: LauncherWidgetSize[] | null;
    /**
     * Which axes the underlying widget allows resizing along, mirroring
     * `AppWidgetProviderInfo.resizeMode`. Picker should grey out drag handles for locked axes.
     * Defaults to `"both"` when no metadata source surfaced a constraint.
     */
    resizeAxes?: LauncherResizeAxes;
}

/**
 * Which axes the launcher-widget container can be resized along. Mirrors Glance's
 * `previewSizeMode` (`SizeMode.Single` → `"none"`; `SizeMode.Responsive` / `SizeMode.Exact` →
 * `"both"`) and `AppWidgetProviderInfo.resizeMode` (`horizontal | vertical | both | none`).
 */
export type LauncherResizeAxes = "none" | "horizontal" | "vertical" | "both";

/** Soft-keyboard (IME) override. See `PreviewOverrides.keyboard`. */
export interface KeyboardOverride {
    visible?: boolean;
    pressedKey?: string;
}

/**
 * Wire spelling for `PermissionsOverride.grants` values. Mirrors `PermissionGrantStateOverride`
 * in `Messages.kt` — the connector accepts a closed `granted | denied` sum so the daemon never
 * has to disambiguate "unspecified" from "denied".
 */
export type PermissionGrantOverride = "granted" | "denied";

/**
 * Android runtime-permissions override for previews. See `PreviewOverrides.permissions`.
 *
 * `grants` is keyed by `Manifest.permission.*` constant string (e.g.
 * `"android.permission.CAMERA"`). Permissions not listed keep whatever Robolectric's
 * manifest baseline started them with — by default everything is denied. An empty `grants`
 * map is the canonical "clear all panel-pinned overrides" payload.
 */
export interface PermissionsOverride {
    grants: Record<string, PermissionGrantOverride>;
}

/**
 * Remote Compose override. See `PreviewOverrides.remoteCompose`. The
 * profile string matches `RemoteComposeProfile` in `types.ts` (re-typed
 * here to keep daemon-protocol module standalone). `namedValues` keys
 * are bound by user code via `LocalRemoteComposeHost`; values use the
 * same typed sum the daemon's `data/fetch` returns so a round-trip
 * (panel edit → renderNow → re-fetch) round-trips identity-equivalently.
 */
export interface RemoteComposeOverride {
    profile?:
        | "androidx"
        | "androidx7"
        | "androidx8"
        | "androidx9"
        | "widgetsV6"
        | "widgetsV7"
        | "wearWidgets"
        | null;
    namedValues?: Record<string, RemoteNamedValueWire>;
    acceptedHostActions?: string[];
}

/** Wire-shape mirror of `RemoteNamedValue` (see Messages.kt). */
export type RemoteNamedValueWire =
    | { kind: "float"; value: number }
    | { kind: "dp"; value: number }
    | { kind: "int"; value: number }
    | { kind: "string"; value: string }
    | { kind: "bool"; value: boolean }
    | { kind: "color"; argb: string };

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

export interface RenderStartedParams {
    id: string;
    queuedMs: number;
}

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

/**
 * Classified render-failure kind (issue #1789). The coarse stages are joined by fine-grained
 * skew discriminants (`classpathSkew` / `missingComposable` / `unsetParameter` / `sdkMismatch`).
 * Decoded tolerantly per VERSIONING.md § 4.1 — the trailing `(string & {})` keeps an unrecognised
 * future kind assignable instead of a type error, and consumers must branch with a default arm.
 */
export type RenderErrorKind =
    | "compile"
    | "runtime"
    | "capture"
    | "timeout"
    | "classpathSkew"
    | "missingComposable"
    | "unsetParameter"
    | "sdkMismatch"
    | "internal"
    | (string & {});

export interface RenderError {
    kind: RenderErrorKind;
    message: string;
    stackTrace?: string;
    /**
     * One-line remediation for a recognised failure signature (#1789), e.g. a classpath-skew or
     * Robolectric SDK-mismatch fix hint. Absent when the daemon had no specific suggestion or
     * pre-dates the field.
     */
    suggestion?: string;
}

export interface RenderFailedParams {
    id: string;
    error: RenderError;
}

export interface ClasspathDirtyParams {
    reason: "fingerprintMismatch" | "fileChanged" | "manifestMissing";
    detail: string;
    changedPaths?: string[];
}

export interface SandboxRecycleParams {
    reason:
        | "heapCeiling"
        | "heapDrift"
        | "renderTimeDrift"
        | "histogramDrift"
        | "renderCount"
        | "leakSuspected"
        | "manual";
    ageMs: number;
    renderCount: number;
    warmSpareReady: boolean;
}

export interface DaemonWarmingParams {
    etaMs: number;
}

export interface LogParams {
    level: "debug" | "info" | "warn" | "error";
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

export type HistorySourceKind = "fs" | "git" | "http";

export interface HistoryListParams {
    previewId?: string;
    since?: string; // ISO 8601 lower bound
    until?: string; // ISO 8601 upper bound
    limit?: number; // daemon defaults to 50, max 500
    cursor?: string; // opaque token from a previous response
    branch?: string;
    branchPattern?: string; // regex
    commit?: string; // long or short SHA
    worktreePath?: string;
    agentId?: string;
    sourceKind?: HistorySourceKind;
    sourceId?: string;
    /** H10-read — serve this listing from an on-demand git reporting branch
     *  (full ref name, e.g. `refs/heads/preview/main`) instead of the daemon's
     *  configured sources. Lets the panel view any reporting branch without it
     *  being wired at daemon startup. */
    ref?: string;
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
    /** H10-read — read this id from an on-demand git reporting branch instead
     *  of the configured sources; must match the `ref` it was listed from. */
    ref?: string;
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

export type HistoryDiffMode = "metadata" | "pixel" | "semantics" | "data";

export interface HistoryDiffParams {
    from: string;
    to: string;
    mode?: HistoryDiffMode; // default 'metadata'
    /** H10-read — resolve both `from` and `to` from this on-demand git
     *  reporting branch instead of the configured sources. One ref per diff. */
    ref?: string;
}

/**
 * `mode = 'metadata'` (default) is cheap: daemon hashes both PNGs and returns
 * the sidecars; the diff/delta fields below stay undefined.
 *
 * `mode = 'pixel'` (H5, #1873) populates `diffPx` / `ssim` / `diffPngPath`
 * (the marked-diff PNG written under `<historyDir>/<previewId>/.diffs/`;
 * undefined on a dimension mismatch).
 *
 * `mode = 'semantics'` (#1785) populates `semanticsDelta` (`compose-semantics-diff/v1`).
 *
 * `mode = 'data'` (#1873) populates `dataDelta` (`history-data-diff/v1`): the
 * data-product roll-up with optional `semantics` / `a11y` / `theme` sections,
 * each present only when both entries carry that product.
 *
 * Nested payloads are typed `unknown` here (matching `fromMetadata` /
 * `toMetadata`); the Kotlin `@Serializable` types under `schema/` are the
 * source of truth for their shapes.
 */
export interface HistoryDiffResult {
    pngHashChanged: boolean;
    fromMetadata: unknown;
    toMetadata: unknown;
    diffPx?: number;
    ssim?: number;
    diffPngPath?: string;
    /** Semantics-mode delta (`compose-semantics-diff/v1`); undefined otherwise. */
    semanticsDelta?: unknown;
    /** Data-mode roll-up (`history-data-diff/v1`); undefined outside `mode='data'`. */
    dataDelta?: unknown;
}

export interface HistoryAddedParams {
    /** Sidecar JSON of the newly-written entry. */
    entry: unknown;
}

/**
 * `historyPruned` notification (HISTORY.md). Emitted after a non-empty prune
 * pass (auto-prune that actually removed entries, or `history/prune` manual
 * call). Auto-prune passes that removed nothing produce no notification.
 */
export interface HistoryPrunedParams {
    /** IDs of the entries that were removed. */
    removedIds: string[];
    /** Total bytes reclaimed across all removed entries' on-disk artifacts. */
    freedBytes: number;
    /** Why the prune ran. */
    reason: "auto" | "manual";
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

export interface InteractiveStartParams {
    previewId: string;
    inspectionMode?: boolean;
}
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

export interface InteractiveStopParams {
    frameStreamId: string;
}

/**
 * `interactive/setRemoteCompose` notification — push one Remote Compose state edit (profile
 * selection or named-value change) into the held interactive session's
 * `RemoteComposeController` without forcing a fresh `renderNow`. The daemon's session
 * dispatches into the controller's `setProfile(...)` / `setNamedValue(...)` and snapshot-state
 * recomposition repaints the held scene on the next streaming frame.
 *
 * Distinct from `renderNow.overrides.remoteCompose` (which still works and re-renders from
 * scratch): this is the snappy live-session path that bypasses the override-apply + full-
 * recompose round-trip. Hosts without a live binding silently drop the notification — the
 * caller's fallback re-issues `renderNow` with overrides as the canonical source-of-truth path.
 */
export interface InteractiveSetRemoteComposeParams {
    frameStreamId: string;
    change: RemoteComposeChangeDetail;
}

/**
 * `interactive/setLottie` notification params — scrub a held Lottie scene's timeline in place. The
 * Lottie analogue of {@link InteractiveSetRemoteComposeParams}: the panel's timeline slider sends
 * this on every drag tick when a live session is up, and the daemon coalesces ticks to the latest
 * before recomposing the held scene to that frame — no fresh `renderNow.overrides.lottie.progress`
 * round-trip. Hosts without a live Lottie binding silently drop it and the panel falls back to
 * `renderNow`.
 */
export interface InteractiveSetLottieParams {
    frameStreamId: string;
    /** Timeline position in 0..1 (the daemon clamps). */
    progress: number;
}

/**
 * Discriminated edit shape carried by `interactive/setRemoteCompose`. Mirrors the
 * `RemoteComposeChange` Kotlin sealed class on the daemon wire side and the
 * `RemoteComposeChangeDetail` discriminated union the VS Code panel emits. Single shape across
 * all three boundaries so the host can forward the panel's payload verbatim.
 */
export type RemoteComposeChangeDetail =
    | {
          field: "profile";
          value:
              | "androidx"
              | "androidx7"
              | "androidx8"
              | "androidx9"
              | "widgetsV6"
              | "widgetsV7"
              | "wearWidgets"
              | null;
      }
    | { field: "namedValue"; name: string; value: RemoteNamedValueWire };

export type InteractiveInputKind =
    | "click"
    | "pointerDown"
    | "pointerMove"
    | "pointerUp"
    | "rotaryScroll"
    | "keyDown"
    | "keyUp";

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

export type RecordingFormat = "apng" | "mp4" | "webm";

export type RecordingScriptEventStatus = "applied" | "unsupported";

export interface RecordingStartParams {
    previewId: string;
    fps?: number;
    scale?: number;
    overrides?: PreviewOverrides;
    live?: boolean;
}

export interface RecordingStartResult {
    recordingId: string;
}

/**
 * Stable handle for the node an interaction targets, resolved server-side against the live
 * semantics tree (issue #1784). Set exactly one of `ref` / `testTag` / (`role` and/or `text`).
 * The daemon back-resolves panel pixel clicks into one of these for the captured live script
 * (issue #2047) so a recorded session is coordinate-free.
 */
export interface SemanticsInputTarget {
    ref?: string;
    testTag?: string;
    role?: string;
    text?: string;
}

export interface RecordingInputParams {
    recordingId: string;
    kind: InteractiveInputKind;
    pixelX?: number;
    pixelY?: number;
    /** Optional semantic handle — lets an agent drive a live recording by ref/testTag/role+text. */
    target?: SemanticsInputTarget;
    pointerId?: number;
    scrollDeltaY?: number;
    keyCode?: string;
}

export interface RecordingScriptEvent {
    tMs: number;
    kind: string;
    pixelX?: number;
    pixelY?: number;
    /** Coordinate-free handle the live tick loop resolved this event's pixel to (issue #2047). */
    target?: SemanticsInputTarget;
    pointerId?: number;
    scrollDeltaY?: number;
    keyCode?: string;
    label?: string;
    checkpointId?: string;
    lifecycleEvent?: string;
    tags?: string[];
}

export interface RecordingScriptEvidence {
    tMs: number;
    kind: string;
    status: RecordingScriptEventStatus;
    label?: string;
    checkpointId?: string;
    lifecycleEvent?: string;
    tags?: string[];
    message?: string;
}

export interface RecordingStopParams {
    recordingId: string;
}

export interface RecordingStopResult {
    frameCount: number;
    durationMs: number;
    framesDir: string;
    frameWidthPx: number;
    frameHeightPx: number;
    scriptEvents?: RecordingScriptEvidence[];
    /**
     * Coordinate-free timeline captured from a `live = true` recording (the record-live bridge,
     * issue #2047). Empty/absent for scripted recordings. Feeds `recording/generateTest`.
     */
    capturedScript?: RecordingScriptEvent[];
}

/**
 * `recording/generateTest` request (issue #2047) — turn a captured live timeline into a runnable
 * Compose UI test. The daemon resolves the composable's real function name from its preview catalog.
 */
export interface RecordingGenerateTestParams {
    previewId: string;
    events: RecordingScriptEvent[];
    className?: string;
    methodName?: string;
    composableInvocation?: string;
    packageName?: string;
}

export interface RecordingGenerateTestResult {
    source: string;
}

export interface RecordingEncodeParams {
    recordingId: string;
    format?: RecordingFormat;
}

export interface RecordingEncodeResult {
    videoPath: string;
    mimeType: string;
    sizeBytes: number;
}

/**
 * Wire format of `<module>/build/compose-previews/daemon-launch.json`,
 * authored by `DaemonBootstrapTask`. See
 * `gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/daemonlaunch/DaemonClasspathDescriptor.kt`.
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
    /**
     * Stage-2 in-process compile config. Non-null when
     * the gradle plugin resolved the BTA classpath for this variant (the common case — KSP/KAPT
     * modules emit a populated block carrying an `ineligibilityReason` so the daemon can refuse
     * gracefully). Null only when the wiring is incomplete. The VS Code extension dispatches
     * `daemonScheduler.compileSourcesInProcess()` through this path only when the workspace
     * setting `composePreview.daemon.compileInProcess` is on; otherwise the scheduler falls
     * back to stage 1 (`gradle --continuous`) or stage 0 (one-shot Gradle).
     */
    btaCompile: BtaCompileConfig | null;
}

/**
 * Stage-2 in-process compile config. Mirrors the
 * `BtaCompileConfig` data class in `DaemonClasspathDescriptor.kt`; field-for-field. The
 * extension doesn't act on these fields directly — they're for the daemon JVM, which reads
 * the launch JSON at startup and constructs a `DefaultBtaCompileService` from them. The
 * extension only checks whether the field is present to decide whether the
 * `compileSources` JSON-RPC method is worth calling.
 */
export interface BtaCompileConfig {
    implClasspath: string[];
    compileClasspath: string[];
    compilerPlugins: string[];
    outputDir: string;
    moduleName: string;
    icWorkingDir: string;
    /** Non-null = module is NOT eligible for stage 2; the daemon's `compileSources` will
     *  return `result=fallback` with this reason. The extension can short-circuit even
     *  earlier and skip the JSON-RPC round-trip. */
    ineligibilityReason: string | null;
}

/**
 * Bumped to 2 when `btaCompile` landed (the v1 reader fails on any unknown field, so adding
 * a field IS a breaking schema change even though the field defaults to null).
 */
export const DAEMON_DESCRIPTOR_SCHEMA_VERSION = 2;

// =====================================================================
// Live-frame streaming (`composestream/1`) — buttery follow-up to
// `interactive/*`. See docs/daemon/STREAMING.md for the rationale.
//
// The data plane lives on `streamFrame` notifications carrying the bytes
// inline, eliminating the `<img src=…>` swap blink and the on-disk PNG
// race that the legacy renderFinished path suffers from.
// =====================================================================

export type StreamCodec = "png" | "webp";

export interface StreamStartParams {
    previewId: string;
    codec?: StreamCodec;
    /** Cap on emit cadence (frames per second). `undefined` = renderer-natural. */
    maxFps?: number;
    hidpi?: boolean;
    inspectionMode?: boolean;
    /**
     * Per-session preview overrides. Mirrors `RecordingStartParams.overrides` — currently
     * carries the per-preview `touchOverlay` and `keyboard` toggles the panel surfaces via
     * the focus-bar `#btn-touch-overlay` and `#btn-keyboard-band` buttons.
     */
    overrides?: PreviewOverrides;
}

export interface StreamStartResult {
    frameStreamId: string;
    /** Codec the daemon will actually emit — may be downgraded from the request. */
    codec: StreamCodec;
    heldSession: boolean;
    fallbackReason?: string;
}

export interface StreamStopParams {
    frameStreamId: string;
}

export interface StreamVisibilityParams {
    frameStreamId: string;
    visible: boolean;
    /** Override the throttled fps when `visible` is false. Defaults to 1. */
    fps?: number;
}

/**
 * `streamFrame` notification — one frame on a live stream.
 *
 * `codec === undefined` and `payloadBase64 === undefined` together signal an
 * `unchanged` heartbeat: bytes-identical to the prior frame on this stream.
 * The newest-wins client should treat this as a no-op tick.
 */
export interface StreamFrameParams {
    frameStreamId: string;
    seq: number;
    ptsMillis: number;
    widthPx: number;
    heightPx: number;
    codec?: StreamCodec;
    keyframe?: boolean;
    final?: boolean;
    payloadBase64?: string;
}
