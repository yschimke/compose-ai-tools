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
  sandboxCount: Int = 1,
  userClassloaderHolderFactory: ((sandboxClassLoader: ClassLoader) -> UserClassLoaderHolder)? =
    null,
) : RobolectricHost(
  userClassloaderHolder = userClassloaderHolder,
  sandboxCount = sandboxCount,
  userClassloaderHolderFactory = userClassloaderHolderFactory,
  previewSpecResolver = { previewId ->
    manifest.previews.firstOrNull { it.id == previewId }?.renderSpec()
  },
) {

  private val byId: Map<String, PreviewManifestEntry> = manifest.previews.associateBy { it.id }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request !is RenderRequest.Shutdown) {
      "Use shutdown() to stop the host, not submit(Shutdown)."
    }
    val typed = request as RenderRequest.Render
    val routed =
      RenderRequest.Render(
        id = typed.id,
        payload = routePayload(typed.payload),
      )
    return super.submit(routed, timeoutMs)
  }

  internal fun routePayload(payload: String): String {
    val inbound = parseInboundPayload(payload)
    val previewId = inbound["previewId"]
    val entry =
      previewId?.let { byId[it] }
        ?: error(
          "PreviewManifestRouter: no manifest entry for previewId='${previewId ?: "<missing>"}' " +
            "(payload='$payload'). Manifest knows: ${byId.keys}"
        )
    val resolved = entry.resolved()
    // PROTOCOL.md § 5 (`renderNow.overrides.device`) — when the inbound carries a `device=` token
    // we resolve it against the catalog and use its widthPx/heightPx/density as the BASE for this
    // render, replacing the manifest's per-preview defaults. Explicit `widthPx` / `heightPx` /
    // `density` overrides on the same call still win over the device-derived values, so a caller
    // can say `device=id:pixel_5;widthPx=600` to force a wider window on the Pixel's density.
    // The override device string is also written through to the output payload so the Android
    // renderer's `isRoundDevice(spec.device)` round-detection sees it (Wear devices applied via
    // override should still get the circular crop).
    val deviceOverride = inbound["device"]?.takeIf { it.isNotBlank() }
    val deviceSpec =
      deviceOverride?.let {
        ee.schimke.composeai.daemon.devices.DeviceDimensions.resolve(it)
      }
    val baseWidthPx = deviceSpec?.let { (it.widthDp * it.density).toInt() } ?: resolved.widthPx
    val baseHeightPx = deviceSpec?.let { (it.heightDp * it.density).toInt() } ?: resolved.heightPx
    val baseDensity = deviceSpec?.density ?: resolved.density
    val effectiveDevice = deviceOverride ?: resolved.device
    return buildString {
      append("previewId=").append(previewId).append(';')
      append("className=").append(entry.className).append(';')
      append("functionName=").append(entry.functionName).append(';')
      // Inbound explicit override wins over both the device-derived value and the
      // per-preview manifest default.
      append("widthPx=").append(inbound["widthPx"] ?: baseWidthPx).append(';')
      append("heightPx=").append(inbound["heightPx"] ?: baseHeightPx).append(';')
      append("density=").append(inbound["density"] ?: baseDensity).append(';')
      append("showBackground=").append(resolved.showBackground).append(';')
      if (resolved.backgroundColor != 0L) {
        append("backgroundColor=").append(resolved.backgroundColor).append(';')
      }
      effectiveDevice?.takeIf { it.isNotBlank() }?.let {
        append("device=").append(it).append(';')
      }
      // PROTOCOL.md § 5 (`renderNow.overrides`) — locale / fontScale / uiMode / orientation
      // pass straight through to the qualifier builder in `RenderEngine`. Wire-format twin
      // of the desktop router; keep both in lockstep so a single payload drives both.
      inbound["localeTag"]?.let { append("localeTag=").append(it).append(';') }
      inbound["fontScale"]?.let { append("fontScale=").append(it).append(';') }
      inbound["uiMode"]?.let { append("uiMode=").append(it).append(';') }
      inbound["orientation"]?.let { append("orientation=").append(it).append(';') }
      inbound["captureAdvanceMs"]?.let {
        append("captureAdvanceMs=").append(it).append(';')
      }
      inbound["inspectionMode"]?.let { append("inspectionMode=").append(it).append(';') }
      append("outputBaseName=").append(resolved.outputBaseName)
    }
  }

  private fun parseInboundPayload(payload: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (entry in payload.split(';')) {
      val trimmed = entry.trim()
      if (trimmed.isEmpty()) continue
      val eq = trimmed.indexOf('=')
      if (eq <= 0) continue
      val k = trimmed.substring(0, eq).trim()
      val v = trimmed.substring(eq + 1).trim()
      if (v.isNotEmpty()) map[k] = v
    }
    return map
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

private fun PreviewManifestEntry.renderSpec(): RenderSpec {
  val resolved = resolved()
  return RenderSpec(
    className = className,
    functionName = functionName,
    widthPx = resolved.widthPx,
    heightPx = resolved.heightPx,
    density = resolved.density,
    showBackground = resolved.showBackground,
    backgroundColor = resolved.backgroundColor,
    device = resolved.device,
    outputBaseName = resolved.outputBaseName,
  )
}

@Serializable data class PreviewManifest(val previews: List<PreviewManifestEntry>)

/**
 * On-the-wire entry the router reads from `previews.json`. Two shapes coexist:
 *
 * - **Flat** (used by harness tests in `:daemon:harness`): top-level `widthPx` / `heightPx` /
 *   `density` / `showBackground` / `device`. Hand-rolled JSON, easy to write inline.
 * - **Nested** (emitted by the gradle plugin's `DiscoverPreviewsTask`): the canonical
 *   [PreviewInfo][ee.schimke.composeai.plugin.PreviewInfo] schema with a `params` block carrying
 *   `widthDp` / `heightDp` / `density` / `device` / `showBackground` / `backgroundColor`. Pre-fix
 *   the daemon ignored the nested shape entirely (kotlinx.serialization with `ignoreUnknownKeys =
 *   true`), so production always rendered at the daemon's hardcoded defaults (320×320, density 2.0,
 *   no device, no wear-round crop) — diagnosed when the wear sample's circular crop went missing
 *   after the URL-ordering fix exposed otherwise-stale renders.
 *
 * [resolved] returns a [ResolvedRenderParams] that prefers flat fields when set and falls back to
 * the nested params, doing the dp→px conversion the plugin's schema requires.
 */
@Serializable
data class PreviewManifestEntry(
  val id: String,
  val className: String,
  val functionName: String,
  /**
   * Production manifests written by the gradle plugin nest these fields under `params`. Optional
   * because harness tests use the flat schema (see kdoc above); when null, the resolver consults
   * the flat fields below.
   */
  val params: PreviewParamsEntry? = null,
  val widthPx: Int? = null,
  val heightPx: Int? = null,
  val density: Float? = null,
  val showBackground: Boolean? = null,
  val backgroundColor: Long? = null,
  /**
   * Raw `@Preview(device = …)` string when the source preview has one set. Forwarded into the
   * `RenderSpec` payload so the render body applies the wear-round crop / `round` resource
   * qualifier for circular Wear devices.
   */
  val device: String? = null,
  val outputBaseName: String? = null,
) {
  fun resolved(): ResolvedRenderParams {
    val p = params
    val density = density ?: p?.density ?: 2.0f
    val widthPx = widthPx ?: p?.widthDp?.let { (it * density).toInt() } ?: 320
    val heightPx = heightPx ?: p?.heightDp?.let { (it * density).toInt() } ?: 320
    val showBackground = showBackground ?: p?.showBackground ?: true
    val backgroundColor = backgroundColor ?: p?.backgroundColor ?: 0L
    val device = device ?: p?.device
    return ResolvedRenderParams(
      widthPx = widthPx,
      heightPx = heightPx,
      density = density,
      showBackground = showBackground,
      backgroundColor = backgroundColor,
      device = device,
      outputBaseName = outputBaseName ?: id,
    )
  }
}

/**
 * Subset of the plugin's
 * [PreviewParams][ee.schimke.composeai.plugin.PreviewParams] the daemon's render path consumes.
 * Any plugin-side fields the daemon doesn't yet care about (fontScale, locale, uiMode, group, …)
 * are silently dropped via `ignoreUnknownKeys = true`. Add them here when the daemon grows the
 * matching render-path support.
 */
@Serializable
data class PreviewParamsEntry(
  val device: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val density: Float? = null,
  val showBackground: Boolean = false,
  val backgroundColor: Long = 0L,
)

/** Output of [PreviewManifestEntry.resolved] — flat, fully-defaulted, ready to format into a
 *  `RenderSpec` payload. */
data class ResolvedRenderParams(
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val showBackground: Boolean,
  val backgroundColor: Long,
  val device: String?,
  val outputBaseName: String,
)
