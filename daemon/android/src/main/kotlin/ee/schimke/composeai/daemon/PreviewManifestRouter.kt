package ee.schimke.composeai.daemon

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Test/harness-only [RobolectricHost] subclass that re-packs an inbound `previewId=<id>` payload
 * into a parseable `className=…;functionName=…` [RenderSpec] payload by looking the previewId up in
 * a JSON manifest provided by the harness.
 *
 * Mirrors `:daemon:desktop`'s
 * [PreviewManifestRouter][ee.schimke.composeai.daemon.PreviewManifestRouter] (the desktop version)
 * exactly — same JSON schema, same payload reshape rules, same activation sysprop. Lives in this
 * module's main source set so [DaemonMain] can mount it when spawned by `:daemon:harness`'s
 * `RealAndroidHarnessLauncher` (D-harness.v2). Without this routing the real Android daemon (driven
 * by `JsonRpcServer.handleRenderNow`, which only forwards `previewId=<id>` in the payload — see
 * `JsonRpcServer.kt` line ~352) would fall through to [RobolectricHost.SandboxRunner]'s
 * `renderStub` path, producing no PNG.
 *
 * **Activated only when** `-Dcomposeai.harness.previewsManifest=<path>` is set on the JVM —
 * production daemon launches don't pass it, so production behaviour is unchanged. **Pending** `B2.2
 * — IncrementalDiscovery` lands the daemon's own `previews.json` ownership and a typed `previewId`
 * field on `RenderRequest`, at which point this whole routing concept folds into `JsonRpcServer`
 * itself and this class goes away.
 *
 * **Why duplicated rather than promoted to `:daemon:core`.** Per DESIGN § 4 + § 7 the router
 * constructs target-specific `RenderSpec` payloads which are themselves duplicated per backend
 * (B1.4 decision). Promoting the router would force promoting `RenderSpec`, which would widen the
 * renderer-agnostic surface for a type slated for replacement. Two near-identical routers is the
 * documented trade-off.
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
  interactiveSessionListener: InteractiveSessionListener? = null,
) :
  RobolectricHost(
    userClassloaderHolder = userClassloaderHolder,
    sandboxCount = sandboxCount,
    userClassloaderHolderFactory = userClassloaderHolderFactory,
    previewSpecResolver = { previewId ->
      manifest.previews.firstOrNull { it.id == previewId }?.renderSpec()
    },
    interactiveSessionListener = interactiveSessionListener,
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
    val deviceSpec = deviceOverride?.let {
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
      // AS-parity wrap flags MUST ride the serialized payload — `RenderSpec.parseFromPayloadOrNull`
      // defaults them false, so without emitting them here the render body never enters the
      // measure-and-crop path and no-height previews reflow past the frame to zero height. An
      // inbound explicit size or a device override pins the axis, so the wrap flag drops on that
      // axis (the base px above already reflect the device/override size).
      if (resolved.wrapWidth && inbound["widthPx"] == null && deviceOverride == null) {
        append("wrapWidth=true;")
      }
      if (resolved.wrapHeight && inbound["heightPx"] == null && deviceOverride == null) {
        append("wrapHeight=true;")
      }
      append("density=").append(inbound["density"] ?: baseDensity).append(';')
      append("showBackground=").append(resolved.showBackground).append(';')
      if (resolved.backgroundColor != 0L) {
        append("backgroundColor=").append(resolved.backgroundColor).append(';')
      }
      effectiveDevice?.takeIf { it.isNotBlank() }?.let { append("device=").append(it).append(';') }
      // PROTOCOL.md § 5 (`renderNow.overrides`) — locale / fontScale / uiMode / orientation
      // pass straight through to the qualifier builder in `RenderEngine`. Wire-format twin
      // of the desktop router; keep both in lockstep so a single payload drives both.
      inbound["localeTag"]?.let { append("localeTag=").append(it).append(';') }
      inbound["fontScale"]?.let { append("fontScale=").append(it).append(';') }
      // uiMode: an inbound override wins; otherwise derive from the manifest-declared
      // `@Preview(uiMode = …)` — the axis a `_Dark`/`_Light` multipreview varies on. Dropping it
      // rendered every such variant identically, so the bundled data products (layout/semantics/
      // figma-svg) for `Foo_Dark` and `Foo_Light` were byte-equal and the published SVG's theme
      // was whatever the session's previous render left behind. Unlike the desktop twin, the
      // no-night case emits an explicit `light`: Robolectric qualifiers are applied incrementally
      // (`setQualifiers("+…")` in `RenderEngine.applyPreviewQualifiers`), so an absent token
      // inherits the previous render's `night` bit and the theme becomes render-order-dependent.
      // An explicit `notnight` reset is Studio's default for `uiMode = 0` previews.
      append("uiMode=")
        .append(inbound["uiMode"] ?: if (uiModeIsNight(resolved.uiMode)) "dark" else "light")
        .append(';')
      inbound["orientation"]?.let { append("orientation=").append(it).append(';') }
      inbound["captureAdvanceMs"]?.let { append("captureAdvanceMs=").append(it).append(';') }
      inbound["inspectionMode"]?.let { append("inspectionMode=").append(it).append(';') }
      inbound["clearBackground"]?.let { append("clearBackground=").append(it).append(';') }
      inbound["overrides"]?.let { append("overrides=").append(it).append(';') }
      inbound["mode"]?.let { append("mode=").append(it).append(';') }
      // Manifest-resolved kind forwards through verbatim; an inbound `kind=` override (rare —
      // currently only test fixtures emit one) wins for parity with the other override fields.
      (inbound["kind"] ?: resolved.kind)
        ?.takeIf { it.isNotBlank() }
        ?.let { append("kind=").append(it).append(';') }
      // `@PreviewWrapper(SomeProvider::class)` FQN sourced from `previews.json` (the gradle
      // plugin's `extractWrapperFqn` reads it off the class-file annotation tables — the
      // upstream annotation is `AnnotationRetention.BINARY` and not visible via
      // `Method.annotations` at runtime, so this manifest-side plumbing is the only path that
      // can recover the wrapper FQN for the render body). Forwarded into the `RenderSpec`
      // payload so [RenderEngine]'s `InvokeWithOptionalWrapper` can route through the
      // wrapper's `Wrap(content)` without resorting to runtime reflection on the composable.
      resolved.wrapperClassName
        ?.takeIf { it.isNotBlank() }
        ?.let { append("wrapperClassName=").append(it).append(';') }
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
    fun loadManifest(file: File, fileSystem: FileSystem = SystemFileSystem): PreviewManifest {
      require(file.isFile) { "PreviewManifestRouter: manifest '$file' does not exist" }
      return json.decodeFromString(
        PreviewManifest.serializer(),
        fileSystem.read(file.path.toPath()) { readUtf8() },
      )
    }
  }
}

private fun PreviewManifestEntry.renderSpec(): RenderSpec {
  val resolved = resolved()
  return RenderSpec(
    previewId = id,
    className = className,
    functionName = functionName,
    widthPx = resolved.widthPx,
    heightPx = resolved.heightPx,
    density = resolved.density,
    showBackground = resolved.showBackground,
    backgroundColor = resolved.backgroundColor,
    device = resolved.device,
    outputBaseName = resolved.outputBaseName,
    kind = resolved.kind,
    wrapperClassName = resolved.wrapperClassName,
    wrapWidth = resolved.wrapWidth,
    wrapHeight = resolved.wrapHeight,
    // The manifest-declared night bit, so a held session composes the same theme the one-shot
    // render painted. Mirrors the desktop router's resolver.
    uiMode = if (uiModeIsNight(resolved.uiMode)) RenderSpec.SpecUiMode.DARK else null,
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
  /**
   * Flat-schema sibling of [PreviewParamsEntry.kind] — see kdoc there. Harness fixtures that use
   * the flat shape can supply this directly; the production gradle plugin writes it under
   * `params.kind` instead. [resolved] consults the flat field first, then the nested one.
   */
  val kind: String? = null,
  /**
   * Flat-schema mirror of `@Preview(uiMode = ...)` (Configuration bits). Optional; when null the
   * resolver consults the nested `params.uiMode`. Only the `UI_MODE_NIGHT_*` bits are consumed —
   * they select the `night`/`notnight` resource qualifier for the render. Mirrors the desktop
   * router.
   */
  val uiMode: Int? = null,
) {
  fun resolved(): ResolvedRenderParams {
    val p = params
    val density = density ?: p?.density ?: 2.0f
    val device = device ?: p?.device
    val kind = kind ?: p?.kind
    val uiMode = uiMode ?: p?.uiMode ?: 0
    // A preview that declares an explicit size — or is pinned to a fixed frame by a device or a
    // non-Compose surface (tile / notification / Glance, whose render helpers consume the concrete
    // widthPx/heightPx) — keeps that frame. One that declares NONE renders wrap-content (AS-parity):
    // the render measures the composable's intrinsic size within a generous sandbox bound and crops
    // to it, so the captured layout/semantics tree — and the figma-svg / wireframe derived from it —
    // reflect the preview's natural size instead of the historical fixed 320² frame that clipped
    // wide content and reflowed tall content to zero height. Mirrors the desktop daemon's resolver
    // and the standalone renderer's wrap crop.
    val pinned = device != null || (kind != null && kind != "COMPOSE")
    val explicitWidthPx = widthPx ?: p?.widthDp?.let { (it * density).toInt() }
    val explicitHeightPx = heightPx ?: p?.heightDp?.let { (it * density).toInt() }
    val wrapWidth = explicitWidthPx == null && !pinned
    val wrapHeight = explicitHeightPx == null && !pinned
    // The generous sandbox bound is only for a WRAPPING axis (measured + cropped). A pinned preview
    // with no explicit size (notification / tile / Glance — their render helpers consume the concrete
    // px) keeps the historical fixed 320px frame, so this fix doesn't resize those surfaces.
    val resolvedWidthPx =
      explicitWidthPx ?: if (wrapWidth) (WRAP_SANDBOX_WIDTH_DP * density).toInt() else DEFAULT_FRAME_PX
    val resolvedHeightPx =
      explicitHeightPx
        ?: if (wrapHeight) (WRAP_SANDBOX_HEIGHT_DP * density).toInt() else DEFAULT_FRAME_PX
    val showBackground = showBackground ?: p?.showBackground ?: true
    val backgroundColor = backgroundColor ?: p?.backgroundColor ?: 0L
    val wrapperClassName = p?.wrapperClassName
    return ResolvedRenderParams(
      widthPx = resolvedWidthPx,
      heightPx = resolvedHeightPx,
      density = density,
      showBackground = showBackground,
      backgroundColor = backgroundColor,
      device = device,
      outputBaseName = outputBaseName ?: id,
      kind = kind,
      wrapperClassName = wrapperClassName,
      wrapWidth = wrapWidth,
      wrapHeight = wrapHeight,
      uiMode = uiMode,
    )
  }

  companion object {
    /**
     * Sandbox bound (dp) for a no-size preview's wrap-content render — matches the standalone
     * renderer's 400×800 dp default and the desktop daemon's [WRAP_SANDBOX_WIDTH_DP]. The render
     * crops to the composable's intrinsic size within this bound; `fillMax*` / LazyColumn measure
     * against it.
     */
    const val WRAP_SANDBOX_WIDTH_DP: Int = 400
    const val WRAP_SANDBOX_HEIGHT_DP: Int = 800

    /**
     * Historical fixed frame (px) for a preview that declares no explicit size and doesn't wrap
     * (device / tile / notification / Glance surfaces). Matches [RenderSpec]'s 320px default —
     * preserving these surfaces' prior render exactly while wrap-content previews use the sandbox
     * bound above.
     */
    const val DEFAULT_FRAME_PX: Int = 320
  }
}

/**
 * Subset of the plugin's [PreviewParams][ee.schimke.composeai.plugin.PreviewParams] the daemon's
 * render path consumes. Any plugin-side fields the daemon doesn't yet care about (fontScale,
 * locale, group, …) are silently dropped via `ignoreUnknownKeys = true`. Add them here when the
 * daemon grows the matching render-path support.
 */
@Serializable
data class PreviewParamsEntry(
  val device: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val density: Float? = null,
  val showBackground: Boolean = false,
  val backgroundColor: Long = 0L,
  /**
   * Raw Configuration bits from `@Preview(uiMode = ...)`. Only the `UI_MODE_NIGHT_*` bits are
   * consumed — they drive the `night`/`notnight` resource qualifier so a `_Dark` multipreview
   * variant actually renders dark (and its captured layout/semantics/figma-svg data products
   * differ from the `_Light` sibling's). Mirrors the desktop router.
   */
  val uiMode: Int = 0,
  /**
   * `"COMPOSE"` / `"TILE"` / `"NOTIFICATION"` / `"GLANCE_APPWIDGET"` — mirrors
   * `ee.schimke.composeai.discovery.PreviewKind`. Forwarded through the router so the daemon's
   * render path can dispatch tile / notification / Glance previews to their dedicated renderer
   * helpers instead of the Compose-method reflection path (which throws `NoSuchMethodException` on
   * non-composable entrypoints, and produces an unwrapped misrender for Glance entrypoints).
   */
  val kind: String? = null,
  /**
   * FQN of the `PreviewWrapperProvider` from `@PreviewWrapper(SomeProvider::class)` when the source
   * preview is annotated. Read at discovery time by `extractWrapperFqn` against the class-file
   * annotation tables — the upstream annotation has `AnnotationRetention.BINARY`, so
   * `Method.annotations` is empty for it at runtime. The daemon's render path consumes this field
   * to drive `InvokeWithOptionalWrapper`; without the manifest plumbing the daemon would see no
   * wrapper present and render the preview body directly (the original "Invalid applier" crash that
   * motivated `@PreviewWrapper` in the first place).
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
  val outputBaseName: String,
  val kind: String? = null,
  val wrapperClassName: String? = null,
  /**
   * AS-parity wrap-content flags (see [RenderSpec.wrapWidth]). Set when the preview declares no
   * explicit size / device / non-Compose surface, so [widthPx]/[heightPx] are a sandbox bound and
   * the render crops to the composable's intrinsic size.
   */
  val wrapWidth: Boolean = false,
  val wrapHeight: Boolean = false,
  /** Raw `@Preview(uiMode = ...)` Configuration bits; 0 = unset. See [PreviewParamsEntry.uiMode]. */
  val uiMode: Int = 0,
)
