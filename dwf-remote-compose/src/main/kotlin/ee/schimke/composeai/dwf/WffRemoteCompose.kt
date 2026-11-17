package ee.schimke.composeai.dwf

import androidx.compose.ui.graphics.ImageBitmap
import ee.schimke.composeai.rccreation.dwf.DwfParser
import ee.schimke.composeai.rccreation.dwf.DwfRenderOptions
import ee.schimke.composeai.rccreation.dwf.DwfResourceResolver
import ee.schimke.composeai.rccreation.dwf.DwfUserConfiguration
import ee.schimke.composeai.rccreation.dwf.DwfVectorDrawable
import ee.schimke.composeai.rccreation.dwf.captureDwfRemoteDocumentBytes
import ee.schimke.composeai.rccreation.dwf.createDwfPlaceholderBitmap
import ee.schimke.composeai.rccreation.dwf.normalizeDwfResourceKey
import ee.schimke.composeai.rcjvm.DwfFileResTree
import ee.schimke.composeai.rcjvm.DwfMapResTree
import ee.schimke.composeai.rcjvm.DwfResTree
import ee.schimke.composeai.rcjvm.DwfResourceDirectoryResolver
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking

/**
 * Where a watch face's `res/` tree comes from: raster and vector drawables, the `values` XMLs,
 * fonts.
 *
 * Paths are Android resource paths relative to `res/` (`drawable/hand_hour.png`,
 * `values/colors.xml`, `drawable-fr/label.png`); a leading `res/` is tolerated.
 */
public sealed interface WffResourceSource {
  /** No resources: every `@drawable/...` reference degrades to a placeholder. */
  public data object Empty : WffResourceSource

  /** Res-relative path to file bytes, held in memory. The editor's usual shape. */
  public class InMemory(public val files: Map<String, ByteArray>) : WffResourceSource

  /** One or more resource directories on disk, searched in order (`res/`, `composeResources/`). */
  public class Directories(public val dirs: List<File>) : WffResourceSource

  public companion object {
    public fun of(files: Map<String, ByteArray>): WffResourceSource = InMemory(files)

    public fun ofDirectory(dir: File): WffResourceSource = Directories(listOf(dir))
  }
}

/**
 * Options for [WffRemoteCompose.compile].
 *
 * @property widthPx output document width in pixels; `null` uses the `<WatchFace width>`. The scene
 *   is scaled to this size, so one XML works for every device in a side-by-side preview.
 * @property heightPx as [widthPx], for the height.
 * @property density Android display density factor (`1.0` = 160 dpi). It is recorded in the
 *   document header; WFF geometry is pixel-based, so it does not scale the scene.
 * @property ambient the default of `dwf.is_ambient`; a host flips the named value to switch live.
 * @property flavor `<Flavor id>` to activate before [configuration], or `null` for the default.
 * @property configuration overrides for `<UserConfigurations>` keyed by configuration id.
 * @property wffVersion the Watch Face Format version the design targets, `1..4`. **Advisory
 *   today**: the translator accepts the union of versions 1–4 and does not yet flag features newer
 *   than the target. It is echoed in [WffCompileResult.wffVersion] so hosts can already thread it.
 * @property clipToWatchShape apply `<WatchFace clipShape>` (circle / rounded rectangle).
 * @property placeholderMissingResources draw a visible placeholder for a drawable that cannot be
 *   found, instead of drawing nothing. A missing resource is a [WffDiagnostic] either way and never
 *   an exception.
 */
public class WffCompileOptions(
  public val widthPx: Int? = null,
  public val heightPx: Int? = null,
  public val density: Float = 1f,
  public val ambient: Boolean = false,
  public val flavor: String? = null,
  public val configuration: Map<String, String> = emptyMap(),
  public val wffVersion: Int = DEFAULT_WFF_VERSION,
  public val clipToWatchShape: Boolean = true,
  public val placeholderMissingResources: Boolean = true,
) {
  public companion object {
    /** The version a design targets unless it says otherwise. */
    public const val DEFAULT_WFF_VERSION: Int = 2
    public const val MIN_WFF_VERSION: Int = 1
    public const val MAX_WFF_VERSION: Int = 4
  }
}

public enum class WffSeverity {
  INFO,
  WARNING,
  ERROR,
}

/**
 * Something the compiler wants the author to know. [code] is stable and machine-readable
 * (`PARSE_ERROR`, `COMPILE_ERROR`, `MISSING_RESOURCE`, `INVALID_OPTION`, `WFF_VERSION`); [message]
 * is for people.
 */
public class WffDiagnostic(
  public val severity: WffSeverity,
  public val code: String,
  public val message: String,
) {
  override fun toString(): String = "$severity $code: $message"
}

/**
 * The outcome of a compile. Compiling never throws for bad input: a document that could not be
 * compiled has [rcBytes] `null` and an [WffSeverity.ERROR] diagnostic, so a host keeps showing the
 * previous good `.rc` while the author is mid-edit.
 *
 * @property rcBytes the serialized Remote Compose document, or `null` on failure.
 * @property namedValues every host-seedable variable the document reads, with defaults.
 * @property widthPx the compiled document's width.
 * @property heightPx the compiled document's height.
 * @property watchFaceWidth the `<WatchFace width>` the author designed against.
 * @property watchFaceHeight the `<WatchFace height>`.
 * @property wffVersion the target version used (see [WffCompileOptions.wffVersion]).
 * @property compileMillis wall time of this call.
 */
public class WffCompileResult(
  public val rcBytes: ByteArray?,
  public val namedValues: List<NamedValueSpec>,
  public val diagnostics: List<WffDiagnostic>,
  public val widthPx: Int,
  public val heightPx: Int,
  public val watchFaceWidth: Int,
  public val watchFaceHeight: Int,
  public val wffVersion: Int,
  public val compileMillis: Long,
) {
  public val isSuccess: Boolean
    get() = rcBytes != null && diagnostics.none { it.severity == WffSeverity.ERROR }

  override fun toString(): String =
    "WffCompileResult(rc=${rcBytes?.size ?: "none"}B, ${namedValues.size} named values, " +
      "${diagnostics.size} diagnostics, ${compileMillis}ms)"
}

/**
 * Watch Face Format XML to Remote Compose, as a library.
 *
 * ```
 * val result = WffRemoteCompose.compile(xml, WffResourceSource.of(resFiles))
 * result.rcBytes?.let(player::load)
 * // Seed what the preview controls drive, without recompiling:
 * player.namedValues["dwf.is_ambient"] = 1
 * ```
 *
 * Calls are independent and thread-safe; each does its own parse and capture. The call blocks its
 * thread (CPU-bound, no I/O unless [WffResourceSource.Directories]).
 */
public object WffRemoteCompose {
  public fun compile(
    xml: String,
    resources: WffResourceSource = WffResourceSource.Empty,
    options: WffCompileOptions = WffCompileOptions(),
  ): WffCompileResult {
    val started = System.nanoTime()
    val diagnostics = mutableListOf<WffDiagnostic>()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000

    var version = options.wffVersion
    if (version !in WffCompileOptions.MIN_WFF_VERSION..WffCompileOptions.MAX_WFF_VERSION) {
      diagnostics +=
        WffDiagnostic(
          WffSeverity.WARNING,
          "INVALID_OPTION",
          "wffVersion $version is outside 1..4; using ${WffCompileOptions.DEFAULT_WFF_VERSION}.",
        )
      version = WffCompileOptions.DEFAULT_WFF_VERSION
    }
    diagnostics +=
      WffDiagnostic(
        WffSeverity.INFO,
        "WFF_VERSION",
        "Target WFF version $version is advisory: features newer than it are not flagged yet.",
      )

    fun failure(code: String, message: String): WffCompileResult {
      diagnostics += WffDiagnostic(WffSeverity.ERROR, code, message)
      return WffCompileResult(
        rcBytes = null,
        namedValues = emptyList(),
        diagnostics = diagnostics,
        widthPx = 0,
        heightPx = 0,
        watchFaceWidth = 0,
        watchFaceHeight = 0,
        wffVersion = version,
        compileMillis = elapsed(),
      )
    }

    val resolver =
      MissingTrackingResolver(
        DwfResourceDirectoryResolver.over(resources.toTrees()),
        placeholder = options.placeholderMissingResources,
      )

    val doc =
      try {
        DwfParser.parse(xml, resolver)
      } catch (e: Exception) {
        return failure("PARSE_ERROR", e.message ?: e.javaClass.simpleName)
      }

    val renderOptions =
      DwfRenderOptions(
        isAmbientDefault = options.ambient,
        flavorDefault = options.flavor,
        configurationDefaults = options.configuration,
        applyWatchShapeClip = options.clipToWatchShape,
      )
    val widthPx = (options.widthPx ?: doc.width).coerceAtLeast(1)
    val heightPx = (options.heightPx ?: doc.height).coerceAtLeast(1)
    val rcBytes =
      try {
        runBlocking {
          captureDwfRemoteDocumentBytes(
            document = doc,
            resources = resolver,
            options = renderOptions,
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = (options.density * 160f).roundToInt().coerceAtLeast(1),
          )
        }
      } catch (e: Exception) {
        return failure("COMPILE_ERROR", e.message ?: e.javaClass.simpleName)
      }

    val namedValues =
      try {
        extractNamedValueSpecs(
          RcDocumentCodec.decode(rcBytes),
          doc.userConfigurations
            .filterIsInstance<DwfUserConfiguration.BooleanConfiguration>()
            .map { it.id }
            .toSet(),
        )
      } catch (e: Exception) {
        diagnostics +=
          WffDiagnostic(
            WffSeverity.WARNING,
            "NAMED_VALUES_UNAVAILABLE",
            "Could not read named values back from the compiled document: ${e.message}",
          )
        emptyList()
      }

    for (name in resolver.missing()) {
      diagnostics +=
        WffDiagnostic(
          WffSeverity.WARNING,
          "MISSING_RESOURCE",
          "Resource '$name' not found" +
            if (options.placeholderMissingResources) "; drawing a placeholder." else ".",
        )
    }

    return WffCompileResult(
      rcBytes = rcBytes,
      namedValues = namedValues,
      diagnostics = diagnostics,
      widthPx = widthPx,
      heightPx = heightPx,
      watchFaceWidth = doc.width,
      watchFaceHeight = doc.height,
      wffVersion = version,
      compileMillis = elapsed(),
    )
  }
}

private fun WffResourceSource.toTrees(): List<DwfResTree> =
  when (this) {
    WffResourceSource.Empty -> emptyList()
    is WffResourceSource.InMemory -> listOf(DwfMapResTree(files))
    is WffResourceSource.Directories -> dirs.map { DwfFileResTree(it) }
  }

/**
 * Wraps the resource lookup to record what the document asked for and could not get, and — when
 * [placeholder] — to hand back a visible stand-in for a missing bitmap. A name is "missing" only
 * when neither a bitmap nor a vector drawable resolves: the renderer asks for the bitmap first.
 */
private class MissingTrackingResolver(
  private val inner: DwfResourceDirectoryResolver,
  private val placeholder: Boolean,
) : DwfResourceResolver {
  private val missingDrawables = LinkedHashSet<String>()
  private val missingOther = LinkedHashSet<String>()
  private val placeholderBitmap: ImageBitmap? by lazy { createDwfPlaceholderBitmap() }

  fun missing(): List<String> = (missingDrawables + missingOther).toList()

  override fun resolveImage(name: String): ImageBitmap? {
    inner.resolveImage(name)?.let {
      return it
    }
    if (inner.resolveVectorDrawable(name) != null) return null
    if (!isResourceName(name)) return null
    missingDrawables += displayName(name)
    return if (placeholder) placeholderBitmap else null
  }

  override fun resolveVectorDrawable(name: String): DwfVectorDrawable? =
    inner.resolveVectorDrawable(name)

  override fun resolveColor(name: String): Int? =
    inner.resolveColor(name).also {
      if (it == null && name.trim().startsWith("@color/")) missingOther += name.trim()
    }

  override fun resolveString(name: String): String? =
    inner.resolveString(name).also {
      if (it == null && name.trim().startsWith("@string/")) missingOther += name.trim()
    }

  override fun resolveFontFamily(name: String): String? =
    inner.resolveFontFamily(name).also {
      // The directory resolver names any `@font/` reference without checking the file exists.
      if (name.trim().startsWith("@font/") && !inner.hasFont(name)) missingOther += name.trim()
    }

  override fun resolveFontBytes(name: String): ByteArray? = inner.resolveFontBytes(name)

  /** Expression sources (`[CONFIGURATION.x]`) and blanks are not resource references. */
  private fun isResourceName(name: String): Boolean {
    val t = name.trim()
    return t.isNotEmpty() && !t.startsWith("[")
  }

  private fun displayName(name: String): String = normalizeDwfResourceKey(name).ifEmpty { name }
}
