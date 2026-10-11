package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.devices.frameDpOverriddenBy
import ee.schimke.composeai.daemon.protocol.DataFetchParams
import ee.schimke.composeai.daemon.protocol.ExtensionsEnableResult
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorProduct
import ee.schimke.composeai.data.layoutinspector.PreviewSlots
import ee.schimke.composeai.data.layoutinspector.PreviewSlotsPayload
import ee.schimke.composeai.data.theme.Material3ThemeProduct
import ee.schimke.composeai.data.theme.ThemePayload
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * A live daemon-backed frame stream. Forward input into the held composition via [input]; [close]
 * tears the stream down. Obtained from [ServeRenderHost.startStream].
 */
public interface StreamHandle : AutoCloseable {
  public fun input(
    kind: InteractiveInputKind,
    pixelX: Int? = null,
    pixelY: Int? = null,
    pointerId: Int? = null,
    scrollDeltaY: Float? = null,
    keyCode: String? = null,
    /** The character a `keyDown` typed, when it produced one (issue #3491). */
    text: String? = null,
    /** `"mouse"` / `"touch"` / `"pen"`; absent means touch (issue #3491). */
    pointerType: String? = null,
  )

  /**
   * Tell the daemon whether this watcher is still looking (tab visible, card in viewport). Hidden
   * watchers keep the session warm at [fps] (default 1) and get a keyframe on return. Default
   * no-op, so backends without the notification degrade to "always visible".
   */
  public fun visibility(visible: Boolean, fps: Int? = null): Unit = Unit
}

/** One servable preview: its id, a human label, and which delivery modes it supports. */
/**
 * One animated capture a preview can offer alongside its still; the viewer surfaces it as an opt-in
 * control, never by default.
 */
public data class ServeMotion(
  /** The route the bytes are served under (`/motion/<id><extension>`). */
  val id: String,
  /** `"interaction"` (a scripted gesture) or `"animation"` (a self-running animation). */
  val kind: String? = null,
  /** The caption the annotation declared: which property the capture shows. */
  val caption: String? = null,
  /** `.apng` or `.gif`. Not interchangeable: an APNG typed as a GIF renders one frame and stops. */
  val extension: String = ".apng",
)

/**
 * One production-composable value parameter published with a catalog component. [type] is a
 * display-only short Kotlin rendering; [composableSlot] marks content slots; [hasDefault] marks
 * optional parameters.
 */
@Serializable
public data class ServeComponentParameter(
  val name: String,
  val type: String,
  val hasDefault: Boolean = false,
  val composableSlot: Boolean = false,
)

public data class ServePreview(
  val id: String,
  val label: String,
  /** Delivery transports available for this preview. Tier 1 is always [PreviewMode.SNAPSHOT]. */
  val modes: List<PreviewMode> = listOf(PreviewMode.SNAPSHOT),
  /** Data products declared for this preview in `previews.json`. */
  val dataProductKinds: Set<String> = emptySet(),
  /**
   * Author-declared editable knobs (`previewOverride*`, the `compose/overrides` payload), from a
   * bundle's `previews/<id>.overrides.json`. Empty when none are declared or carried.
   */
  val overrides: List<ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration> = emptyList(),
  /**
   * Remote Compose named-value knobs (`compose/remotecompose`), from
   * `previews/<id>.remotecompose.json`; edits round-trip through the `rc.<name>=…` override.
   * Distinct from [overrides], the plain-Compose surface.
   */
  val remoteComposeKnobs:
    List<ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration> =
    emptyList(),
  /**
   * Whether this preview carries `@FocusedPreview`, so the viewer offers keyboard focus only where
   * it does something (daemon-only `focus` override).
   */
  val supportsFocus: Boolean = false,
  /**
   * Whether this preview carries `@GestureHintPreview`. The override is Android-only, so the viewer
   * gates the control to Android-backed sessions.
   */
  val supportsGestures: Boolean = false,
  /**
   * Whether this preview's subject is a theme (`@FixedTheme`, or synthesised from a theme catalog).
   * Re-rendering it under a `themeProvider` would destroy what it documents, so the theme axis
   * keeps the baked pixels. Per-preview counterpart of [section] == `"Themes"`.
   */
  val fixedTheme: Boolean = false,
  /**
   * Whether this is a second-tier variant cell (`@OverrideVariant(secondary = true)`): it renders
   * and keeps its URL but is not listed in the variant tree, so exhaustive kit sets stay navigable.
   */
  val secondary: Boolean = false,
  /**
   * The baked component state this render represents (`"pressed"`, `"disabled"`, …), or
   * null/`"default"`. From `previews/variants.json`; folds states into one card with a switcher.
   */
  val state: String? = null,
  /** The baked theme (`"light"`/`"dark"`), or null when unthemed; scopes the state switcher. */
  val theme: String? = null,
  /**
   * The i18n / content / a11y variant axis this render represents (`{"locale":"ar-XB"}`, …), or
   * null/empty for the default. Folded onto the component's card like [state].
   */
  val props: JsonObject? = null,
  /**
   * The declared breakpoint this render was captured at (`"192dp"`, `"compact"`, …), or null when
   * the catalog declares none. Non-primary sizes fold onto the component's card with a size
   * switcher.
   */
  val size: String? = null,
  /**
   * The top-level section (tab) this preview belongs to; a catalog with sections renders tabbed,
   * with [group] as sub-heading. Null for a flat catalog.
   */
  val section: String? = null,
  /** The sub-heading group within a [section]; null means ungrouped. */
  val group: String? = null,
  /**
   * The preview's position in the catalog's authored order, used instead of id order for tabs,
   * groups and cards. Null for a plain bundle.
   */
  val catalogOrder: Int? = null,
  /**
   * Module-relative source path ([ee.schimke.composeai.previewdata.PreviewInfo.sourceFile]), for
   * linking to GitHub when the session has delivery provenance. Null when unknown.
   */
  val sourceFile: String? = null,
  /**
   * Gradle project path that owns [sourceFile]; older single-module catalogs use the catalog-wide
   * module.
   */
  val sourceModule: String? = null,
  /**
   * A 1-based line inside this preview's function body within [sourceFile], so the playground can
   * seed just this declaration rather than the whole file. Null means whole-file.
   */
  val bodyLine: Int? = null,
  /** Discovery-time `@Preview(uiMode=…)`; used to identify the baked Day/Night default. */
  val uiMode: Int = 0,
  /**
   * Discovery-time `@Preview(showBackground/backgroundColor)`: the preview's own stated backdrop,
   * fed to `PreviewBackdrop`. Defaults to the annotation's defaults, so hosts without
   * `previews.json` defer to the catalog stage.
   */
  val showBackground: Boolean = false,
  /** See [showBackground]. `0` means unset — the annotation's own default. */
  val backgroundColor: Long = 0L,
  /**
   * The catalog's original component identifier (`Button/Filled`), keeping casing and word
   * boundaries that the route-safe [id] loses. Null for plain bundles and live discovery.
   */
  val componentId: String? = null,
  /**
   * The catalog's one-line component description (`@CatalogComponent(caption = …)`), shown under
   * the name and as a tooltip. Null when none is authored.
   */
  val caption: String? = null,
  /** Published render failure for a catalog card that has no PNG. */
  val renderFailure: CatalogRenderFailure? = null,
  /**
   * Font families the `compose/figma-svg` export couldn't name, so text was exported as
   * missing-glyph boxes (from `previews/<id>.figma-fonts.warnings.json`). Empty is healthy;
   * surfaced on the sticker.
   */
  val lostFontFamilies: List<String> = emptyList(),
  /**
   * Animated captures published for this preview; opt-in extra surface, never a replacement for the
   * still. Last in the list: callers construct [ServePreview] positionally.
   */
  val motion: List<ServeMotion> = emptyList(),
  /**
   * The device frame this preview renders into, or null for a plain rectangle. Last for
   * positional-call compatibility; see [motion].
   */
  val deviceFrame: ServeDeviceFrame? = null,
  /**
   * Whether this preview carries a portable `scene.json` plus panel textures for the spatial/WebXR
   * viewer. Last for positional-call compatibility; see [motion].
   */
  val spatial: Boolean = false,
  /**
   * The production composable's ordered value parameters; empty for plain bundles and older
   * catalogs. Last for positional-call compatibility; see [motion].
   */
  val componentParameters: List<ServeComponentParameter> = emptyList(),
)

/**
 * What a preview's `@Preview(device = …)` resolves to, reduced to what a clip needs. Resolved once
 * here because device shape lookup (catalog ids, `spec:`, `parent=`) is easy to get wrong.
 */
@Serializable
public data class ServeDeviceFrame(
  val widthDp: Double? = null,
  val heightDp: Double? = null,
  val isRound: Boolean = false,
) {
  public companion object {

    /**
     * The frame for a preview's `@Preview` params, or null when it names no device.
     *
     * Dimensions use [frameDpOverriddenBy] (both axes or neither, matching the renderer). Roundness
     * always comes from the device string separately: [DeviceDimensions.resolve] reports `isRound =
     * false` whenever explicit dimensions are passed.
     */
    public fun from(device: String?, widthDp: Int?, heightDp: Int?): ServeDeviceFrame? {
      val named = device?.takeIf { it.isNotBlank() } ?: return null
      val resolved = DeviceDimensions.resolve(named)
      val (w, h) = resolved.frameDpOverriddenBy(widthDp, heightDp)
      return ServeDeviceFrame(
        widthDp = w.toDouble(),
        heightDp = h.toDouble(),
        isRound = resolved.isRound,
      )
    }
  }
}

/** Structured, catalog-published render failure. Additive to `design-parity-catalog/v1`. */
@Serializable
public data class CatalogRenderFailure(
  val id: String = "",
  val componentId: String? = null,
  val preview: String? = null,
  val phase: String = "render",
  val errorClass: String = "RenderError",
  val message: String = "",
  val stackTrace: String? = null,
  val topAppFrame: RenderFailureFrame? = null,
  val mode: String? = null,
  val state: String? = null,
  val props: JsonObject? = null,
  val section: String? = null,
  val group: String? = null,
  val sourceFile: String? = null,
)

@Serializable
public data class RenderFailureFrame(
  val file: String = "",
  val line: Int = 0,
  val function: String = "",
)

/**
 * Per-preview feature support folded across a discovery entry's captures: keyboard focus
 * (`@FocusedPreview`) and one-handed gestures (`@GestureHintPreview`).
 */
public fun detectedFeaturesOf(
  preview: ee.schimke.composeai.previewdata.PreviewInfo
): Pair<Boolean, Boolean> {
  val focus = preview.captures.any { it.focus != null || it.focusGif != null }
  val gestures = preview.captures.any { it.gestureHint != null }
  return focus to gestures
}

/**
 * One app-declared `@ThemeCatalog` theme any preview can be rendered under. Module-global, so it
 * hangs off [ServeHost.declaredThemes]. [providerFqn] is sent verbatim as the `themeProvider`
 * override; [group] buckets related themes.
 */
public data class ServeTheme(
  val name: String,
  val providerFqn: String,
  val group: String? = null,
  /** Light/dark mode implied by this theme, when its name or provider is unambiguous. */
  val mode: String? = inferredThemeMode(name, providerFqn),
)

internal fun inferredThemeMode(name: String, providerFqn: String): String? {
  val words =
    "$name $providerFqn"
      .replace(Regex("([a-z])([A-Z])"), "$1 $2")
      .split(Regex("[^A-Za-z]+"))
      .map { it.lowercase() }
      .toSet()
  val light = "light" in words
  val dark = "dark" in words
  return when {
    light && !dark -> "light"
    dark && !light -> "dark"
    else -> null
  }
}

/**
 * Lift the module's `@ThemeCatalog` / `@WearThemeCatalog` themes out of a discovery manifest's
 * synthetic `THEME_CATALOG` / `WEAR_THEME_CATALOG` previews (provider FQN on
 * `params.wrapperClassName`). Entries without an FQN are skipped; deduped by FQN.
 */
public fun declaredThemesFromPreviews(
  previews: List<ee.schimke.composeai.previewdata.PreviewInfo>
): List<ServeTheme> =
  previews
    .filter { it.params.kind == "THEME_CATALOG" || it.params.kind == "WEAR_THEME_CATALOG" }
    .mapNotNull { p ->
      val fqn = p.params.wrapperClassName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      ServeTheme(
        name = p.params.name?.takeIf { it.isNotBlank() } ?: p.functionName.ifBlank { p.id },
        providerFqn = fqn,
        group = p.params.group?.takeIf { it.isNotBlank() },
        mode =
          inferredThemeMode(
            p.params.name?.takeIf { it.isNotBlank() } ?: p.functionName.ifBlank { p.id },
            fqn,
          ) ?: if (p.params.kind == "WEAR_THEME_CATALOG") "dark" else null,
      )
    }
    .distinctBy { it.providerFqn }

/** Result of a snapshot render request. */
public sealed interface RenderOutcome {
  public data class Ok(
    val png: ByteArray,
    /** How these bytes were produced, exposed on HTTP responses for remote diagnosis. */
    val generation: Generation = Generation.DAEMON,
  ) : RenderOutcome

  public enum class Generation(public val wire: String) {
    /** Read directly from a published bundle; no renderer was involved in this request. */
    BAKED("baked"),
    /** Reused from the catalog host's theme cache, which survives per-preview daemon eviction. */
    CATALOG_CACHE("catalog-cache"),
    /** Reused from the daemon host's in-memory override cache. */
    DAEMON_CACHE("daemon-cache"),
    /** Produced by a daemon render during this request. */
    DAEMON("daemon"),
    /**
     * A player's published render from the catalog's `rc-compare` staging, answering a bare
     * `?rcPlayer=<wire>` browse. Not [BAKED]: these bytes are the requested player's output, and
     * BAKED would turn the request into a refusal.
     */
    RC_PUBLISHED("rc-published"),
  }

  /** No such preview id in this session's module. */
  public data object NotFound : RenderOutcome

  /** The render was attempted but rejected / failed / timed out. [reason] is human-readable. */
  public data class Failed(val reason: String) : RenderOutcome

  /**
   * The per-daemon render lock was held (a cold Android render can hold it for minutes), so this
   * request backed off rather than pin an HTTP render slot. Not an error: serve baked or retry. See
   * [ServeRenderHost.DAEMON_BUSY_WAIT_MS].
   */
  public data object Busy : RenderOutcome
}

/** Result of a figma-svg render request — the SVG counterpart of [RenderOutcome]. */
public sealed interface SvgOutcome {
  public data class Ok(
    val svg: ByteArray,
    /**
     * How these bytes were produced, exposed for remote diagnosis. [RenderOutcome.Generation.BAKED]
     * lets the HTTP layer notice an override-bearing request answered without a renderer.
     */
    val generation: RenderOutcome.Generation = RenderOutcome.Generation.DAEMON,
  ) : SvgOutcome

  /** No such preview id, or this host can't produce SVG (a static bundle has no daemon). */
  public data object NotFound : SvgOutcome

  /** The render or SVG export was attempted but failed. [reason] is human-readable. */
  public data class Failed(val reason: String) : SvgOutcome
}

/** Result of a preview-slots request ([PreviewSlotsPayload] JSON). */
public sealed interface SlotsOutcome {
  public data class Ok(val json: ByteArray) : SlotsOutcome

  /** No such preview id, or this host can't extract slots (a static bundle has no daemon). */
  public data object NotFound : SlotsOutcome

  /** The render or semantics fetch was attempted but failed. [reason] is human-readable. */
  public data class Failed(val reason: String) : SlotsOutcome
}

/**
 * Result of a design-annotation request: typography + theme layers from the render's semantics tree
 * ([ServeDesignAnnotations]).
 */
public sealed interface AnnotationsOutcome {
  public data class Ok(val json: ByteArray) : AnnotationsOutcome

  /** No such preview id, or this host has no daemon to capture a semantics tree. */
  public data object NotFound : AnnotationsOutcome

  /** The render or semantics fetch was attempted but failed. [reason] is human-readable. */
  public data class Failed(val reason: String) : AnnotationsOutcome
}

/**
 * Result of an accessibility-overlay request: merged `a11y/hierarchy` + `a11y/atf` +
 * `a11y/touchTargets` JSON.
 */
public sealed interface A11yOutcome {
  public data class Ok(val json: ByteArray) : A11yOutcome

  /** No such preview id, or this host has no daemon to produce a11y data products. */
  public data object NotFound : A11yOutcome

  /** The a11y re-render / fetch was attempted but failed. [reason] is human-readable. */
  public data class Failed(val reason: String) : A11yOutcome
}

/**
 * Long-lived, thread-safe wrapper around one [RenderSession] for the `compose-preview serve` HTTP
 * server; the long-lived sibling of [ee.schimke.composeai.cli.MatrixRenderFetcher].
 *
 * Holds no per-client state. [RenderSession] isn't thread-safe and the daemon renders one at a
 * time, so renders funnel through [renderLock], and [cache] (keyed by preview + overrides)
 * coalesces repeats. Bound to a module, so switching previews is just another request.
 */
public class ServeRenderHost
internal constructor(
  /**
   * Opens the daemon session on first use, not at construction, so registered catalogs don't spawn
   * JVMs nobody asked for; browsing needs only the manifest data passed to the constructor.
   */
  openSession: () -> RenderSession,
  override val previews: List<ServePreview>,
  /** Human label for this tenant (e.g. the module's Gradle path); shown in the served pages. */
  override val label: String = "",
  /** App-declared `@ThemeCatalog` themes discovered for this module (module-global). */
  override val declaredThemes: List<ServeTheme> = emptyList(),
  private val fileSystem: FileSystem = SystemFileSystem,
  private val onLog: (String) -> Unit = {},
  private val renderTimeoutSeconds: Long = RENDER_TIMEOUT_SECONDS,
  private val frameRenderTimeoutSeconds: Long = FRAME_RENDER_TIMEOUT_SECONDS,
  /** True when the caller handed over an already-open session (a live subprocess from birth). */
  private val sessionAlreadyOpen: Boolean = false,
  /**
   * Extra sentence for a fatal breaker trip, given the failure text (see [RenderCircuitBreaker]);
   * [Companion.open] uses it to name the Skiko pair a Skia link error resolves to.
   */
  private val linkageDiagnosis: (String) -> String? = { null },
) : ServeHost {

  /**
   * Wrap an already-open session. The lazies below are shared with the deferred path, so their RPCs
   * fire on first access.
   */
  // `public`: `:cli`'s `BundleRenderKnobTest` wraps a fake session from another module. Wrapping an
  // owned session is fine to expose; spawning one is not.
  public constructor(
    session: RenderSession,
    previews: List<ServePreview>,
    label: String = "",
    declaredThemes: List<ServeTheme> = emptyList(),
    fileSystem: FileSystem = SystemFileSystem,
    onLog: (String) -> Unit = {},
    renderTimeoutSeconds: Long = RENDER_TIMEOUT_SECONDS,
    frameRenderTimeoutSeconds: Long = FRAME_RENDER_TIMEOUT_SECONDS,
  ) : this(
    { session },
    previews,
    label,
    declaredThemes,
    fileSystem,
    onLog,
    renderTimeoutSeconds,
    frameRenderTimeoutSeconds,
    sessionAlreadyOpen = true,
  )

  /**
   * Registered inside [sessionDelegate]'s initializer so it exists before any render is issued;
   * otherwise an early `renderFinished` would be lost.
   */
  private var notificationHandle: AutoCloseable? = null

  /**
   * Guards opening against [close], so a close during the handshake can't miss the subprocess that
   * appears a moment later (a catalog refresh hits exactly this race).
   */
  private val sessionLock = Any()

  /**
   * Set when [openSession] is entered. `Lazy.isInitialized()` stays false for the whole cold open
   * (tens of seconds on Android) even though the subprocess already exists.
   */
  private val sessionOpening = AtomicBoolean(false)

  private val sessionDelegate =
    lazy(sessionLock) {
      sessionOpening.set(true)
      val opened = openSession()
      if (closed.get()) {
        // Lost the race with [close]: tear the subprocess down here. Still publish the dead session
        // so capability reads on a retiring host don't throw; real use gets a closed-transport
        // error.
        runCatching { opened.close() }
      } else {
        notificationHandle = opened.onNotification(::onDaemonNotification)
      }
      opened
    }

  private val session: RenderSession by sessionDelegate

  /** Whether the daemon subprocess has actually been started, checked without waking it. */
  /** One subprocess, once it exists. */
  override val daemonProcessCount: Int
    get() = if (daemonStarted) 1 else 0

  override val daemonStarted: Boolean
    get() = sessionAlreadyOpen || sessionOpening.get() || sessionDelegate.isInitialized()

  // A daemon backs this host, so an override edit actually re-renders (unlike a static bundle).
  override val canApplyOverrides: Boolean = true

  // The daemon registers export and inspection products inactive (fetching them first fails `-32020
  // kind not advertised`), so enable them once on open. `hasSvgExport` etc. read what was enabled.
  // Best-effort: a failed enable disables these optional surfaces.
  private val extensionEnableResult: ExtensionsEnableResult by lazy {
    // Resolve the session outside the `runCatching`: an open failure must not be cached as "no
    // exports"; propagating leaves the lazy uninitialized so the next caller retries.
    val opened = session
    runCatching {
      opened.enableExtensions(
        listOf(
          ComposeFigmaSvgProduct.KIND,
          ComposeFigmaSvgProduct.KIND_LONG,
          SCROLL_EXTENSION_ID,
          A11Y_EXTENSION_ID,
          ComposeSemanticsProduct.KIND,
          LayoutInspectorProduct.KIND,
          THEME_EXTENSION_ID,
        )
      )
    }
      .getOrElse { e ->
        onLog("export and inspection data unavailable: enable failed: ${e.message}")
        ExtensionsEnableResult.Builder()
          .also {
            it.unknown =
              listOf(
                ComposeFigmaSvgProduct.KIND,
                ComposeFigmaSvgProduct.KIND_LONG,
                SCROLL_EXTENSION_ID,
                A11Y_EXTENSION_ID,
                ComposeSemanticsProduct.KIND,
                LayoutInspectorProduct.KIND,
                THEME_EXTENSION_ID,
              )
          }
          .build()
      }
  }

  override val hasSvgExport: Boolean by lazy {
    ComposeFigmaSvgProduct.KIND !in extensionEnableResult.unknown
  }

  override val hasScrollExport: Boolean by lazy {
    SCROLL_EXTENSION_ID !in extensionEnableResult.unknown &&
      extensionEnableResult.dataProducts.any { it.kind == SCROLL_LONG_KIND }
  }

  // Rides the same `extensions/enable`; a backend without it reports `unknown`, hiding the control.
  override val hasA11yOverlay: Boolean by lazy {
    A11Y_EXTENSION_ID !in extensionEnableResult.unknown
  }

  override fun hasScrollExportFor(previewId: String): Boolean =
    hasScrollExport &&
      previews.firstOrNull { it.id == previewId }?.dataProductKinds?.contains(SCROLL_LONG_KIND) ==
        true

  /**
   * Run the one-shot `extensions/enable` before any data-product fetch: every fetch lane needs it,
   * not just those that happen to read a capability flag first (e.g. [ServeCatalogLiveHost] routes
   * `renderA11y` to a per-preview daemon nobody else enabled).
   *
   * Returns null on success, or why the daemon could not be opened. That open throws through the
   * lazy (so it can be retried), so each lane must convert it into its own failure outcome.
   */
  private fun ensureExtensionsEnabled(): String? =
    try {
      extensionEnableResult
      null
    } catch (e: Exception) {
      "inspection data unavailable: ${e.message}".also(onLog)
    }

  // Gesture override is Android-only; read the daemon's advertised capabilities.
  override val gesturesRenderable: Boolean by lazy {
    "gestures" in session.initializeResult.capabilities.supportedOverrides
  }

  // The Remote Compose `player` override only matters on the Android backend (the only one with the
  // runtime); read the daemon's declared backend.
  override val remoteComposePlayerSelectable: Boolean by lazy {
    session.initializeResult.capabilities.backend ==
      ee.schimke.composeai.daemon.protocol.BackendKind.ANDROID
  }

  /**
   * Offers [RcPlayerBackend.ANDROIDX_VIEW] / [RcPlayerBackend.ANDROIDX_EMBEDDED] for a Remote
   * Compose preview when the backend honours the player override, plus
   * [RcPlayerBackend.CAMAELON_JS] when the `.rc` document is available.
   */
  override fun enabledRcPlayersFor(previewId: String): List<RcPlayerBackend> {
    val isRemoteCompose =
      hasRemoteComposeDoc(previewId) ||
        previews.firstOrNull { it.id == previewId }?.remoteComposeKnobs?.isNotEmpty() == true
    if (!isRemoteCompose) return emptyList()
    return buildList {
      if (hasRemoteComposeDoc(previewId)) add(RcPlayerBackend.CAMAELON_JS)
      if (remoteComposePlayerSelectable) {
        add(RcPlayerBackend.ANDROIDX_VIEW)
        add(RcPlayerBackend.ANDROIDX_EMBEDDED)
        // Not [RcPlayerBackend.CMP_ANDROID] yet: the daemon doesn't advertise which players it can
        // resolve, and older ones refuse it. A hand-typed `?rcPlayer=cmp-android` still reaches the
        // daemon.
      }
      // Inert for a daemon-only host, kept so this can't drift from the other implementations.
      bakedRcPlayerBackend(previewId)?.let { if (it !in this) add(it) }
    }
  }

  private val previewIds: Set<String> = previews.map { it.id }.toHashSet()

  // Decodes streamFrame notification params for the live-stream lane (startStream).
  private val streamJson = Json { ignoreUnknownKeys = true }

  // Bounded LRU of rendered PNGs keyed by ServeOverrides.cacheKey.
  private val cache = LruByteCache(MAX_CACHE_ENTRIES)

  // The figma-svg counterpart of [cache], keyed the same way (previewId × overrides).
  private val svgCache = LruByteCache(MAX_CACHE_ENTRIES)

  // The full-page (scrolling) figma-svg counterpart of [svgCache], keyed the same way.
  private val scrollSvgCache = LruByteCache(MAX_CACHE_ENTRIES)

  // The full-page raster counterpart of [scrollSvgCache], keyed by preview id + overrides.
  private val scrollPngCache = LruByteCache(MAX_CACHE_ENTRIES)

  // The preview-slots counterpart of [cache], keyed the same way (previewId × overrides).
  private val slotsCache = LruByteCache(MAX_CACHE_ENTRIES)

  // The accessibility-overlay counterpart of [cache], keyed the same way (previewId × overrides).
  private val a11yCache = LruByteCache(MAX_CACHE_ENTRIES)

  // The typography/theme inspection-layer counterpart of [cache], keyed the same way.
  private val annotationsCache = LruByteCache(MAX_CACHE_ENTRIES)

  // Tolerates additive schema fields in fetched semantics payloads.
  private val dataJson = Json { ignoreUnknownKeys = true }

  // Fair, so a waiting interactive render beats the background prewarm re-acquiring the lock.
  private val renderLock = ReentrantLock(/* fair= */ true)

  // Serve-side render-latency accounting for `/status` (`renderStats`) — see [RenderPerfStats].
  private val perfStats = RenderPerfStats()

  /**
   * Stops re-attempting renders this host has proved it cannot serve (linkage faults, sustained
   * failure rates). See [RenderCircuitBreaker].
   */
  private val breaker = RenderCircuitBreaker(linkageDiagnosis = linkageDiagnosis)

  override fun renderPerfStats(): RenderPerfSnapshot =
    perfStats.snapshot().copy(breaker = breaker.snapshot())

  override fun renderBreaker(): RenderBreakerSnapshot? = breaker.snapshot()

  /**
   * An open breaker is host-wide, so it latches every preview; HTTP answers a terminal 409 instead
   * of a retryable busy.
   */
  override fun renderFailureLatch(previewId: String, overrides: PreviewOverrides): String? =
    breaker.peekReason()

  /**
   * An open breaker means no working live lane. Reads only the breaker, so unopened catalogs are
   * unaffected.
   */
  override val hasLiveStream: Boolean
    get() = breaker.peekReason() == null

  /** Publishes the open breaker as a session degradation, so `/status` says WHY it went dark. */
  override val degradations: List<ServeDegradation>
    get() =
      breaker.snapshot()?.let { listOf(ServeDegradation.renderLaneBroken(it.reason, it.fatal)) }
        ?: emptyList()

  /** Record a failed render against both the perf counters and the breaker. */
  private fun recordFailure(durationMs: Long, timeout: Boolean, reason: String) {
    perfStats.recordFailed(durationMs, timeout = timeout, reason = reason)
    breaker.recordFailure(reason)
  }

  // Set under renderLock before each renderNow; the single in-flight render's notification fills
  // pngPath and trips it.
  private val pendingLatch = AtomicReference<CountDownLatch?>(null)
  private val pendingPreviewId = AtomicReference<String?>(null)
  private val pendingPngPath = AtomicReference<String?>(null)

  // Set when the in-flight render ends with `renderFailed`, so a broken preview fails immediately
  // instead of sleeping out the render budget.
  private val pendingFailure = AtomicReference<String?>(null)

  // Timed-out renders per preview id whose `renderFinished` is still owed. Notifications carry no
  // correlation id, so a late event would otherwise complete the next same-id render with the wrong
  // PNG; the daemon delivers them reliably and in order, so drain one per timeout.
  private val staleRenders = ConcurrentHashMap<String, Int>()

  // The first render pays cold start and gets [renderTimeoutSeconds]; later frames are capped at
  // [frameRenderTimeoutSeconds] so a wedged render can't hold the slot.
  private val warmedUp = AtomicBoolean(false)

  // The first override-bearing render is also effectively cold (real recomposition) even when a
  // background prewarm already set [warmedUp], so it also gets the generous budget.
  private val overridesWarmedUp = AtomicBoolean(false)

  // Fans one upstream daemon stream out to all watchers of the same preview/overrides/codec/fps.
  private val broadcast = ServeBroadcastHub(::startStream)

  private val closed = AtomicBoolean(false)

  /** Daemon render lifecycle events; a method so [sessionDelegate] can register it on open. */
  private fun onDaemonNotification(method: String, params: JsonObject?) {
    if (params == null) return
    val isFinished = method == "renderFinished"
    val isFailed = method == "renderFailed"
    if (!isFinished && !isFailed) return
    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return
    // Drain a timed-out render's late terminal event (finished or failed) so it can't complete a
    // fresh same-id render.
    if ((staleRenders[id] ?: 0) > 0) {
      staleRenders.compute(id) { _, v -> ((v ?: 0) - 1).takeIf { it > 0 } }
      return
    }
    if (id != pendingPreviewId.get()) return
    if (isFinished) {
      // `unchanged` renders still carry a (re-used) pngPath, so this captures bytes either way.
      params["pngPath"]?.jsonPrimitive?.contentOrNull?.let { pendingPngPath.set(it) }
    } else {
      // `renderFailed` must complete the wait too, or [render] sleeps out the whole cold budget
      // holding [renderLock] for a render the daemon already reported dead.
      pendingFailure.set(
        params["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
          ?: "daemon reported renderFailed"
      )
    }
    pendingLatch.get()?.countDown()
  }

  /** Render [previewId] at [overrides], serving a cached result when one exists. Thread-safe. */
  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return RenderOutcome.NotFound

    val key = ServeOverrides.cacheKey(previewId, overrides)
    cache.get(key)?.let {
      perfStats.recordCacheHit()
      return RenderOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
    }

    // The breaker already knows this daemon can't serve; answer that before taking the lock, so a
    // broken daemon doesn't push others into Busy. Rate trips allow one probe per cooldown.
    breaker.blockedReason()?.let {
      perfStats.recordShortCircuit()
      return RenderOutcome.Failed(it)
    }

    // Perf accounting for `/status`: the clock starts at the cache miss; "cold" means no render has
    // completed yet.
    val perfStartNs = System.nanoTime()
    val coldAtEntry = !warmedUp.get()
    fun perfElapsedMs(): Long = (System.nanoTime() - perfStartNs) / 1_000_000

    // Bounded acquire: don't pin the caller's HTTP slot behind a minutes-long cold render; back off
    // to Busy so it serves baked.
    if (!renderLock.tryLock(DAEMON_BUSY_WAIT_MS, TimeUnit.MILLISECONDS)) {
      perfStats.recordBusy()
      return RenderOutcome.Busy
    }
    try {
      // Double-check: another request may have filled the cache while we waited for the lock.
      cache.get(key)?.let {
        perfStats.recordCacheHit()
        return RenderOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
      }

      // The daemon rejects an override render while one for the same preview is still flagged in
      // flight (cleared just after `renderFinished`); honour its resubmit contract with bounded
      // backoff.
      var attempt = 0
      while (true) {
        val latch = CountDownLatch(1)
        pendingLatch.set(latch)
        pendingPreviewId.set(previewId)
        pendingPngPath.set(null)
        pendingFailure.set(null)

        val ack =
          try {
            session.renderNow(
              previewIds = listOf(previewId),
              reason = "serve",
              overrides = overrides,
              timeout = RENDER_ACK_TIMEOUT,
            )
          } catch (e: RenderSessionException) {
            val reason = "renderNow failed: ${e.message}"
            onLog(reason)
            recordFailure(perfElapsedMs(), timeout = false, reason = reason)
            return RenderOutcome.Failed(reason)
          }

        val rejected = ack.rejected.firstOrNull { it.id == previewId }
        if (rejected != null) {
          if (rejected.reason.startsWith("coalesced") && attempt++ < MAX_COALESCED_RETRIES) {
            Thread.sleep(COALESCED_RETRY_BACKOFF_MS)
            continue
          }
          val reason = "render rejected: ${rejected.reason}"
          onLog(reason)
          recordFailure(perfElapsedMs(), timeout = false, reason = reason)
          return RenderOutcome.Failed(reason)
        }

        // Cold budget for the first render and the first override render; per-frame cap after that.
        val hasOverrides = overrides != PreviewOverrides()
        val warmForThisRender = warmedUp.get() && (!hasOverrides || overridesWarmedUp.get())
        val budget = if (warmForThisRender) frameRenderTimeoutSeconds else renderTimeoutSeconds
        val completed =
          try {
            latch.await(budget, TimeUnit.SECONDS)
          } catch (_: InterruptedException) {
            // A tighter caller bound still leaves a terminal event owed, so quarantine it like a
            // timeout before releasing the lock. `CountDownLatch` checks interruption first: if the
            // event already arrived, don't record it as outstanding.
            if (latch.count > 0) staleRenders.merge(previewId, 1, Int::plus)
            Thread.currentThread().interrupt()
            perfStats.recordBusy()
            return RenderOutcome.Busy
          }
        if (!completed) {
          // The daemon still owes this render a `renderFinished`; record it so it gets drained.
          staleRenders.merge(previewId, 1, Int::plus)
          val reason = "timed out after ${budget}s waiting for render"
          onLog(reason)
          recordFailure(perfElapsedMs(), timeout = true, reason = reason)
          return RenderOutcome.Failed(reason)
        }
        // `renderFailed`: fail immediately. No [staleRenders] entry (the event was delivered), and
        // [warmedUp] is unchanged since a failure proves nothing about warmth.
        pendingFailure.get()?.let { failure ->
          val reason = "render failed: $failure"
          onLog(reason)
          recordFailure(perfElapsedMs(), timeout = false, reason = reason)
          return RenderOutcome.Failed(reason)
        }
        warmedUp.set(true)
        if (hasOverrides) overridesWarmedUp.set(true)
        break
      }

      val path = pendingPngPath.get()
      val bytes =
        path
          ?.toPath()
          ?.takeIf { fileSystem.exists(it) }
          ?.let { p -> fileSystem.read(p) { readByteArray() } }
      if (bytes == null) {
        val reason = "render produced no PNG"
        onLog(reason)
        recordFailure(perfElapsedMs(), timeout = false, reason = reason)
        return RenderOutcome.Failed(reason)
      }

      cache.put(key, bytes)
      perfStats.recordOk(perfElapsedMs(), cold = coldAtEntry)
      breaker.recordOk()
      return RenderOutcome.Ok(bytes)
    } finally {
      renderLock.unlock()
    }
  }

  /**
   * Render [previewId] at [overrides] and return its `compose/figma-svg` export, cached.
   * Thread-safe.
   *
   * The daemon writes the SVG to a shared per-preview path during the PNG render, so the render
   * (with the PNG cache entry evicted) and the fetch happen in one [renderLock] critical section.
   */
  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return SvgOutcome.NotFound
    // No figma-svg producer: 404 instead of a `-32020` fetch failure.
    if (!hasSvgExport) return SvgOutcome.NotFound

    val key = ServeOverrides.cacheKey(previewId, overrides)
    svgCache.get(key)?.let {
      return SvgOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
    }

    return renderLock.withLock {
      svgCache.get(key)?.let {
        return@withLock SvgOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
      }

      // Force a fresh render so the shared SVG file on disk matches these overrides.
      cache.remove(key)
      when (val pngOutcome = render(previewId, overrides)) {
        RenderOutcome.NotFound -> return@withLock SvgOutcome.NotFound
        is RenderOutcome.Failed -> return@withLock SvgOutcome.Failed(pngOutcome.reason)
        // Unreachable (render() re-enters the held lock); callers fall back to baked on any non-Ok.
        RenderOutcome.Busy -> return@withLock SvgOutcome.Failed("daemon busy")
        is RenderOutcome.Ok -> {} // rendered; the SVG for these overrides is now on disk
      }

      val svgPath =
        try {
          session.fetchData(previewId, ComposeFigmaSvgProduct.KIND).path?.toPath()
        } catch (e: Exception) {
          val reason = "figma-svg fetch failed: ${e.message}"
          onLog(reason)
          return@withLock SvgOutcome.Failed(reason)
        }
      val raw =
        svgPath
          ?.takeIf { fileSystem.exists(it) }
          ?.let { p -> fileSystem.read(p) { readByteArray() } }
      if (raw == null) {
        val reason = "render produced no SVG"
        onLog(reason)
        return@withLock SvgOutcome.Failed(reason)
      }

      // Inline figma-raster crops so the SVG is self-contained for Figma's importer.
      val bytes = inlineRasters(svgPath, raw)
      svgCache.put(key, bytes)
      SvgOutcome.Ok(bytes)
    }
  }

  /**
   * Render [previewId]'s full-page figma-svg (`compose/figma-svg-long`) at [overrides], cached.
   * Thread-safe.
   *
   * The fetch itself drives the re-render, so no separate PNG render is needed. The output file is
   * shared per preview, so [overrides] travel in the fetch params with
   * [DataFetchParams.PARAM_FORCE_RERENDER], and the read happens under [renderLock].
   */
  override fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return SvgOutcome.NotFound
    // No figma-svg producer: 404, as in [renderSvg].
    if (!hasSvgExport) return SvgOutcome.NotFound

    val key = ServeOverrides.cacheKey(previewId, overrides)
    scrollSvgCache.get(key)?.let {
      return SvgOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
    }

    return renderLock.withLock {
      scrollSvgCache.get(key)?.let {
        return@withLock SvgOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
      }

      // Force a fresh full-page render at these overrides and read it under the held lock.
      val fetchParams = buildJsonObject {
        put(DataFetchParams.PARAM_FORCE_RERENDER, JsonPrimitive(true))
        put(
          DataFetchParams.PARAM_OVERRIDES,
          Json.encodeToJsonElement(PreviewOverrides.serializer(), overrides),
        )
      }
      val svgPath =
        try {
          session
            .fetchData(previewId, ComposeFigmaSvgProduct.KIND_LONG, params = fetchParams)
            .path
            ?.toPath()
        } catch (e: Exception) {
          val reason = "figma-svg-long fetch failed: ${e.message}"
          onLog(reason)
          return@withLock SvgOutcome.Failed(reason)
        }
      val raw =
        svgPath
          ?.takeIf { fileSystem.exists(it) }
          ?.let { p -> fileSystem.read(p) { readByteArray() } }
      if (raw == null) {
        val reason = "render produced no full-page SVG"
        onLog(reason)
        return@withLock SvgOutcome.Failed(reason)
      }

      val bytes = inlineRasters(svgPath, raw)
      scrollSvgCache.put(key, bytes)
      SvgOutcome.Ok(bytes)
    }
  }

  /**
   * Fetch the daemon's tall raster scroll product at [overrides]; like [renderScrollSvg], each miss
   * forces an override-aware re-render read under [renderLock].
   */
  override fun renderScrollPng(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return RenderOutcome.NotFound
    // The scroll registry is registered inactive too, and this lane reads no capability of its own.
    ensureExtensionsEnabled()?.let {
      return RenderOutcome.Failed(it)
    }

    val key = ServeOverrides.cacheKey(previewId, overrides)
    scrollPngCache.get(key)?.let {
      return RenderOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
    }

    return renderLock.withLock {
      scrollPngCache.get(key)?.let {
        return@withLock RenderOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
      }

      val fetchParams = buildJsonObject {
        put(DataFetchParams.PARAM_FORCE_RERENDER, JsonPrimitive(true))
        put(
          DataFetchParams.PARAM_OVERRIDES,
          Json.encodeToJsonElement(PreviewOverrides.serializer(), overrides),
        )
      }
      val pngPath =
        try {
          session.fetchData(previewId, SCROLL_LONG_KIND, params = fetchParams).path?.toPath()
        } catch (e: Exception) {
          val reason = "scroll-long fetch failed: ${e.message}"
          onLog(reason)
          return@withLock RenderOutcome.Failed(reason)
        }
      val bytes =
        pngPath
          ?.takeIf { fileSystem.exists(it) }
          ?.let { p -> fileSystem.read(p) { readByteArray() } }
      if (bytes == null) {
        val reason = "render produced no full-page PNG"
        onLog(reason)
        return@withLock RenderOutcome.Failed(reason)
      }

      scrollPngCache.put(key, bytes)
      RenderOutcome.Ok(bytes)
    }
  }

  /**
   * Inline a hybrid SVG's `figma-raster/<node>.png` crops as `data:` URIs so it is self-contained.
   */
  private fun inlineRasters(svgPath: okio.Path, raw: ByteArray): ByteArray {
    val dir = svgPath.parent ?: return raw
    return inlineFigmaRasters(fileSystem, dir, raw.decodeToString()).encodeToByteArray()
  }

  /**
   * Render [previewId] at [overrides] and return its declared preview slots (`dp-slot:<name>`
   * markers, see [PreviewSlots]) as [PreviewSlotsPayload] JSON, cached. Thread-safe. Same
   * render-then-fetch-under-lock pattern as [renderSvg], since the semantics file is shared.
   */
  override fun renderSlots(previewId: String, overrides: PreviewOverrides): SlotsOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return SlotsOutcome.NotFound
    // `compose/semantics` is registered inactive like the rest; this lane reads no capability.
    ensureExtensionsEnabled()?.let {
      return SlotsOutcome.Failed(it)
    }

    val key = ServeOverrides.cacheKey(previewId, overrides)
    slotsCache.get(key)?.let {
      return SlotsOutcome.Ok(it)
    }

    return renderLock.withLock {
      slotsCache.get(key)?.let {
        return@withLock SlotsOutcome.Ok(it)
      }

      // Force a fresh render so the shared semantics file on disk matches these overrides.
      cache.remove(key)
      when (val pngOutcome = render(previewId, overrides)) {
        RenderOutcome.NotFound -> return@withLock SlotsOutcome.NotFound
        is RenderOutcome.Failed -> return@withLock SlotsOutcome.Failed(pngOutcome.reason)
        // Unreachable (render() re-enters the held lock); kept exhaustive.
        RenderOutcome.Busy -> return@withLock SlotsOutcome.Failed("daemon busy")
        is RenderOutcome.Ok -> {} // rendered; the semantics for these overrides is now on disk
      }

      val payload =
        try {
          fetchSemantics(previewId)
        } catch (e: Exception) {
          val reason = "compose/semantics fetch failed: ${e.message}"
          onLog(reason)
          return@withLock SlotsOutcome.Failed(reason)
        } ?: return@withLock SlotsOutcome.Failed("render produced no semantics")

      val slots = PreviewSlots.extractSlots(payload)
      val json =
        dataJson
          .encodeToString(
            PreviewSlotsPayload.serializer(),
            PreviewSlotsPayload.Builder(previewId, slots).build(),
          )
          .encodeToByteArray()
      slotsCache.put(key, json)
      SlotsOutcome.Ok(json)
    }
  }

  /**
   * Render [previewId] at [overrides] and return its inspection layers plus the
   * [ServeSemanticsTags] tag index as JSON, cached. Thread-safe. Same pattern as [renderSlots];
   * both projections read one payload, so they always agree.
   *
   * Not yet coupled to `/render/<id>.png`: this re-renders, so a non-deterministic preview's bounds
   * may not match pixels the client already shows. See the "same render" requirement in
   * [COMPONENT_PARITY_WORKFLOW.md](../../../../../../../../docs/design/COMPONENT_PARITY_WORKFLOW.md).
   */
  override fun renderAnnotations(
    previewId: String,
    // Ignored: all layers come from one capture, and the contract permits a superset.
    overrides: PreviewOverrides,
    @Suppress("UNUSED_PARAMETER") layers: Set<String>?,
  ): AnnotationsOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return AnnotationsOutcome.NotFound
    // Enable extensions first; an unopenable daemon becomes this request's failure.
    ensureExtensionsEnabled()?.let {
      return AnnotationsOutcome.Failed(it)
    }

    val key = ServeOverrides.cacheKey(previewId, overrides)
    annotationsCache.get(key)?.let {
      return AnnotationsOutcome.Ok(it)
    }

    return renderLock.withLock {
      annotationsCache.get(key)?.let {
        return@withLock AnnotationsOutcome.Ok(it)
      }

      cache.remove(key)
      // Container layers use the layout tree when available (catches padding/gaps without
      // semantics); older daemons fall back to the semantics tree.
      val captureLayout = LayoutInspectorProduct.KIND !in extensionEnableResult.unknown
      val captureTheme = THEME_EXTENSION_ID !in extensionEnableResult.unknown
      if (captureTheme) {
        runCatching { session.subscribeData(previewId, Material3ThemeProduct.KIND) }
          .onFailure { onLog("compose/theme subscription failed: ${it.message}") }
      }
      try {
        when (val pngOutcome = render(previewId, overrides)) {
          RenderOutcome.NotFound -> return@withLock AnnotationsOutcome.NotFound
          is RenderOutcome.Failed -> return@withLock AnnotationsOutcome.Failed(pngOutcome.reason)
          RenderOutcome.Busy -> return@withLock AnnotationsOutcome.Failed("daemon busy")
          is RenderOutcome.Ok -> {} // rendered; the semantics for these overrides is now on disk
        }

        val payload =
          try {
            fetchSemantics(previewId)
          } catch (e: Exception) {
            val reason = "compose/semantics fetch failed: ${e.message}"
            onLog(reason)
            return@withLock AnnotationsOutcome.Failed(reason)
          } ?: return@withLock AnnotationsOutcome.Failed("render produced no semantics")
        val theme = if (captureTheme) fetchTheme(previewId, overrides) else null
        val layout = if (captureLayout) fetchLayout(previewId) else null

        val json =
          ServeAnnotationsPayload.encode(
            previewId,
            ServeDesignAnnotations.annotations(payload, theme, layout),
            ServeSemanticsTags.index(payload),
          )
        annotationsCache.put(key, json)
        AnnotationsOutcome.Ok(json)
      } finally {
        if (captureTheme) {
          runCatching { session.unsubscribeData(previewId, Material3ThemeProduct.KIND) }
            .onFailure { onLog("compose/theme unsubscribe failed: ${it.message}") }
        }
      }
    }
  }

  /**
   * Fetch and decode the freshly written `compose/semantics` tree (inline or on-disk), or null.
   * Callers hold [renderLock].
   */
  private fun fetchSemantics(previewId: String): ComposeSemanticsPayload? {
    val result = session.fetchData(previewId, ComposeSemanticsProduct.KIND)
    result.payload?.let {
      return dataJson.decodeFromJsonElement(ComposeSemanticsPayload.serializer(), it)
    }
    val path = result.path?.toPath()?.takeIf { fileSystem.exists(it) } ?: return null
    val text = fileSystem.read(path) { readUtf8() }
    return dataJson.decodeFromString(ComposeSemanticsPayload.serializer(), text)
  }

  /**
   * Fetch the `layout/inspector` tree; null drops container layers back to semantics. Callers hold
   * [renderLock].
   */
  private fun fetchLayout(previewId: String): LayoutInspectorPayload? = runCatching {
    val result = session.fetchData(previewId, LayoutInspectorProduct.KIND)
    result.payload?.let {
      return@runCatching dataJson.decodeFromJsonElement(
        LayoutInspectorPayload.serializer(),
        it,
      )
    }
    val path = result.path?.toPath()?.takeIf { fileSystem.exists(it) } ?: return@runCatching null
    dataJson.decodeFromString(
      LayoutInspectorPayload.serializer(),
      fileSystem.read(path) { readUtf8() },
    )
  }
    .onFailure { onLog("layout/inspector fetch failed: ${it.message}") }
    .getOrNull()

  /** The theme captured by the same subscribed render as [fetchSemantics], when available. */
  private fun fetchTheme(previewId: String, overrides: PreviewOverrides): ThemePayload? =
    runCatching {
      val params = buildJsonObject {
        put(
          DataFetchParams.PARAM_OVERRIDES,
          Json.encodeToJsonElement(PreviewOverrides.serializer(), overrides),
        )
      }
      val result =
        session.fetchData(previewId, Material3ThemeProduct.KIND, inline = true, params = params)
      result.payload?.let { dataJson.decodeFromJsonElement(ThemePayload.serializer(), it) }
        ?: result.path
          ?.toPath()
          ?.takeIf { fileSystem.exists(it) }
          ?.let { path ->
            dataJson.decodeFromString(
              ThemePayload.serializer(),
              fileSystem.read(path) { readUtf8() },
            )
          }
    }
    .onFailure { onLog("compose/theme fetch failed: ${it.message}") }
    .getOrNull()

  /**
   * Fetch [previewId]'s accessibility products at [overrides], merged for the viewer overlay:
   * ```json
   * {"previewId":"…","nodes":[…],"findings":[…],"touchTargets":[…]}
   * ```
   *
   * `nodes` is `a11y/hierarchy`; `findings` (`a11y/atf`) and `touchTargets` are Android-only and
   * contribute empty arrays elsewhere. The products are shared per-preview files from an
   * `a11y`-mode render, so a miss forces a re-render under [renderLock]; results are cached.
   */
  override fun renderA11y(previewId: String, overrides: PreviewOverrides): A11yOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return A11yOutcome.NotFound
    // Enable extensions first. A backend reporting the extension `unknown` gets NotFound (404)
    // rather than a `-32020` 500.
    ensureExtensionsEnabled()?.let {
      return A11yOutcome.Failed(it)
    }
    if (!hasA11yOverlay) return A11yOutcome.NotFound

    val key = ServeOverrides.cacheKey(previewId, overrides)
    a11yCache.get(key)?.let {
      return A11yOutcome.Ok(it)
    }

    return renderLock.withLock {
      a11yCache.get(key)?.let {
        return@withLock A11yOutcome.Ok(it)
      }

      val fetchParams = buildJsonObject {
        put(DataFetchParams.PARAM_FORCE_RERENDER, JsonPrimitive(true))
        put(
          DataFetchParams.PARAM_OVERRIDES,
          Json.encodeToJsonElement(PreviewOverrides.serializer(), overrides),
        )
      }
      // The hierarchy is the overlay itself; the other two only decorate it.
      val hierarchy =
        try {
          fetchA11yProduct(previewId, A11Y_HIERARCHY_KIND, fetchParams)
        } catch (e: Exception) {
          val reason = "a11y/hierarchy fetch failed: ${e.message}"
          onLog(reason)
          return@withLock A11yOutcome.Failed(reason)
        } ?: return@withLock A11yOutcome.Failed("render produced no accessibility hierarchy")

      val json =
        dataJson
          .encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
              put("previewId", JsonPrimitive(previewId))
              put("nodes", arrayField(hierarchy, "nodes"))
              put("findings", arrayField(optionalA11yProduct(previewId, A11Y_ATF_KIND), "findings"))
              put(
                "touchTargets",
                arrayField(optionalA11yProduct(previewId, A11Y_TOUCH_TARGETS_KIND), "targets"),
              )
            },
          )
          .encodeToByteArray()
      a11yCache.put(key, json)
      A11yOutcome.Ok(json)
    }
  }

  /** Fetch an a11y product (inline or on-disk), or null. Callers hold [renderLock]. */
  private fun fetchA11yProduct(previewId: String, kind: String, params: JsonObject?): JsonObject? {
    val result = session.fetchData(previewId, kind, inline = true, params = params)
    result.payload?.let {
      return it as? JsonObject
    }
    val path = result.path?.toPath()?.takeIf { fileSystem.exists(it) } ?: return null
    val text = fileSystem.read(path) { readUtf8() }
    return dataJson.parseToJsonElement(text) as? JsonObject
  }

  /**
   * [fetchA11yProduct] for an optional (Android-only) kind; failures are swallowed. No forced
   * re-render: the hierarchy fetch already did it.
   */
  private fun optionalA11yProduct(previewId: String, kind: String): JsonObject? =
    try {
      fetchA11yProduct(previewId, kind, params = null)
    } catch (e: Exception) {
      onLog("$kind unavailable for '$previewId': ${e.message}")
      null
    }

  /** [obj]'s [name] array, or an empty one when the product was absent or shaped differently. */
  private fun arrayField(obj: JsonObject?, name: String): JsonArray =
    obj?.get(name) as? JsonArray ?: JsonArray(emptyList())

  /**
   * Try to open a daemon-backed live stream for [previewId]; frames go to [onFrame] and the handle
   * forwards input and closes the stream. Returns null when unsupported, so the caller falls back
   * to per-frame [render]. Runs independently of the snapshot render lock.
   */
  public fun startStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec? = null,
    maxFps: Int? = null,
    onUnavailable: ((String) -> Unit)? = null,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) {
      onUnavailable?.invoke("daemon has no preview '$previewId'")
      return null
    }

    // Register the listener before `stream/start`: the initial keyframe can arrive before the RPC
    // returns. Buffer frames until the frameStreamId is known.
    val frameStreamIdRef = AtomicReference<String?>(null)
    val pending = ArrayList<StreamFrameParams>()
    val listener = session.onNotification { method, params ->
      if (method != "streamFrame" || params == null) return@onNotification
      val frame =
        try {
          streamJson.decodeFromJsonElement(StreamFrameParams.serializer(), params)
        } catch (_: Exception) {
          return@onNotification
        }
      val known = frameStreamIdRef.get()
      if (known != null) {
        if (frame.frameStreamId == known) onFrame(frame)
        return@onNotification
      }
      // id not yet known — buffer under lock, re-checking in case it was just set.
      synchronized(pending) {
        if (frameStreamIdRef.get() == null) {
          pending.add(frame)
          return@onNotification
        }
      }
      if (frame.frameStreamId == frameStreamIdRef.get()) onFrame(frame)
    }

    val result =
      try {
        session.streamStart(
          previewId = previewId,
          codec = codec,
          maxFps = maxFps,
          overrides = overrides,
        )
      } catch (e: Exception) {
        // Unsupported or daemon error: degrade, and pass the daemon's message to [onUnavailable] so
        // the viewer can show why input isn't live.
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        onLog("stream/start unavailable for $previewId ($reason); falling back to snapshots")
        onUnavailable?.invoke(reason)
        runCatching { listener.close() }
        return null
      }

    if (!result.heldSession) {
      // The daemon couldn't hold an interactive session, so fall back to snapshots; prefer its
      // `fallbackReason` for the viewer.
      val reason =
        result.fallbackReason?.takeIf { it.isNotBlank() }
          ?: "the daemon could not hold an interactive session for this preview"
      onLog("stream/start for $previewId has no held session ($reason); falling back to snapshots")
      onUnavailable?.invoke(reason)
      runCatching { listener.close() }
      runCatching { session.streamStop(result.frameStreamId) }
      return null
    }

    val frameStreamId = result.frameStreamId
    // Publish the id and replay any frames that arrived before it was known.
    val replay: List<StreamFrameParams>
    synchronized(pending) {
      frameStreamIdRef.set(frameStreamId)
      replay = pending.filter { it.frameStreamId == frameStreamId }
      pending.clear()
    }
    replay.forEach(onFrame)

    return object : StreamHandle {
      private val handleClosed = AtomicBoolean(false)

      override fun input(
        kind: InteractiveInputKind,
        pixelX: Int?,
        pixelY: Int?,
        pointerId: Int?,
        scrollDeltaY: Float?,
        keyCode: String?,
        text: String?,
        pointerType: String?,
      ) {
        if (handleClosed.get()) return
        runCatching {
          session.interactiveInput(
            frameStreamId,
            kind,
            pixelX,
            pixelY,
            pointerId,
            scrollDeltaY,
            keyCode,
            text,
            pointerType,
          )
        }
      }

      override fun visibility(visible: Boolean, fps: Int?) {
        if (handleClosed.get()) return
        // Optional: older daemons throw here, which must not break a working stream.
        runCatching { session.streamVisibility(frameStreamId, visible, fps) }
      }

      override fun close() {
        if (!handleClosed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        runCatching { session.streamStop(frameStreamId) }
      }
    }
  }

  /**
   * Join the shared live stream for [previewId], one upstream stream per preview + overrides +
   * codec + fps fanned out to every watcher. Prefer this over [startStream] for clients. Null when
   * unsupported.
   */
  override fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onUnavailable: ((String) -> Unit)?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? {
    check(!closed.get()) { "ServeRenderHost is closed" }
    return broadcast.subscribe(
      previewId,
      overrides,
      codec,
      maxFps,
      onUnavailable = onUnavailable,
      onFrame = onFrame,
    )
  }

  /** Live shared upstream streams (one per distinct preview/overrides/codec/fps). Diagnostics. */
  override fun activeStreamCount(): Int = broadcast.activeStreamCount()

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    // Don't touch `session` on a never-used host: it would spawn a subprocess just to kill it.
    synchronized(sessionLock) {
      // Not `isInitialized()`: a host wrapping an already-open session owns a subprocess from
      // birth.
      if (!daemonStarted) return
      try {
        notificationHandle?.close()
      } catch (_: Exception) {
        // best effort
      }
      session.close()
    }
  }

  public companion object {
    // Public because `:server` call sites are in another module; not a widened API by intent.
    public const val SCROLL_LONG_KIND: String = "render/scroll/long"
    internal const val SCROLL_EXTENSION_ID = "scroll"

    // Wire strings for the a11y extension and its product kinds (the CLI doesn't depend on
    // `:data-a11y-core`). Desktop advertises no `a11y/touchTargets` and returns empty `a11y/atf`.
    internal const val A11Y_EXTENSION_ID = "a11y"
    internal const val A11Y_HIERARCHY_KIND = "a11y/hierarchy"
    internal const val A11Y_ATF_KIND = "a11y/atf"
    internal const val A11Y_TOUCH_TARGETS_KIND = "a11y/touchTargets"
    internal const val THEME_EXTENSION_ID = "data/theme"
    /** RPC ack budget for the (fast, queue-only) `renderNow` call itself. */
    private val RENDER_ACK_TIMEOUT = 60.seconds

    /**
     * Cold-start render budget. Android/Robolectric first renders are much slower, so it is
     * overridable via `-Dcomposeai.serve.renderTimeoutSeconds=<n>`.
     */
    private val RENDER_TIMEOUT_SECONDS: Long =
      System.getProperty("composeai.serve.renderTimeoutSeconds")?.toLongOrNull()?.coerceAtLeast(1)
        ?: 180L

    /**
     * Per-frame render budget once warm, overridable via
     * `-Dcomposeai.serve.frameRenderTimeoutSeconds=<n>`.
     */
    private val FRAME_RENDER_TIMEOUT_SECONDS: Long =
      System.getProperty("composeai.serve.frameRenderTimeoutSeconds")
        ?.toLongOrNull()
        ?.coerceAtLeast(1) ?: 10L

    /**
     * Bounded retries when the daemon coalesces an in-flight override render; only needs to outlast
     * it clearing its flag after `renderFinished`.
     */
    private const val MAX_COALESCED_RETRIES = 50
    private const val COALESCED_RETRY_BACKOFF_MS = 100L

    /**
     * How long a render waits for [renderLock] before reporting [RenderOutcome.Busy]. Waiting out a
     * minutes-long cold render would pin shared HTTP render slots; a couple of seconds still rides
     * out a warm re-emit.
     */
    private const val DAEMON_BUSY_WAIT_MS = 2_000L

    private const val MAX_CACHE_ENTRIES = 256

    /**
     * Open a long-lived session against a daemon launch descriptor and wrap it. Does not spawn the
     * daemon: [RenderSessionException] surfaces at the first request that needs it.
     */
    public fun open(
      descriptorPath: File,
      workspaceRoot: File,
      workspaceName: String,
      previews: List<ServePreview>,
      label: String = "",
      declaredThemes: List<ServeTheme> = emptyList(),
      systemPropertyOverrides: Map<String, String> = emptyMap(),
      onLog: (String) -> Unit = {},
      factory: RenderSessionFactory = SubprocessRenderSessions,
    ): ServeRenderHost {
      val config =
        RenderSessionConfig(
          descriptorPath = descriptorPath,
          workspaceRoot = workspaceRoot.absoluteFile,
          workspaceName = workspaceName.ifBlank { workspaceRoot.name },
          systemPropertyOverrides = systemPropertyOverrides,
          logSink = onLog,
        )
      return ServeRenderHost(
        openSession = { factory.open(config) },
        previews = previews,
        label = label,
        declaredThemes = declaredThemes,
        onLog = onLog,
        // Diagnoses in order of specificity, since this is the only report outside readers see:
        // split Skiko pair, attributed classpath gap, Remote Compose family split, and last the
        // unattributed gap (which matches any missing coordinate, related or not).
        linkageDiagnosis = { reason ->
          SkikoNativePairing.linkageDiagnosis(reason, descriptorPath)
            ?: BundleClasspathGaps.attributedDiagnosis(reason, descriptorPath)
            ?: RemoteComposePairing.linkageDiagnosis(reason, descriptorPath)
            ?: BundleClasspathGaps.unattributedDiagnosis(reason, descriptorPath)
        },
      )
    }
  }
}

/** Minimal thread-safe LRU byte cache (access-order [LinkedHashMap] under a lock). */
private class LruByteCache(private val maxEntries: Int) {
  private val map =
    object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
        size > maxEntries
    }

  @Synchronized fun get(key: String): ByteArray? = map[key]

  @Synchronized
  fun put(key: String, value: ByteArray) {
    map[key] = value
  }

  @Synchronized
  fun remove(key: String) {
    map.remove(key)
  }
}
