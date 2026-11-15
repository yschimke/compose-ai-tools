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
 * Fallback DWF renderer backend (`dwf-rc`): compiles `<WatchFace>` XML to RemoteCompose and renders
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
      isDefault = false,
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
 * Primary watch-face renderer backend (`dwf-apk`), rendering `<WatchFace>` packages through the
 * Android Wear OS DWF renderer APK (`DeclarativeWatchFaceRuntime.apk` running in a
 * `:simulator-host` daemon).
 *
 * Resolves the runtime APK from `-Dcomposeai.watchface.apkRenderer`,
 * `COMPOSE_PREVIEW_DWF_RENDERER_APK`, `COMPOSE_PREVIEW_DWF_RUNTIME_APK`, `DWF_RUNTIME_APK`, or the
 * `dwf-runtime` cache directory, and delegates to [bridge] (defaulting to [DwfApkDaemonBridge]).
 * Reports `available = false` with an actionable diagnostic when the runtime APK, simulator sidecar
 * jars, Android SDK tools, or live watch-face bundle are absent, so callers never silently receive
 * `dwf-rc` pixels when explicitly asking for `dwf-apk`.
 */
public object DwfApkWatchFaceRendererBackend : WatchFaceRendererBackend {
  override val id: String = WatchFaceRendererIds.DWF_APK
  override val label: String = "DWF Renderer APK (Launch)"
  override val badgeLabel: String = "dwf · apk"

  public const val APK_PATH_SYS_PROP: String = "composeai.watchface.apkRenderer"
  public const val APK_PATH_ENV: String = "COMPOSE_PREVIEW_DWF_RENDERER_APK"
  public const val RUNTIME_APK_ENV: String = "COMPOSE_PREVIEW_DWF_RUNTIME_APK"
  public const val RUNTIME_APK_ENV_ALIAS: String = "DWF_RUNTIME_APK"

  @Volatile private var bridge: DwfApkSessionBridge = DwfApkDaemonBridge

  /** Override the [DwfApkSessionBridge] used by `dwf-apk` (intended for custom hosts or tests). */
  public fun setBridge(customBridge: DwfApkSessionBridge) {
    bridge = customBridge
  }

  /** Restore the default [DwfApkDaemonBridge]. */
  public fun resetBridge() {
    bridge = DwfApkDaemonBridge
  }

  internal fun hasReadyEnvironment(): Boolean {
    val activeBridge = bridge
    return if (activeBridge === DwfApkDaemonBridge) {
      DwfApkDaemonBridge.isEnvironmentReady()
    } else {
      true
    }
  }

  internal fun configuredApkFile(): File? {
    val raw =
      System.getProperty(APK_PATH_SYS_PROP)?.takeIf { it.isNotBlank() }
        ?: System.getenv(APK_PATH_ENV)?.takeIf { it.isNotBlank() }
        ?: System.getenv(RUNTIME_APK_ENV)?.takeIf { it.isNotBlank() }
        ?: System.getenv(RUNTIME_APK_ENV_ALIAS)?.takeIf { it.isNotBlank() }
    if (raw != null) {
      return File(raw).takeIf(File::isFile)
    }
    val cached =
      File(
        ee.schimke.composeai.io.composeAiCacheDir("dwf-runtime"),
        "DeclarativeWatchFaceRuntime.apk",
      )
    return cached.takeIf(File::isFile)
  }

  override fun infoFor(host: ServeHost, preview: ServePreview): WatchFaceRendererInfo {
    val activeBridge = bridge
    val available = activeBridge.isAvailable(host, preview)
    return WatchFaceRendererInfo(
      id = id,
      label = label,
      badgeLabel = badgeLabel,
      available = available,
      unavailableReason = if (available) null else activeBridge.unavailableReason(host, preview),
      supportsLiveOverrides = available,
      isDefault = available,
    )
  }

  override fun render(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): RenderOutcome = bridge.render(host, preview, overrides)

  override fun complications(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): WatchFaceComplicationsOutcome = bridge.complications(host, preview, overrides)
}

/**
 * Process-wide registry of [WatchFaceRendererBackend]s.
 *
 * Pre-populated with [DwfApkWatchFaceRendererBackend] (`dwf-apk`, preferred default when available)
 * and [DwfRcWatchFaceRendererBackend] (`dwf-rc`, fallback when `dwf-apk` is unavailable).
 * Additional or replacement backends (for example a live `launch` simulator bridge for `dwf-apk`,
 * or test doubles) can be installed via [register].
 */
public object WatchFaceRendererRegistry {
  private val backends =
    CopyOnWriteArrayList<WatchFaceRendererBackend>(
      listOf(DwfApkWatchFaceRendererBackend, DwfRcWatchFaceRendererBackend),
    )

  /** All registered watch-face renderer backends in display order. */
  public fun all(): List<WatchFaceRendererBackend> = backends.toList()

  /** Look up a backend by its wire [id] (case-insensitive), or null when unknown. */
  public fun resolve(id: String?): WatchFaceRendererBackend? {
    val normalized = id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return defaultBackend()
    return backends.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
  }

  /**
   * Look up a backend for [preview] on [host] by its wire [id] (case-insensitive), selecting
   * [defaultBackendFor] when [id] is null or blank, or returning null when [id] is unknown.
   */
  public fun resolveFor(
    host: ServeHost,
    preview: ServePreview,
    id: String?,
  ): WatchFaceRendererBackend? {
    val normalized =
      id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return defaultBackendFor(host, preview)
    return backends.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
  }

  /**
   * The default backend when no host/preview context is supplied: prefers `dwf-apk` when a custom
   * `dwf-apk` backend or ready `DeclarativeWatchFaceRuntime.apk` environment is present, falling
   * back to `dwf-rc` otherwise.
   */
  public fun defaultBackend(): WatchFaceRendererBackend {
    val apkBackend = backends.firstOrNull {
      it.id.equals(WatchFaceRendererIds.DWF_APK, ignoreCase = true)
    }
    if (
      apkBackend != null &&
        (apkBackend !== DwfApkWatchFaceRendererBackend ||
          DwfApkWatchFaceRendererBackend.hasReadyEnvironment())
    ) {
      return apkBackend
    }
    return backends.firstOrNull { it.id.equals(WatchFaceRendererIds.DWF_RC, ignoreCase = true) }
      ?: DwfRcWatchFaceRendererBackend
  }

  /**
   * The default backend for [preview] on [host]: prefers `dwf-apk` whenever available, falling back
   * to the first available backend (`dwf-rc`) when `dwf-apk` cannot run on [host].
   */
  public fun defaultBackendFor(host: ServeHost, preview: ServePreview): WatchFaceRendererBackend {
    val apkBackend = backends.firstOrNull {
      it.id.equals(WatchFaceRendererIds.DWF_APK, ignoreCase = true)
    }
    if (apkBackend != null && apkBackend.infoFor(host, preview).available) {
      return apkBackend
    }
    return backends.firstOrNull { it.infoFor(host, preview).available }
      ?: backends.firstOrNull { it.id.equals(WatchFaceRendererIds.DWF_RC, ignoreCase = true) }
      ?: DwfRcWatchFaceRendererBackend
  }

  /** Renderer descriptors for [preview] on [host], or empty when [preview] is not a watch face. */
  public fun renderersFor(host: ServeHost, preview: ServePreview): List<WatchFaceRendererInfo> {
    if (!preview.isWatchFace) return emptyList()
    val infos = backends.map { it.infoFor(host, preview) }
    val defaultId =
      infos
        .firstOrNull {
          it.id.equals(WatchFaceRendererIds.DWF_APK, ignoreCase = true) && it.available
        }
        ?.id
        ?: infos.firstOrNull { it.available }?.id
        ?: infos.firstOrNull { it.id.equals(WatchFaceRendererIds.DWF_RC, ignoreCase = true) }?.id
        ?: infos.firstOrNull()?.id
    return infos.map { info -> info.copy(isDefault = info.id.equals(defaultId, ignoreCase = true)) }
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

  /** Restore the built-in backend set (`dwf-apk`, `dwf-rc`); intended for tests. */
  public fun resetDefaults() {
    DwfApkWatchFaceRendererBackend.resetBridge()
    backends.clear()
    backends.addAll(listOf(DwfApkWatchFaceRendererBackend, DwfRcWatchFaceRendererBackend))
  }
}
