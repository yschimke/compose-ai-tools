package ee.schimke.composeai.daemon

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Test/harness-only [DesktopHost] subclass that re-packs an inbound `previewId=<id>` payload into a
 * parseable `className=…;functionName=…` [RenderSpec] payload by looking the previewId up in a JSON
 * manifest provided by the harness.
 *
 * Mirrors the test-only `SpecRoutingHost` from
 * [JsonRpcDesktopIntegrationTest][ee.schimke.composeai.daemon.JsonRpcDesktopIntegrationTest], but
 * lives in the main source set so [DaemonMain] can mount it when spawned by `:daemon:harness`'s
 * `RealDesktopHarnessLauncher`. Without this routing the real daemon (driven by
 * `JsonRpcServer.handleRenderNow`, which only forwards `previewId=<id>` in the payload — see
 * `JsonRpcServer.kt` line ~352) would fall through to [DesktopHost.dispatchRender]'s
 * `renderStubFallback` path, producing no PNG.
 *
 * **Activated only when** `-Dcomposeai.harness.previewsManifest=<path>` is set on the JVM —
 * production daemon launches don't pass it, so production behaviour is unchanged. **Pending** `B2.2
 * — IncrementalDiscovery` lands the daemon's own `previews.json` ownership and a typed `previewId`
 * field on `RenderRequest`, at which point this whole routing concept folds into `JsonRpcServer`
 * itself and this class goes away.
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
  engine: RenderEngine = RenderEngine(),
  userClassloaderHolder: UserClassLoaderHolder? = null,
) :
  DesktopHost(
    engine = engine,
    userClassloaderHolder = userClassloaderHolder,
    // Issue #1203 — wire a manifest-backed `previewSpecResolver` so harness real-mode tests can
    // drive `interactive/start` + `recording/start` against the same fixtures `submit` already
    // routes by id. Without this the parent `DesktopHost` advertises `supportsInteractive = false`
    // and `interactive/start` falls back to v1, which the new key-dispatch scenarios can't use.
    previewSpecResolver = manifestPreviewSpecResolver(manifest.previews.associateBy { it.id }),
  ) {

  private val byId: Map<String, PreviewManifestEntry> = manifest.previews.associateBy { it.id }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request !is RenderRequest.Shutdown) {
      "Use shutdown() to stop the host, not submit(Shutdown)."
    }
    val typed = request as RenderRequest.Render
    val inbound = parseInboundPayload(typed.payload)
    val previewId = inbound["previewId"]
    val entry =
      previewId?.let { byId[it] }
        ?: error(
          "PreviewManifestRouter: no manifest entry for previewId='${previewId ?: "<missing>"}' " +
            "(payload='${typed.payload}'). Manifest knows: ${byId.keys}"
        )
    val resolved = entry.resolved()
    // PROTOCOL.md § 5 (`renderNow.overrides.device`) — when the inbound carries a `device=` token
    // we resolve it against the catalog and use its widthPx/heightPx/density as the BASE for this
    // render, replacing the manifest's per-preview defaults. Explicit `widthPx` / `heightPx` /
    // `density` overrides on the same call still win over the device-derived values, so a caller
    // can say `device=id:pixel_5;widthPx=600` to force a wider window on the Pixel's density.
    val deviceOverride = inbound["device"]?.takeIf { it.isNotBlank() }
    val deviceSpec = deviceOverride?.let {
      ee.schimke.composeai.daemon.devices.DeviceDimensions.resolve(it)
    }
    val baseWidthPx = deviceSpec?.let { (it.widthDp * it.density).toInt() } ?: resolved.widthPx
    val baseHeightPx = deviceSpec?.let { (it.heightDp * it.density).toInt() } ?: resolved.heightPx
    val baseDensity = deviceSpec?.density ?: resolved.density
    val effectiveDevice = deviceOverride ?: resolved.device
    // Issue #1208 — `orientation` on desktop is a `widthPx ↔ heightPx` swap; explicit pixel
    // overrides (or device-derived dims) win over the hint. The hint is idempotent: only swap
    // when the requested orientation conflicts with the current aspect ratio (e.g. a base that
    // is already landscape + `orientation=landscape` stays put). We apply the swap here, before
    // the rewritten payload reaches `DesktopHost`, because the router unconditionally emits
    // widthPx/heightPx tokens which would otherwise hide the "no explicit dims" signal from the
    // downstream `DesktopHost.specFromPreviewIdPayload` swap branch.
    val requestedOrientation = inbound["orientation"]?.lowercase()
    val orientationCanSwap =
      inbound["widthPx"] == null && inbound["heightPx"] == null && deviceOverride == null
    val shouldSwapOrientation =
      orientationCanSwap &&
        when (requestedOrientation) {
          "landscape" -> baseHeightPx > baseWidthPx
          "portrait" -> baseWidthPx > baseHeightPx
          else -> false
        }
    val effectiveBaseWidthPx = if (shouldSwapOrientation) baseHeightPx else baseWidthPx
    val effectiveBaseHeightPx = if (shouldSwapOrientation) baseWidthPx else baseHeightPx
    val routed =
      RenderRequest.Render(
        id = typed.id,
        payload =
          buildString {
            append("previewId=").append(entry.id).append(';')
            inbound["mode"]?.let { append("mode=").append(it).append(';') }
            append("className=").append(entry.className).append(';')
            append("functionName=").append(entry.functionName).append(';')
            // Inbound explicit override wins over both the device-derived value and the
            // per-preview manifest default.
            append("widthPx=").append(inbound["widthPx"] ?: effectiveBaseWidthPx).append(';')
            append("heightPx=").append(inbound["heightPx"] ?: effectiveBaseHeightPx).append(';')
            append("density=").append(inbound["density"] ?: baseDensity).append(';')
            append("showBackground=").append(resolved.showBackground).append(';')
            if (resolved.backgroundColor != 0L) {
              append("backgroundColor=").append(resolved.backgroundColor).append(';')
            }
            effectiveDevice
              ?.takeIf { it.isNotBlank() }
              ?.let { append("device=").append(it).append(';') }
            // `@Preview(showSystemUi = ...)` (issue #1930) — forwarded so the render body wraps the
            // composition in the synthetic `SystemBarsFrame`. Only emitted when set; absent keeps
            // the chrome-less default.
            if (resolved.showSystemUi) append("showSystemUi=true;")
            // PROTOCOL.md § 5 (`renderNow.overrides`) — locale / fontScale / uiMode / orientation
            // pass straight through. `orientation = landscape` is applied above as a swap of the
            // forwarded widthPx/heightPx; the token still rides along so downstream consumers see
            // the resolved orientation. Other fields are honoured by `RenderEngine` directly.
            inbound["localeTag"]?.let { append("localeTag=").append(it).append(';') }
            inbound["fontScale"]?.let { append("fontScale=").append(it).append(';') }
            inbound["uiMode"]?.let { append("uiMode=").append(it).append(';') }
            inbound["orientation"]?.let { append("orientation=").append(it).append(';') }
            inbound["captureAdvanceMs"]?.let { append("captureAdvanceMs=").append(it).append(';') }
            inbound["inspectionMode"]?.let { append("inspectionMode=").append(it).append(';') }
            inbound["overrides"]?.let { append("overrides=").append(it).append(';') }
            // `@PreviewWrapper(SomeProvider::class)` FQN sourced from `previews.json` (the
            // gradle plugin's `extractWrapperFqn` reads it off the class-file annotation tables
            // — the upstream annotation is `AnnotationRetention.BINARY` and invisible to
            // `Method.annotations` at runtime, so this manifest-side plumbing is the only path
            // that can recover the wrapper FQN for the render body). See issue #1440.
            resolved.wrapperClassName
              ?.takeIf { it.isNotBlank() }
              ?.let { append("wrapperClassName=").append(it).append(';') }
            // kind=LOTTIE + the asset path so `DesktopHost` builds a `RenderSpec` that inflates the
            // asset instead of reflecting a (non-existent) class. Plain Compose previews omit both.
            resolved.kind
              ?.takeIf { it.isNotBlank() && it != "COMPOSE" }
              ?.let { append("kind=").append(it).append(';') }
            resolved.assetPath
              ?.takeIf { it.isNotBlank() }
              ?.let { append("assetPath=").append(it).append(';') }
            append("outputBaseName=").append(resolved.outputBaseName)
          },
      )
    return super.submit(routed, timeoutMs)
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
    fun loadManifest(file: File, fileSystem: FileSystem = SystemFileSystem): PreviewManifest {
      require(file.isFile) { "PreviewManifestRouter: manifest '$file' does not exist" }
      return json.decodeFromString(
        PreviewManifest.serializer(),
        fileSystem.read(file.path.toPath()) { readUtf8() },
      )
    }

    /**
     * Issue #1203 — build a `previewSpecResolver` lambda from the manifest's entries so the parent
     * `DesktopHost` can advertise interactive / recording support. Returns `null` for unknown ids,
     * which collapses cleanly into the host's existing "no resolver match → throw
     * UnsupportedOperationException" path.
     */
    private fun manifestPreviewSpecResolver(
      byId: Map<String, PreviewManifestEntry>
    ): (String) -> RenderSpec? = { previewId ->
      byId[previewId]?.let { entry ->
        val resolved = entry.resolved()
        RenderSpec(
          className = entry.className,
          functionName = entry.functionName,
          widthPx = resolved.widthPx,
          heightPx = resolved.heightPx,
          density = resolved.density,
          outputBaseName = resolved.outputBaseName,
          showBackground = resolved.showBackground,
          backgroundColor = resolved.backgroundColor,
          device = resolved.device,
          showSystemUi = resolved.showSystemUi,
          wrapperClassName = resolved.wrapperClassName,
          kind = resolved.kind,
          assetPath = resolved.assetPath,
        )
      }
    }
  }
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
 *   true`), so production always rendered at the daemon's hardcoded defaults — diagnosed from the
 *   wear sample's missing circular crop after the URL-ordering fix exposed otherwise-stale renders.
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
   * Raw `@Preview(device = …)` string when the source preview has one set. Forwarded into
   * `RenderSpec` so the Android render path can apply the wear-round crop / `round` resource
   * qualifier; the desktop path ignores it (no circular crop on JVM rendering).
   */
  val device: String? = null,
  /**
   * Flat-schema mirror of `@Preview(showSystemUi = ...)` (issue #1930). Optional; when null the
   * resolver consults the nested `params.showSystemUi`. Drives the synthetic `SystemBarsFrame` wrap
   * in the render body.
   */
  val showSystemUi: Boolean? = null,
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
    val showSystemUi = showSystemUi ?: p?.showSystemUi ?: false
    val wrapperClassName = p?.wrapperClassName
    return ResolvedRenderParams(
      widthPx = widthPx,
      heightPx = heightPx,
      density = density,
      showBackground = showBackground,
      backgroundColor = backgroundColor,
      device = device,
      showSystemUi = showSystemUi,
      outputBaseName = outputBaseName ?: id,
      wrapperClassName = wrapperClassName,
      kind = p?.kind,
      assetPath = p?.assetPath,
    )
  }
}

/**
 * Subset of the plugin's [PreviewParams][ee.schimke.composeai.plugin.PreviewParams] the daemon's
 * render path consumes. Any plugin-side fields the daemon doesn't yet care about (fontScale,
 * locale, uiMode, group, …) are silently dropped via `ignoreUnknownKeys = true`. Add them here when
 * the daemon grows the matching render-path support.
 */
@Serializable
data class PreviewParamsEntry(
  val device: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val density: Float? = null,
  val showBackground: Boolean = false,
  val backgroundColor: Long = 0L,
  /** Preview flavour mirror of `PreviewKind`; `"LOTTIE"` drives the asset-inflate render path. */
  val kind: String? = null,
  /** For `kind="LOTTIE"`: the classpath-relative Lottie asset path. */
  val assetPath: String? = null,
  /**
   * `@Preview(showSystemUi = ...)` (issue #1930). Drives the synthetic `SystemBarsFrame` wrap so
   * the daemon's desktop capture draws Android phone chrome to match the Android / standalone
   * renderers.
   */
  val showSystemUi: Boolean = false,
  /**
   * FQN of the `PreviewWrapperProvider` from `@PreviewWrapper(SomeProvider::class)` when the source
   * preview is annotated. Read at discovery time by `extractWrapperFqn` against the class-file
   * annotation tables (the upstream annotation has `AnnotationRetention.BINARY`, so
   * `Method.annotations` is empty for it at runtime — see issue #1440). Threaded into
   * [RenderSpec.wrapperClassName] for the render body.
   */
  val wrapperClassName: String? = null,
)

/**
 * Output of [PreviewManifestEntry.resolved] — flat, fully-defaulted, ready to format into a
 * `RenderSpec` payload.
 */
data class ResolvedRenderParams(
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val showBackground: Boolean,
  val backgroundColor: Long,
  val device: String?,
  val showSystemUi: Boolean = false,
  val outputBaseName: String,
  val wrapperClassName: String? = null,
  val kind: String? = null,
  val assetPath: String? = null,
)
