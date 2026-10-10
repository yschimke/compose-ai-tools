package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
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
 * Results kept under [directory] (`build/compose-previews/guidelines/`), keyed by everything a
 * verdict depends on ([inputsKey]): the guidelines' content, the subject's surface and profile, the
 * bytes of every picture, its accessibility nodes, its source, and the model. A preview whose
 * render, source and nodes did not change, judged against the same rules, is not asked again; a
 * source-only or label-only edit is. A preview with no render hash is never cached.
 */
public class GuidelineResultCache(private val directory: File) {
  /** The result for [subject] judged against [guidelines] by [model], when nothing has changed. */
  public fun get(
    subject: PreviewSubject,
    guidelines: CatalogGuidelinesV1,
    model: String,
  ): PreviewGuidelineResult? {
    subject.renderHash ?: return null
    return read(File(directory, path(inputsKey(subject, guidelines, model))))
  }

  /** Keeps [result], judged on [subject] against [guidelines] by [model]. */
  public fun put(
    result: PreviewGuidelineResult,
    subject: PreviewSubject,
    guidelines: CatalogGuidelinesV1,
    model: String,
  ) {
    subject.renderHash ?: return
    write(File(directory, path(inputsKey(subject, guidelines, model))), result)
  }

  @Deprecated(
    "Keyed only on the render and the rules' version, so a source-only edit or changed rule " +
      "text returns a stale verdict. Use get(subject, guidelines, model)."
  )
  public fun get(
    previewId: String,
    renderHash: String?,
    rulesVersion: Int,
    model: String,
  ): PreviewGuidelineResult? {
    val file = fileFor(previewId, renderHash, rulesVersion, model) ?: return null
    return read(file)
  }

  private fun read(file: File): PreviewGuidelineResult? {
    if (!file.isFile) return null
    return runCatching {
        GUIDELINES_JSON.decodeFromString(PreviewGuidelineResult.serializer(), file.readText())
      }
      .getOrNull()
      ?.copy(fromCache = true)
  }

  @Deprecated(
    "Keyed only on the render and the rules' version. Use put(result, subject, guidelines, model)."
  )
  public fun put(result: PreviewGuidelineResult, rulesVersion: Int, model: String) {
    val file = fileFor(result.previewId, result.renderHash, rulesVersion, model) ?: return
    write(file, result)
  }

  private fun write(file: File, result: PreviewGuidelineResult) {
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
    return File(
      directory,
      path(sha256("$previewId\u0000$renderHash\u0000$rulesVersion\u0000$model")),
    )
  }

  private fun path(key: String): String = "${key.take(2)}/$key.json"

  public companion object {
    /**
     * Bumped when the request the engine builds changes in a way that changes verdicts (the prompt,
     * how evidence is attached), so results from an older engine are not reused.
     */
    public const val REQUEST_FORMAT: Int = 2

    /**
     * The identity of one judgement: [subject]'s id, surface, profile, every picture's bytes and
     * settings, its accessibility nodes and its source, the full content of [guidelines] (rules and
     * frames, not only the version a catalog may forget to bump), [model] and [REQUEST_FORMAT].
     */
    public fun inputsKey(
      subject: PreviewSubject,
      guidelines: CatalogGuidelinesV1,
      model: String,
    ): String {
      val digest = MessageDigest.getInstance("SHA-256")
      fun part(text: String?) {
        digest.update((text ?: "\u0001").toByteArray())
        digest.update(0)
      }
      part(REQUEST_FORMAT.toString())
      part(model)
      part(GUIDELINES_JSON.encodeToString(CatalogGuidelinesV1.serializer(), guidelines))
      part(subject.previewId)
      part(subject.surface)
      part(subject.profile)
      part(subject.renderHash)
      subject.pictures.forEach { picture ->
        part(picture.kind)
        part("${picture.widthDp}x${picture.heightDp}")
        part(picture.theme)
        part(picture.fontScale?.toString())
        part(picture.device)
        part(picture.description)
        digest.update(picture.png)
        digest.update(0)
      }
      subject.nodes.forEach { node ->
        part(
          "${node.id}|${node.role}|${node.label}|${node.left},${node.top},${node.right},${node.bottom}"
        )
      }
      part(subject.source)
      return digest.digest().joinToString("") { "%02x".format(it) }
    }
  }

  private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
      "%02x".format(it)
    }
}
