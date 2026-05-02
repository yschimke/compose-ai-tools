package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.history.HistoryFilter
import ee.schimke.composeai.daemon.history.HistoryManager
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `history/diff/regions` — compares the latest render for a preview against an explicit history
 * baseline and returns connected changed-pixel regions.
 */
class HistoryDiffRegionsDataProductRegistry(private val historyManager: HistoryManager) :
  DataProductRegistry {

  private val json = Json { encodeDefaults = false }
  private val subscriptions = ConcurrentHashMap<String, DiffParams>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      )
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val diffParams =
      parseParams(params)
        ?: return DataProductRegistry.Outcome.FetchFailed(
          "history/diff/regions requires params.baselineHistoryId"
        )
    return fetchDiff(previewId, diffParams)
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val params = subscriptions[previewId] ?: return emptyList()
    val outcome = fetchDiff(previewId, params)
    if (outcome !is DataProductRegistry.Outcome.Ok) return emptyList()
    return listOf(
      DataProductAttachment(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = outcome.result.payload,
      )
    )
  }

  override fun onSubscribe(previewId: String, kind: String, params: JsonElement?) {
    if (kind != KIND) return
    val parsed = parseParams(params) ?: return
    subscriptions[previewId] = parsed
  }

  override fun onUnsubscribe(previewId: String, kind: String) {
    if (kind == KIND) subscriptions.remove(previewId)
  }

  private fun fetchDiff(previewId: String, params: DiffParams): DataProductRegistry.Outcome {
    val latestEntry =
      historyManager.list(HistoryFilter(previewId = previewId, limit = 1)).entries.firstOrNull()
        ?: return DataProductRegistry.Outcome.NotAvailable
    val latest =
      historyManager.read(latestEntry.id, includeBytes = false)
        ?: return DataProductRegistry.Outcome.NotAvailable
    val baseline =
      historyManager.read(params.baselineHistoryId, includeBytes = false)
        ?: return DataProductRegistry.Outcome.FetchFailed(
          "baseline history entry ${params.baselineHistoryId} was not found"
        )
    if (baseline.entry.previewId != previewId) {
      return DataProductRegistry.Outcome.FetchFailed(
        "baseline history entry ${params.baselineHistoryId} belongs to ${baseline.entry.previewId}, not $previewId"
      )
    }
    val latestImage =
      ImageIO.read(File(latest.pngPath))
        ?: return DataProductRegistry.Outcome.FetchFailed(
          "could not decode latest PNG ${latest.pngPath}"
        )
    val baselineImage =
      ImageIO.read(File(baseline.pngPath))
        ?: return DataProductRegistry.Outcome.FetchFailed(
          "could not decode baseline PNG ${baseline.pngPath}"
        )
    if (latestImage.width != baselineImage.width || latestImage.height != baselineImage.height) {
      return DataProductRegistry.Outcome.FetchFailed(
        "latest and baseline PNG dimensions differ: ${latestImage.width}x${latestImage.height} vs " +
          "${baselineImage.width}x${baselineImage.height}"
      )
    }
    val payload = diff(params.baselineHistoryId, latestImage, baselineImage, params.threshold)
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(DiffPayload.serializer(), payload),
      )
    )
  }

  private fun diff(
    baselineHistoryId: String,
    latest: java.awt.image.BufferedImage,
    baseline: java.awt.image.BufferedImage,
    threshold: Int,
  ): DiffPayload {
    val width = latest.width
    val height = latest.height
    val changed = BooleanArray(width * height)
    var totalChanged = 0L
    for (y in 0 until height) {
      for (x in 0 until width) {
        val latestArgb = latest.getRGB(x, y)
        val baselineArgb = baseline.getRGB(x, y)
        if (pixelChanged(latestArgb, baselineArgb, threshold)) {
          changed[y * width + x] = true
          totalChanged++
        }
      }
    }
    val regions = mutableListOf<MutableRegion>()
    val stack = IntArray(width * height)
    for (start in changed.indices) {
      if (!changed[start]) continue
      var stackSize = 0
      stack[stackSize++] = start
      changed[start] = false
      val region = MutableRegion(width)
      while (stackSize > 0) {
        val idx = stack[--stackSize]
        val x = idx % width
        val y = idx / width
        region.add(x, y, latest.getRGB(x, y), baseline.getRGB(x, y))
        fun push(next: Int) {
          if (changed[next]) {
            changed[next] = false
            stack[stackSize++] = next
          }
        }
        if (x > 0) push(idx - 1)
        if (x + 1 < width) push(idx + 1)
        if (y > 0) push(idx - width)
        if (y + 1 < height) push(idx + width)
      }
      regions += region
    }
    val totalPixels = width.toLong() * height.toLong()
    return DiffPayload(
      baselineHistoryId = baselineHistoryId,
      totalPixelsChanged = totalChanged,
      changedFraction = if (totalPixels == 0L) 0.0 else totalChanged.toDouble() / totalPixels,
      regions = regions.sortedByDescending { it.pixelCount }.take(MAX_REGIONS).map { it.toRegion() },
    )
  }

  private class MutableRegion(private val imageWidth: Int) {
    var minX: Int = imageWidth
    var minY: Int = Int.MAX_VALUE
    var maxX: Int = -1
    var maxY: Int = -1
    var pixelCount: Long = 0
    private var sumR: Long = 0
    private var sumG: Long = 0
    private var sumB: Long = 0
    private var sumA: Long = 0

    fun add(x: Int, y: Int, latestArgb: Int, baselineArgb: Int) {
      minX = minOf(minX, x)
      minY = minOf(minY, y)
      maxX = maxOf(maxX, x)
      maxY = maxOf(maxY, y)
      pixelCount++
      sumA += channel(latestArgb, 24) - channel(baselineArgb, 24)
      sumR += channel(latestArgb, 16) - channel(baselineArgb, 16)
      sumG += channel(latestArgb, 8) - channel(baselineArgb, 8)
      sumB += channel(latestArgb, 0) - channel(baselineArgb, 0)
    }

    fun toRegion(): DiffRegion =
      DiffRegion(
        bounds = "$minX,$minY,${maxX + 1},${maxY + 1}",
        pixelCount = pixelCount,
        avgDelta =
          AverageDelta(
            r = sumR.toDouble() / pixelCount,
            g = sumG.toDouble() / pixelCount,
            b = sumB.toDouble() / pixelCount,
            a = sumA.toDouble() / pixelCount,
          ),
      )
  }

  private fun parseParams(params: JsonElement?): DiffParams? {
    val obj = params as? JsonObject ?: return null
    val baselineHistoryId =
      obj["baselineHistoryId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
    val threshold =
      obj["thresholdAlphaDelta"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(0, 255)
        ?: DEFAULT_THRESHOLD
    return DiffParams(baselineHistoryId = baselineHistoryId, threshold = threshold)
  }

  private data class DiffParams(val baselineHistoryId: String, val threshold: Int)

  @Serializable
  data class DiffPayload(
    val baselineHistoryId: String,
    val totalPixelsChanged: Long,
    val changedFraction: Double,
    val regions: List<DiffRegion>,
  )

  @Serializable
  data class DiffRegion(val bounds: String, val pixelCount: Long, val avgDelta: AverageDelta)

  @Serializable data class AverageDelta(val r: Double, val g: Double, val b: Double, val a: Double)

  companion object {
    const val KIND: String = "history/diff/regions"
    const val SCHEMA_VERSION: Int = 1
    private const val DEFAULT_THRESHOLD = 4
    private const val MAX_REGIONS = 50

    private fun pixelChanged(latestArgb: Int, baselineArgb: Int, threshold: Int): Boolean =
      kotlin.math.abs(channel(latestArgb, 24) - channel(baselineArgb, 24)) > threshold ||
        kotlin.math.abs(channel(latestArgb, 16) - channel(baselineArgb, 16)) > threshold ||
        kotlin.math.abs(channel(latestArgb, 8) - channel(baselineArgb, 8)) > threshold ||
        kotlin.math.abs(channel(latestArgb, 0) - channel(baselineArgb, 0)) > threshold

    private fun channel(argb: Int, shift: Int): Int = (argb ushr shift) and 0xff
  }
}
