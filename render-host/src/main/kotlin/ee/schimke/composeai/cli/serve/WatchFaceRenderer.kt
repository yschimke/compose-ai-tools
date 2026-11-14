package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.WatchFaceComplicationsPayload
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.Serializable

/** Canonical identifiers for Declarative Watch Face (`<WatchFace>`) render backends. */
public object WatchFaceRendererIds {
  /** Watch Face XML compiled to RemoteCompose and rendered via `DwfDaemonMain` / CMP-JVM Skiko. */
  public const val DWF_RC: String = "dwf-rc"

  /** Watch Face rendered via the Wear OS DWF renderer APK (`compose-preview launch` simulator). */
  public const val DWF_APK: String = "dwf-apk"

  /** Query / WebSocket / MCP parameter selecting a watch-face renderer backend. */
  public const val QUERY_PARAM: String = WatchFaceOverrides.WF_RENDERER_PARAM
}

/**
 * Capability and status descriptor for one [WatchFaceRendererBackend] on a given preview, exposed
 * by `/api/previews` and the viewer's lane selector so UI and MCP callers discover renderers
 * dynamically rather than hardcoding a single pipeline.
 */
@Serializable
public data class WatchFaceRendererInfo(
  val id: String,
  val label: String,
  val badgeLabel: String,
  val available: Boolean,
  val unavailableReason: String? = null,
  val supportsLiveOverrides: Boolean = true,
  val isDefault: Boolean = false,
)

/** Outcome of fetching a watch face's complication slot metadata and active slot state. */
public sealed interface WatchFaceComplicationsOutcome {
  public data class Ok(
    val payload: WatchFaceComplicationsPayload,
    val json: ByteArray,
    val rendererId: String = WatchFaceRendererIds.DWF_RC,
  ) : WatchFaceComplicationsOutcome

  public data object NotFound : WatchFaceComplicationsOutcome

  public data class Unavailable(val reason: String) : WatchFaceComplicationsOutcome

  public data class Failed(val reason: String) : WatchFaceComplicationsOutcome
}

/**
 * Pluggable backend for rendering Declarative Watch Faces (`<WatchFace>`) and querying their
 * complication state.
 *
 * Keeping the server's HTTP routes, WebSocket session handler, viewer lane selector, and MCP tools
 * routed through [WatchFaceRendererBackend] allows multiple watch-face renderers (the JVM
 * RemoteCompose renderer [DwfRcWatchFaceRendererBackend] and the launch-based Wear OS DWF renderer
 * APK [DwfApkWatchFaceRendererBackend]) to coexist cleanly behind one contract.
 */
public interface WatchFaceRendererBackend {
  public val id: String
  public val label: String
  public val badgeLabel: String

  /** Describe whether this backend can currently render [preview] on [host]. */
  public fun infoFor(host: ServeHost, preview: ServePreview): WatchFaceRendererInfo

  /** Render [preview] under [overrides] using this backend. */
  public fun render(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): RenderOutcome

  /** Fetch [preview]'s complication metadata and active slot values under [overrides]. */
  public fun complications(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): WatchFaceComplicationsOutcome
}

/**
 * Default DWF renderer backend (`dwf-rc`): compiles `<WatchFace>` XML to RemoteCompose and renders
 * frames via `DwfDaemonMain` on a live [ServeHost], with a stateless fallback to
 * [RcJvmServerRenderer] when a static catalog carries `ir/<id>.rc` without a live daemon seat.
 */
public object DwfRcWatchFaceRendererBackend : WatchFaceRendererBackend {
  override val id: String = WatchFaceRendererIds.DWF_RC
  override val label: String = "DWF RemoteCompose"
  override val badgeLabel: String = "dwf · rc-jvm"

  override fun infoFor(host: ServeHost, preview: ServePreview): WatchFaceRendererInfo {
    val live = host.canRenderOverridesFor(preview.id)
    val rcFallback = host.supportsCmpJvm(preview.id)
    val baked = host.bakedRender(preview.id, PreviewOverrides()) != null
    val available = live || rcFallback || baked
    return WatchFaceRendererInfo(
      id = id,
      label = label,
      badgeLabel = badgeLabel,
      available = available,
      unavailableReason =
        if (available) null
        else "Neither a live DWF daemon nor a captured RemoteCompose document is available",
      supportsLiveOverrides = live || rcFallback,
      isDefault = true,
    )
  }

  override fun render(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): RenderOutcome {
    if (overrides == PreviewOverrides()) {
      host.bakedRender(preview.id, overrides)?.let {
        return it
      }
    }
    if (host.canRenderOverridesFor(preview.id)) {
      return host.render(preview.id, overrides)
    }
    // Stateless fallback for static catalogs/bundles carrying ir/<id>.rc + sidecar declarations:
    // replay through RcJvmServerRenderer when no clockEpochMillis shift is requested (RcJvm's CLI
    // worker does not yet accept a custom clock epoch).
    if (overrides.clockEpochMillis == null && host.supportsCmpJvm(preview.id)) {
      val docBytes = host.remoteComposeDoc(preview.id)
      val baseSpec = host.remoteComposeRenderSpec(preview.id)
      if (docBytes != null && baseSpec != null) {
        val spec =
          baseSpec.copy(
            widthPx = overrides.widthPx ?: baseSpec.widthPx,
            heightPx = overrides.heightPx ?: baseSpec.heightPx,
            density = overrides.density ?: baseSpec.density,
            fontScale = overrides.fontScale ?: 1f,
          )
        val seeds = LinkedHashMap<String, RemoteNamedValue>()
        for (decl in preview.remoteComposeKnobs) {
          seeds[decl.name] = decl.default
        }
        seeds.putAll(WatchFaceOverrides.effectiveRcNamedValues(overrides, preview.watchFace))
        return when (val r = RcJvmServerRenderer.render(docBytes, spec, seeds)) {
          is RcJvmServerRenderer.RenderResult.Ok ->
            RenderOutcome.Ok(r.bytes, RenderOutcome.Generation.DAEMON)
          is RcJvmServerRenderer.RenderResult.Failed -> RenderOutcome.Failed(r.reason)
          is RcJvmServerRenderer.RenderResult.Unavailable -> RenderOutcome.Failed(r.reason)
        }
      }
    }
    return host.render(preview.id, overrides)
  }

  override fun complications(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): WatchFaceComplicationsOutcome = host.renderWatchFaceComplications(preview.id, overrides)
}

/**
 * Second watch-face renderer backend (`dwf-apk`), reserved for rendering `<WatchFace>` packages
 * through the Android Wear OS DWF renderer APK (`compose-preview launch` / `:simulator-host`).
 *
 * The built-in placeholder stays `available = false` until a launch session bridge backend is
 * installed via [WatchFaceRendererRegistry.register], so registry consumers are never offered a
 * backend that cannot render and callers never silently receive `dwf-rc` pixels when explicitly
 * asking for `dwf-apk`.
 */
public object DwfApkWatchFaceRendererBackend : WatchFaceRendererBackend {
  override val id: String = WatchFaceRendererIds.DWF_APK
  override val label: String = "DWF Renderer APK (Launch)"
  override val badgeLabel: String = "dwf · apk"

  public const val APK_PATH_SYS_PROP: String = "composeai.watchface.apkRenderer"
  public const val APK_PATH_ENV: String = "COMPOSE_PREVIEW_DWF_RENDERER_APK"

  internal fun configuredApkFile(): File? {
    val raw =
      System.getProperty(APK_PATH_SYS_PROP)?.takeIf { it.isNotBlank() }
        ?: System.getenv(APK_PATH_ENV)?.takeIf { it.isNotBlank() }
        ?: return null
    return File(raw).takeIf(File::isFile)
  }

  private fun unavailableReason(): String {
    val apk = configuredApkFile()
    return if (apk != null) {
      "wfRenderer '$id' APK '${apk.name}' is configured, but no launch session bridge is registered on this host"
    } else {
      "wfRenderer '$id' is unavailable: no Wear OS DWF renderer APK launch session bridge is registered on this host"
    }
  }

  override fun infoFor(host: ServeHost, preview: ServePreview): WatchFaceRendererInfo =
    WatchFaceRendererInfo(
      id = id,
      label = label,
      badgeLabel = badgeLabel,
      available = false,
      unavailableReason = unavailableReason(),
      supportsLiveOverrides = false,
      isDefault = false,
    )

  override fun render(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): RenderOutcome = RenderOutcome.Failed(unavailableReason())

  override fun complications(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): WatchFaceComplicationsOutcome = WatchFaceComplicationsOutcome.Unavailable(unavailableReason())
}

/**
 * Process-wide registry of [WatchFaceRendererBackend]s.
 *
 * Pre-populated with [DwfRcWatchFaceRendererBackend] (`dwf-rc`, default) and
 * [DwfApkWatchFaceRendererBackend] (`dwf-apk`). Additional or replacement backends (for example a
 * live `launch` simulator bridge for `dwf-apk`, or test doubles) can be installed via [register].
 */
public object WatchFaceRendererRegistry {
  private val backends =
    CopyOnWriteArrayList<WatchFaceRendererBackend>(
      listOf(DwfRcWatchFaceRendererBackend, DwfApkWatchFaceRendererBackend),
    )

  /** All registered watch-face renderer backends in display order. */
  public fun all(): List<WatchFaceRendererBackend> = backends.toList()

  /** Look up a backend by its wire [id] (case-insensitive), or null when unknown. */
  public fun resolve(id: String?): WatchFaceRendererBackend? {
    val normalized = id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return defaultBackend()
    return backends.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
  }

  /** The default backend used when no `wfRenderer` parameter is supplied (`dwf-rc`). */
  public fun defaultBackend(): WatchFaceRendererBackend =
    backends.firstOrNull { it.id == WatchFaceRendererIds.DWF_RC } ?: DwfRcWatchFaceRendererBackend

  /** Renderer descriptors for [preview] on [host], or empty when [preview] is not a watch face. */
  public fun renderersFor(host: ServeHost, preview: ServePreview): List<WatchFaceRendererInfo> {
    if (!preview.isWatchFace) return emptyList()
    return backends.map { it.infoFor(host, preview) }
  }

  /** Register or replace a [WatchFaceRendererBackend] by [WatchFaceRendererBackend.id]. */
  public fun register(backend: WatchFaceRendererBackend) {
    val idx = backends.indexOfFirst { it.id.equals(backend.id, ignoreCase = true) }
    if (idx >= 0) {
      backends[idx] = backend
    } else {
      backends.add(backend)
    }
  }

  /** Restore the built-in backend set (`dwf-rc`, `dwf-apk`); intended for tests. */
  public fun resetDefaults() {
    backends.clear()
    backends.addAll(listOf(DwfRcWatchFaceRendererBackend, DwfApkWatchFaceRendererBackend))
  }
}
