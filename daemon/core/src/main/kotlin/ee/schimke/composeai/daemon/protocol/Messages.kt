package ee.schimke.composeai.daemon.protocol

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------
// Preview daemon — IPC protocol message types.
//
// Source of truth: docs/daemon/PROTOCOL.md (v1, locked). Field names match the
// JSON shapes in that document; we lean on Kotlin/JSON name parity and only
// use @SerialName when the JSON spelling diverges from idiomatic Kotlin.
//
// The TypeScript counterpart lives in vscode-extension/src/daemon/
// daemonProtocol.ts (Stream C, C1.1). Both suites round-trip the JSON
// fixtures under docs/daemon/protocol-fixtures/ as a shared corpus —
// see PROTOCOL.md § 9.
// ---------------------------------------------------------------------------

// =====================================================================
// 1. JSON-RPC envelope (PROTOCOL.md § 2)
//
// `params`, `result`, and `error.data` are typed as JsonElement so the
// envelope layer is generic. The dispatch layer parses these into the
// concrete message classes below using kotlinx.serialization.
// =====================================================================

// `jsonrpc: "2.0"` is mandatory on the wire per the JSON-RPC 2.0 spec, but
// having a default value keeps Kotlin construction ergonomic. @EncodeDefault
// forces it to be written even when a Json configuration sets
// `encodeDefaults = false`.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcRequest(
  @EncodeDefault val jsonrpc: String = "2.0",
  val id: Long,
  val method: String,
  val params: JsonElement? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcResponse(
  @EncodeDefault val jsonrpc: String = "2.0",
  val id: Long,
  val result: JsonElement? = null,
  val error: JsonRpcError? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcNotification(
  @EncodeDefault val jsonrpc: String = "2.0",
  val method: String,
  val params: JsonElement? = null,
)

@Serializable
data class JsonRpcError(val code: Int, val message: String, val data: JsonElement? = null)

// =====================================================================
// 2. initialize (PROTOCOL.md § 3)
// =====================================================================

@Serializable
data class InitializeParams(
  val protocolVersion: Int,
  val clientVersion: String,
  val workspaceRoot: String,
  val moduleId: String,
  val moduleProjectDir: String,
  val capabilities: ClientCapabilities,
  val options: Options? = null,
)

@Serializable data class ClientCapabilities(val visibility: Boolean, val metrics: Boolean)

@Serializable
data class Options(
  val maxHeapMb: Int? = null,
  val warmSpare: Boolean? = null,
  val detectLeaks: DetectLeaks? = null,
  val foreground: Boolean? = null,
  // D1 — data-product kinds the client wants ambient on every render. See
  // docs/daemon/DATA-PRODUCTS.md § "Wire surface". Most clients leave this
  // null/empty and use `data/subscribe` for sticky-while-visible attachment.
  val attachDataProducts: List<String>? = null,
  /**
   * Per-render timeout (in milliseconds) the daemon enforces on every `host.submit(...)` call for
   * this client's session. Defaults to 5 minutes (`5 * 60_000`) — generous enough for Robolectric
   * cold-sandbox bootstrap (5–15s) plus any single render. Bump for CI-style runs that render many
   * heavy previews and want headroom; lower for interactive sessions that prefer a fast failure
   * over a long hang. Values ≤ 0 fall back to the default.
   */
  val maxRenderMs: Long? = null,
  /**
   * Initialize-time override for the daemon's default history pruning policy. Each present value
   * wins over the matching JVM sysprop/default; null fields preserve the daemon-configured value.
   * Values ≤ 0 keep the existing pruning semantics for that knob: disabled.
   */
  val historyPrune: HistoryPruneOptions? = null,
)

@Serializable
data class HistoryPruneOptions(
  val maxEntriesPerPreview: Int? = null,
  val maxAgeDays: Int? = null,
  val maxTotalSizeBytes: Long? = null,
  val autoIntervalMs: Long? = null,
)

@Serializable
enum class DetectLeaks {
  @SerialName("off") OFF,
  @SerialName("light") LIGHT,
  @SerialName("heavy") HEAVY,
}

@Serializable
data class InitializeResult(
  val protocolVersion: Int,
  val daemonVersion: String,
  val pid: Long,
  val capabilities: ServerCapabilities,
  val classpathFingerprint: String,
  val manifest: Manifest,
)

@Serializable
data class ServerCapabilities(
  val incrementalDiscovery: Boolean,
  val sandboxRecycle: Boolean,
  // Subset of {"light","heavy"}; empty means leak detection unavailable.
  val leakDetection: List<LeakDetectionMode>,
  // D1 — kinds the daemon can produce. Empty list = pre-D1 daemon (the
  // client side treats absent and `[]` identically). See
  // docs/daemon/DATA-PRODUCTS.md § "Wire surface".
  val dataProducts: List<DataProductCapability> = emptyList(),
  /** Metadata for registered data extensions, including namespaced recording script events. */
  val dataExtensions: List<DataExtensionDescriptor> = emptyList(),
  /**
   * Metadata for extension steps the daemon can plan into a render pipeline. Clients should use
   * this for generic UI affordances and validation instead of keying behavior off product strings.
   */
  val previewExtensions: List<PreviewExtensionDescriptor> = emptyList(),
  // INTERACTIVE.md § 9 — `true` when the daemon's host can dispatch
  // `interactive/input` events into a held composition (v2). `false` means
  // `interactive/start` still works but inputs trigger a re-render rather than
  // mutating state (v1 fallback). Defaulted for old daemons that pre-date the
  // capability — clients treat absent and `false` identically.
  val interactive: Boolean = false,
  /**
   * `true` when the daemon's host can drive a virtual-frame-clock recording session (the scripted
   * screen-record surface — see RECORDING.md). Defaulted to `false` for old daemons that pre-date
   * the capability; clients treat absent and `false` identically and fall back to surfacing
   * "recording unsupported by this backend" when the toggle is offered.
   */
  val recording: Boolean = false,
  /**
   * Encoded video formats the daemon's host can produce — names match the wire spelling on
   * [RecordingFormat]. APNG is always present when [recording] is true (pure-JVM encoder, no native
   * deps). MP4 / WEBM appear only when an `ffmpeg` binary is available on the daemon process's
   * `PATH`; clients that ask for an unadvertised format should expect a clean rejection from
   * `record_preview` rather than a daemon-side runtime error. Empty list = pre-feature daemon
   * (clients treat absent and `[]` identically and fall back to APNG).
   */
  val recordingFormats: List<String> = emptyList(),
  /**
   * The `@Preview(device = ...)` ids the daemon's `DeviceDimensions` catalog recognises, paired
   * with their resolved geometry. Lets clients build a "render this preview at..." picker without
   * re-bundling the catalog. Empty list = pre-feature daemon (clients treat absent and `[]`
   * identically). The `spec:width=…,height=…,dpi=…` grammar is not enumerable — clients pass it as
   * a free-form `device` override and the daemon parses it at resolve-time. See
   * `daemon/core/.../daemon/devices/DeviceDimensions.kt` for the source of truth.
   */
  val knownDevices: List<KnownDevice> = emptyList(),
  /**
   * The `PreviewOverrides` field names this daemon's host actually applies (see PROTOCOL.md § 5
   * `renderNow.overrides`). Names match the JSON spelling on the wire: `widthPx`, `heightPx`,
   * `density`, `localeTag`, `fontScale`, `uiMode`, `orientation`, `device`, `captureAdvanceMs`,
   * `inspectionMode`. Lets clients grey out unsupported sliders and lets MCP warn agents who set
   * fields the backend would silently ignore. Empty list = pre-feature daemon (clients treat absent
   * and `[]` identically and assume any field they pass might be ignored).
   *
   * Today: `RobolectricHost` advertises every field; `DesktopHost` omits `orientation` (no rotation
   * concept on `ImageComposeScene`), Android-only timing knobs, and `localeTag` unless the Compose
   * UI runtime exposes a providable locale list.
   */
  val supportedOverrides: List<String> = emptyList(),
  /**
   * Identifier for the renderer backend behind this daemon. Lets clients render backend-specific UI
   * hints (e.g. "Wear preview not supported on desktop", "round-device qualifier requires the
   * Android backend") without per-call probing. Today: `"desktop"` for the Compose Desktop / Skiko
   * backend (`DesktopHost`), `"android"` for the Robolectric backend (`RobolectricHost`). `null`
   * (the default) on hosts that haven't been classified — e.g. `FakeHost` from the harness, or a
   * future stub backend; clients should treat absent and `null` as "unknown".
   */
  val backend: BackendKind? = null,
  /**
   * Fixed Android SDK level the backend renders against. Populated by the Robolectric backend from
   * its pinned `@Config(sdk = ...)` value; `null` on Desktop and other non-Android backends.
   */
  val androidSdk: Int? = null,
)

/**
 * Renderer backend identifier surfaced via `ServerCapabilities.backend`. Stable string spellings —
 * these values appear in panel UI matching and MCP-side dispatch heuristics, so adding a new
 * variant is a wire change.
 */
@Serializable
enum class BackendKind {
  @SerialName("desktop") DESKTOP,
  @SerialName("android") ANDROID,
}

/**
 * One entry in `ServerCapabilities.knownDevices`. The id is the string a caller passes via
 * `renderNow.overrides.device` (or `@Preview(device = ...)` at discovery time); the geometry fields
 * let a UI label the device ("Pixel 5 — 393×851 dp @ 2.75x") without re-resolving. [isRound]
 * identifies circular Wear-style displays.
 */
@Serializable
data class KnownDevice(
  val id: String,
  val widthDp: Int,
  val heightDp: Int,
  val density: Float,
  val isRound: Boolean = false,
)

/**
 * One advertised data-product kind. Mirrors `DataProductCapability` in
 * `vscode-extension/src/daemon/daemonProtocol.ts`. See
 * [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md) § "The
 * primitive" for semantics — `transport` picks how the payload travels; `attachable` / `fetchable`
 * discriminate which surfaces support the kind; `requiresRerender = true` warns the client that a
 * `data/fetch` may pay a render cost when the latest pass didn't compute the kind.
 */
@Serializable
data class DataProductCapability(
  val kind: String,
  val schemaVersion: Int,
  val transport: DataProductTransport,
  val attachable: Boolean,
  val fetchable: Boolean,
  val requiresRerender: Boolean,
  val displayName: String? = null,
  val facets: List<DataProductFacet> = emptyList(),
  val mediaTypes: List<String> = emptyList(),
  val sampling: SamplingPolicy? = null,
)

@Serializable
enum class DataProductTransport {
  @SerialName("inline") INLINE,
  @SerialName("path") PATH,
  @SerialName("both") BOTH,
}

@Serializable
enum class DataProductFacet {
  @SerialName("structured") STRUCTURED,
  @SerialName("artifact") ARTIFACT,
  @SerialName("image") IMAGE,
  @SerialName("animation") ANIMATION,
  @SerialName("overlay") OVERLAY,
  @SerialName("check") CHECK,
  @SerialName("diagnostic") DIAGNOSTIC,
  @SerialName("profile") PROFILE,
  @SerialName("interactive") INTERACTIVE,
}

@Serializable
enum class LeakDetectionMode {
  @SerialName("light") LIGHT,
  @SerialName("heavy") HEAVY,
}

@Serializable data class Manifest(val path: String, val previewCount: Int)

// =====================================================================
// 2b. extensions/{list,enable,disable} (PROTOCOL.md § 3a)
//
// Daemons register every extension as inactive. Clients call `extensions/enable` to opt in to the
// ones they want — the corresponding kinds, descriptors, and override planners come online for
// that daemon's lifetime (until a matching `extensions/disable`). Dependencies declared by an
// extension are pulled in transitively but stay invisible to direct client RPC.
// =====================================================================

@Serializable
data class ExtensionInfoDto(
  val id: String,
  val displayName: String,
  val dependencies: List<String> = emptyList(),
  val publiclyEnabled: Boolean = false,
  val active: Boolean = false,
  val dataProductKinds: List<String> = emptyList(),
  val dataExtensionIds: List<String> = emptyList(),
  val previewExtensionIds: List<String> = emptyList(),
)

@Serializable data class ExtensionsListResult(val extensions: List<ExtensionInfoDto>)

@Serializable data class ExtensionsEnableParams(val ids: List<String>)

@Serializable
data class ExtensionsEnableResult(
  val newlyEnabled: List<String> = emptyList(),
  val pulledIn: List<String> = emptyList(),
  val alreadyEnabled: List<String> = emptyList(),
  val unknown: List<String> = emptyList(),
  /** New public capability snapshots so a client doesn't need a follow-up `extensions/list`. */
  val dataProducts: List<DataProductCapability> = emptyList(),
  val dataExtensions: List<DataExtensionDescriptor> = emptyList(),
  val previewExtensions: List<PreviewExtensionDescriptor> = emptyList(),
)

@Serializable data class ExtensionsDisableParams(val ids: List<String>)

@Serializable
data class ExtensionsDisableResult(
  val disabled: List<String> = emptyList(),
  val deactivated: List<String> = emptyList(),
  val stillActiveAsDependency: List<String> = emptyList(),
  val notEnabled: List<String> = emptyList(),
  val unknown: List<String> = emptyList(),
  val dataProducts: List<DataProductCapability> = emptyList(),
  val dataExtensions: List<DataExtensionDescriptor> = emptyList(),
  val previewExtensions: List<PreviewExtensionDescriptor> = emptyList(),
)

// =====================================================================
// 3. Client → daemon notifications (PROTOCOL.md § 4)
// =====================================================================

@Serializable data class SetVisibleParams(val ids: List<String>)

@Serializable data class SetFocusParams(val ids: List<String>)

@Serializable
data class FileChangedParams(val path: String, val kind: FileKind, val changeType: ChangeType)

@Serializable
enum class FileKind {
  @SerialName("source") SOURCE,
  @SerialName("resource") RESOURCE,
  @SerialName("classpath") CLASSPATH,
}

@Serializable
enum class ChangeType {
  @SerialName("modified") MODIFIED,
  @SerialName("created") CREATED,
  @SerialName("deleted") DELETED,
}

// =====================================================================
// 4. Client → daemon requests (PROTOCOL.md § 5)
// =====================================================================

@Serializable
data class RenderNowParams(
  val previews: List<String>,
  val tier: RenderTier,
  val reason: String? = null,
  /**
   * Optional per-call display-property overrides. Applied to every preview in [previews] for this
   * call only; a subsequent `renderNow` without `overrides` reverts to the discovery-time
   * `RenderSpec` from `previews.json`. See PROTOCOL.md § 5 ("renderNow") and
   * docs/daemon/INTERACTIVE.md § "Display overrides".
   */
  val overrides: PreviewOverrides? = null,
)

/**
 * Per-render display-property overrides, threaded through to each backend's `RenderEngine`. Every
 * field is optional — fields left null fall back to the discovery-time `RenderSpec`. Backends that
 * don't model a particular field (e.g. desktop has no `uiMode` resource qualifier) ignore it. See
 * PROTOCOL.md § 5 ("renderNow.overrides").
 */
@Serializable
data class PreviewOverrides(
  /** Sandbox width in pixels. Mirrors `@Preview(widthDp=…)` × density. */
  val widthPx: Int? = null,
  /** Sandbox height in pixels. */
  val heightPx: Int? = null,
  /** Display density (1.0 = mdpi/160dpi, 2.0 = xhdpi/320dpi, etc.). */
  val density: Float? = null,
  /** BCP-47 locale tag (e.g. `"en-US"`, `"fr"`, `"ja-JP"`). */
  val localeTag: String? = null,
  /** Font scale multiplier (1.0 = system default, 1.3 = "large", 2.0 = max accessibility). */
  val fontScale: Float? = null,
  /** Light/dark mode override. Android-only today. */
  val uiMode: UiMode? = null,
  /** Portrait/landscape override. Android-only today. */
  val orientation: Orientation? = null,
  /**
   * `@Preview(device = ...)` string — `id:pixel_5`, `id:wearos_small_round`, `id:tv_1080p`, or a
   * full `spec:width=400dp,height=800dp,dpi=320,isRound=true` grammar. The daemon resolves the
   * string against its built-in catalog (`ee.schimke.composeai.daemon.devices.DeviceDimensions`)
   * and merges the resulting `widthPx` / `heightPx` / `density` into the render spec. Explicit
   * `widthPx` / `heightPx` / `density` overrides on this same object take precedence — so a caller
   * can say `device: "id:pixel_5", widthPx: 600` to force a wider window on the Pixel 5's density.
   * Unknown device ids fall back to the default (400×800 dp at xxhdpi).
   */
  val device: String? = null,
  /**
   * Paused-clock advance (in milliseconds) before the renderer captures the PNG. Android-only today
   * — the Robolectric backend uses `mainClock.advanceTimeBy(...)` to tick a deterministic snapshot
   * point past initial composition + any `LaunchedEffect` settle. Default (~32ms ≈ 2 Choreographer
   * frames) is enough for static previews and one `LaunchedEffect` pass; bump for animation-heavy
   * previews that need longer to settle (e.g. staged enter animations, `rememberInfiniteTransition`
   * chains where you want a specific phase). Values ≤ 0 fall back to the default. Desktop ignores
   * it (no paused-clock concept).
   */
  val captureAdvanceMs: Long? = null,
  /**
   * Per-render `LocalInspectionMode` value for one-shot renders. Null preserves the backend's
   * default preview behaviour (`true` for renderNow). Set `false` to render as runtime-like content
   * without allocating a held interactive session.
   */
  val inspectionMode: Boolean? = null,
  /**
   * Optional Material 3 theme token overrides applied by the renderer as a normal
   * `MaterialTheme(...) { preview() }` wrapper around the invoked preview. This lets callers test
   * components under alternate color, shape, or typography tokens without editing source previews.
   */
  val material3Theme: Material3ThemeOverrides? = null,
  /**
   * Optional wallpaper seed-color override. The renderer derives a Material 3 color scheme from the
   * seed and wraps the preview in a `MaterialTheme(colorScheme = …)`; an explicit `material3Theme`
   * override on the same call still wins for any role the caller pinned. Sending a fresh
   * `wallpaper` on a subsequent `renderNow` re-renders the held preview with the new scheme — the
   * "live update" path the wallpaper data product covers.
   */
  val wallpaper: WallpaperOverride? = null,
  /**
   * Optional Wear OS ambient-state override. Drives the connector-side `AmbientLifecycleObserver`
   * shadow so consumer code wrapping its UI in `AmbientAware { ... }` (or registering its own
   * `AmbientLifecycleCallback`) renders under the requested state. The shadow defaults to
   * `Inactive` when no override is set; setting `state = AMBIENT` causes `isAmbient()` to return
   * `true` and primes registered callbacks with `onEnterAmbient(...)`. Wear-only — the desktop
   * backend ignores this field.
   */
  val ambient: AmbientOverride? = null,
  /**
   * Optional focus / keyboard-traversal override. Drives the connector-side
   * `FocusOverrideExtension` (see `:data-focus-connector`) so a single-frame render under the
   * daemon can land focus on a specific tab index or apply a directional move (Tab / Shift-Tab /
   * D-pad). Static `@Preview` rendering through the gradle plugin doesn't populate this field —
   * `@FocusedPreview` discovery emits per-capture state that the renderer pushes into
   * `FocusController` directly. Backends without a Compose focus owner (e.g. desktop
   * Compose-Multiplatform) ignore this field.
   */
  val focus: FocusOverride? = null,
)

/**
 * Focus / keyboard-traversal override for previews. Drives the connector-side
 * `FocusOverrideExtension` (see `:data-focus-connector`).
 *
 * Two driving modes — same shape `@FocusedPreview` already produces:
 *
 * * **Indexed** ([tabIndex]): focus the n-th focusable in tab order. The connector issues
 *   `moveFocus(Enter)` once on the first activation, then `moveFocus(Next)` to walk forward.
 * * **Traversal** ([direction]): apply a single directional step. The connector issues
 *   `moveFocus(Enter)` once before the first step, then `moveFocus(direction)` per call. [step]
 *   carries the 1-based step index for overlay labels.
 *
 * Set both null to leave the focus driver inactive — the around-composable still installs keyboard
 * input mode so `Modifier.clickable`'s focusable accepts focus if user code requests it
 * programmatically.
 *
 * [overlay] toggles the post-capture stroke + label overlay drawn over the focused element's
 * bounds. The renderer's per-capture loop reads it and calls
 * `ee.schimke.composeai.daemon.FocusOverlay.apply` when set.
 */
@Serializable
data class FocusOverride(
  val tabIndex: Int? = null,
  val direction: FocusDirection? = null,
  val step: Int? = null,
  val overlay: Boolean = false,
)

/**
 * Mirror of Compose's `androidx.compose.ui.focus.FocusDirection`. Duplicated here because the
 * gradle plugin's discovery task and the protocol can't take a runtime dep on `compose-ui`; the
 * focus connector's `toCompose` adapter maps each value to the upstream constant at render time.
 */
@Serializable
enum class FocusDirection {
  Next,
  Previous,
  Up,
  Down,
  Left,
  Right,
}

/**
 * Single-color seed for the wallpaper data extension.
 *
 * The renderer derives a Material 3 [androidx.compose.material3.ColorScheme] from [seedColor] via
 * Google's Material Color Utilities (HCT tonal palettes), picks the brightness from [isDark] (when
 * null, inherits the host theme's surface luminance), and wraps the preview in a
 * `MaterialTheme(colorScheme = …)`. [paletteStyle] selects the algorithm variant the wallpaper
 * picker exposes (Tonal Spot / Vibrant / Expressive / etc.) and [contrastLevel] threads through the
 * accessibility contrast control (`-1.0` → reduced, `0.0` → default, `0.5` → medium, `1.0` → high).
 */
@Serializable
data class WallpaperOverride(
  /** Seed color as `#RRGGBB` or `#AARRGGBB`. */
  val seedColor: String,
  /** When non-null, forces the dark variant of the derived scheme. */
  val isDark: Boolean? = null,
  /**
   * Algorithm variant. Mirrors the styles the Android wallpaper picker exposes; null falls back to
   * the connector's default (`TONAL_SPOT`).
   */
  val paletteStyle: WallpaperPaletteStyle? = null,
  /**
   * Material 3 contrast level in `[-1.0, 1.0]` — `0.0` is the default, `0.5` is medium, `1.0` is
   * high contrast. Null falls back to `0.0`.
   */
  val contrastLevel: Double? = null,
)

/**
 * Style of palette derivation for [WallpaperOverride].
 *
 * Mirrors `com.materialkolor.PaletteStyle` so the protocol stays free of an external dependency;
 * the wallpaper connector maps each value to the upstream enum.
 */
/**
 * Wear OS ambient-mode override for previews. Drives the
 * `androidx.wear.ambient.AmbientLifecycleObserver` shadow (see `:data-ambient-connector`) so a
 * preview's `AmbientAware`-wrapped UI composes under the requested ambient state without flashing a
 * real watch.
 *
 * `AmbientStateOverride.AMBIENT` triggers `onEnterAmbient(...)` on every registered
 * `AmbientLifecycleCallback`. During an interactive recording session the controller flips back to
 * `Interactive` on activating input gestures (touch click / pointer-down, RSB rotary scroll) — the
 * same gestures the AOSP `AmbientLifecycleObserver` itself wakes on — and restores the override's
 * requested state after [idleTimeoutMs] of further inactivity.
 */
@Serializable
data class AmbientOverride(
  /** Requested ambient state. */
  val state: AmbientStateOverride,
  /**
   * Mirrors `AmbientLifecycleObserver.AmbientDetails.burnInProtectionRequired`. Forwarded to
   * `onEnterAmbient(...)` so consumer code that branches on burn-in protection runs unchanged. Null
   * falls back to `false`.
   */
  val burnInProtectionRequired: Boolean? = null,
  /**
   * Mirrors `AmbientLifecycleObserver.AmbientDetails.deviceHasLowBitAmbient`. Forwarded to
   * `onEnterAmbient(...)`. Null falls back to `false`.
   */
  val deviceHasLowBitAmbient: Boolean? = null,
  /**
   * Synthetic minute-tick timestamp threaded through the connector's payload. Null means the
   * controller uses the render-time wall-clock when capturing the [AmbientPayload]. The renderer
   * does not synthesise periodic `onUpdateAmbient(...)` ticks — Wear's minute-tick cadence is
   * driven by explicit timestamps so render-time captures stay deterministic. A future
   * `ambient.updateTime` recording-script event will fire ticks at scripted points without a
   * wall-clock timer.
   */
  val updateTimeMillis: Long? = null,
  /**
   * Idle-after-input timeout (in milliseconds) before the controller restores the override's
   * requested state during a `record_preview` / interactive session. Null falls back to ~5000 ms,
   * matching the Wear OS system's default ambient timeout.
   */
  val idleTimeoutMs: Long? = null,
)

/** Wire spelling for [AmbientOverride.state]. */
@Serializable
enum class AmbientStateOverride {
  @SerialName("interactive") INTERACTIVE,
  @SerialName("ambient") AMBIENT,
  @SerialName("inactive") INACTIVE,
}

@Serializable
enum class WallpaperPaletteStyle {
  @SerialName("tonalSpot") TONAL_SPOT,
  @SerialName("neutral") NEUTRAL,
  @SerialName("vibrant") VIBRANT,
  @SerialName("expressive") EXPRESSIVE,
  @SerialName("rainbow") RAINBOW,
  @SerialName("fruitSalad") FRUIT_SALAD,
  @SerialName("monochrome") MONOCHROME,
  @SerialName("fidelity") FIDELITY,
  @SerialName("content") CONTENT,
}

@Serializable
data class Material3ThemeOverrides(
  /** Material 3 color role -> `#RRGGBB` or `#AARRGGBB`. */
  val colorScheme: Map<String, String> = emptyMap(),
  /** Material 3 text style name -> partial text-style override. */
  val typography: Map<String, Material3TypographyOverride> = emptyMap(),
  /** Material 3 shape token name -> rounded corner size in dp. */
  val shapes: Map<String, Float> = emptyMap(),
)

@Serializable
data class Material3TypographyOverride(
  val fontSizeSp: Float? = null,
  val lineHeightSp: Float? = null,
  val letterSpacingSp: Float? = null,
  val fontWeight: Int? = null,
  val italic: Boolean? = null,
)

@Serializable
enum class UiMode {
  @SerialName("light") LIGHT,
  @SerialName("dark") DARK,
}

@Serializable
enum class Orientation {
  @SerialName("portrait") PORTRAIT,
  @SerialName("landscape") LANDSCAPE,
}

@Serializable
enum class RenderTier {
  @SerialName("fast") FAST,
  @SerialName("full") FULL,
}

@Serializable
data class RenderNowResult(val queued: List<String>, val rejected: List<RejectedRender>)

@Serializable data class RejectedRender(val id: String, val reason: String)

// ---------------------------------------------------------------------------
// D1 — data products (see docs/daemon/DATA-PRODUCTS.md).
//
// `params` is per-kind options carried as JsonElement so the dispatch surface
// stays kind-agnostic — kinds that take params (e.g. `layout/inspector` keyed by
// nodeId) decode against their own serializer at producer time.
// ---------------------------------------------------------------------------

@Serializable
data class DataFetchParams(
  val previewId: String,
  val kind: String,
  val params: JsonElement? = null,
  val inline: Boolean = false,
)

@Serializable
data class DataFetchResult(
  val kind: String,
  val schemaVersion: Int,
  val payload: JsonElement? = null,
  val path: String? = null,
  // Reserved for non-local clients; populated only when caller passes
  // `inline: true` and the kind's transport is blob-shaped.
  val bytes: String? = null,
  /**
   * Additional non-JSON outputs the producer wrote alongside the primary payload — typically
   * derived images such as the a11y overlay PNG. Each entry points at a sibling file under the
   * preview's data dir; clients read the file directly. Always omitted (`null` on the wire) when no
   * extras landed for this fetch — older clients ignore the field. See
   * [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md) § "Image
   * processors and extras".
   */
  val extras: List<DataProductExtra>? = null,
)

/**
 * Shared params shape for `data/subscribe` and `data/unsubscribe`.
 *
 * `params` is the per-kind subscription option bag — e.g. `compose/recomposition` consumes `{
 * frameStreamId, mode: "delta" }` from it. Stateless kinds (`a11y/atf`, `a11y/hierarchy`) leave it
 * null. See [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md) §
 * "Recomposition + interactive mode".
 */
@Serializable
data class DataSubscribeParams(
  val previewId: String,
  val kind: String,
  val params: JsonElement? = null,
)

/** Acknowledgement-only result; trivial by design so growing it stays additive. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DataSubscribeResult(@EncodeDefault val ok: Boolean = true) {
  companion object {
    val OK: DataSubscribeResult = DataSubscribeResult(ok = true)
  }
}

// =====================================================================
// 5. Daemon → client notifications (PROTOCOL.md § 6)
// =====================================================================

@Serializable
data class DiscoveryUpdatedParams(
  // PreviewInfo is the schema emitted by DiscoverPreviewsTask plus the
  // sourceFile field added in P0.2. Carried as JsonElement here because the
  // canonical shape lives in :gradle-plugin and we don't want to duplicate
  // the data class across modules — the daemon dispatch layer can decode
  // into the real type when it's wired up.
  val added: List<JsonElement>,
  val removed: List<String>,
  val changed: List<JsonElement>,
  val totalPreviews: Int,
)

@Serializable data class RenderStartedParams(val id: String, val queuedMs: Long)

@Serializable
data class RenderFinishedParams(
  val id: String,
  val pngPath: String,
  val tookMs: Long,
  val metrics: RenderMetrics? = null,
  // D1 — populated only with the `(id, kind)` pairs the client subscribed
  // to (or globally attached via `attachDataProducts`). Absent and `[]` are
  // interchangeable on the wire. See docs/daemon/DATA-PRODUCTS.md.
  val dataProducts: List<DataProductAttachment>? = null,
  /**
   * Interactive-mode frame deduplication signal — see docs/daemon/INTERACTIVE.md § 5. When `true`
   * the daemon has determined the rendered bytes are byte-identical to the previously notified
   * frame for the same preview id, so the client can short-circuit the read-PNG → base64 →
   * postMessage hop. Always omitted (`null` on the wire) when dedup didn't fire — a fresh
   * `renderFinished` whose `unchanged` field is `null` means "client must paint these bytes".
   * Additive per PROTOCOL.md § 7; older clients ignore the field and keep painting unconditionally.
   */
  val unchanged: Boolean? = null,
)

/**
 * One data-product attachment riding on a `renderFinished`. `payload` is per-kind JSON when the
 * producer's transport is `inline`; `path` is an absolute path to a sibling file when the
 * producer's transport is `path`. Exactly one of the two is set per attachment.
 *
 * `extras` carries derived non-JSON outputs the producer wrote alongside (e.g. the a11y overlay
 * PNG). Always omitted on the wire when empty so pre-feature clients ignore it.
 */
@Serializable
data class DataProductAttachment(
  val kind: String,
  val schemaVersion: Int,
  val payload: JsonElement? = null,
  val path: String? = null,
  val extras: List<DataProductExtra>? = null,
)

/**
 * One additional output a data-product producer wrote alongside its primary payload. Used for
 * derived images (the Paparazzi-style a11y overlay PNG, layout-tree visualisations, recomposition
 * heat maps) that the producer emits as a side effect of running. The wire format is intentionally
 * minimal — pointer-only, no inlining — because the file is typically tens of KB and the daemon
 * already lives on the client's filesystem.
 *
 * `name` is a producer-stable, human-readable identifier (`"overlay"`, `"diff"`); the registry uses
 * it as the cache key for fetch-on-demand and also as the suggested file basename when the producer
 * writes the extra to disk. `mediaType` is the IANA media type when known (`image/png`), left null
 * when the producer doesn't classify the file. `sizeBytes` is the file size at write time; clients
 * use it for "show this only when small enough to inline" UI heuristics.
 *
 * See [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md) § "Image
 * processors and extras".
 */
@Serializable
data class DataProductExtra(
  val name: String,
  val path: String,
  val mediaType: String? = null,
  val sizeBytes: Long? = null,
)

@Serializable
data class RenderMetrics(
  val heapAfterGcMb: Long,
  val nativeHeapMb: Long,
  val sandboxAgeRenders: Long,
  val sandboxAgeMs: Long,
) {
  companion object {
    /**
     * The four flat-map keys [RenderHost] implementations populate on `RenderResult.metrics` to
     * carry B2.3 measurement values across the renderer-agnostic seam.
     *
     * Pinned here so `:daemon:core`, `:daemon:android`, `:daemon:desktop`, and `:daemon:harness`
     * agree on the exact spelling without each reaching for a string literal at the call site.
     */
    const val KEY_HEAP_AFTER_GC_MB: String = "heapAfterGcMb"
    const val KEY_NATIVE_HEAP_MB: String = "nativeHeapMb"
    const val KEY_SANDBOX_AGE_RENDERS: String = "sandboxAgeRenders"
    const val KEY_SANDBOX_AGE_MS: String = "sandboxAgeMs"

    /**
     * Translates the flat `Map<String, Long>` carrier on `RenderResult.metrics` into a structured
     * [RenderMetrics] for the wire. Returns `null` when any of the four B2.3 keys is missing — we
     * deliberately do not emit a half-populated metrics object since callers can't tell the
     * difference between "field truly was zero" and "field was missing", and the wire-level
     * presence of `metrics: null` already encodes "measurement unavailable" cleanly. Extra unknown
     * keys (e.g. the renderer's pre-existing `tookMs`) are ignored — they continue to flow through
     * `RenderFinishedParams.tookMs` at the top level.
     *
     * Returns a `Result` so the caller (`JsonRpcServer.renderFinishedFromResult`) can warn-log the
     * partial-map case and observe drift — a common shape early in a host backend's measurement
     * plumbing.
     */
    fun fromFlatMap(map: Map<String, Long>?): FromFlatMapResult {
      if (map == null) return FromFlatMapResult.AbsentSource
      val heap = map[KEY_HEAP_AFTER_GC_MB]
      val native = map[KEY_NATIVE_HEAP_MB]
      val ageRenders = map[KEY_SANDBOX_AGE_RENDERS]
      val ageMs = map[KEY_SANDBOX_AGE_MS]
      val missing = buildList {
        if (heap == null) add(KEY_HEAP_AFTER_GC_MB)
        if (native == null) add(KEY_NATIVE_HEAP_MB)
        if (ageRenders == null) add(KEY_SANDBOX_AGE_RENDERS)
        if (ageMs == null) add(KEY_SANDBOX_AGE_MS)
      }
      if (missing.isNotEmpty()) return FromFlatMapResult.PartialMap(missing)
      return FromFlatMapResult.Populated(
        RenderMetrics(
          heapAfterGcMb = heap!!,
          nativeHeapMb = native!!,
          sandboxAgeRenders = ageRenders!!,
          sandboxAgeMs = ageMs!!,
        )
      )
    }
  }

  /**
   * Tagged outcome of [fromFlatMap]. The three cases the wire layer needs to distinguish:
   *
   * - [AbsentSource] — the host returned `null` metrics (e.g. the B1.5-era stub hosts that don't
   *   measure anything). The wire emits `metrics: null` with no log noise — pre-B2.3 behaviour.
   * - [PartialMap] — the host populated *some* B2.3 keys but not all four. The wire still emits
   *   `metrics: null` (no half-populated objects), but [JsonRpcServer.renderFinishedFromResult]
   *   logs a warn-level notification so caller-side drift is observable.
   * - [Populated] — all four keys present; the wire carries the structured object.
   */
  sealed interface FromFlatMapResult {
    data object AbsentSource : FromFlatMapResult

    data class PartialMap(val missingKeys: List<String>) : FromFlatMapResult

    data class Populated(val metrics: RenderMetrics) : FromFlatMapResult
  }
}

@Serializable data class RenderFailedParams(val id: String, val error: RenderError)

@Serializable
data class RenderError(
  val kind: RenderErrorKind,
  val message: String,
  val stackTrace: String? = null,
)

@Serializable
enum class RenderErrorKind {
  @SerialName("compile") COMPILE,
  @SerialName("runtime") RUNTIME,
  @SerialName("capture") CAPTURE,
  @SerialName("timeout") TIMEOUT,
  @SerialName("internal") INTERNAL,
}

@Serializable
data class ClasspathDirtyParams(
  val reason: ClasspathDirtyReason,
  val detail: String,
  val changedPaths: List<String>? = null,
)

@Serializable
enum class ClasspathDirtyReason {
  @SerialName("fingerprintMismatch") FINGERPRINT_MISMATCH,
  @SerialName("fileChanged") FILE_CHANGED,
  @SerialName("manifestMissing") MANIFEST_MISSING,
}

@Serializable
data class SandboxRecycleParams(
  val reason: SandboxRecycleReason,
  val ageMs: Long,
  val renderCount: Long,
  val warmSpareReady: Boolean,
)

@Serializable
enum class SandboxRecycleReason {
  @SerialName("heapCeiling") HEAP_CEILING,
  @SerialName("heapDrift") HEAP_DRIFT,
  @SerialName("renderTimeDrift") RENDER_TIME_DRIFT,
  @SerialName("histogramDrift") HISTOGRAM_DRIFT,
  @SerialName("renderCount") RENDER_COUNT,
  @SerialName("leakSuspected") LEAK_SUSPECTED,
  @SerialName("manual") MANUAL,
}

@Serializable data class DaemonWarmingParams(val etaMs: Long)

@Serializable
class DaemonReadyParams {
  // Empty-object payload per PROTOCOL.md § 6 ("daemonReady"). Modelled as a
  // class with no fields so kotlinx-serialization emits/accepts {}.
  override fun equals(other: Any?): Boolean = other is DaemonReadyParams

  override fun hashCode(): Int = 0

  override fun toString(): String = "DaemonReadyParams()"
}

@Serializable
data class LogParams(
  val level: LogLevel,
  val message: String,
  val category: String? = null,
  val context: Map<String, JsonElement>? = null,
)

@Serializable
enum class LogLevel {
  @SerialName("debug") DEBUG,
  @SerialName("info") INFO,
  @SerialName("warn") WARN,
  @SerialName("error") ERROR,
}

// =====================================================================
// 6. History — H1 + H2 wire-format. See docs/daemon/HISTORY.md § "Layer 2 —
//    JSON-RPC API" and `HistoryEntry` in
//    ee.schimke.composeai.daemon.history.
//
// The `entry`, `previewMetadata` fields below carry already-encoded JSON
// rather than typed Kotlin classes — kotlinx.serialization can't reach
// across the package boundary into ee.schimke.composeai.daemon.history
// without pulling its types onto the Messages.kt import surface, which
// would create a circular include for the JsonRpcServer dispatch path.
// We use JsonElement + the dispatch layer encodes/decodes against the
// real `HistoryEntry` / `PreviewInfoDto` serializers at the call site.
// =====================================================================

@Serializable
data class HistoryListParams(
  val previewId: String? = null,
  val since: String? = null,
  val until: String? = null,
  val limit: Int? = null,
  val cursor: String? = null,
  val branch: String? = null,
  val branchPattern: String? = null,
  val commit: String? = null,
  val worktreePath: String? = null,
  val agentId: String? = null,
  val sourceKind: String? = null,
  val sourceId: String? = null,
)

@Serializable
data class HistoryListResult(
  val entries: List<JsonElement>,
  val nextCursor: String? = null,
  val totalCount: Int,
)

@Serializable data class HistoryReadParams(val id: String, val inline: Boolean = false)

@Serializable
data class HistoryReadResultDto(
  val entry: JsonElement,
  val previewMetadata: JsonElement? = null,
  val pngPath: String,
  val pngBytes: String? = null,
)

@Serializable data class HistoryAddedParams(val entry: JsonElement)

// =====================================================================
// 5b. Interactive (live-stream) mode — see docs/daemon/INTERACTIVE.md § 8.
//
// Pins a previewId as one of the daemon's render-priority targets ("warm" sandbox semantics
// once B2.4 lands). Multi-target on the wire: each `interactive/start` registers a fresh
// slot and returns a unique stream id; concurrent streams targeting different (or even the
// same) preview ids coexist. Inputs route by `frameStreamId` so a stop on one stream leaves
// the others untouched. Inputs are fire-and-forget notifications; the daemon responds by
// emitting a fresh `renderFinished` for the target preview.
// =====================================================================

@Serializable
data class InteractiveStartParams(
  val previewId: String,
  /**
   * Optional `LocalInspectionMode` override for held interactive sessions. Null preserves the
   * current runtime-like interactive default (`false`); set `true` for previews that need their
   * preview/stub-data branch while still using a held session.
   */
  val inspectionMode: Boolean? = null,
)

/**
 * Opaque correlation token returned by `interactive/start`. The client passes it back on every
 * subsequent `interactive/input` and `interactive/stop` so the daemon can route the input to the
 * right frame stream and drop stale ids cleanly.
 */
@Serializable
data class InteractiveStartResult(
  val frameStreamId: String,
  /**
   * True when the daemon acquired a held composition for this stream. False means the stream is
   * using the backwards-compatible v1 path where inputs trigger stateless renders.
   */
  val heldSession: Boolean,
  /** Human-readable reason for v1 fallback, when known. */
  val fallbackReason: String? = null,
)

@Serializable data class InteractiveStopParams(val frameStreamId: String)

@Serializable
data class InteractiveInputParams(
  val frameStreamId: String,
  val kind: InteractiveInputKind,
  /** Image-natural pixel coordinates. Daemon translates to dp using the last render's density. */
  val pixelX: Int? = null,
  val pixelY: Int? = null,
  /** Browser wheel delta for `rotaryScroll`; positive means wheel-down. */
  val scrollDeltaY: Float? = null,
  /** For `keyDown` / `keyUp`. */
  val keyCode: String? = null,
)

@Serializable
enum class InteractiveInputKind {
  @SerialName("click") CLICK,
  @SerialName("pointerDown") POINTER_DOWN,
  @SerialName("pointerMove") POINTER_MOVE,
  @SerialName("pointerUp") POINTER_UP,
  @SerialName("rotaryScroll") ROTARY_SCROLL,
  @SerialName("keyDown") KEY_DOWN,
  @SerialName("keyUp") KEY_UP,
}

// ---------------------------------------------------------------------------
// H3 — `history/diff` metadata-mode wire shape. See HISTORY.md § "What this PR
// lands § H3" and PROTOCOL.md § 5 ("history/diff").
//
// Pixel-mode fields (`diffPx`, `ssim`, `diffPngPath`) are reserved on the
// `HistoryDiffResult` shape but always null in METADATA mode — H5 lands the
// full pixel pass. A METADATA caller asking for `mode = PIXEL` receives a
// distinct -32603 error so `null` pixel fields stay unambiguous.
// ---------------------------------------------------------------------------

@Serializable
enum class HistoryDiffMode {
  @SerialName("metadata") METADATA,
  @SerialName("pixel") PIXEL,
}

@Serializable
data class HistoryDiffParams(
  val from: String,
  val to: String,
  val mode: HistoryDiffMode = HistoryDiffMode.METADATA,
)

@Serializable
data class HistoryDiffResult(
  val pngHashChanged: Boolean,
  val fromMetadata: JsonElement,
  val toMetadata: JsonElement,
  // Pixel-mode fields — null in METADATA mode; populated by H5.
  val diffPx: Long? = null,
  val ssim: Double? = null,
  val diffPngPath: String? = null,
)

// ---------------------------------------------------------------------------
// H4 — `history/prune` request + `historyPruned` notification. See HISTORY.md
// § "Pruning policy" + § "historyPruned".
// ---------------------------------------------------------------------------

/**
 * Manual prune trigger. Each parameter is optional and overrides the daemon's configured default
 * for THIS call only — the auto-prune scheduler keeps using its configured defaults. Set any value
 * to `0` or negative to disable that knob (e.g. `maxAgeDays: 0` → no age-based pruning).
 *
 * `dryRun = true` returns the would-remove set without touching disk.
 */
@Serializable
data class HistoryPruneParams(
  val maxEntriesPerPreview: Int? = null,
  val maxAgeDays: Int? = null,
  val maxTotalSizeBytes: Long? = null,
  val dryRun: Boolean = false,
)

@Serializable
data class HistoryPruneSourceResult(val removedEntryIds: List<String>, val freedBytes: Long)

/**
 * Result of `history/prune`. [removedEntries] / [freedBytes] are the cross-source aggregate;
 * [sourceResults] is the per-source breakdown keyed by `HistorySource.id` (only writable sources
 * are listed — read-only git/HTTP sources don't participate in pruning).
 */
@Serializable
data class HistoryPruneResult(
  val removedEntries: List<String>,
  val freedBytes: Long,
  val sourceResults: Map<String, HistoryPruneSourceResult>,
)

/**
 * `historyPruned` notification (HISTORY.md § "historyPruned"). Emitted after each NON-EMPTY prune
 * pass — auto-prune passes that removed nothing produce no notification.
 */
@Serializable
data class HistoryPrunedParams(
  val removedIds: List<String>,
  val freedBytes: Long,
  val reason: PruneReasonWire,
)

@Serializable
enum class PruneReasonWire {
  @SerialName("auto") AUTO,
  @SerialName("manual") MANUAL,
}

// =====================================================================
// 5c. Recording (scripted screen-record) mode — see docs/daemon/RECORDING.md.
//
// A recording session is a held [androidx.compose.ui.ImageComposeScene] driven by a virtual
// frame clock at a fixed `fps`. The agent posts a script of `(tMs, kind, pixelX, pixelY)`
// events; on `recording/stop` the daemon plays the timeline back in virtual time, encoding
// one PNG per frame to `<framesDir>/frame-NNNNN.png`. `recording/encode` then assembles those
// frames into a single video file (APNG in v1; mp4 / webm in v2).
//
// "Feels like normal user time" property: agents that send "click at t=0ms, click at t=500ms"
// produce a video with a full 500 ms of inter-click animation regardless of how long the
// agent took to compose the script. The virtual clock decouples wire latency from playback
// pacing.
// =====================================================================

/**
 * `recording/start` parameters.
 *
 * @property previewId the discovery-time preview id to record. Same shape and resolution path as
 *   `interactive/start.previewId` and `renderNow.previews[i]`.
 * @property fps frames per second at the virtual clock. Must be in `[1, 120]`. Defaults to 30.
 * @property scale output-frame size multiplier. Must be in `(0, 8]`. Defaults to 1.0. Coordinates
 *   on the wire stay in image-natural pixel space — the Skiko surface is scaled at encode time, not
 *   at composition time, so the agent's `pixelX`/`pixelY` always refer to the scene's own
 *   coordinates.
 * @property overrides per-render display overrides applied to the held scene; mirrors
 *   `renderNow.overrides` exactly. Lets a `Button` preview be recorded at `widthPx: 240, heightPx:
 *   80, backgroundColor: 0xFFFFFFFF` (or whatever the agent prefers) without editing source.
 * @property live when `true`, the recording captures real-time interactions instead of replaying a
 *   scripted timeline. The daemon spins a background tick thread at [fps] cadence using a
 *   wall-clock-driven virtual nanoTime; agents (or the panel) post `recording/input` notifications
 *   that the tick loop drains and dispatches at the current virtual `tMs`. Mutually exclusive with
 *   `recording/script` — once a session is allocated `live`, `recording/script` is rejected.
 *   Defaults to `false` (scripted mode); see RECORDING.md § "live mode".
 */
@Serializable
data class RecordingStartParams(
  val previewId: String,
  val fps: Int? = null,
  val scale: Float? = null,
  val overrides: PreviewOverrides? = null,
  val live: Boolean = false,
)

/**
 * `recording/input` notification — fire-and-forget input event for a `live = true` recording.
 * Mirrors [InteractiveInputParams] modulo the routing key (recordingId vs frameStreamId).
 *
 * The daemon's tick loop drains pending events at every frame boundary and stamps them with the
 * current virtual `tMs` (= wall-clock elapsed since `recording/start`); the `pixelX` / `pixelY` are
 * dispatched through the held scene's pointer pipeline at the same virtual nanoTime as the
 * surrounding frame's `scene.render(nanoTime = …)` call.
 *
 * Inputs against a `live = false` (scripted) recording are dropped silently on the daemon side —
 * the analogous wire shape there is `recording/script`.
 */
@Serializable
data class RecordingInputParams(
  val recordingId: String,
  val kind: InteractiveInputKind,
  /** Image-natural pixel coordinates. Daemon translates to dp using the held scene's density. */
  val pixelX: Int? = null,
  val pixelY: Int? = null,
  /** For `keyDown` / `keyUp` (reserved; v1 dispatch is a no-op). */
  val keyCode: String? = null,
)

/**
 * Opaque correlation token returned by `recording/start`. The client passes it back on every
 * subsequent `recording/script`, `recording/stop`, and `recording/encode` so the daemon can route
 * to the right held session.
 */
@Serializable data class RecordingStartResult(val recordingId: String)

@Serializable
enum class RecordingScriptEventStatus {
  @SerialName("applied") APPLIED,
  @SerialName("unsupported") UNSUPPORTED,
}

/** One scripted input/control event on the virtual timeline. */
@Serializable
data class RecordingScriptEvent(
  /** Virtual time offset from `recording/start`, in milliseconds. Must be ≥ 0. */
  val tMs: Long,
  /**
   * Input event wire value (`click`, `pointerDown`, …) or a namespaced data-extension script event
   * id advertised in `ServerCapabilities.dataExtensions[].recordingScriptEvents[]`.
   */
  val kind: String,
  /** Image-natural pixel coordinates. Same coordinate system as `interactive/input`. */
  val pixelX: Int? = null,
  val pixelY: Int? = null,
  /** For `keyDown` / `keyUp` (no-op in v1; reserved for v2 key dispatch). */
  val keyCode: String? = null,
  /** Browser wheel delta for `rotaryScroll`; positive means wheel-down. */
  val scrollDeltaY: Float? = null,
  /** Agent-supplied label for probes and state checkpoints. */
  val label: String? = null,
  /** Agent-supplied checkpoint id for save/restore state markers. */
  val checkpointId: String? = null,
  /**
   * Vestigial after compose-ai-tools#754: the legacy `kind = "lifecycle.event"` / `lifecycleEvent:
   * <transition>` shape was split into per-id events (`lifecycle.pause` / `lifecycle.resume` /
   * `lifecycle.stop`), so no handler reads this field anymore. Retained on the wire as a free-form
   * passthrough — agents that set it for trace context still see it round-trip into
   * [RecordingScriptEvidence.lifecycleEvent]. Future cleanup may remove the field entirely.
   */
  val lifecycleEvent: String? = null,
  /** Optional free-form tags copied into script evidence. */
  val tags: List<String> = emptyList(),
  /**
   * Accessibility-node identifier for `kind = a11y.action.*` events: the visible content
   * description of the target node (`Modifier.semantics { contentDescription = "Save" }` /
   * `Icon(contentDescription = "Save")`). The handler resolves this against the held composition's
   * semantics tree and dispatches the corresponding `SemanticsActions` action — same lookup a
   * screen reader would perform. Ignored for input/probe/state/lifecycle events. Future a11y
   * matchers (visible text, role, tag) will land as sibling fields rather than a generic params map
   * so per-action validation stays typed end-to-end.
   */
  val nodeContentDescription: String? = null,
  /**
   * Multi-axis BySelector-style predicate for `kind = uia.*` events. The shape is `SelectorJson`
   * from `:data-uiautomator-core` — a flat object with optional `text` / `desc` / `clazz` / `res`
   * (plus `*Matches` regex variants), boolean state predicates (`enabled` / `clickable` / …), and
   * tree predicates (`hasChild` / `hasDescendant`). Carried as a `JsonObject` so the daemon hands
   * it to the Android sandbox as a JSON string without parsing into the matcher type at this layer
   * (the matcher lives in `:data-uiautomator-core`, which `:daemon:core` doesn't depend on).
   * Ignored for non-`uia` events.
   */
  val selector: kotlinx.serialization.json.JsonObject? = null,
  /**
   * Mirror of `UiAutomator.findObject(..., useUnmergedTree)` — `false` (default) walks Compose's
   * merged accessibility tree (matches on-device UIAutomator semantics: `By.text + click` targets
   * `Button { Text(...) }` as one node); `true` walks the unmerged tree to reach inner Compose
   * nodes. Ignored for non-`uia` events.
   */
  val useUnmergedTree: Boolean? = null,
  /**
   * Payload for `uia.inputText`: the text to type into the matched editable node. Routed through
   * `SemanticsActions.SetText` (Compose) or `ACTION_SET_TEXT` (View). Ignored for other event
   * kinds.
   */
  val inputText: String? = null,
  /**
   * Payload for `kind = "navigation.deepLink"`: the URI to fire as `Intent(ACTION_VIEW, …)` at the
   * held activity, exercising the consumer's intent-filter / NavController deep-link routing.
   * Ignored for other event kinds.
   */
  val deepLinkUri: String? = null,
  /**
   * Predictive-back progress value (0.0–1.0) for `navigation.predictiveBackStarted` /
   * `navigation.predictiveBackProgressed`. Threaded into the synthesised
   * [`androidx.activity.BackEventCompat`] so animation observers driven by the back-progress flow
   * see the same shape on-device gestures emit. Ignored for other event kinds.
   */
  val backProgress: Float? = null,
  /**
   * Predictive-back swipe edge for `navigation.predictiveBackStarted` /
   * `navigation.predictiveBackProgressed` — `"left"` or `"right"`, mapped sandbox-side to
   * [`androidx.activity.BackEventCompat.EDGE_LEFT`] / `EDGE_RIGHT`. Defaults to `"left"` when
   * absent. Ignored for other event kinds.
   */
  val backEdge: String? = null,
)

@Serializable
data class RecordingScriptParams(val recordingId: String, val events: List<RecordingScriptEvent>)

@Serializable data class RecordingStopParams(val recordingId: String)

@Serializable
data class RecordingScriptEvidence(
  val tMs: Long,
  val kind: String,
  val status: RecordingScriptEventStatus,
  val label: String? = null,
  val checkpointId: String? = null,
  val lifecycleEvent: String? = null,
  val tags: List<String> = emptyList(),
  val message: String? = null,
)

/**
 * Result of `recording/stop`. The daemon has played the script back in virtual time, written one
 * PNG per virtual frame to [framesDir], and freed the held scene.
 */
@Serializable
data class RecordingStopResult(
  /**
   * Number of frames written. Equals `ceil(durationMs * fps / 1000) + 1` (inclusive of frame 0).
   */
  val frameCount: Int,
  /** Virtual duration covered by the recording, in milliseconds — `max(scriptEvent.tMs)` or 0. */
  val durationMs: Long,
  /** Absolute path of the directory containing `frame-NNNNN.png` files. */
  val framesDir: String,
  /** Frame width in pixels, after `scale`. */
  val frameWidthPx: Int,
  /** Frame height in pixels, after `scale`. */
  val frameHeightPx: Int,
  /** Per-script-event execution evidence for input, lifecycle, state, and probe events. */
  val scriptEvents: List<RecordingScriptEvidence> = emptyList(),
)

/**
 * v1 supports only animated PNG (pure JVM, no native deps, plays in every browser/webview). mp4 /
 * webm via `ffmpeg` shell-out land in v2 — the enum is open so new values don't bump
 * `protocolVersion` per PROTOCOL.md § 7.
 */
/**
 * v1 ships APNG (pure-JVM, no native deps); v2 adds [MP4] and [WEBM] via optional `ffmpeg`
 * shell-out. Daemons advertise the formats they actually support via
 * `ServerCapabilities.recordingFormats` so clients can grey out unavailable options without
 * round-tripping a request that would only fail. The enum stays open per PROTOCOL.md § 7 — adding a
 * new variant is additive and does not bump `protocolVersion`.
 */
@Serializable
enum class RecordingFormat {
  @SerialName("apng") APNG,
  @SerialName("mp4") MP4,
  @SerialName("webm") WEBM,
}

@Serializable
data class RecordingEncodeParams(
  val recordingId: String,
  val format: RecordingFormat = RecordingFormat.APNG,
)

@Serializable
data class RecordingEncodeResult(
  /** Absolute path of the encoded video file. */
  val videoPath: String,
  /** MIME type — `image/apng` for APNG; `video/mp4` / `video/webm` once v2 lands those. */
  val mimeType: String,
  val sizeBytes: Long,
)

// =====================================================================
// 5c. Live-frame streaming (`composestream/1`) — buttery follow-up to
// `interactive/*`.
//
// `interactive/*` keeps the composition alive but still publishes each frame
// as a JSON `renderFinished` carrying a re-used `pngPath` on disk. Two
// glitches fall out of that:
//   * the webview swaps `<img src=…>` and the browser blanks the element
//     until the new PNG decodes — that's the "blink" on every input;
//   * the daemon overwrites the same on-disk path every frame, so a busy
//     webview can race a partial write and decode torn bytes.
//
// `stream/start` opts the same held session into a binary-framed stream:
// frames ride on `streamFrame` notifications carrying the bytes inline (no
// reused file path) plus a sequence number, and the client paints into a
// canvas via `createImageBitmap` with a newest-wins queue. See
// `docs/daemon/STREAMING.md` for the wire contract and the rationale.
//
// Additive on the wire (PROTOCOL.md § 7): a daemon that hasn't grown the
// new methods rejects `stream/start` with MethodNotFound and the client
// falls back to the existing `<img>` swap path.
// =====================================================================

@Serializable
enum class StreamCodec {
  /** Raw PNG bytes — same encoding the renderer already produces. The default. */
  @SerialName("png") PNG,
  /**
   * WebP-lossless. Smaller than PNG (typically 30–60% smaller for UI frames) and decoded by every
   * browser via `createImageBitmap`. Opt-in: requires an encoder on the daemon side
   * (`StreamFrameEncoder.WebP`); pre-encoder builds advertise PNG only and downgrade silently.
   */
  @SerialName("webp") WEBP,
}

/**
 * `stream/start` — opens a live frame stream against a held interactive session.
 *
 * The daemon allocates a fresh `frameStreamId`, opens a held session against [previewId] (same
 * machinery `interactive/start` uses — see [InteractiveStartParams]), and emits `streamFrame`
 * notifications on every `renderFinished` for that preview until `stream/stop` arrives.
 *
 * - [codec] requests an encoding. Daemons that don't support the requested codec downgrade to PNG
 *   and report the chosen codec in [StreamStartResult.codec]; the client must inspect that field
 *   rather than assume its requested codec is in use.
 * - [maxFps] caps the emit rate. Bursts of `renderFinished` notifications are coalesced — the
 *   daemon emits the most recent frame at most once per `1000 / maxFps` ms; intermediate frames are
 *   dropped (but their `renderFinished` notifications still flow on the legacy channel for clients
 *   that don't subscribe to streams). `null` means "no cap" (renderer-natural cadence).
 * - [hidpi] hints the renderer to keep the source pixels at the captured density (the default).
 *   `false` lets the encoder downscale to logical density to save bytes — useful for previews of
 *   very high-density devices feeding a small webview.
 */
@Serializable
data class StreamStartParams(
  val previewId: String,
  val codec: StreamCodec? = null,
  val maxFps: Int? = null,
  val hidpi: Boolean? = null,
  /** Mirrors [InteractiveStartParams.inspectionMode]; `null` keeps the v2 default. */
  val inspectionMode: Boolean? = null,
)

/**
 * `stream/start` reply.
 *
 * - [frameStreamId] is the routing key for follow-up `stream/stop` / `stream/visibility` /
 *   `interactive/input` notifications and the value of [StreamFrameParams.frameStreamId].
 * - [codec] is the codec the daemon will actually emit — equal to or downgraded from the client's
 *   requested [StreamStartParams.codec]. Clients pick a decoder off this field.
 * - [heldSession] mirrors [InteractiveStartResult.heldSession]; `false` means the daemon couldn't
 *   acquire a held composition for [StreamStartParams.previewId] and is using the v1 stateless
 *   path. Frames still flow.
 * - [fallbackReason] carries a human-readable string when [heldSession] is `false`.
 */
@Serializable
data class StreamStartResult(
  val frameStreamId: String,
  val codec: StreamCodec,
  val heldSession: Boolean,
  val fallbackReason: String? = null,
)

@Serializable data class StreamStopParams(val frameStreamId: String)

/**
 * `stream/visibility` — fire-and-forget signal the client uses to throttle a stream when the
 * preview card scrolls out of viewport (or the tab becomes hidden). Replaces the old "auto-stop on
 * scroll-out" semantics: the held session stays warm, but the emit rate drops to keyframes only
 * (`fps = 1` by convention) until visibility flips back. Cards that scroll back into view re-paint
 * from the cached last frame immediately, then catch up — no blanking.
 *
 * - [visible] toggles the throttle. `true` = renderer-natural cadence (capped by the `stream/start`
 *   `maxFps`); `false` = throttled.
 * - [fps] overrides the throttled rate. `null` → `1.0` fps when [visible] is false; ignored when
 *   [visible] is true.
 */
@Serializable
data class StreamVisibilityParams(
  val frameStreamId: String,
  val visible: Boolean,
  val fps: Int? = null,
)

/**
 * `streamFrame` notification — one frame on a live stream. Sent by the daemon for every render that
 * survives the per-stream dedup + visibility filters.
 *
 * Wire layout mirrors the binary header documented in `STREAMING.md`:
 * - [frameStreamId] routes to the receiving stream.
 * - [seq] is monotonic per-stream and lets the client drop late frames; sequencing is independent
 *   per stream so multi-target streams stay independent.
 * - [ptsMillis] is the daemon's wall-clock at frame production, suitable for client-side fps and
 *   latency telemetry.
 * - [codec] is the encoding of [payloadBase64]. When [codec] is omitted the frame is an `unchanged`
 *   heartbeat — bytes-identical to the previous frame on this stream — and [payloadBase64] is null.
 *   Sequence numbers are still consumed so clients can drive a "no-op tick" indicator without a
 *   re-decode.
 * - [keyframe] marks the first frame after `stream/start` or after a visibility flip; clients cache
 *   it as the "show on scroll-back-into-view" anchor.
 * - [final] flags the last frame the server will emit on this stream (a `stream/stop` arrived
 *   between the render kicking off and the frame leaving the wire); clients can release decoder
 *   state on receipt.
 */
@Serializable
data class StreamFrameParams(
  val frameStreamId: String,
  val seq: Long,
  val ptsMillis: Long,
  val widthPx: Int,
  val heightPx: Int,
  val codec: StreamCodec? = null,
  val keyframe: Boolean = false,
  val final: Boolean = false,
  /**
   * Frame bytes encoded with [codec], base64'd into the JSON payload. Null when the frame is an
   * `unchanged` heartbeat — see [codec].
   */
  val payloadBase64: String? = null,
)
