package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import java.io.File
import kotlinx.serialization.json.Json

/**
 * A [ServeHost] backed by a **portable bundle** on disk (the `ServeBundle` / WebEmbed layout:
 * `previews/<id>.png` beside an `index.html`), not a daemon. This is the shared/public mode: a
 * pre-rendered bundle is uploaded once and served read-only, with no checkout, build, or render
 * session. Overrides are ignored (the bundle is whatever was baked); there is no live stream lane,
 * so connections transparently use the snapshot fallback that returns these PNGs.
 *
 * Cheap and stateless (just file reads), so the registry pins it resident rather than suspending
 * it.
 */
class ServeBundleHost(private val bundleDir: File, override val label: String) : ServeHost {

  private val previewsDir = File(bundleDir, PREVIEWS_SUBDIR)

  override val previews: List<ServePreview> =
    // Walk recursively: a preview id may contain '/', stored as a nested `previews/<id>.png`. Ids
    // are reconstructed relative to `previews/` with '/' separators (matching the bundle layout).
    previewsDir
      .walkTopDown()
      .filter { it.isFile && it.name.endsWith(PNG_SUFFIX) }
      .map { it.relativeTo(previewsDir).invariantSeparatorsPath.removeSuffix(PNG_SUFFIX) }
      .sorted()
      .map { id -> ServePreview(id = id, label = id, overrides = readOverrides(id)) }
      .toList()

  /**
   * Read the editable knobs carried for [id] in the bundle's `previews/<id>.overrides.json` sidecar
   * (the `compose/overrides` payload the producer packed). Absent / unreadable → no knobs. The host
   * can't re-render (it replays baked PNGs), so [canApplyOverrides] stays false and the viewer
   * shows these as disabled, informational controls.
   */
  private fun readOverrides(
    id: String
  ): List<ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration> {
    val sidecar = File(previewsDir, "$id$OVERRIDES_SUFFIX")
    if (!sidecar.isFile) return emptyList()
    return try {
      OVERRIDES_JSON.decodeFromString(PreviewOverridesPayload.serializer(), sidecar.readText())
        .declarations
    } catch (e: Exception) {
      emptyList()
    }
  }

  private val previewIds: Set<String> = previews.map { it.id }.toHashSet()

  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    if (previewId !in previewIds) return RenderOutcome.NotFound
    val png = File(previewsDir, "$previewId$PNG_SUFFIX")
    if (!png.isFile) return RenderOutcome.NotFound
    return RenderOutcome.Ok(png.readBytes())
  }

  /**
   * A bundle has no daemon, so no live lane — callers fall back to the snapshot ([render]) lane.
   */
  override fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? = null

  override fun activeStreamCount(): Int = 0

  override fun close() {
    // Nothing to release — a bundle host owns no daemon or sockets.
  }

  companion object {
    private const val PREVIEWS_SUBDIR = "previews"
    private const val PNG_SUFFIX = ".png"
    private const val OVERRIDES_SUFFIX = ".overrides.json"
    private val OVERRIDES_JSON = Json { ignoreUnknownKeys = true }

    /** True when [dir] looks like a servable bundle (a `previews/` tree with at least one PNG). */
    fun looksLikeBundle(dir: File): Boolean {
      val previews = File(dir, PREVIEWS_SUBDIR)
      return previews.isDirectory &&
        previews.walkTopDown().any { it.isFile && it.name.endsWith(PNG_SUFFIX) }
    }
  }
}
