package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.devices.frameDpOverriddenBy
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
    val routed = RenderRequest.Render(id = typed.id, payload = routePayload(typed.payload))
    return super.submit(routed, timeoutMs)
  }

  /**
   * Rewrites an inbound `previewId=<id>` payload into the parseable `className=…;functionName=…`
   * [RenderSpec] payload the parent host dispatches. Split out of [submit] — as on the Android twin
   * — so the routing rules are unit-testable without standing a render thread up.
   */
  internal fun routePayload(payload: String): String {
    val inbound = parseInboundPayload(payload)
    val previewId = inbound["previewId"]
    // Issue #3749 — an exact manifest hit is the ordinary case; a miss may still be a `@Preview
    // Parameter` **row** of a known base id (`<baseId>_Dark` / `<baseId>_PARAM_4`), which discovery
    // could not have enumerated. [rowAddressed] resolves that against the entries we do have.
    val addressed = previewId?.let(::rowAddressed)
    val entry =
      addressed?.entry
        ?: error(
          "PreviewManifestRouter: no manifest entry for previewId='${previewId ?: "<missing>"}' " +
            "(payload='$payload'). Manifest knows: ${byId.keys}"
        )
    // An explicit inbound token wins over the one parsed out of the id, so a caller can render a
    // row of the bare base id without minting a row id for it.
    val row = inbound["previewParameterRow"]?.takeIf { it.isNotBlank() } ?: addressed.row
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
    val baseWidthPx =
      deviceSpec?.let { (it.widthDp * it.density).toInt().coerceAtLeast(1) } ?: resolved.widthPx
    val baseHeightPx =
      deviceSpec?.let { (it.heightDp * it.density).toInt().coerceAtLeast(1) } ?: resolved.heightPx
    val baseDensity = deviceSpec?.density ?: resolved.density
    val effectiveDevice = deviceOverride ?: resolved.device
    // Issue #1208 — `orientation` on desktop is a `widthPx ↔ heightPx` swap; explicit pixel
    // overrides win over the hint. The hint is idempotent: only swap when the requested
    // orientation conflicts with the current aspect ratio (e.g. a base that is already landscape
    // + `orientation=landscape` stays put). We apply the swap here, before the rewritten payload
    // reaches `DesktopHost`, because the router unconditionally emits widthPx/heightPx tokens
    // which would otherwise hide the "no explicit dims" signal from the downstream
    // `DesktopHost.specFromPreviewIdPayload` swap branch.
    //
    // A `device=` token is deliberately NOT treated as "explicit dims" here (#3547). The device
    // supplies the frame's *natural* geometry — `id:pixel_tablet` is 1280×800, landscape — and
    // rotating that frame is the whole point of asking for `orientation=portrait` alongside it.
    // Excluding the device lane meant the commonest spelling of the request (pick a tablet, then
    // pick portrait) silently rendered landscape. Only `widthPx` / `heightPx`, where the caller
    // named the pixels outright, outrank the rotation.
    val orientationCanSwap = inbound["widthPx"] == null && inbound["heightPx"] == null
    val (effectiveBaseWidthPx, effectiveBaseHeightPx) =
      if (orientationCanSwap)
        ee.schimke.composeai.daemon.devices.FrameOrientation.orientedPx(
          baseWidthPx,
          baseHeightPx,
          inbound["orientation"],
        )
      else baseWidthPx to baseHeightPx
    // The wrap flags name an *axis*, so a rotated frame trades them with the dimensions: a
    // fixed-width / wrapped-height preview turned landscape must wrap width instead, or the
    // measure-and-crop pass sizes the axis that is no longer the free one.
    val rotated = effectiveBaseWidthPx != baseWidthPx || effectiveBaseHeightPx != baseHeightPx
    val resolvedWrapWidth = if (rotated) resolved.wrapHeight else resolved.wrapWidth
    val resolvedWrapHeight = if (rotated) resolved.wrapWidth else resolved.wrapHeight
    return buildString {
      // The *requested* id, not the base entry's — a row render is its own preview as far as
      // every downstream consumer keyed by previewId is concerned (data products, history,
      // the panel's card).
      append("previewId=").append(previewId).append(';')
      inbound["mode"]?.let { append("mode=").append(it).append(';') }
      append("className=").append(entry.className).append(';')
      append("functionName=").append(entry.functionName).append(';')
      // Inbound explicit override wins over both the device-derived value and the
      // per-preview manifest default.
      append("widthPx=").append(inbound["widthPx"] ?: effectiveBaseWidthPx).append(';')
      append("heightPx=").append(inbound["heightPx"] ?: effectiveBaseHeightPx).append(';')
      // AS-parity wrap-content: a no-size preview crops to its intrinsic size (the widthPx/
      // heightPx above are then a sandbox bound, not a fixed frame). An inbound explicit
      // `widthPx`/`heightPx` (or a device-derived size) pins that axis, so wrap is emitted
      // only
      // when neither an inbound override nor a device forced a size on it.
      if (inbound["widthPx"] == null && deviceSpec == null && resolvedWrapWidth) {
        append("wrapWidth=true;")
      }
      if (inbound["heightPx"] == null && deviceSpec == null && resolvedWrapHeight) {
        append("wrapHeight=true;")
      }
      append("density=").append(inbound["density"] ?: baseDensity).append(';')
      append("showBackground=").append(resolved.showBackground).append(';')
      if (resolved.backgroundColor != 0L) {
        append("backgroundColor=").append(resolved.backgroundColor).append(';')
      }
      effectiveDevice?.takeIf { it.isNotBlank() }?.let { append("device=").append(it).append(';') }
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
      // uiMode: an inbound override wins; otherwise derive from the manifest-declared
      // `@Preview(uiMode = …)` so a night `showSystemUi` preview paints dark
      // `SystemBarsFrame`
      // chrome in the live daemon, matching the standalone Gradle renderer (which gets the
      // raw
      // uiMode arg). `RenderSpec.parseFromPayload` only understands `light`/`dark`, so map
      // the
      // night bit to a token; light/unset emits nothing (the daemon's default).
      val effectiveUiMode =
        inbound["uiMode"] ?: if ((resolved.uiMode and 0x30) == 0x20) "dark" else null
      effectiveUiMode?.let { append("uiMode=").append(it).append(';') }
      inbound["orientation"]?.let { append("orientation=").append(it).append(';') }
      inbound["captureAdvanceMs"]?.let { append("captureAdvanceMs=").append(it).append(';') }
      inbound["inspectionMode"]?.let { append("inspectionMode=").append(it).append(';') }
      inbound["slotMode"]?.let { append("slotMode=").append(it).append(';') }
      inbound["clearBackground"]?.let { append("clearBackground=").append(it).append(';') }
      inbound["overrides"]?.let { append("overrides=").append(it).append(';') }
      // `@PreviewWrapper(SomeProvider::class)` FQN sourced from `previews.json` (the
      // gradle plugin's `extractWrapperFqn` reads it off the class-file annotation tables
      // — the upstream annotation is `AnnotationRetention.BINARY` and invisible to
      // `Method.annotations` at runtime, so this manifest-side plumbing is the only path
      // that can recover the wrapper FQN for the render body). See issue #1440.
      resolved.wrapperClassName
        ?.takeIf { it.isNotBlank() }
        ?.let { append("wrapperClassName=").append(it).append(';') }
      // `@PreviewParameter` provider FQN sourced from `previews.json` (same BINARY-retention
      // provenance as the wrapper — discovery reads it off the class-file annotation tables).
      // When set the render body renders the provider's first value under the bare id. The
      // limit rides along so the resolver's `limit <= 0` guard matches the annotation.
      resolved.previewParameterProviderClassName
        ?.takeIf { it.isNotBlank() }
        ?.let {
          append("previewParameterProvider=").append(it).append(';')
          append("previewParameterLimit=").append(resolved.previewParameterLimit).append(';')
          // Which row to bind (issue #3749). Absent means value 0, the pre-existing
          // contract.
          row?.let { r -> append("previewParameterRow=").append(r).append(';') }
        }
      // kind=LOTTIE + the asset path so `DesktopHost` builds a `RenderSpec` that inflates the
      // asset instead of reflecting a (non-existent) class. Plain Compose previews omit both.
      resolved.kind
        ?.takeIf { it.isNotBlank() && it != "COMPOSE" }
        ?.let { append("kind=").append(it).append(';') }
      resolved.assetPath
        ?.takeIf { it.isNotBlank() }
        ?.let { append("assetPath=").append(it).append(';') }
      // A row render writes its own artifact, keyed the way the fan-out renderer keys it
      // (`<stem>_<row>.png`), so rendering row 4 can't clobber row 0's PNG or the data
      // products the file-backed registry resolves from it.
      append("outputBaseName=").append(resolved.outputBaseName)
      row?.let { append('_').append(it) }
    }
  }

  /**
   * Resolves [previewId] to the manifest entry that should render it, plus the `@PreviewParameter`
   * row it names (null for an ordinary preview).
   *
   * Exact hit first; on a miss, [PreviewRowAddress] splits `<baseId>_<row>` against the entries
   * that declare a provider. Returns null when neither resolves, which is the caller's "unknown
   * previewId" error.
   */
  internal fun rowAddressed(previewId: String): Addressed? {
    byId[previewId]?.let {
      return Addressed(it, null)
    }
    val split = PreviewRowAddress.split(previewId, isParameterized(byId)) ?: return null
    return Addressed(byId.getValue(split.baseId), split.row)
  }

  /** A previewId resolved against the manifest: the entry to render, and which row of it. */
  internal data class Addressed(val entry: PreviewManifestEntry, val row: String?)

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
     * [PreviewRowAddress.split]'s predicate over a manifest: "is this base id an entry that
     * declares a `@PreviewParameter` provider?". Gating on the provider — not merely on the id
     * existing — is what stops an unrelated preview that happens to share a prefix from being read
     * as a row of its neighbour.
     */
    internal fun isParameterized(byId: Map<String, PreviewManifestEntry>): (String) -> Boolean = {
      byId[it]?.resolved()?.previewParameterProviderClassName?.isNotBlank() == true
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
      // Row-addressed ids (issue #3749) resolve here too, so a held session — `interactive/start`,
      // `recording/start`, `stream/start` — on `<baseId>_Dark` composes that row rather than
      // falling back to "unknown previewId" (which surfaces as MethodNotFound and drops the panel
      // to v1).
      val split =
        if (byId.containsKey(previewId)) null
        else PreviewRowAddress.split(previewId, isParameterized(byId))
      byId[split?.baseId ?: previewId]?.let { entry ->
        val resolved = entry.resolved()
        RenderSpec(
          className = entry.className,
          functionName = entry.functionName,
          widthPx = resolved.widthPx,
          heightPx = resolved.heightPx,
          wrapWidth = resolved.wrapWidth,
          wrapHeight = resolved.wrapHeight,
          density = resolved.density,
          outputBaseName = resolved.outputBaseName,
          showBackground = resolved.showBackground,
          backgroundColor = resolved.backgroundColor,
          device = resolved.device,
          showSystemUi = resolved.showSystemUi,
          // Map the manifest night bit to the daemon's light/dark enum so interactive/recording
          // sessions of a night `showSystemUi` preview also get dark chrome (issue #1930
          // follow-up).
          uiMode = if ((resolved.uiMode and 0x30) == 0x20) RenderSpec.SpecUiMode.DARK else null,
          wrapperClassName = resolved.wrapperClassName,
          previewParameterProviderClassName = resolved.previewParameterProviderClassName,
          previewParameterLimit = resolved.previewParameterLimit,
          previewParameterRow = split?.row,
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
  /**
   * Flat-schema mirror of `@Preview(uiMode = ...)`. Optional; when null the resolver consults the
   * nested `params.uiMode`. Only the `UI_MODE_NIGHT_*` bits matter — drives dark `SystemBarsFrame`
   * chrome for night `showSystemUi` previews (issue #1930 follow-up).
   */
  val uiMode: Int? = null,
  /**
   * Flat-schema mirror of `@PreviewParameter`'s provider FQN (used by `:daemon:harness` tests).
   * Optional; when null the resolver consults the nested
   * `params.previewParameterProviderClassName`. When set the render body renders the provider's
   * first value under the bare id.
   */
  val previewParameterProviderClassName: String? = null,
  /** Flat-schema mirror of `@PreviewParameter.limit`; null falls back to the nested params. */
  val previewParameterLimit: Int? = null,
  val outputBaseName: String? = null,
) {
  fun resolved(): ResolvedRenderParams {
    val p = params
    val device = device ?: p?.device
    val deviceDims = device?.takeIf { it.isNotBlank() }?.let { DeviceDimensions.resolve(it) }
    // The manifest's density wins (the plugin writes the device's own density there); the catalog's
    // is the fallback, so a bare `spec:…,dpi=160` entry resolves at 1.0 instead of the 2.0 default.
    val density = density ?: p?.density ?: deviceDims?.density ?: 2.0f
    val showSystemUi = showSystemUi ?: p?.showSystemUi ?: false
    // A preview that declares an explicit size, a device frame, or system UI keeps its fixed frame.
    // One that declares NONE renders wrap-content (AS-parity): the render crops to the composable's
    // intrinsic size so the captured layout/semantics tree — and the figma-svg / wireframe derived
    // from it — reflect the preview's natural size, instead of the historical fixed 320² frame that
    // clipped wide content and reflowed text (diverging from the standalone renderer's wrap crop).
    // The sandbox bound (400×800 dp) matches the standalone renderer's DesktopRendererMain default.
    // A device frame owns its geometry (#3113): the manifest's `widthDp`/`heightDp` are ignored and
    // the frame comes from the device catalog instead — the same source the inbound
    // `overrides.device` path in [PreviewManifestRouter.submit] resolves against, and the same one
    // the plugin's `resolveForRender` bakes with. Resolving it HERE is what makes a
    // manifest-declared `@Preview(device = …)` render at its device size: without it a device
    // preview has no explicit size AND doesn't wrap (it's pinned), so it fell through to the
    // 400×800 dp sandbox bound — a Wear preview rendered 1050×2100 instead of 504×504. The
    // manifest's density is kept (the plugin writes the device's own density there); only the dp
    // extent comes from the catalog, so an unknown device string still degrades to the catalog's
    // documented default. The dp→px conversion TRUNCATES, matching `RenderPreviewsTask`'s
    // device-frame branch and the inbound-`device` path in `submit` — a fractional product
    // (id:pixel_5 = 393dp × 2.75 = 1080.75) must land on the same 1080 the bake produces, or the
    // live lane sits one pixel off its own snapshot. Only the explicit-dp path below rounds
    // half-up (#3113). Annotation dp still displace the catalog when BOTH axes are set —
    // [frameDpOverriddenBy] holds that precedence for all four resolvers.
    val deviceFrameDp = deviceDims?.frameDpOverriddenBy(p?.widthDp, p?.heightDp)
    val explicitWidthPx =
      widthPx
        ?: deviceFrameDp?.let { (it.first * density).toInt().coerceAtLeast(1) }
        ?: p?.widthDp?.let { (it * density).roundHalfUpPx() }
    val explicitHeightPx =
      heightPx
        ?: deviceFrameDp?.let { (it.second * density).toInt().coerceAtLeast(1) }
        ?: p?.heightDp?.let { (it * density).roundHalfUpPx() }
    val pinned = device != null || showSystemUi
    val wrapWidth = explicitWidthPx == null && !pinned
    val wrapHeight = explicitHeightPx == null && !pinned
    // A per-preview wrap sandbox narrows the generic 400×800 dp bound WITHOUT fixing the axis — the
    // `wrap*` flags above are untouched, so the capture still crops to measured size. See
    // `discovery.PreviewParams.wrapSandboxWidthDp`.
    val sandboxWidthDp = p?.wrapSandboxWidthDp?.takeIf { it > 0 } ?: WRAP_SANDBOX_WIDTH_DP
    val sandboxHeightDp = p?.wrapSandboxHeightDp?.takeIf { it > 0 } ?: WRAP_SANDBOX_HEIGHT_DP
    val widthPx = explicitWidthPx ?: (sandboxWidthDp * density).roundHalfUpPx()
    val heightPx = explicitHeightPx ?: (sandboxHeightDp * density).roundHalfUpPx()
    val showBackground = showBackground ?: p?.showBackground ?: true
    val backgroundColor = backgroundColor ?: p?.backgroundColor ?: 0L
    val uiMode = uiMode ?: p?.uiMode ?: 0
    val wrapperClassName = p?.wrapperClassName
    val previewParameterProviderClassName =
      previewParameterProviderClassName ?: p?.previewParameterProviderClassName
    val previewParameterLimit = previewParameterLimit ?: p?.previewParameterLimit ?: Int.MAX_VALUE
    return ResolvedRenderParams(
      widthPx = widthPx,
      heightPx = heightPx,
      density = density,
      showBackground = showBackground,
      backgroundColor = backgroundColor,
      device = device,
      showSystemUi = showSystemUi,
      uiMode = uiMode,
      outputBaseName = outputBaseName ?: id,
      wrapperClassName = wrapperClassName,
      kind = p?.kind,
      assetPath = p?.assetPath,
      wrapWidth = wrapWidth,
      wrapHeight = wrapHeight,
      previewParameterProviderClassName = previewParameterProviderClassName,
      previewParameterLimit = previewParameterLimit,
    )
  }

  companion object {
    /**
     * Sandbox bound (dp) for a no-size preview's wrap-content render — matches the standalone
     * renderer's `BundleRenderer` default (400×800 dp). The render crops to the composable's
     * intrinsic size within this bound; `fillMax*` / LazyColumn measure against it.
     */
    const val WRAP_SANDBOX_WIDTH_DP: Int = 400
    const val WRAP_SANDBOX_HEIGHT_DP: Int = 800
  }
}

private fun Float.roundHalfUpPx(): Int = kotlin.math.floor(this + 0.5f).toInt().coerceAtLeast(1)

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
  /**
   * Bound a **wrapped** axis is measured against, replacing [WRAP_SANDBOX_WIDTH_DP] /
   * [WRAP_SANDBOX_HEIGHT_DP]. Mirrors `discovery.PreviewParams.wrapSandboxWidthDp`; unlike
   * [widthDp] it does not fix the axis, so [PreviewManifestEntry.resolved] still reports `wrapWidth
   * = true` and the capture still crops to measured size.
   */
  val wrapSandboxWidthDp: Int? = null,
  /** See [wrapSandboxWidthDp]. */
  val wrapSandboxHeightDp: Int? = null,
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
   * `@Preview(uiMode = ...)`. Only the `UI_MODE_NIGHT_*` bits are consumed — selects dark
   * `SystemBarsFrame` chrome (and dark `LocalSystemTheme`) for night `showSystemUi` previews so the
   * live daemon matches the standalone renderer instead of painting light chrome (issue #1930).
   */
  val uiMode: Int = 0,
  /**
   * FQN of the `PreviewWrapperProvider` from `@PreviewWrapper(SomeProvider::class)` when the source
   * preview is annotated. Read at discovery time by `extractWrapperFqn` against the class-file
   * annotation tables (the upstream annotation has `AnnotationRetention.BINARY`, so
   * `Method.annotations` is empty for it at runtime — see issue #1440). Threaded into
   * [RenderSpec.wrapperClassName] for the render body.
   */
  val wrapperClassName: String? = null,
  /**
   * FQN of the `@PreviewParameter` provider harvested by discovery (BINARY-retention annotation,
   * invisible to runtime reflection — same provenance as [wrapperClassName]). Threaded into
   * [RenderSpec.previewParameterProviderClassName] so the render body renders the provider's first
   * value under the bare id.
   */
  val previewParameterProviderClassName: String? = null,
  /** Mirrors `@PreviewParameter.limit`. `Int.MAX_VALUE` is the annotation default. */
  val previewParameterLimit: Int = Int.MAX_VALUE,
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
  val uiMode: Int = 0,
  val outputBaseName: String,
  val wrapperClassName: String? = null,
  val kind: String? = null,
  val assetPath: String? = null,
  /**
   * AS-parity wrap-content flags (see [RenderSpec.wrapWidth]). Set when the preview declares no
   * explicit size/device/system-ui, so [widthPx]/[heightPx] are a sandbox bound and the render
   * crops to the composable's intrinsic size — matching the standalone renderer and keeping the
   * captured tree (figma-svg / wireframe / semantics) at the preview's natural size.
   */
  val wrapWidth: Boolean = false,
  val wrapHeight: Boolean = false,
  val previewParameterProviderClassName: String? = null,
  val previewParameterLimit: Int = Int.MAX_VALUE,
)
