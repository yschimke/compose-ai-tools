package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.RenderHost
import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RenderResult
import ee.schimke.composeai.daemon.protocol.RenderMetrics
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Renderer-agnostic [RenderHost] that serves PNGs from a local fixture directory keyed by preview
 * id — the test fixture described in
 * [TEST-HARNESS § 8a](../../../docs/daemon/TEST-HARNESS.md#8a-the-fakehost-test-fixture).
 *
 * Why this exists: the harness can ship its full v1 scenario catalogue (and v0's S1 right now)
 * **without** depending on the real renderer wiring. Once a renderer's Stream B / B-desktop work
 * lands, `-Pharness.host=real` switches to the real launch descriptor; `FakeHost` stays available
 * as the way harness scenarios drive deterministic failure modes ("render took exactly 2.7 seconds
 * and reported metrics X").
 *
 * Each preview id in [manifest] maps to a [FakePreviewSpec]; the underlying PNG bytes (and optional
 * `.delay-ms` / `.error` / `.metrics.json` overrides) live in [fixtureDir].
 *
 * **Threading.** `submit()` is called by `JsonRpcServer.submitRenderAsync` on a fresh
 * fire-and-forget worker thread — see `JsonRpcServer.kt` § "Threading model". This implementation
 * simply reads the fixture file and returns; we don't need a single dedicated render thread because
 * there is no shared sandbox to serialise against. Concurrent calls are safe.
 *
 * **No-mid-render-cancellation invariant** (DESIGN § 9). [shutdown] is a no-op here — there's
 * nothing to drain because each `submit` returns synchronously. The drain semantics live in
 * `JsonRpcServer` itself; this host just refuses no submissions.
 */
class FakeHost(private val fixtureDir: File, private val manifest: Map<String, FakePreviewSpec>) :
  RenderHost {

  /**
   * Tracks the next "internal request id" the host would assign if anyone called the legacy
   * `RenderHost.Companion.nextRequestId()` path. Unused for the v0 scenarios but kept so future
   * fakes can mimic real-host bookkeeping if needed.
   */
  private val internalIdSource = AtomicLong(1)

  /**
   * Cache of decoded `<previewId>.error` / `<previewId>.delay-ms` / `<previewId>.metrics.json`
   * sidecar files. Lazy because most fixtures only set one or two of them.
   *
   * **Sidecar lookup is intentionally re-resolved on each render** by clearing the entry before
   * each read — see the v1 S3 (render-after-edit) scenario which swaps which `<previewId>.png`
   * variant the host serves between two `renderNow` calls. Caching the path resolution is fine;
   * caching the decoded contents would make the fixture-swap invisible to the host. We keep the
   * sidecar cache (errors / delays / metrics rarely change mid-scenario) but the PNG file itself is
   * always read fresh below.
   */
  private val sidecarCache = ConcurrentHashMap<String, ResolvedSidecars>()

  /**
   * B2.3 — per-host sandbox-lifecycle counters. Captured at host construction so `sandboxAgeMs` is
   * wall-clock since the FakeHost was instantiated; `sandboxAgeRenders` increments on every
   * `submit()`. Synthetic but real values (the harness wire-format contract is "metrics are present
   * and parse"; their numeric meaning under fake-mode is nominal). The S8 + B2.3 soak tests assert
   * presence + monotonic increment of `sandboxAgeRenders`, not absolute heap numbers — those live
   * behind real-mode soak tests.
   */
  private val sandboxStartNs: Long = System.nanoTime()
  private val renderCount: AtomicLong = AtomicLong(0)

  override fun start() {
    // No-op — no real sandbox to bootstrap. Future iterations may pre-decode PNGs here if a
    // scenario shows an unacceptable cold-render delay; for now lazy on-demand reads are fine.
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render) {
      "FakeHost.submit() does not accept Shutdown poison pills."
    }
    // The harness scenario writes the PNG path back into the request's payload as
    // "previewId=<id>" so JsonRpcServer's preview-id → host-id mapping can be inverted on the
    // server side. The cleanest path is for the **caller** (JsonRpcServer) to record the mapping;
    // we prefer to read it from JsonRpcServer's hostIdToPreviewId map at the wire layer. But
    // JsonRpcServer doesn't expose that map to the host today. So we accept any single-entry
    // manifest in v0 (S1 only renders one preview id) and look it up by shape: if exactly one
    // preview is registered, serve that one; otherwise the request payload must carry the id.
    val previewId = resolvePreviewId(request)
    val spec =
      manifest[previewId]
        ?: error(
          "FakeHost: no fixture registered for previewId='$previewId' " +
            "(known=${manifest.keys.sorted()})"
        )
    val sidecars = sidecarCache.computeIfAbsent(previewId) { loadSidecars(it) }
    sidecars.delayMs?.let { Thread.sleep(it.coerceAtLeast(0L)) }
    sidecars.error?.let { err ->
      // Throw the configured exception. JsonRpcServer's emitRenderFailed catches Throwable and
      // wires it into a `renderFailed` notification with `kind=internal` (the v1 server doesn't yet
      // surface the structured `kind` field from the fixture; it stuffs it into the message instead
      // — that's a downstream gap, not a FakeHost concern). The exception message starts with the
      // configured `kind` so test scenarios can pattern-match.
      throw RuntimeException("[${err.kind}] ${err.message}")
    }
    val pngFile = File(fixtureDir, "$previewId.png")
    require(pngFile.exists()) {
      "FakeHost: missing fixture PNG ${pngFile.absolutePath} for previewId='$previewId'"
    }
    val cl = Thread.currentThread().contextClassLoader
    // B2.3 — populate the four cost-model keys on every render. Synthetic but real values:
    // `heapAfterGcMb = 1`, `nativeHeapMb = 1`, `sandboxAgeRenders = renderCount`,
    // `sandboxAgeMs = monotonic delta from host start`. The S8 + B2.3 soak tests assert these
    // four keys arrive on the wire (after `JsonRpcServer.renderFinishedFromResult` translates
    // the flat map into the structured `RenderMetrics`). Sidecar-supplied metrics from
    // `<previewId>.metrics.json` (e.g. the legacy S8 fixture's `heapAfterGcMb=42` example) take
    // precedence so existing fixtures keep their explicit values; the four B2.3 keys are merged
    // as fallback defaults so a fixture with NO `.metrics.json` still satisfies the wire-format
    // contract.
    val nextRenderCount = renderCount.incrementAndGet()
    val ageMs = (System.nanoTime() - sandboxStartNs) / 1_000_000L
    val b23Defaults: Map<String, Long> =
      mapOf(
        RenderMetrics.KEY_HEAP_AFTER_GC_MB to 1L,
        RenderMetrics.KEY_NATIVE_HEAP_MB to 1L,
        RenderMetrics.KEY_SANDBOX_AGE_RENDERS to nextRenderCount,
        RenderMetrics.KEY_SANDBOX_AGE_MS to ageMs,
      )
    val mergedMetrics: Map<String, Long> =
      // Sidecar wins on key collision so legacy fixtures (S8's `heapAfterGcMb=42`) still
      // assert their explicit values; B2.3 defaults fill in the keys the sidecar omits.
      b23Defaults + (sidecars.metrics ?: emptyMap())
    return RenderResult(
      id = request.id,
      classLoaderHashCode = System.identityHashCode(cl),
      classLoaderName = cl?.javaClass?.name ?: "<null>",
      pngPath = pngFile.absolutePath,
      metrics = mergedMetrics,
    )
  }

  override fun shutdown(timeoutMs: Long) {
    // Nothing to drain — every submit() returns synchronously.
  }

  private fun resolvePreviewId(request: RenderRequest.Render): String {
    // Convention: the caller may stuff "previewId=<id>" into RenderRequest.payload — that's how
    // future v1 scenarios will disambiguate concurrent renders. For v0 (single-preview S1) we also
    // accept "any single-entry manifest = that entry's id" as a convenience so the test fixture
    // builders don't have to thread the id through.
    val prefix = "previewId="
    val payload = request.payload
    if (payload.startsWith(prefix)) return payload.substringAfter(prefix)
    if (manifest.size == 1) return manifest.keys.single()
    error(
      "FakeHost: cannot resolve previewId — RenderRequest.payload was '$payload' " +
        "but manifest has ${manifest.size} entries (${manifest.keys.sorted()}); " +
        "set request.payload = \"previewId=<id>\" or use a single-entry manifest"
    )
  }

  private fun loadSidecars(previewId: String): ResolvedSidecars {
    val errorFile = File(fixtureDir, "$previewId.error")
    val delayFile = File(fixtureDir, "$previewId.delay-ms")
    val metricsFile = File(fixtureDir, "$previewId.metrics.json")
    val error = errorFile.takeIf { it.exists() }?.let { parseErrorSidecar(it) }
    val delayMs = delayFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
    val metrics: Map<String, Long>? =
      metricsFile
        .takeIf { it.exists() }
        ?.let { f -> JSON.decodeFromString<Map<String, Long>>(f.readText()) }
    return ResolvedSidecars(error = error, delayMs = delayMs, metrics = metrics)
  }

  /**
   * Parses a `<previewId>.error` sidecar. Two accepted shapes:
   * 1. JSON object `{"kind": "runtime"|"compile"|"capture"|"internal", "message": "...",
   *    "stackTrace": "..."}` — the v1 D-harness format from TEST-HARNESS § 3 / S5. `kind` and
   *    `message` are required; `stackTrace` is optional and unused today (the v1 server's
   *    `renderFailed` payload doesn't carry through fixture-supplied stack traces).
   * 2. Plain text — legacy v0 path; treated as a message with `kind="runtime"`.
   */
  private fun parseErrorSidecar(file: File): ErrorSpec {
    val raw = file.readText().trim()
    return if (raw.startsWith("{")) {
      val obj: JsonObject = JSON.parseToJsonElement(raw).jsonObject
      val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "runtime"
      val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "(no message)"
      val stackTrace = obj["stackTrace"]?.jsonPrimitive?.contentOrNull
      ErrorSpec(kind = kind, message = message, stackTrace = stackTrace)
    } else {
      ErrorSpec(kind = "runtime", message = raw, stackTrace = null)
    }
  }

  private data class ErrorSpec(val kind: String, val message: String, val stackTrace: String?)

  private data class ResolvedSidecars(
    val error: ErrorSpec?,
    val delayMs: Long?,
    val metrics: Map<String, Long>?,
  )

  companion object {

    /**
     * Loads a `previews.json` manifest into the in-memory shape [FakeHost] expects. Defensive
     * against optional fields so test scenarios can grow without rewriting the loader.
     */
    fun loadManifest(file: File): Map<String, FakePreviewSpec> {
      require(file.exists()) { "FakeHost.loadManifest: ${file.absolutePath} does not exist" }
      val list = JSON.decodeFromString<List<FakePreviewSpec>>(file.readText())
      return list.associateBy { it.id }
    }

    private val JSON = Json { ignoreUnknownKeys = true }
  }
}

/**
 * One row of `previews.json` for a fake-mode harness fixture — same shape as a real
 * `composePreviewDaemonStart` manifest entry, just trimmed to what the harness actually reads.
 *
 * `className`/`functionName` are echoed verbatim into log lines and the eventual `discoveryUpdated`
 * notification (v1+); they have no semantic effect on the v0 S1 flow. Only `id` is load-bearing.
 *
 * **B2.2 phase 2** added [sourceFile], [displayName], and [group] so the harness can stage a
 * fake-mode `discoveryUpdated` end-to-end. When the test writes a `.kt` file under the fixture dir,
 * sets `sourceFile` to that absolute path, and sends `fileChanged({kind: source, path: <.kt>})`,
 * the daemon's diff path observes "preview removed from this file" (because ClassGraph has no
 * compiled bytecode under the fixture dir to find) and emits `discoveryUpdated`. Optional —
 * fixtures predating phase 2 omit the field and the diff path collapses to a no-op for them.
 */
@Serializable
data class FakePreviewSpec(
  val id: String,
  val className: String = "",
  val functionName: String = "",
  val sourceFile: String? = null,
  val displayName: String? = null,
  val group: String? = null,
)
