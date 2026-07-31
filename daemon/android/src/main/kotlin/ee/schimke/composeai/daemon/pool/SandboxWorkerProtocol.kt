package ee.schimke.composeai.daemon.pool

import ee.schimke.composeai.daemon.MATERIAL3_THEME_PAYLOAD_CONTEXT_KEY
import ee.schimke.composeai.daemon.RenderResult
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.PreviewDeviceContext
import ee.schimke.composeai.data.render.PreviewDeviceSpec
import ee.schimke.composeai.data.theme.ThemePayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire types for the out-of-process sandbox pool (issue #3072) — one newline-delimited JSON message
 * per line over a loopback socket between [SandboxProcessPool] (parent, in the daemon JVM) and
 * [SandboxWorkerMain] (child, one Robolectric sandbox per JVM).
 *
 * **Why a bespoke protocol and not the daemon's own JSON-RPC?** The worker is not a daemon: it
 * owns no preview index, no extension registry, no watch state. The parent resolves `previewId` to
 * a full spec payload *before* dispatch (`RobolectricHost.submit`'s `reshapeRenderPayload`), so the
 * only things that need to cross the process boundary are a spec payload in and a [RenderResult]
 * out. Three message kinds each way is the whole surface.
 *
 * **Why the result survives the trip intact.** `RobolectricHost` already reduces a sandbox-side
 * `RenderResult` to plain data before handing it to callers — `copyPreviewContextAcrossClassloaders`
 * copies the device context and the Material3 theme payload and deliberately drops the live
 * `slotTables` / `rootForTest` handles, which are Compose objects the host classloader can't use
 * anyway. Everything that survives that copy is a `String`/number/`Map`, so [RenderResultDto] is a
 * faithful carrier: a render served by a worker process yields the same host-side `RenderResult` as
 * one served in-process, and the host-side data products (`ExtensionRegistry.onRender`) see the
 * same input either way.
 */
@Serializable
sealed interface WorkerRequest {

  /** Render [payload] (an already-resolved `key=value;…` spec payload) and reply with the result. */
  @Serializable
  @SerialName("render")
  data class Render(val id: Long, val payload: String, val timeoutMs: Long) : WorkerRequest

  /**
   * Broadcast of `RenderHost.swapUserClassLoaders` — the worker drops its child `URLClassLoader` so
   * the next render resolves recompiled user bytecode. Replies [WorkerResponse.Ok].
   */
  @Serializable @SerialName("swap") data object Swap : WorkerRequest

  /** Drain and exit. The worker replies [WorkerResponse.Ok] and then closes the socket. */
  @Serializable @SerialName("shutdown") data object Shutdown : WorkerRequest
}

@Serializable
sealed interface WorkerResponse {

  /**
   * Sent once, unsolicited, after the worker's Robolectric sandbox has booted. [pid] is the
   * worker's own process id — the pool surfaces it in diagnostics and the pool test asserts on it
   * to prove slots really are distinct processes.
   */
  @Serializable
  @SerialName("ready")
  data class Ready(val slot: Int, val pid: Long) : WorkerResponse

  /** Sent instead of [Ready] when the worker's sandbox never came up. */
  @Serializable
  @SerialName("bootFailed")
  data class BootFailed(val slot: Int, val diagnostic: String) : WorkerResponse

  /** Successful render. */
  @Serializable
  @SerialName("result")
  data class Result(val result: RenderResultDto) : WorkerResponse

  /**
   * Failed render. [diagnostic] is the flattened `class: message` cause chain of the worker-side
   * throwable — `RenderErrorClassifier` matches on exactly that text, so a remote failure classifies
   * into the same `renderFailed.kind` an in-process one would.
   */
  @Serializable
  @SerialName("failed")
  data class Failed(val id: Long, val diagnostic: String) : WorkerResponse

  /** Acknowledgement for the non-render requests. */
  @Serializable @SerialName("ok") data object Ok : WorkerResponse
}

@Serializable
data class RenderResultDto(
  val id: Long,
  val classLoaderHashCode: Int,
  val classLoaderName: String,
  val pngPath: String? = null,
  val metrics: Map<String, Long>? = null,
  val previewContext: PreviewContextDto? = null,
) {
  fun toRenderResult(): RenderResult =
    RenderResult(
      id = id,
      classLoaderHashCode = classLoaderHashCode,
      classLoaderName = classLoaderName,
      pngPath = pngPath,
      metrics = metrics?.let { LinkedHashMap(it) },
      previewContext = previewContext?.toPreviewContext(),
    )

  companion object {
    fun of(result: RenderResult): RenderResultDto =
      RenderResultDto(
        id = result.id,
        classLoaderHashCode = result.classLoaderHashCode,
        classLoaderName = result.classLoaderName,
        pngPath = result.pngPath,
        metrics = result.metrics,
        previewContext = result.previewContext?.let(PreviewContextDto::of),
      )
  }
}

/**
 * The serializable projection of [PreviewContext] — exactly the fields
 * `RobolectricHost.copyPreviewContextAcrossClassloaders` already carries across the sandbox
 * classloader boundary. Keep the two in sync: a field that starts surviving the classloader copy
 * has to be added here too, or worker-served renders would silently lose it.
 */
@Serializable
data class PreviewContextDto(
  val previewId: String? = null,
  val backend: String? = null,
  val renderMode: String? = null,
  val outputBaseName: String? = null,
  val device: PreviewDeviceContextDto? = null,
  val parameterInformationCollected: Boolean = false,
  val themePayload: ThemePayload? = null,
) {
  fun toPreviewContext(): PreviewContext {
    val builder =
      PreviewContext.Builder(
        previewId = previewId,
        backend = backend,
        renderMode = renderMode,
        outputBaseName = outputBaseName,
      )
    device?.let { builder.device(it.toDeviceContext()) }
    if (parameterInformationCollected) builder.parameterInformationCollected()
    themePayload?.let { builder.putInspectionValue(MATERIAL3_THEME_PAYLOAD_CONTEXT_KEY, it) }
    return builder.build()
  }

  companion object {
    fun of(context: PreviewContext): PreviewContextDto =
      PreviewContextDto(
        previewId = context.previewId,
        backend = context.backend,
        renderMode = context.renderMode,
        outputBaseName = context.outputBaseName,
        device = PreviewDeviceContextDto.of(context.device),
        parameterInformationCollected = context.inspection.parameterInformationCollected,
        themePayload = context.inspection.values[MATERIAL3_THEME_PAYLOAD_CONTEXT_KEY] as? ThemePayload,
      )
  }
}

@Serializable
data class PreviewDeviceContextDto(
  val device: String? = null,
  val widthDp: Double? = null,
  val heightDp: Double? = null,
  val density: Float? = null,
  val resolvedDevice: PreviewDeviceSpecDto? = null,
) {
  fun toDeviceContext(): PreviewDeviceContext =
    PreviewDeviceContext(
      device = device,
      widthDp = widthDp,
      heightDp = heightDp,
      density = density,
      resolvedDevice = resolvedDevice?.toSpec(),
    )

  companion object {
    fun of(device: PreviewDeviceContext): PreviewDeviceContextDto =
      PreviewDeviceContextDto(
        device = device.device,
        widthDp = device.widthDp,
        heightDp = device.heightDp,
        density = device.density,
        resolvedDevice = device.resolvedDevice?.let(PreviewDeviceSpecDto::of),
      )
  }
}

@Serializable
data class PreviewDeviceSpecDto(
  val widthDp: Int,
  val heightDp: Int,
  val density: Float,
  val isRound: Boolean = false,
) {
  fun toSpec(): PreviewDeviceSpec =
    PreviewDeviceSpec(
      widthDp = widthDp,
      heightDp = heightDp,
      density = density,
      isRound = isRound,
    )

  companion object {
    fun of(spec: PreviewDeviceSpec): PreviewDeviceSpecDto =
      PreviewDeviceSpecDto(
        widthDp = spec.widthDp,
        heightDp = spec.heightDp,
        density = spec.density,
        isRound = spec.isRound,
      )
  }
}

/** Shared codec. `encodeDefaults = false` keeps the per-render line small. */
internal val workerJson: Json = Json {
  ignoreUnknownKeys = true
  encodeDefaults = false
  classDiscriminator = "type"
}

/** Flattens a cause chain into the `class: message` text `RenderErrorClassifier` matches on. */
internal fun flattenDiagnostic(t: Throwable, maxDepth: Int = 10): String = buildString {
  var cause: Throwable? = t
  var depth = 0
  while (cause != null && depth < maxDepth) {
    append(cause.javaClass.name).append(": ").append(cause.message ?: "").append('\n')
    cause = cause.cause
    depth++
  }
  append(t.stackTraceToString().lineSequence().take(40).joinToString("\n"))
}

/**
 * Host-side stand-in for a throwable raised inside a worker process. Carries the worker's flattened
 * cause chain as its message so `RenderErrorClassifier.classify` produces the same `renderFailed`
 * kind + suggestion an in-process failure would.
 */
class RemoteSandboxRenderException(diagnostic: String) : RuntimeException(diagnostic)
