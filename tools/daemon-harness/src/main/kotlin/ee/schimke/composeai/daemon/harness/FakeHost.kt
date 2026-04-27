package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.RenderHost
import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RenderResult
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
   */
  private val sidecarCache = ConcurrentHashMap<String, ResolvedSidecars>()

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
    if (sidecars.errorMessage != null) {
      throw RuntimeException("FakeHost configured error for '$previewId': ${sidecars.errorMessage}")
    }
    val pngFile = File(fixtureDir, "$previewId.png")
    require(pngFile.exists()) {
      "FakeHost: missing fixture PNG ${pngFile.absolutePath} for previewId='$previewId'"
    }
    val cl = Thread.currentThread().contextClassLoader
    return RenderResult(
      id = request.id,
      classLoaderHashCode = System.identityHashCode(cl),
      classLoaderName = cl?.javaClass?.name ?: "<null>",
      pngPath = pngFile.absolutePath,
      metrics = sidecars.metrics,
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
    val errorMessage = errorFile.takeIf { it.exists() }?.readText()?.trim()
    val delayMs = delayFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
    val metrics: Map<String, Long>? =
      metricsFile
        .takeIf { it.exists() }
        ?.let { f -> JSON.decodeFromString<Map<String, Long>>(f.readText()) }
    return ResolvedSidecars(errorMessage = errorMessage, delayMs = delayMs, metrics = metrics)
  }

  private data class ResolvedSidecars(
    val errorMessage: String?,
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
 */
@Serializable
data class FakePreviewSpec(
  val id: String,
  val className: String = "",
  val functionName: String = "",
)
