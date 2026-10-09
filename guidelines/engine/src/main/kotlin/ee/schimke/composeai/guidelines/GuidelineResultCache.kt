package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** One preview's result: its record, the regions it points at, and the rules left undecided. */
@Serializable
public data class PreviewGuidelineResult(
  val previewId: String,
  val renderHash: String? = null,
  val record: GuidelineRecordV1,
  /** Rules still `needs_evidence` after the last round: unchecked, not passed. */
  val unchecked: List<String> = emptyList(),
  val fromCache: Boolean = false,
)

/**
 * Results kept per (preview id, render hash, rules version, model) under [directory]
 * (`build/compose-previews/guidelines/`): a preview whose render did not change is not asked again.
 * A preview with no render hash is never cached.
 */
public class GuidelineResultCache(private val directory: File) {
  public fun get(
    previewId: String,
    renderHash: String?,
    rulesVersion: Int,
    model: String,
  ): PreviewGuidelineResult? {
    val file = fileFor(previewId, renderHash, rulesVersion, model) ?: return null
    if (!file.isFile) return null
    return runCatching {
        GUIDELINES_JSON.decodeFromString(PreviewGuidelineResult.serializer(), file.readText())
      }
      .getOrNull()
      ?.copy(fromCache = true)
  }

  public fun put(result: PreviewGuidelineResult, rulesVersion: Int, model: String) {
    val file = fileFor(result.previewId, result.renderHash, rulesVersion, model) ?: return
    file.parentFile.mkdirs()
    val temp = File(file.parentFile, file.name + ".tmp")
    temp.writeText(
      GUIDELINES_JSON.encodeToString(
        PreviewGuidelineResult.serializer(),
        result.copy(fromCache = false),
      )
    )
    if (!temp.renameTo(file)) {
      file.delete()
      temp.renameTo(file)
    }
  }

  private fun fileFor(
    previewId: String,
    renderHash: String?,
    rulesVersion: Int,
    model: String,
  ): File? {
    renderHash ?: return null
    val key = sha256("$previewId\u0000$renderHash\u0000$rulesVersion\u0000$model")
    return File(directory, "${key.take(2)}/$key.json")
  }

  private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
      "%02x".format(it)
    }
}
