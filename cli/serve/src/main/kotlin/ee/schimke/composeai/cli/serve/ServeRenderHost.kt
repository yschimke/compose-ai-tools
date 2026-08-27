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
interface StreamHandle : AutoCloseable {
  fun input(
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
   * Tell the daemon whether this watcher is still looking at the stream (tab visible, card in
   * viewport). A hidden watcher keeps its held session warm but the daemon drops to [fps] (default
   * 1) for both the emit gate *and* the render loop behind it, and repaints from a keyframe as soon
   *    as visibility comes back.
   *
   * Default no-op so a handle from a backend without the notification (an older daemon, a test
   * double) degrades to "always visible" rather than failing the socket.
   */
  fun visibility(visible: Boolean, fps: Int? = null) = Unit
}

/** One servable preview: its id, a human label, and which delivery modes it supports. */
/**
 * One animated capture a preview can offer instead of its still.
 *
 * Motion answers a question a screenshot cannot: whether a component's own interaction plumbing
 * actually drives its transition, and how that transition is shaped. It is also not what most
 * readers came for — so the viewer surfaces this as a control beside the still rather than playing
 * it by default, and a preview carrying none is presented exactly as it was before.
 */
data class ServeMotion(
  /** The route the bytes are served under (`/motion/<id><extension>`). */
  val id: String,
  /** `"interaction"` (a scripted gesture) or `"animation"` (a self-running animation). */
  val kind: String? = null,
  /**
   * The caption the annotation declared — which property the reader is being shown. Without it a
   * capture tells someone only that *something* moved, so the viewer shows it alongside.
   */
  val caption: String? = null,
  /** `.apng` or `.gif`. Not interchangeable: an APNG typed as a GIF renders one frame and stops. */
  val extension: String = ".apng",
)

data class ServePreview(
  val id: String,
  val label: String,
  /** Delivery transports available for this preview. Tier 1 is always [PreviewMode.SNAPSHOT]. */
  val modes: List<PreviewMode> = listOf(PreviewMode.SNAPSHOT),
  /** Data products declared for this preview in `previews.json`. */
  val dataProductKinds: Set<String> = emptySet(),
  /**
   * The author-declared editable knobs this preview exposed via `previewOverride*` (the
   * `compose/overrides` payload). Populated from a bundle's `previews/<id>.overrides.json` sidecar
   * so the viewer can present editable controls (label / list length / per-item indexed values).
   * Empty when the preview declared none (or the host doesn't carry them).
   */
  val overrides: List<ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration> = emptyList(),
  /**
   * The Remote Compose named-value knobs this preview declared during its render (the
   * `compose/remotecompose` editable surface). Populated from a bundle's
   * `previews/<id>.remotecompose.json` sidecar so the viewer can present a control per knob (text
   * field / colour swatch / slider) whose edits round-trip through the `rc.<name>=…` serve
   * override. Empty when the preview binds no named values through the declaring
   * `rememberOverridableRemote*` wrappers (or the host doesn't carry them). Distinct from
   * [overrides], which is the plain-Compose `previewOverride*` surface.
   */
  val remoteComposeKnobs:
    List<ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration> =
    emptyList(),
  /**
   * Whether this preview supports **keyboard focus** — it carries `@FocusedPreview` (discovery
   * emits a `focus` capture). Lets the viewer offer a "Keyboard focus" control *only* for previews
   * that actually have something focusable, rather than as a dead control everywhere. Applied live
   * via the `focus` override (daemon-only); the desktop daemon honours it.
   */
  val supportsFocus: Boolean = false,
  /**
   * Whether this preview supports **one-handed (wear) gestures** — it carries `@GestureHintPreview`
   * (discovery emits a `gestureHint` capture). Surfaced for detection, but the gesture *override*
   * is Android-only (the desktop daemon behind `serve` ignores `overrides.gestures`), so the viewer
   * gates the control to Android-backed sessions.
   */
  val supportsGestures: Boolean = false,
  /**
   * Whether this preview's **subject is a theme** — it carries `@FixedTheme`, or discovery
   * synthesised it from a `@ThemeCatalog` / `@WearThemeCatalog`. Re-rendering such a card under a
   * `themeProvider` override destroys the very thing it documents, so the landing keeps its baked
   * pixels for the theme axis (the same "no themed base" path a card with no daemon twin takes).
   *
   * The catalog-wide counterpart is [section] == `"Themes"`, which speaks for a whole tab; this is
   * the per-preview signal for a specimen that lives outside such a tab.
   */
  val fixedTheme: Boolean = false,
  /**
   * The baked component **state** this preview render represents — `"unchecked"`, `"pressed"`,
   * `"disabled"`, `"unselected"`, … — or `null`/`"default"` for the default render (and for plain
   * bundles / app screens that carry no state). Carried from the catalog's `previews/variants.json`
   * manifest so the grid can fold non-default states into one card and the viewer can offer a state
   * switcher. Null keeps the current behaviour everywhere.
   */
  val state: String? = null,
  /**
   * The baked **theme** this preview render represents — `"light"`/`"dark"` — or `null` when the
   * render is unthemed (or a plain bundle). Also from `previews/variants.json`; used to scope the
   * viewer's state switcher to same-theme siblings.
   */
  val theme: String? = null,
  /**
   * The i18n / content / a11y **variant axis** this render represents — `{"locale":"ar-XB"}`,
   * `{"direction":"rtl"}`, `{"fontScale":"2.0"}`, `{"content":"icon+label"}`, … — or `null`/empty
   * for the component's default render. From the catalog's `previews/variants.json`; lets the grid
   * fold these variants onto the component's one card (like [state], rather than a tile each) and
   * the viewer offer a variant switcher. Null keeps the current behaviour everywhere.
   */
  val props: JsonObject? = null,
  /**
   * The declared **breakpoint** this render was captured at — `"192dp"`, `"compact"`,
   * `"smallRound"`, … — from the catalog's `previews/variants.json`, which carries the `size` name
   * the spec's `breakpoints` table declares. Null when the catalog declares no breakpoints (or for
   * a plain bundle), which is every catalog that had no size axis before.
   *
   * A size is a different *rendering* of one component rather than a different component, so the
   * grid folds the non-primary sizes onto that component's one card — the same treatment [state]
   * and [props] get — and the viewer offers them as a size switcher. Without this the axis is
   * invisible to the server: five breakpoints publish five identically-named cards, which is what a
   * five-size Wear catalog actually did (wear-m3-catalog#41).
   */
  val size: String? = null,
  /**
   * The top-level **section** (tab) this preview belongs to — `"Themes"`, `"Components"`,
   * `"Screens"`, `"Animations"`, … — from the catalog's `previews/variants.json`. Drives the
   * landing page's tab bar: a catalog whose previews carry sections renders tabbed, one tab per
   * distinct section, with [group] as a sub-heading inside a tab. Null for a plain (untabbed)
   * catalog / uploaded bundle, which stays a flat grid.
   */
  val section: String? = null,
  /**
   * The sub-heading **group** within a [section] (e.g. `"Foundation"`, `"Contacts"`), from
   * `previews/variants.json`. Rendered as a labelled sub-group inside its section's tab panel. Null
   * ⇒ the section's cards are ungrouped.
   */
  val group: String? = null,
  /**
   * The preview's position in the catalog's **authored** component order, from
   * `previews/variants.json`. [ServeBundleHost] lists previews sorted by id, so the landing uses
   * this to order tabs, sub-groups, and cards by authoring intent (Themes before Components before
   * Screens, …) rather than alphabetically. Null for a plain bundle (no ordering metadata).
   */
  val catalogOrder: Int? = null,
  /**
   * Module-relative source path of the file this preview was authored in
   * (`src/main/kotlin/…/Foo.kt`), from the bundle's `previews.json` manifest
   * ([ee.schimke.composeai.previewdata.PreviewInfo.sourceFile]). Lets the viewer link the preview
   * to its source on GitHub when the session carries delivery provenance (repo + branch). Null for
   * a bundle without a manifest, a preview whose manifest entry recorded no source path, or a
   * live/local session with no published source to point at.
   */
  val sourceFile: String? = null,
  /**
   * Gradle project path that owns [sourceFile]. Repository-wide catalogs set this per preview;
   * older single-module catalogs use their catalog-wide source module instead.
   */
  val sourceModule: String? = null,
  /**
   * A 1-based line inside this preview's function body within [sourceFile], from the bundle's
   * `previews.json` ([ee.schimke.composeai.previewdata.PreviewInfo.bodyLine]).
   *
   * Lets the playground handoff seed the editor with the one declaration that was clicked rather
   * than the whole file it shares with its group — a section file is one *group*, so opening
   * `Button/Filled` otherwise hands over four other components too. Null for a bundle without a
   * manifest, or one produced before discovery recorded it; the seed is then whole-file, as it
   * always was.
   */
  val bodyLine: Int? = null,
  /** Discovery-time `@Preview(uiMode=…)`; used to identify the baked Day/Night default. */
  val uiMode: Int = 0,
  /**
   * Discovery-time `@Preview(showBackground = …)` / `@Preview(backgroundColor = …)`.
   *
   * Carried for one reason: they are the two rungs where the *preview itself* states the ground it
   * wants, and without them a page can only infer one from the catalog's stage or from a variant
   * name — which is how a catalog full of `showBackground = false` stickers and one deliberately
   * white specimen ended up presented identically. Fed to `PreviewBackdrop`; see [ServeWeb]'s
   * backdrop resolution. Both default to the annotation's own defaults, so a host with no
   * `previews.json` behind it (a plain uploaded bundle) simply contributes no opinion and the
   * catalog stage answers, exactly as before.
   */
  val showBackground: Boolean = false,
  /** See [showBackground]. `0` means unset — the annotation's own default. */
  val backgroundColor: Long = 0L,
  /**
   * The catalog's original component identifier (`SessionDetails`, `Button/Filled`, …). Unlike
   * [id], this retains word boundaries and casing after the image path is flattened into a
   * route-safe slug. Null for ordinary uploaded bundles and live discovery previews.
   */
  val componentId: String? = null,
  /**
   * The one-line description the catalog authored for this preview's component — what it is FOR, in
   * the design system's own words (`@CatalogComponent(caption = …)`). Surfaced by the viewer under
   * the component's name and as the component drawer's tooltip, so a reader who does not already
   * know what "Button Loading" means can find out without opening the source. Null for a catalog
   * that authors none, and for a plain uploaded bundle.
   */
  val caption: String? = null,
  /** Published render failure for a catalog card that has no PNG. */
  val renderFailure: CatalogRenderFailure? = null,
  /**
   * Animated captures published for this preview. Empty for the overwhelming majority — a still is
   * the whole artifact for most components — so a viewer treats this as opt-in extra surface and
   * never as a replacement for the baked pixels.
   *
   * Last in the parameter list deliberately: several callers construct a [ServePreview]
   * positionally, so a new field anywhere earlier silently rebinds their arguments.
   */
  val motion: List<ServeMotion> = emptyList(),
  /**
   * The device frame this preview renders into, when it names one — the raw material for the
   * device-frame clip. Null for the ordinary case: a preview with no `device =` renders into a
   * plain rectangle and every pixel of it is screen.
   *
   * Also last in the parameter list, for the reason [motion] is.
   */
  val deviceFrame: ServeDeviceFrame? = null,
)

/**
 * What a preview's `@Preview(device = …)` resolves to, reduced to what a clip needs.
 *
 * Resolved once here rather than carried as the raw device string, because working out whether a
 * device is round is a real lookup — a catalog id, a `spec:` term, a `parent=` that supplies the
 * shape it doesn't restate — and every surface that re-derived it from a name would get the
 * `parent=` case wrong.
 */
@Serializable
data class ServeDeviceFrame(
  val widthDp: Double? = null,
  val heightDp: Double? = null,
  val isRound: Boolean = false,
) {
  companion object {

    /**
     * The frame for a preview's `@Preview` params, or null when it names no device.
     *
     * Dimensions go through [frameDpOverriddenBy], which is the repo's one answer to "do the
     * annotation's dp displace the device catalog?" — **both axes or neither**, because that is
     * what the renderer that produced the PNG did, and Studio ignores a single-axis hint on a
     * device frame too. Deciding it per axis here would put a 120×227 clip over a 227×227 render
     * and crop live screen, and the helper exists precisely so the places making this call cannot
     * drift apart on it.
     *
     * **Roundness always comes from the device string**, separately, and that split is not
     * cosmetic: [DeviceDimensions.resolve] returns early with `isRound = false` the moment it is
     * handed explicit dimensions, so asking it for both at once reports every sized Wear preview as
     * square, which is exactly the whole set this feature is for.
     */
    fun from(device: String?, widthDp: Int?, heightDp: Int?): ServeDeviceFrame? {
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
data class CatalogRenderFailure(
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
data class RenderFailureFrame(val file: String = "", val line: Int = 0, val function: String = "")

/**
 * Detected per-preview feature support, folded across a discovery
 * [ee.schimke.composeai.previewdata.PreviewInfo]'s captures: keyboard focus (`@FocusedPreview` → a
 * `focus`/`focusGif` capture) and one-handed gestures (`@GestureHintPreview` → a `gestureHint`
 * capture). Returns the two booleans the viewer gates its feature controls on. A preview with
 * neither annotation yields `(false, false)`.
 */
fun detectedFeaturesOf(
  preview: ee.schimke.composeai.previewdata.PreviewInfo
): Pair<Boolean, Boolean> {
  val focus = preview.captures.any { it.focus != null || it.focusGif != null }
  val gestures = preview.captures.any { it.gestureHint != null }
  return focus to gestures
}

/**
 * One app-declared `@ThemeCatalog` theme this session can render an arbitrary preview under — the
 * discrete-theme counterpart of the built-in light/dark axis. Discovered as a module-global set (a
 * theme applies to every preview, not one), so it hangs off [ServeHost.declaredThemes] rather than
 * [ServePreview]. [providerFqn] is the `PreviewWrapperProvider` FQN sent verbatim as the
 * `themeProvider` override; [name] is the human label; [group] buckets related themes (a brand).
 */
data class ServeTheme(
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
 * Extract the module's declared `@ThemeCatalog` / `@WearThemeCatalog` themes from a discovery
 * manifest's preview list. Discovery materializes each annotated `PreviewWrapperProvider` as a
 * synthetic `THEME_CATALOG` / `WEAR_THEME_CATALOG` preview carrying the provider FQN on
 * `params.wrapperClassName` plus its `name` / `group`; this lifts those into [ServeTheme]s (the
 * module-global theme options) without disturbing the ordinary preview cards. Both kinds feed the
 * same switcher — the platform only decides which specimen *sheet* gets rendered, while applying a
 * theme to some other preview is just `Wrap`, which is platform-agnostic. Entries missing a
 * provider FQN are skipped (nothing to apply). Deduped by FQN.
 */
fun declaredThemesFromPreviews(
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
sealed interface RenderOutcome {
  data class Ok(
    val png: ByteArray,
    /** How these bytes were produced, exposed on HTTP responses for remote diagnosis. */
    val generation: Generation = Generation.DAEMON,
  ) : RenderOutcome

  enum class Generation(val wire: String) {
    /** Read directly from a published bundle; no renderer was involved in this request. */
    BAKED("baked"),
    /** Reused from the catalog host's theme cache, which survives per-preview daemon eviction. */
    CATALOG_CACHE("catalog-cache"),
    /** Reused from the daemon host's in-memory override cache. */
    DAEMON_CACHE("daemon-cache"),
    /** Produced by a daemon render during this request. */
    DAEMON("daemon"),
    /**
     * A **player's published render**, read off the catalog's `rc-compare` staging — the offline
     * parity pipeline already drew every `ir/<id>.rc` document with every player, so a bare
     * `?rcPlayer=<wire>` browse is answerable from published bytes exactly as an override-free
     * browse is answerable from the baked PNG.
     *
     * Its own generation rather than [BAKED] because the two make different claims: BAKED means "no
     * renderer was involved and the request's overrides are NOT reflected", which is what turns an
     * override-bearing request into a refusal. These bytes *are* the requested player's output, so
     * reporting them as baked would refuse the very request they answer.
     */
    RC_PUBLISHED("rc-published"),
  }

  /** No such preview id in this session's module. */
  data object NotFound : RenderOutcome

  /** The render was attempted but rejected / failed / timed out. [reason] is human-readable. */
  data class Failed(val reason: String) : RenderOutcome

  /**
   * The per-daemon render lock was held by another in-flight render (a cold Android render can hold
   * it for up to `renderTimeoutSeconds`, minutes on a public host), so this request **backed off**
   * rather than block a shared HTTP render slot on that wait. NOT an error: the caller should serve
   * the baked fallback immediately (or retry). See [ServeRenderHost.DAEMON_BUSY_WAIT_MS].
   */
  data object Busy : RenderOutcome
}

/** Result of a figma-svg render request — the SVG counterpart of [RenderOutcome]. */
sealed interface SvgOutcome {
  data class Ok(
    val svg: ByteArray,
    /**
     * How these bytes were produced, exposed on HTTP responses for remote diagnosis — the same
     * ladder [RenderOutcome.Ok] reports. [RenderOutcome.Generation.BAKED] means the vector was read
     * from a published bundle with no renderer involved, which is what lets the HTTP layer notice
     * that an override-bearing request was answered with pixels (well, paths) that ignore it.
     */
    val generation: RenderOutcome.Generation = RenderOutcome.Generation.DAEMON,
  ) : SvgOutcome

  /** No such preview id, or this host can't produce SVG (a static bundle has no daemon). */
  data object NotFound : SvgOutcome

  /** The render or SVG export was attempted but failed. [reason] is human-readable. */
  data class Failed(val reason: String) : SvgOutcome
}

/**
 * Result of a preview-slots request — the [PreviewSlotsPayload] JSON counterpart of
 * [RenderOutcome].
 */
sealed interface SlotsOutcome {
  data class Ok(val json: ByteArray) : SlotsOutcome

  /** No such preview id, or this host can't extract slots (a static bundle has no daemon). */
  data object NotFound : SlotsOutcome

  /** The render or semantics fetch was attempted but failed. [reason] is human-readable. */
  data class Failed(val reason: String) : SlotsOutcome
}

/**
 * Result of a design-annotation request — the typography + theme inspection layers derived from a
 * render's own `compose/semantics` tree ([ServeDesignAnnotations]).
 */
sealed interface AnnotationsOutcome {
  data class Ok(val json: ByteArray) : AnnotationsOutcome

  /** No such preview id, or this host has no daemon to capture a semantics tree. */
  data object NotFound : AnnotationsOutcome

  /** The render or semantics fetch was attempted but failed. [reason] is human-readable. */
  data class Failed(val reason: String) : AnnotationsOutcome
}

/**
 * Result of an accessibility-overlay request — the merged `a11y/hierarchy` + `a11y/atf` +
 * `a11y/touchTargets` JSON the viewer draws its overlay boxes and legend from.
 */
sealed interface A11yOutcome {
  data class Ok(val json: ByteArray) : A11yOutcome

  /** No such preview id, or this host has no daemon to produce a11y data products. */
  data object NotFound : A11yOutcome

  /** The a11y re-render / fetch was attempted but failed. [reason] is human-readable. */
  data class Failed(val reason: String) : A11yOutcome
}

/**
 * Long-lived, thread-safe wrapper around **one** [RenderSession], fronting it for the
 * `compose-preview serve` HTTP server. The long-lived sibling of
 * [ee.schimke.composeai.cli.MatrixRenderFetcher]: same `renderNow` + await-`renderFinished` +
 * read-PNG sequence, but the session is held for the server's lifetime and shared across all
 * connected clients.
 *
 * ## Multi-client + serialisation
 *
 * The host holds **no per-client state** — any number of browsers can hit it concurrently. The
 * daemon renders one-at-a-time per session and [RenderSession] is not promised thread-safe, so all
 * renders funnel through one [renderLock]; a [cache] keyed by `(previewId, overrides)` means
 * identical concurrent requests coalesce to a single render and every later request is a cache hit.
 *
 * ## Preview switching
 *
 * Bound to a module, not a single preview: [previews] is the whole servable set and [render] takes
 * any id in it, so switching previews is just a different request — no session churn.
 */
class ServeRenderHost
internal constructor(
  /**
   * Opens the daemon session — called **once, on first use**, not at construction.
   *
   * Spawning here rather than in [Companion.open] is what keeps a registered catalog from costing a
   * JVM nobody asked for. Every catalog registers its live host at startup and again on each
   * republish, so an eager spawn meant ~18 daemons resident on the public box with zero active
   * streams and the live-seat budget reading fully free. Everything the browse surface needs
   * ([previews], [label], [declaredThemes]) comes from the module manifest via the constructor, so
   * a visitor can browse a catalog end-to-end without waking anything; the first request that
   * genuinely needs the daemon forces it.
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
  /**
   * True when the caller handed over a session that is already open, so there is a live subprocess
   * from the moment this host exists even though [sessionDelegate] has not been touched. Only the
   * deferred [Companion.open] path leaves this false.
   */
  private val sessionAlreadyOpen: Boolean = false,
  /**
   * One extra sentence appended to a **fatal** breaker trip, given the failure text — see
   * [RenderCircuitBreaker]'s parameter of the same name. The [Companion.open] path supplies the
   * daemon's own launch descriptor, so a Skia link error is answered with the Skiko pair that
   * classpath resolves (#4220) rather than leaving the symbol name to speak for itself.
   */
  private val linkageDiagnosis: (String) -> String? = { null },
) : ServeHost {

  /**
   * Wrap an already-open session. Nothing is deferred — the session exists — but the lazies below
   * are shared with the deferred path, so their RPCs fire on first access rather than here.
   */
  // `public`, unlike the deferred primary constructor above: `:cli`'s `BundleRenderKnobTest` wraps
  // a
  // fake session to exercise `bundle render --knob` without a daemon subprocess, and `serve` is its
  // own
  // module now, so `internal` no longer reaches it. Wrapping a session the caller already owns is a
  // reasonable thing to expose; spawning one is not, which is why only this half opens up.
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
   * Registered inside [sessionDelegate]'s initializer rather than as its own lazy, so it is always
   * hooked up before any caller can issue a render — a `renderFinished` that arrived before the
   * listener existed would strand that render waiting for an event already delivered.
   */
  private var notificationHandle: AutoCloseable? = null

  /**
   * Guards opening against [close]. Both take it, so a `close()` that lands while `openSession()`
   * is still handshaking either waits for the session to be published and then tears it down, or
   * lets the initializer below notice `closed` and tear it down itself. Without a shared lock the
   * `isInitialized()` check in [close] reads false during the handshake, returns, and the
   * subprocess that appears a moment later is never reaped — the exact race a catalog refresh runs
   * into, since it closes the old host while requests may be waking it.
   */
  private val sessionLock = Any()

  /**
   * Set the instant [openSession] is entered, before the handshake completes.
   *
   * `Lazy.isInitialized()` stays false for the whole of a cold open — tens of seconds on an
   * Android/Robolectric backend — even though the subprocess is already launched and consuming the
   * box. Reporting "no daemon" across exactly that window would hide the single largest resource
   * spike the lazy open is meant to make visible.
   */
  private val sessionOpening = AtomicBoolean(false)

  private val sessionDelegate =
    lazy(sessionLock) {
      sessionOpening.set(true)
      val opened = openSession()
      if (closed.get()) {
        // Lost the race: [close] already ran and found nothing to reap. Tear the subprocess down
        // here instead of leaking it. The dead session is still published rather than thrown from,
        // because callers that merely read a capability flag off a host being retired should not
        // eat an exception for it — anything that goes on to actually use it gets the session's own
        // closed-transport error, which is the honest answer.
        runCatching { opened.close() }
      } else {
        notificationHandle = opened.onNotification(::onDaemonNotification)
      }
      opened
    }

  private val session: RenderSession by sessionDelegate

  /**
   * Whether the daemon subprocess has actually been started. Lets `/status` and [close] reason
   * about a host that is registered but never woken, without waking it to find out.
   */
  /** One subprocess, once it exists. */
  override val daemonProcessCount: Int
    get() = if (daemonStarted) 1 else 0

  override val daemonStarted: Boolean
    get() = sessionAlreadyOpen || sessionOpening.get() || sessionDelegate.isInitialized()

  // A daemon backs this host, so an override edit actually re-renders (unlike a static bundle).
  override val canApplyOverrides: Boolean = true

  // The daemon registers its export and inspection data products **inactive**, so fetching SVG,
  // accessibility, semantics, or theme data before enabling their extensions fails with `-32020
  // kind not advertised`. Enable them together once on open; gate
  // `hasSvgExport` on whether the daemon actually has them (a backend without figma-svg reports
  // them in `unknown`), so a non-figma backend cleanly offers no SVG rather than dead-ending in a
  // 500. Best-effort: an enable RPC failure disables these optional surfaces, it doesn't break the
  // host.
  private val extensionEnableResult: ExtensionsEnableResult by lazy {
    // Resolve the session OUTSIDE the runCatching. A failure to open the daemon is not the same
    // fact as "this backend advertises no exports", but folding them together cached the latter
    // forever: a transient open failure on the first capability lookup left `hasSvgExport` /
    // `hasScrollExport` false for the host's lifetime, so every export 404'd even after a later
    // render brought the daemon up fine. Letting it propagate leaves the lazy uninitialized, so the
    // next caller retries.
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
        ExtensionsEnableResult(
          unknown =
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
  }

  override val hasSvgExport: Boolean by lazy {
    ComposeFigmaSvgProduct.KIND !in extensionEnableResult.unknown
  }

  override val hasScrollExport: Boolean by lazy {
    SCROLL_EXTENSION_ID !in extensionEnableResult.unknown &&
      extensionEnableResult.dataProducts.any { it.kind == SCROLL_LONG_KIND }
  }

  // The a11y extension is registered inactive like the exports above, so it rides the same
  // `extensions/enable` call. A backend that doesn't carry it reports it in `unknown`, which is
  // what makes the viewer omit the Accessibility overlay control rather than offer one whose fetch
  // would 500 on `kind not advertised`.
  override val hasA11yOverlay: Boolean by lazy {
    A11Y_EXTENSION_ID !in extensionEnableResult.unknown
  }

  override fun hasScrollExportFor(previewId: String): Boolean =
    hasScrollExport &&
      previews.firstOrNull { it.id == previewId }?.dataProductKinds?.contains(SCROLL_LONG_KIND) ==
        true

  /**
   * Run the one-shot `extensions/enable` ([extensionEnableResult]) before any data-product fetch.
   *
   * The daemon registers its export and inspection products **inactive**, so a `data/fetch` that
   * reaches a session nobody enabled fails `-32020 kind not advertised` — the enable is a
   * precondition of every fetch lane, not a detail of the capability flags. Until this existed the
   * only thing that ran it was a lane that happened to read a capability on the way past
   * ([renderSvg] reads [hasSvgExport], [renderAnnotations] reads [THEME_EXTENSION_ID]);
   * [renderA11y] and [renderScrollPng] read none, so on a host whose capabilities nobody ever asked
   * for they fetched against an un-enabled session and 500'd.
   *
   * That host is not hypothetical: [ServeCatalogLiveHost] answers `hasA11yOverlayFor` from the
   * SHARED daemon while routing `renderA11y` to the **per-preview** one, so the viewer offered the
   * Accessibility layer on a catalog page whose fetch could only fail — until some unrelated
   * request (an SVG export, a scroll capture) happened to enable that daemon's extensions, at which
   * point the same URL started working. Hence the intermittency.
   *
   * Returns null on success, or the reason the daemon could not be opened at all. That open is
   * deliberately outside [extensionEnableResult]'s own `runCatching` (so a transient failure leaves
   * the lazy uninitialized and the next caller retries), which means it throws straight through the
   * capability read. Every lane that forces the enable therefore has to turn it into its own
   * failure outcome: these lanes used to reach the session only from inside a `try` that answered
   * `Failed`, and an exception escaping instead would skip the route's 500-with-reason and its log
   * line. Reporting it here keeps the retry — nothing was cached — while keeping the lane's
   * contract.
   */
  private fun ensureExtensionsEnabled(): String? =
    try {
      extensionEnableResult
      null
    } catch (e: Exception) {
      "inspection data unavailable: ${e.message}".also(onLog)
    }

  // The one-handed gesture override is honoured only by the Android (Robolectric) backend — the
  // desktop backend ignores `overrides.gestures`. Read the daemon's advertised capabilities so the
  // viewer offers the "Show gesture hints" control only when it would actually re-render.
  override val gesturesRenderable: Boolean by lazy {
    "gestures" in session.initializeResult.capabilities.supportedOverrides
  }

  // The Remote Compose `player` override (VIEW ⇄ EMBEDDED server-side player) is meaningful only on
  // the Android backend, which is the only one carrying the Remote Compose runtime — the desktop
  // backend has no runtime and ignores it. Read the daemon's declared backend so the viewer offers
  // the server-side java / cmp-android backend chips only where they actually re-render.
  override val remoteComposePlayerSelectable: Boolean by lazy {
    session.initializeResult.capabilities.backend ==
      ee.schimke.composeai.daemon.protocol.BackendKind.ANDROID
  }

  /**
   * A daemon-backed host offers the server-side [RcPlayerBackend.JAVA] /
   * [RcPlayerBackend.CMP_ANDROID] lanes for a Remote Compose preview when its backend honours the
   * player override ([remoteComposePlayerSelectable]); the client-side [RcPlayerBackend.JS] lane
   * rides on top whenever the host can also hand back the `.rc` document. RC-ness is taken from the
   * preview's declared Remote Compose knobs (populated for a Remote Compose preview) or a carried
   * `.rc` doc.
   */
  override fun enabledRcPlayersFor(previewId: String): List<RcPlayerBackend> {
    val isRemoteCompose =
      hasRemoteComposeDoc(previewId) ||
        previews.firstOrNull { it.id == previewId }?.remoteComposeKnobs?.isNotEmpty() == true
    if (!isRemoteCompose) return emptyList()
    return buildList {
      if (hasRemoteComposeDoc(previewId)) add(RcPlayerBackend.JS)
      if (remoteComposePlayerSelectable) {
        add(RcPlayerBackend.JAVA)
        add(RcPlayerBackend.CMP_ANDROID)
      }
    }
  }

  private val previewIds: Set<String> = previews.map { it.id }.toHashSet()

  // Decodes streamFrame notification params for the live-stream lane (startStream).
  private val streamJson = Json { ignoreUnknownKeys = true }

  // Bounded LRU of rendered PNGs keyed by ServeOverrides.cacheKey. A dev-facing server fronting one
  // module won't accumulate many distinct (preview × overrides) combos, so a small cap is plenty.
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

  // Decodes a fetched compose/semantics payload and encodes the slots response; tolerant of the
  // schema's additive fields (a v7 file read by this v-agnostic slot extractor).
  private val dataJson = Json { ignoreUnknownKeys = true }

  // Fair (FIFO) so a waiter can't be starved and the longest-waiting render — an interactive
  // browse — wins the daemon when it frees, ahead of the background prewarm re-acquiring the lock
  // for the next warm render.
  private val renderLock = ReentrantLock(/* fair= */ true)

  // Serve-side render-latency accounting for `/status` (`renderStats`) — see [RenderPerfStats].
  private val perfStats = RenderPerfStats()

  /**
   * Stops this host re-attempting renders it has proved it cannot serve — a linkage fault on the
   * first occurrence, an unclassified sustained failure rate on the backstop. See
   * [RenderCircuitBreaker] and issue #3448 (3794 retries of one `UnsatisfiedLinkError` in 14
   * minutes).
   */
  private val breaker = RenderCircuitBreaker(linkageDiagnosis = linkageDiagnosis)

  override fun renderPerfStats(): RenderPerfSnapshot =
    perfStats.snapshot().copy(breaker = breaker.snapshot())

  override fun renderBreaker(): RenderBreakerSnapshot? = breaker.snapshot()

  /**
   * An open breaker is host-wide, so it latches EVERY preview: the fault is in the daemon's
   * classpath, not in one composition. This is what turns the `503 render busy; retry shortly` into
   * a terminal 409 naming the linkage error — the HTTP layer consults the latch before it takes a
   * render slot.
   */
  override fun renderFailureLatch(previewId: String, overrides: PreviewOverrides): String? =
    breaker.peekReason()

  /**
   * A host whose breaker is open has no working live lane, so it must stop advertising one — the
   * `/status` `live` / `running` columns and the viewer's stream toggle both read this. Reported
   * from the breaker alone; no session is touched, so a registered-but-unopened catalog is
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

  // Set under renderLock immediately before each renderNow; the (single) in-flight render's
  // renderFinished notification fills pngPath and trips the latch. Safe because the lock guarantees
  // exactly one render in flight at a time.
  private val pendingLatch = AtomicReference<CountDownLatch?>(null)
  private val pendingPreviewId = AtomicReference<String?>(null)
  private val pendingPngPath = AtomicReference<String?>(null)

  // Set (instead of [pendingPngPath]) when the in-flight render's terminal event is
  // `renderFailed` — the render body threw, e.g. a preview whose composition NPEs. Carrying the
  // failure through the same latch turns "broken preview" into an immediate
  // [RenderOutcome.Failed] instead of a full render-budget sleep under [renderLock].
  private val pendingFailure = AtomicReference<String?>(null)

  // Count of timed-out renders per preview id whose `renderFinished` is still outstanding. A render
  // that timed out releases the lock, but the daemon still emits that render's `renderFinished`
  // later; since the notification carries only the preview id (no per-render correlation id), a
  // stale event for the same id would otherwise complete the *next* same-id render's latch and
  // cache
  // the wrong PNG under the new override key. The daemon delivers `renderFinished` reliably and in
  // order per session (the S4 harness tests assert none are lost / reordered), so we drain exactly
  // one outstanding event per timed-out render here before honouring a fresh one.
  private val staleRenders = ConcurrentHashMap<String, Int>()

  // The first render after the session opens pays Skiko/JVM cold start, so it gets the generous
  // [renderTimeoutSeconds] budget; once one render has succeeded, each subsequent frame is capped
  // at
  // [frameRenderTimeoutSeconds] so a single wedged render can't hold the only render slot.
  private val warmedUp = AtomicBoolean(false)

  // An override-bearing render (a `?knob.…=` edit, device/locale/theme override, …) forces a real
  // recomposition and is much slower than a plain re-emit — its first occurrence is effectively
  // cold even when [warmedUp] is already set by a background prewarm (a throwaway default render
  // that flips [warmedUp] without ever exercising the override path). Without a separate gate the
  // very first override render on `preview.coo.ee` — where `warmInBackground` prewarms — is charged
  // the tight [frameRenderTimeoutSeconds] cap and times out (the public `?knob.…` 500). Give the
  // first override render the generous [renderTimeoutSeconds] budget too; subsequent override
  // frames
  // are capped like any other so a wedged one can't pin the slot.
  private val overridesWarmedUp = AtomicBoolean(false)

  // Fans one upstream daemon stream out to all watchers of the same preview/overrides/codec/fps, so
  // many browsers cost one held session instead of one each. Built on [startStream]; shared because
  // there's one host per server.
  private val broadcast = ServeBroadcastHub(::startStream)

  private val closed = AtomicBoolean(false)

  /**
   * Daemon render lifecycle events. A method rather than an inline lambda so [sessionDelegate] can
   * register it the instant the session opens — see [notificationHandle].
   */
  private fun onDaemonNotification(method: String, params: JsonObject?) {
    if (params == null) return
    val isFinished = method == "renderFinished"
    val isFailed = method == "renderFailed"
    if (!isFinished && !isFailed) return
    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return
    // Drain the late event of a previously timed-out render (FIFO: it arrives before the current
    // render's own event) so it can't complete a fresh same-id render's latch with a stale PNG.
    // Either terminal event drains — the daemon owes exactly one (finished OR failed) per render.
    if ((staleRenders[id] ?: 0) > 0) {
      staleRenders.compute(id) { _, v -> ((v ?: 0) - 1).takeIf { it > 0 } }
      return
    }
    if (id != pendingPreviewId.get()) return
    if (isFinished) {
      // `unchanged` renders still carry a (re-used) pngPath, so this captures bytes either way.
      params["pngPath"]?.jsonPrimitive?.contentOrNull?.let { pendingPngPath.set(it) }
    } else {
      // `renderFailed` must complete the wait too. Without this the render body's failure (a
      // preview whose composition throws) left the latch untouched and [render] slept out its
      // ENTIRE cold budget — 900s on the public server — holding [renderLock] for a render the
      // daemon had already reported dead seconds in. Profiled on the confetti-mobile bundle: a
      // broken preview failed in ~4s and the host still burned the full 180s CLI budget per
      // render of it, which read as "cold Android renders take minutes".
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

    // The daemon has already proved it cannot serve this (a linkage fault, or a sustained failure
    // rate) — answer with that reason instead of asking it again. Ahead of the lock acquire, so a
    // broken daemon neither holds the render lock nor pushes other callers into a Busy back-off
    // that reads to the browser as "the server is busy, retry" (issue #3448). A rate-tripped
    // breaker lets one probe render through per cooldown, so a transient wave still heals.
    breaker.blockedReason()?.let {
      perfStats.recordShortCircuit()
      return RenderOutcome.Failed(it)
    }

    // Perf accounting for `/status` (`renderStats`): the round-trip clock starts at the cache
    // miss, and "cold" is judged before the render — a render issued while this host has never
    // completed one is the cold-start population the boot/warm work targets.
    val perfStartNs = System.nanoTime()
    val coldAtEntry = !warmedUp.get()
    fun perfElapsedMs(): Long = (System.nanoTime() - perfStartNs) / 1_000_000

    // Bounded acquire (Fix 4): a cold render holds [renderLock] for up to renderTimeoutSeconds
    // (minutes); don't pin the caller's HTTP render slot on that wait — back off to Busy so the
    // caller serves baked instead. Waiting the [DAEMON_BUSY_WAIT_MS] window still rides out a fast
    // warm re-emit.
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

      // The daemon coalesces an override-bearing `renderNow` whose previewId already has one in
      // flight, expecting the client to resubmit once it clears. Because the daemon clears that
      // flag
      // on (just after) `renderFinished`, the very next serialised render here can momentarily race
      // the not-yet-cleared flag and get rejected. Honour the daemon's retry contract with a
      // bounded
      // backoff instead of surfacing it to the browser as a 500.
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

        // Cold start gets the generous budget; every frame after the first is capped so a wedged
        // render can't pin the slot. An override-bearing render's *first* occurrence is cold too
        // (real recompose, and prewarm may have flipped [warmedUp] without ever paying it), so it
        // keeps the generous budget until one override render has succeeded.
        val hasOverrides = overrides != PreviewOverrides()
        val warmForThisRender = warmedUp.get() && (!hasOverrides || overridesWarmedUp.get())
        val budget = if (warmForThisRender) frameRenderTimeoutSeconds else renderTimeoutSeconds
        val completed =
          try {
            latch.await(budget, TimeUnit.SECONDS)
          } catch (_: InterruptedException) {
            // A caller may impose a tighter foreground bound than this host's cold-render budget.
            // The daemon still owes a terminal event, so quarantine it exactly like a timeout
            // before releasing the render lock; otherwise it can complete the next same-id render.
            // CountDownLatch checks interruption before its completed state. If the terminal event
            // won that race it has already been consumed, so recording it as outstanding would
            // discard the next legitimate same-preview event instead.
            if (latch.count > 0) staleRenders.merge(previewId, 1, Int::plus)
            Thread.currentThread().interrupt()
            perfStats.recordBusy()
            return RenderOutcome.Busy
          }
        if (!completed) {
          // The daemon still owes this queued render a `renderFinished`; record it so the late
          // event
          // is drained instead of completing a future same-id render with a stale PNG.
          staleRenders.merge(previewId, 1, Int::plus)
          val reason = "timed out after ${budget}s waiting for render"
          onLog(reason)
          recordFailure(perfElapsedMs(), timeout = true, reason = reason)
          return RenderOutcome.Failed(reason)
        }
        // The latch also trips on `renderFailed` — the daemon reported the render body threw.
        // Fail immediately: sleeping out the budget here (the pre-fix behaviour, since only
        // `renderFinished` completed the latch) held [renderLock] for minutes per broken preview
        // and is what read as "cold Android renders take minutes" in the serve 503 investigation.
        // No [staleRenders] entry — the daemon already delivered this render's terminal event —
        // and [warmedUp] stays as-is: a failed render proves nothing about engine warmth.
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
   * Render [previewId] at [overrides] and return its **figma-svg** export (`compose/figma-svg`),
   * serving a cached result when one exists. Thread-safe.
   *
   * The daemon writes the SVG to a per-preview path as a side effect of the *same* render that
   * produces the PNG, so this renders (reusing [render] and its retry/timeout handling) and then
   * fetches the just-written SVG. Both happen under [renderLock] with the PNG cache entry evicted
   * first: the SVG file is shared per preview and overwritten by every render, so it must be
   * fetched in the same critical section as the render that produced it — a PNG cache hit would
   * otherwise skip the render and leave a prior render's (stale) SVG on disk.
   */
  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return SvgOutcome.NotFound
    // A daemon without the figma-svg producer ([hasSvgExport] false) can't export it — fetchData
    // would fail `-32020 kind not advertised`. Short-circuit to NotFound (a clean 404) instead, so
    // a direct/stale `/render/<id>.svg` matches the "no SVG lane" this host already advertises.
    if (!hasSvgExport) return SvgOutcome.NotFound

    val key = ServeOverrides.cacheKey(previewId, overrides)
    svgCache.get(key)?.let {
      return SvgOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
    }

    return renderLock.withLock {
      svgCache.get(key)?.let {
        return@withLock SvgOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
      }

      // Force a fresh render of these overrides so the shared per-preview SVG file on disk is
      // theirs; the held lock keeps any other render from overwriting it before the fetch below.
      cache.remove(key)
      when (val pngOutcome = render(previewId, overrides)) {
        RenderOutcome.NotFound -> return@withLock SvgOutcome.NotFound
        is RenderOutcome.Failed -> return@withLock SvgOutcome.Failed(pngOutcome.reason)
        // Unreachable in practice — render() re-enters the lock we already hold — but the caller
        // falls back to the baked vector on any non-Ok, so a busy signal degrades gracefully.
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

      // Inline any hybrid figma-raster crops so the served SVG is self-contained (a vector-only SVG
      // passes through untouched); Figma's importer can't resolve external hrefs.
      val bytes = inlineRasters(svgPath, raw)
      svgCache.put(key, bytes)
      SvgOutcome.Ok(bytes)
    }
  }

  /**
   * Render [previewId]'s **full-page** figma-svg (`compose/figma-svg-long`) at [overrides], serving
   * a cached result when one exists. Thread-safe.
   *
   * Unlike [renderSvg] this fetches the `requiresRerender = true` long kind directly:
   * `session.fetchData` drives the daemon's `figma-svg-long` re-render (an expanded-viewport /
   * slice- stitched render that composes the whole list) and returns the written SVG path — so
   * there's no separate PNG render to force first. Still serialised through [renderLock] with the
   * fetch reading the file the re-render just wrote.
   *
   * **Override-aware.** The full-page SVG file is shared per preview, so serving it at non-default
   * overrides needs a fresh render: the [overrides] ride the fetch's kind-agnostic `params` bag
   * ([DataFetchParams.PARAM_OVERRIDES]) — the daemon threads them into the `figma-svg-long`
   * re-render — and [DataFetchParams.PARAM_FORCE_RERENDER] makes the file-backed registry re-render
   * even though a prior (differently-themed) file exists. The cache is keyed by
   * [ServeOverrides.cacheKey] so themed and default capsules don't collide; the held [renderLock]
   * keeps the shared file from being overwritten between the re-render and the read, mirroring the
   * viewport [renderSvg] lane.
   */
  override fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return SvgOutcome.NotFound
    // No figma-svg producer ⇒ no export; NotFound (404) rather than a `-32020` fetch 500. See
    // [renderSvg].
    if (!hasSvgExport) return SvgOutcome.NotFound

    val key = ServeOverrides.cacheKey(previewId, overrides)
    scrollSvgCache.get(key)?.let {
      return SvgOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
    }

    return renderLock.withLock {
      scrollSvgCache.get(key)?.let {
        return@withLock SvgOutcome.Ok(it, RenderOutcome.Generation.DAEMON_CACHE)
      }

      // Force a fresh full-page render at these overrides (the shared per-preview file may hold a
      // different theme's export) and read it under the held lock.
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
   * Fetch the daemon's tall raster scroll product at [overrides]. Like [renderScrollSvg], the
   * product is a shared per-preview file, so every cache miss forces an override-aware re-render
   * and reads the resulting bytes while [renderLock] is held.
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
   * Inline a hybrid SVG's sibling `figma-raster/<node>.png` crops as `data:` URIs so the served SVG
   * is self-contained — the Figma importer (and any consumer that can't resolve external hrefs)
   * needs every layer embedded. A vector-only SVG has no such refs and passes through. Shares the
   * inlining with the static catalog path via {@link inlineFigmaRasters}.
   */
  private fun inlineRasters(svgPath: okio.Path, raw: ByteArray): ByteArray {
    val dir = svgPath.parent ?: return raw
    return inlineFigmaRasters(fileSystem, dir, raw.decodeToString()).encodeToByteArray()
  }

  /**
   * Render [previewId] at [overrides] and return its declared **preview slots** as
   * [PreviewSlotsPayload] JSON, serving a cached result when one exists. Thread-safe.
   *
   * The slots are the `dp-slot:<name>` markers the preview's author placed (see [PreviewSlots]);
   * they're captured into the `compose/semantics` tree of the *same* render that produces the PNG,
   * with their absolute-to-root bounds. Like [renderSvg] this renders (reusing [render] and its
   * retry/timeout handling) then fetches the just-written product, both under [renderLock] with the
   * PNG cache entry evicted first — the semantics file is shared per preview and overwritten by
   * every render, so it must be read in the same critical section as the render that produced it.
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

      // Force a fresh render of these overrides so the shared per-preview semantics file on disk is
      // theirs; the held lock keeps any other render from overwriting it before the fetch below.
      cache.remove(key)
      when (val pngOutcome = render(previewId, overrides)) {
        RenderOutcome.NotFound -> return@withLock SlotsOutcome.NotFound
        is RenderOutcome.Failed -> return@withLock SlotsOutcome.Failed(pngOutcome.reason)
        // Unreachable in practice — render() re-enters the lock we already hold — but keep the
        // match exhaustive and degrade to a clean failure rather than pretending we rendered.
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
          .encodeToString(PreviewSlotsPayload.serializer(), PreviewSlotsPayload(previewId, slots))
          .encodeToByteArray()
      slotsCache.put(key, json)
      SlotsOutcome.Ok(json)
    }
  }

  /**
   * Render [previewId] at [overrides] and return its **typography + theme** inspection layers as
   * `{"previewId":"…","annotations":[…]}`, serving a cached result when one exists. Thread-safe.
   *
   * Same shape as [renderSlots] — both read the `compose/semantics` tree of the render that just
   * produced the pixels, so both force a fresh render under [renderLock] before fetching the
   * per-preview file it overwrote. The layers themselves are pure projection
   * ([ServeDesignAnnotations]): resolved type size / face / weight per text node, resolved fill /
   * border / corner radius per container.
   *
   * The response also carries [ServeSemanticsTags]' tag index for that same tree. Both projections
   * read one payload under one lock, so the index and the annotations always agree with each other.
   *
   * **That is not yet the coupling the parity element gates need**, and the difference matters.
   * `/render/<id>.png` and `/render/<id>.annotations` are separate requests, and this method
   * deliberately evicts the PNG cache entry and re-renders before reading semantics — so a client
   * that already displayed the PNG holds pixels from the *previous* render while these bounds
   * describe the new one. Identical for a deterministic preview; not for one that animates or
   * composes conditionally, where an element gate could then validate a region the scored frame
   * never contained. Closing it needs the two responses to share a render generation the client can
   * match (or the index to travel with the pixels), which the design doc assigns to Phase 2's
   * transport work — see the "same render" requirement in
   * [COMPONENT_PARITY_WORKFLOW.md](../../../../../../../../docs/design/COMPONENT_PARITY_WORKFLOW.md).
   */
  override fun renderAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
  ): AnnotationsOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return AnnotationsOutcome.NotFound
    // Same precondition as the lanes above: `compose/semantics` (and the theme product below) are
    // registered inactive, and the capability read further down forces the enable through a lazy
    // that throws when the daemon can't be opened at all. Doing it here turns that into this
    // request's failure instead of an exception escaping past the route's 500-with-reason.
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
      // The container layers read the layout tree when the daemon has it: `layout/inspector` walks
      // every `LayoutNode`, so a `Column` that declares padding and an arrangement gap but no
      // semantics still reaches the overlay. A daemon too old to know the kind leaves it in
      // `unknown` and the layers fall back to the semantics tree's mirrored tokens.
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
   * Fetch and decode the freshly written `compose/semantics` tree for [previewId] from whichever
   * transport the session used (inline payload or an on-disk path); null when the fetch yielded
   * neither. Callers hold [renderLock] so the file read matches the render that produced it.
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
   * Fetch and decode the freshly written `layout/inspector` tree for [previewId], the twin of
   * [fetchSemantics]; null when the product is absent or unreadable, which drops the container
   * layers back to the semantics tree rather than failing the request. Callers hold [renderLock].
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
   * Fetch [previewId]'s accessibility products at [overrides] and return them merged as one JSON
   * object the viewer can draw an overlay + legend from:
   * ```json
   * {"previewId":"…","nodes":[…],"findings":[…],"touchTargets":[…]}
   * ```
   *
   * `nodes` is `a11y/hierarchy` (what a screen reader sees — label, role, states, merged-ness and
   * source-bitmap bounds), `findings` is `a11y/atf` (Android-only; empty on the desktop backend)
   * and `touchTargets` is `a11y/touchTargets` (Android-only, absent elsewhere). Each is fetched
   * independently and tolerantly: a kind this backend doesn't carry contributes an empty array
   * rather than failing the request, so the desktop overlay still draws its focus map.
   *
   * The products are per-preview files the daemon overwrites on every render, and they're produced
   * only by a render in `a11y` mode — so the fetch asks for a forced re-render carrying [overrides]
   * (like [renderScrollPng]) and runs under [renderLock] so nothing else overwrites them in
   * between. Results are cached per `(previewId, overrides)`; only a miss pays for the re-render.
   */
  override fun renderA11y(previewId: String, overrides: PreviewOverrides): A11yOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return A11yOutcome.NotFound
    // Enable the daemon's inactive a11y extension before fetching from it; a daemon that can't be
    // opened is this request's failure, not an escaped exception. Past that, a backend that reports
    // the extension `unknown` can't produce the hierarchy at all, so answer NotFound (a clean 404)
    // rather than fetching into a `-32020` 500 — the same shape [renderSvg] gives a backend without
    // figma-svg. [hasA11yOverlay] only reads the result the line above already resolved.
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
      // The hierarchy is the overlay itself — without it there is nothing to draw, so its failure
      // is the request's failure. The other two only decorate it.
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

  /**
   * Fetch an a11y product for [previewId] from whichever transport the daemon used (an inline
   * payload, or an on-disk path written by the re-render), or null when it yielded neither. Callers
   * hold [renderLock] so a path read matches the render that produced it.
   */
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
   * [fetchA11yProduct] for a kind that may not exist on this backend (ATF findings and touch
   * targets are Android-only). Swallows the fetch failure — the overlay is still worth drawing from
   * the hierarchy alone. No `params`: the forced re-render already ran for the hierarchy, and
   * asking for another one per optional product would triple the cost of every overlay.
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
   * Try to open a daemon-backed live stream for [previewId] (tier-2). On success the daemon pushes
   * `streamFrame` notifications; each is decoded and handed to [onFrame], and the returned
   * [StreamHandle] forwards input + tears the stream down on close. Returns **null** when streaming
   * is unsupported (older daemon / backend without held compositions, or a `stream/start` that
   * couldn't allocate a held session) so the caller falls back to the [render]-per-frame lane.
   * Independent of the snapshot render lock — a held stream runs concurrently with snapshot
   * renders.
   */
  fun startStream(
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

    // Register the listener BEFORE stream/start: the daemon's frame loop can emit the initial
    // keyframe before the RPC response returns, and missing it leaves static previews blank (later
    // frames are payload-less `unchanged` heartbeats). We don't know the frameStreamId yet, so
    // buffer frames until it's known, then replay the matching ones.
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
        // UnsupportedOperationException (no streaming on this backend) or a daemon error — degrade.
        // The exception message IS the daemon's original failure (e.g. "interactive session already
        // held", "previewSpecResolver returned null for previewId=…", a 30s interactive-start
        // timeout) — carry it to the viewer via [onUnavailable] rather than only logging it, so the
        // client can show why input isn't live instead of the opaque "input requires a live
        // stream".
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        onLog("stream/start unavailable for $previewId ($reason); falling back to snapshots")
        onUnavailable?.invoke(reason)
        runCatching { listener.close() }
        return null
      }

    if (!result.heldSession) {
      // The daemon accepted stream/start but couldn't hold an interactive session, so it won't run
      // the live frame loop — fall back to the snapshot lane rather than open a frameless stream.
      // The daemon records the actual acquisition failure in `fallbackReason` (e.g.
      // `UnsupportedOperationException: interactive session already held`); prefer it so the viewer
      // shows the real cause, and use the generic text only when the daemon sent none.
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
        // Fire-and-forget, and optional: a daemon that predates `stream/visibility` (or a backend
        // that doesn't implement it at all) throws UnsupportedOperationException here, which must
        // not take down a lane that is otherwise streaming fine.
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
   * Join the shared live stream for [previewId] (tier-2), opening one upstream daemon stream per
   * distinct preview + overrides + codec + fps and fanning its frames out to every watcher. This is
   * the multi-client front door to [startStream]: prefer it over [startStream] for client
   * connections so N viewers of the same preview ride one held session. Returns `null` when
   * streaming is unsupported (caller falls back to the snapshot lane).
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
    // A host that was never used has no subprocess to tear down — and touching `session` here would
    // spawn one only to kill it, which on an Android backend is tens of seconds of pure waste. This
    // is the common case now that catalogs register without opening: a republish closes and
    // replaces every host, most of which nobody visited.
    synchronized(sessionLock) {
      // `daemonStarted`, not `isInitialized()`: a host constructed around an already-open session
      // owns a subprocess from birth, and must close it even if nothing ever touched the lazy.
      if (!daemonStarted) return
      try {
        notificationHandle?.close()
      } catch (_: Exception) {
        // best effort
      }
      session.close()
    }
  }

  companion object {
    internal const val SCROLL_LONG_KIND = "render/scroll/long"
    internal const val SCROLL_EXTENSION_ID = "scroll"

    // The accessibility extension + its three product kinds, spelled here rather than imported
    // from `:data-a11y-core` — the CLI doesn't depend on that module, and these are wire strings
    // (`AccessibilityPreviewExtension.ID` / `KIND_HIERARCHY`, `AtfChecksPreviewExtension.KIND_ATF`
    // / `KIND_TOUCH_TARGETS`), not types. Both daemon backends register all of them under the one
    // `a11y` extension id; the desktop one advertises no `a11y/touchTargets` (ATF is Android-only)
    // and answers `a11y/atf` with empty findings, which the overlay handles as "nothing to flag".
    internal const val A11Y_EXTENSION_ID = "a11y"
    internal const val A11Y_HIERARCHY_KIND = "a11y/hierarchy"
    internal const val A11Y_ATF_KIND = "a11y/atf"
    internal const val A11Y_TOUCH_TARGETS_KIND = "a11y/touchTargets"
    internal const val THEME_EXTENSION_ID = "data/theme"
    /** RPC ack budget for the (fast, queue-only) `renderNow` call itself. */
    private val RENDER_ACK_TIMEOUT = 60.seconds

    /**
     * Cold-start render budget — the first render pays the daemon's warm-up. 180s covers a
     * desktop/Skiko daemon, but an **Android/Robolectric** daemon's first render is much slower (it
     * fetches the `android-all-instrumented` runtime and initialises the Android/Compose stack), so
     * make it overridable via `-Dcomposeai.serve.renderTimeoutSeconds=<n>` for those backends.
     */
    private val RENDER_TIMEOUT_SECONDS: Long =
      System.getProperty("composeai.serve.renderTimeoutSeconds")?.toLongOrNull()?.coerceAtLeast(1)
        ?: 180L

    /**
     * Per-frame render budget once warm; a wedged render can't hold the slot past this. 10s suits a
     * warm Skiko daemon; a warm Android/Robolectric render is slower, so it's overridable via
     * `-Dcomposeai.serve.frameRenderTimeoutSeconds=<n>`.
     */
    private val FRAME_RENDER_TIMEOUT_SECONDS: Long =
      System.getProperty("composeai.serve.frameRenderTimeoutSeconds")
        ?.toLongOrNull()
        ?.coerceAtLeast(1) ?: 10L

    /**
     * Bounded retries when the daemon coalesces an override-bearing render already in flight. The
     * window only needs to outlast the daemon clearing its in-flight flag right after
     * `renderFinished`, so a handful of short backoffs is ample.
     */
    private const val MAX_COALESCED_RETRIES = 50
    private const val COALESCED_RETRY_BACKOFF_MS = 100L

    /**
     * How long a render waits for the per-daemon [renderLock] before reporting
     * [RenderOutcome.Busy]. The lock is held for the whole render, and a cold Android render can
     * hold it for `renderTimeoutSeconds` (minutes on a public host). Blocking that long pins a
     * shared HTTP render slot ([ServeHttpServer.renderSemaphore]) and — enough times over —
     * saturates the whole server. This caps the wait to a couple of seconds (enough to ride out a
     * fast warm re-emit); past it the caller serves the baked fallback instead of blocking a slot
     * on a busy daemon.
     */
    private const val DAEMON_BUSY_WAIT_MS = 2_000L

    private const val MAX_CACHE_ENTRIES = 256

    /**
     * Open a long-lived session against a daemon launch descriptor and wrap it. Mirrors
     * [ee.schimke.composeai.cli.MatrixRenderFetcher] config; the caller supplies the servable
     * [previews] read from the module manifest.
     *
     * Does NOT spawn the daemon — the session opens on first use, so [RenderSessionException] now
     * surfaces at the first request that needs it rather than here.
     */
    fun open(
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
        // Two causes, tried in order of specificity: a split Skiko pair (which names the exact
        // versions), then a classpath the bundle asked for and this server could not assemble.
        // Either turns the open breaker's reason — the only report anyone outside the box reads —
        // from a bare missing symbol into something that says what to do about it.
        linkageDiagnosis = { reason ->
          SkikoNativePairing.linkageDiagnosis(reason, descriptorPath)
            ?: BundleClasspathGaps.linkageDiagnosis(reason, descriptorPath)
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
