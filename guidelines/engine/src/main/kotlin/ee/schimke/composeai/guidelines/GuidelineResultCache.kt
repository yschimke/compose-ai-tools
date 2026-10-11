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
  /**
   * Not asked this run — the cost cap was reached or its request failed — so every rule is
   * unchecked. Never cached; the next run asks again.
   */
  val pending: Boolean = false,
) {
  /**
   * Why it was asked nothing: no catalog rule applies to its surface and profile ([noRulesFor]).
   * Neither a pass nor a finding.
   *
   * A body property so the constructor and `copy` keep their ABI; serialized, but not seen by
   * `equals` or `copy`.
   */
  public var noRules: String? = null
    internal set

  /**
   * Rules of [record] passed by the reply's "every unlisted rule passes" statement rather than
   * their own verdict (whose reason is [IMPLICIT_PASS_REASON]). A body property like [noRules].
   */
  public var implicitPasses: List<String> = emptyList()
    internal set

  /** This result carrying [other]'s body properties, which `copy` resets. */
  internal fun withBodyOf(other: PreviewGuidelineResult): PreviewGuidelineResult = also {
    it.noRules = other.noRules
    it.implicitPasses = other.implicitPasses
    it.jev = other.jev
  }

  /**
   * How the EXPERIMENTAL jev checker reached this result — rounds, requests, spend, latency, and
   * per rule the facts it was handed and the evidence it asked for — for comparing it against a
   * vision run. Null for any other checker. A body property, like [noRules].
   */
  public var jev: JevSubjectTrace? = null
    internal set
}

/**
 * Results kept under [directory] (`build/compose-previews/guidelines/`), keyed by everything a
 * verdict depends on ([inputsKey]). An unchanged preview judged against the same rules is not asked
 * again. A preview with no render hash is never cached.
 */
public class GuidelineResultCache(private val directory: File) {
  /** The result for [subject] judged against [guidelines] by [model], when nothing has changed. */
  public fun get(
    subject: PreviewSubject,
    guidelines: CatalogGuidelinesV1,
    model: String,
  ): PreviewGuidelineResult? {
    subject.renderHash ?: return null
    val key = inputsKey(subject, guidelines, model)
    return read(File(directory, path(key)))?.also { touched += key }
  }

  /**
   * Whether a result for [previewId] was ever kept here, under any inputs. A capped run asks for
   * previews never checked before ahead of those whose earlier verdict went stale.
   */
  public fun checked(previewId: String): Boolean = marker(previewId).isFile

  /**
   * Deletes every result neither read nor written through this instance, and markers of previews
   * not in [previewIds], so a cache carried between CI runs doesn't grow. Call only after a
   * whole-catalog run.
   */
  public fun prune(previewIds: Set<String>) {
    directory
      .listFiles()
      .orEmpty()
      .filter { it.isDirectory && it.name.length == 2 }
      .forEach { shard ->
        shard
          .listFiles()
          .orEmpty()
          .filter { it.name.removeSuffix(".json") !in touched }
          .forEach { it.delete() }
        if (shard.listFiles().isNullOrEmpty()) shard.delete()
      }
    val keep = previewIds.map { sha256(it) }.toSet()
    File(directory, CHECKED_DIR)
      .listFiles()
      .orEmpty()
      .filter { it.name !in keep }
      .forEach { it.delete() }
  }

  private val touched = mutableSetOf<String>()

  private fun marker(previewId: String): File = File(directory, "$CHECKED_DIR/${sha256(previewId)}")

  /** Keeps [result], judged on [subject] against [guidelines] by [model]. */
  public fun put(
    result: PreviewGuidelineResult,
    subject: PreviewSubject,
    guidelines: CatalogGuidelinesV1,
    model: String,
  ) {
    subject.renderHash ?: return
    val key = inputsKey(subject, guidelines, model)
    write(File(directory, path(key)), result)
    touched += key
    marker(subject.previewId).apply { parentFile.mkdirs() }.writeText("")
  }

  /**
   * Forgets the result for [subject] against [guidelines] by [model], for a host whose evidence
   * failed after the result was kept under inputs that promised it.
   */
  public fun remove(subject: PreviewSubject, guidelines: CatalogGuidelinesV1, model: String) {
    subject.renderHash ?: return
    val key = inputsKey(subject, guidelines, model)
    File(directory, path(key)).delete()
    touched -= key
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
      ?.let { it.copy(fromCache = true).withBodyOf(it) }
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
        result.copy(fromCache = false).withBodyOf(result),
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
    private const val CHECKED_DIR = "checked"

    /**
     * Bumped when the engine's request changes in a way that changes verdicts, so older results
     * aren't reused. 8: replies list only non-passes plus an `others` statement per subject.
     */
    public const val REQUEST_FORMAT: Int = 8

    /**
     * The identity of one judgement: [subject]'s id, surface, profile, pictures, accessibility data
     * and source, the full content of [guidelines] (not just its version), [model] and
     * [REQUEST_FORMAT].
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
          "${node.id}|${node.role}|${node.label}|${node.left},${node.top},${node.right},${node.bottom}" +
            "|${node.states.joinToString(",")}"
        )
      }
      subject.checks.forEach { check ->
        part("${check.type}|${check.level}|${check.element}|${check.bounds}|${check.message}")
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
