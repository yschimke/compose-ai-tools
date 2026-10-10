package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRegionV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * One catalog's design-guideline results as a bundle carries them: what `compose-preview
 * guidelines` wrote to `build/compose-previews/guidelines.json`, one [GuidelineRecordV1] per
 * preview, keyed by preview id.
 */
public class ServeGuidelineResults(
  /** The catalog whose `ui-builder.guidelines.json` the results were judged against. */
  public val catalog: String?,
  /** The model the run asked for; each record names the one that answered. */
  public val model: String?,
  public val records: Map<String, GuidelineRecordV1>,
) {
  public fun forPreview(previewId: String): GuidelineRecordV1? = records[previewId]
}

/**
 * Fail-soft trust boundary for a bundle's `guidelines.json`, as [ServeParityIssuesStore] is for
 * `parity/issues.json`: a file a catalog published is read, bounded and cleaned before anything
 * serves it, and an unreadable or oversized one is no results rather than an error.
 *
 * The CLI's report is `{module, catalog, model, results: [{previewId, record, …}]}`; only the
 * contract-shaped [GuidelineRecordV1] of each result is kept, so this module depends on the
 * contracts and not on the CLI or the engine.
 */
public object ServeGuidelineResultsStore {
  public const val FILE: String = "guidelines.json"
  public const val MAX_PREVIEWS: Int = 5000
  private const val MAX_VERDICTS = 200
  private const val MAX_TEXT = 1000
  private const val MAX_IDS = 50
  private val VERDICTS =
    setOf(
      GuidelineVerdictV1.PASS,
      GuidelineVerdictV1.FAIL,
      GuidelineVerdictV1.NOT_APPLICABLE,
      GuidelineVerdictV1.NEEDS_EVIDENCE,
    )
  private val ID = Regex("[^\\p{Cc}]{1,300}")
  private val JSON = Json { ignoreUnknownKeys = true }

  public fun load(
    bundleDir: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): ServeGuidelineResults? {
    val path = bundleDir.toOkioPath() / FILE
    val text =
      runCatching {
        if (!fileSystem.exists(path)) return@runCatching null
        fileSystem.read(path) { readUtf8() }
      }
        .getOrNull() ?: return null
    return parse(text)
  }

  /** [text] as results, or null when it is not a guidelines report this store can trust. */
  public fun parse(text: String): ServeGuidelineResults? {
    val root =
      runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    val results = root["results"] as? JsonArray ?: return null
    if (results.size > MAX_PREVIEWS) return null
    val records = linkedMapOf<String, GuidelineRecordV1>()
    results.forEach { element ->
      val entry = element as? JsonObject ?: return@forEach
      val previewId =
        (entry["previewId"] as? JsonPrimitive)?.contentOrNull.cleanId() ?: return@forEach
      val record =
        runCatching {
          JSON.decodeFromJsonElement(GuidelineRecordV1.serializer(), entry["record"]!!)
        }
          .getOrNull() ?: return@forEach
      sanitize(record)?.let { records.putIfAbsent(previewId, it) }
    }
    if (records.isEmpty()) return null
    return ServeGuidelineResults(
      catalog = (root["catalog"] as? JsonPrimitive)?.contentOrNull.cleanId(),
      model = (root["model"] as? JsonPrimitive)?.contentOrNull.cleanId(),
      records = records,
    )
  }

  /**
   * [raw] bounded: verdicts of a known kind only, text clamped, ids cleaned, regions inside the
   * picture. Null when nothing of it is left.
   */
  public fun sanitize(raw: GuidelineRecordV1): GuidelineRecordV1? {
    if (raw.schema != GuidelineRecordV1.SCHEMA) return null
    val verdicts =
      raw.verdicts
        .asSequence()
        .filter { it.verdict in VERDICTS && it.ruleId.cleanId() != null }
        .take(MAX_VERDICTS)
        .map { verdict ->
          verdict
            .newBuilder()
            .apply {
              confidence = verdict.confidence.coerceIn(0.0, 1.0)
              reason = clamp(verdict.reason, MAX_TEXT)
              nodeIds = verdict.nodeIds.mapNotNull { it.cleanId() }.distinct().take(MAX_IDS)
              regions = verdict.regions.mapNotNull(::sanitizeRegion).take(MAX_IDS)
            }
            .build()
        }
        .toList()
    if (verdicts.isEmpty()) return null
    return raw
      .newBuilder()
      .apply {
        this.verdicts = verdicts
        asked = raw.asked.mapNotNull { it.cleanId() }.take(MAX_VERDICTS)
      }
      .build()
  }

  private fun sanitizeRegion(region: GuidelineRegionV1): GuidelineRegionV1? {
    val box = listOf(region.x, region.y, region.width, region.height)
    if (box.any { it.isNaN() }) return null
    return region
      .newBuilder()
      .apply {
        x = region.x.coerceIn(0.0, 1.0)
        y = region.y.coerceIn(0.0, 1.0)
        width = region.width.coerceIn(0.0, 1.0 - x)
        height = region.height.coerceIn(0.0, 1.0 - y)
        label = region.label?.let { clamp(it, 120) }
      }
      .build()
  }

  private fun String?.cleanId(): String? =
    this?.trim()?.takeIf { ID.matches(it) }?.let { clamp(it, 300) }

  private fun clamp(value: String, max: Int): String =
    if (value.length <= max) value else value.take(max - 1) + "…"
}
