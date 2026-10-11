package ee.schimke.composeai.guidelines

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What earlier runs learned about one model ([GuidelineRunOptions.cacheModel]), so the next starts
 * from it instead of paying to learn it again. Per model, not per key: how far its bills run from
 * the prediction and which parameters its providers refuse are the model's.
 */
@Serializable
internal data class GuidelineModelRecord(
  /**
   * Billed cost over predicted cost, smoothed across runs: what the next run's predictions are
   * scaled by before any request has come back. Null until a priced run has billed something.
   */
  val costRatio: Double? = null,
  /** How many runs [costRatio] was learned from. */
  val pricedRuns: Int = 0,
  /**
   * When OpenRouter last answered that no provider honours every parameter sent (epoch millis): the
   * next runs within [RELEARN_AFTER_MILLIS] are routed without `provider.require_parameters` from
   * the start. Null when strict routing worked, or was never tried.
   */
  val relaxedAt: Long? = null,
  /** As [relaxedAt], for a model none of whose providers honours `reasoning` with the rest. */
  val noReasoningAt: Long? = null,
) {
  /** Whether [at] is recent enough, on [now], to act on rather than test again. */
  fun fresh(at: Long?, now: Long): Boolean = at != null && now - at in 0 until RELEARN_AFTER_MILLIS

  companion object {
    /** A refusal is asked about again after a week: providers come and go. */
    const val RELEARN_AFTER_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /** The most a run moves [costRatio]: half way to what it saw. */
    const val SMOOTHING: Double = 0.5

    /** [costRatio] is kept within these, so one odd run cannot zero or explode every prediction. */
    const val MIN_RATIO: Double = 0.1
    const val MAX_RATIO: Double = 20.0
  }
}

@Serializable
private data class LedgerFile(val models: Map<String, GuidelineModelRecord> = emptyMap())

/**
 * [GuidelineModelRecord]s by model, kept in [file] beside the cached verdicts
 * ([GuidelineResultCache]), so a cache carried between CI runs carries them too. An unreadable file
 * reads as empty: what is lost is only a head start.
 */
internal class GuidelineModelLedger(private val file: File) {
  @Synchronized fun get(model: String): GuidelineModelRecord? = read().models[model]

  @Synchronized
  fun put(model: String, record: GuidelineModelRecord) {
    val models = read().models + (model to record)
    runCatching {
      file.parentFile?.mkdirs()
      val temp = File(file.parentFile, file.name + ".tmp")
      temp.writeText(JSON.encodeToString(LedgerFile.serializer(), LedgerFile(models)))
      if (!temp.renameTo(file)) {
        file.writeText(temp.readText())
        temp.delete()
      }
    }
  }

  private fun read(): LedgerFile =
    if (!file.isFile) LedgerFile()
    else
      runCatching { JSON.decodeFromString(LedgerFile.serializer(), file.readText()) }
        .getOrDefault(LedgerFile())

  companion object {
    const val FILE_NAME: String = "model-ledger.json"
    private val JSON = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
    }
  }
}
