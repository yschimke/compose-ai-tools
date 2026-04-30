package ee.schimke.composeai.daemon

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Test/harness-only [RobolectricHost] subclass that re-packs an inbound `previewId=<id>` payload
 * into a parseable `className=…;functionName=…` [RenderSpec] payload by looking the previewId up in
 * a JSON manifest provided by the harness.
 *
 * Mirrors `:daemon:desktop`'s
 * [PreviewManifestRouter][ee.schimke.composeai.daemon.PreviewManifestRouter] (the desktop version)
 * exactly — same JSON schema, same payload reshape rules, same activation sysprop. Lives in this
 * module's main source set so [DaemonMain] can mount it when spawned by `:daemon:harness`'s
 * `RealAndroidHarnessLauncher` (D-harness.v2). Without this routing the real Android daemon
 * (driven by `JsonRpcServer.handleRenderNow`, which only forwards `previewId=<id>` in the payload —
 * see `JsonRpcServer.kt` line ~352) would fall through to [RobolectricHost.SandboxRunner]'s
 * `renderStub` path, producing no PNG.
 *
 * **Activated only when** `-Dcomposeai.harness.previewsManifest=<path>` is set on the JVM —
 * production daemon launches don't pass it, so production behaviour is unchanged. **Pending** `B2.2
 * — IncrementalDiscovery` lands the daemon's own `previews.json` ownership and a typed `previewId`
 * field on `RenderRequest`, at which point this whole routing concept folds into `JsonRpcServer`
 * itself and this class goes away.
 *
 * **Why duplicated rather than promoted to `:daemon:core`.** Per DESIGN § 4 + § 7 the
 * router constructs target-specific `RenderSpec` payloads which are themselves duplicated per
 * backend (B1.4 decision). Promoting the router would force promoting `RenderSpec`, which would
 * widen the renderer-agnostic surface for a type slated for replacement. Two near-identical
 * routers is the documented trade-off.
 *
 * **Manifest schema** (`PreviewManifest`):
 * ```json
 * { "previews": [
 *     { "id": "red-square",
 *       "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
 *       "functionName": "RedSquare",
 *       "widthPx": 64, "heightPx": 64, "density": 1.0 }
 * ] }
 * ```
 *
 * `id` and `className`/`functionName` are required; everything else falls back to [RenderSpec]'s
 * defaults.
 */
class PreviewManifestRouter(
  private val manifest: PreviewManifest,
  userClassloaderHolder: UserClassLoaderHolder? = null,
) : RobolectricHost(userClassloaderHolder = userClassloaderHolder) {

  private val byId: Map<String, PreviewManifestEntry> = manifest.previews.associateBy { it.id }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request !is RenderRequest.Shutdown) {
      "Use shutdown() to stop the host, not submit(Shutdown)."
    }
    val typed = request as RenderRequest.Render
    val previewId = parsePreviewId(typed.payload)
    val entry =
      previewId?.let { byId[it] }
        ?: error(
          "PreviewManifestRouter: no manifest entry for previewId='${previewId ?: "<missing>"}' " +
            "(payload='${typed.payload}'). Manifest knows: ${byId.keys}"
        )
    val outputBaseName = entry.outputBaseName ?: entry.id
    val routed =
      RenderRequest.Render(
        id = typed.id,
        payload =
          buildString {
            append("className=").append(entry.className).append(';')
            append("functionName=").append(entry.functionName).append(';')
            append("widthPx=").append(entry.widthPx ?: 320).append(';')
            append("heightPx=").append(entry.heightPx ?: 320).append(';')
            append("density=").append(entry.density ?: 2.0f).append(';')
            append("showBackground=").append(entry.showBackground ?: true).append(';')
            entry.device?.takeIf { it.isNotBlank() }?.let {
              append("device=").append(it).append(';')
            }
            append("outputBaseName=").append(outputBaseName)
          },
      )
    return super.submit(routed, timeoutMs)
  }

  private fun parsePreviewId(payload: String): String? {
    for (entry in payload.split(';')) {
      val trimmed = entry.trim()
      if (trimmed.startsWith("previewId=")) return trimmed.substring("previewId=".length).trim()
    }
    return null
  }

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /** Loads a [PreviewManifest] from [file]. Throws if the file does not exist or is malformed. */
    fun loadManifest(file: File): PreviewManifest {
      require(file.isFile) { "PreviewManifestRouter: manifest '$file' does not exist" }
      return json.decodeFromString(PreviewManifest.serializer(), file.readText())
    }
  }
}

@Serializable data class PreviewManifest(val previews: List<PreviewManifestEntry>)

@Serializable
data class PreviewManifestEntry(
  val id: String,
  val className: String,
  val functionName: String,
  val widthPx: Int? = null,
  val heightPx: Int? = null,
  val density: Float? = null,
  val showBackground: Boolean? = null,
  /**
   * Raw `@Preview(device = …)` string when the source preview has one set. Forwarded into the
   * `RenderSpec` payload so the render body applies the wear-round crop / `round` resource
   * qualifier for circular Wear devices.
   */
  val device: String? = null,
  val outputBaseName: String? = null,
)
