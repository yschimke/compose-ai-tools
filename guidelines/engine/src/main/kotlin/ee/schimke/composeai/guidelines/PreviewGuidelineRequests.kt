package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceV1
import ee.schimke.composeai.guidelines.protocol.GuidelinePictureV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRequestRulesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRuleV1
import ee.schimke.composeai.guidelines.protocol.GuidelineSubjectV1
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A node of a rendered preview a model can point at, from its accessibility hierarchy: [id] is what
 * a verdict's `nodeIds` names, and the bounds (pixels of the render) are what a host draws.
 */
public data class PreviewNode(
  val id: String,
  val role: String? = null,
  val label: String = "",
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  public companion object {
    /** `left,top,right,bottom`, as the accessibility hierarchy writes `boundsInScreen`. */
    public fun parseBounds(
      id: String,
      bounds: String,
      role: String? = null,
      label: String = "",
    ): PreviewNode? {
      val parts = bounds.split(',').mapNotNull { it.trim().toIntOrNull() }
      if (parts.size != 4) return null
      return PreviewNode(id, role, label, parts[0], parts[1], parts[2], parts[3])
    }
  }
}

/** A picture of a subject: its render, or one drawn later at other settings. */
public data class SubjectPicture(
  val kind: String,
  val png: ByteArray,
  val widthDp: Int,
  val heightDp: Int,
  val theme: String? = null,
  val fontScale: Double? = null,
  val device: String? = null,
  val description: String? = null,
) {
  override fun equals(other: Any?): Boolean =
    other is SubjectPicture &&
      kind == other.kind &&
      widthDp == other.widthDp &&
      heightDp == other.heightDp &&
      theme == other.theme &&
      fontScale == other.fontScale &&
      device == other.device &&
      png.contentEquals(other.png)

  override fun hashCode(): Int = kind.hashCode() * 31 + png.contentHashCode()
}

/**
 * One rendered @Preview to judge: [previewId] identifies it to the host, [renderHash] (the render's
 * sha256) is what its result is cached under, [surface] chooses its rules ([GuidelineSurfaces]).
 */
public data class PreviewSubject(
  val previewId: String,
  val label: String = previewId,
  val surface: String = GuidelineSurfaces.COMPONENT,
  val renderHash: String? = null,
  val pictures: List<SubjectPicture> = emptyList(),
  val nodes: List<PreviewNode> = emptyList(),
  val source: String? = null,
  /** The Remote Compose profile it targets (`launcher-widgets-v7`, …), when known. */
  val profile: String? = null,
)

/** How big one request may grow before the next batch starts. */
public data class GuidelineBudget(
  val maxPictures: Int = 12,
  val maxInputTokens: Int = 60_000,
  val maxSubjects: Int = 16,
  /**
   * How much source one batch may carry, in characters. Source is the first thing given up under
   * pressure: a subject whose source would pass this, or whose source alone would not fit the
   * request, goes in without it (truncated where a part fits) rather than losing a picture.
   */
  val maxSourceChars: Int = 32_000,
)

/** A batch: subjects sharing a surface, so they share one rule list. */
public data class GuidelineBatch(
  val surface: String,
  val subjects: List<PreviewSubject>,
) {
  /** The short id each subject is called in the prompt (`s1`, `s2`, …), by preview id. */
  val aliases: Map<String, String> =
    subjects.mapIndexed { index, subject -> subject.previewId to "s${index + 1}" }.toMap()
}

/** Building the batched requests a model is asked. */
public object PreviewGuidelineRequests {
  public const val SYSTEM_PROMPT: String =
    "You review rendered Android UI previews against design guidelines. You are given several " +
      "subjects (rendered @Preview functions), each with its pictures and, when available, its " +
      "accessibility nodes and its Kotlin source. Use the source for rules about code — fixed " +
      "sizes and padding, hard-coded colours and text sizes, missing content descriptions, which " +
      "component or variant is used — and the pictures for what is drawn. Judge EACH subject " +
      "separately against every rule listed for it: " +
      "verdict `pass` when the rule's yes/no `check` is answered yes, `fail` when it is no, " +
      "`not_applicable` when the rule does not apply to that subject. When you cannot decide " +
      "from what is given but more evidence would decide it, answer `needs_evidence` and list " +
      "what you need in `needs` (only kinds listed as available) instead of guessing. Judge only " +
      "from what is provided; do not assume content that is not there; answer `fail` only when " +
      "the subject clearly breaks the rule. Every verdict names its `subjectId` (e.g. `s1`); a " +
      "verdict for a rule judged once across all subjects has `subjectId` null. `nodeIds` cite " +
      "node ids from that subject's accessibility nodes for a `fail` (empty when none fits). " +
      "When a problem is visible but no single node holds it, add a `regions` entry: the picture " +
      "number and a rough box as fractions (0 to 1) of that picture. `confidence` is your " +
      "probability (0 to 1) that the verdict is right. `reason` is one short sentence a designer " +
      "can act on. Reply with JSON only, held to the response schema."

  /**
   * Splits [subjects] into batches by surface, each within [budget]. A subject's text is estimated
   * at four characters a token and a picture at [PICTURE_TOKENS].
   */
  public fun batches(
    guidelines: CatalogGuidelinesV1,
    subjects: List<PreviewSubject>,
    budget: GuidelineBudget = GuidelineBudget(),
  ): List<GuidelineBatch> =
    subjects
      .groupBy { it.surface }
      .flatMap { (surface, group) ->
        val rulesTokens =
          guidelines.subjectRules(surface).sumOf { (it.guidance.length + it.check.length) / 4 } +
            guidelines.setRules().sumOf { (it.guidance.length + it.check.length) / 4 }
        val out = mutableListOf<GuidelineBatch>()
        var current = mutableListOf<PreviewSubject>()
        var pictures = 0
        var tokens = rulesTokens + SYSTEM_PROMPT.length / 4
        var sourceChars = 0
        val fixedTokens = rulesTokens + SYSTEM_PROMPT.length / 4
        for (original in group) {
          var subject = original
          // A subject whose source alone would not fit a request keeps only what fits.
          val withoutSource = estimateTokens(subject.copy(source = null))
          subject.source?.let { source ->
            val room = (budget.maxInputTokens - fixedTokens - withoutSource) * 4
            if (source.length.coerceAtMost(MAX_SOURCE_CHARS) > room) {
              subject = subject.copy(source = source.take(room.coerceAtLeast(0)).ifEmpty { null })
            }
          }
          val subjectTokens = estimateTokens(subject)
          val subjectPictures = subject.pictures.size
          val full =
            current.isNotEmpty() &&
              (current.size >= budget.maxSubjects ||
                pictures + subjectPictures > budget.maxPictures ||
                tokens + subjectTokens > budget.maxInputTokens)
          if (full) {
            out += GuidelineBatch(surface, current)
            current = mutableListOf()
            pictures = 0
            tokens = fixedTokens
            sourceChars = 0
          }
          // The batch's source allowance: past it, a subject keeps what is left, or none.
          subject.source?.let { source ->
            val left = budget.maxSourceChars - sourceChars
            val kept = source.take(left.coerceAtLeast(0).coerceAtMost(MAX_SOURCE_CHARS))
            subject = subject.copy(source = kept.ifEmpty { null })
          }
          sourceChars += subject.source?.length ?: 0
          current += subject
          pictures += subjectPictures
          tokens += estimateTokens(subject)
        }
        if (current.isNotEmpty()) out += GuidelineBatch(surface, current)
        out
      }

  /**
   * The request for [batch]. [onlyRules], for a follow-up round, narrows each subject to the rules
   * still undecided (by preview id); set-scoped rules are asked in round 0 only.
   */
  public fun request(
    guidelines: CatalogGuidelinesV1,
    batch: GuidelineBatch,
    rulesSource: String,
    evidenceAvailable: List<String>,
    round: Int = 0,
    onlyRules: Map<String, Set<String>>? = null,
  ): GuidelineRequestV1 {
    val anyPicture = batch.subjects.any { it.pictures.isNotEmpty() }
    val perSubject: Map<String, List<GuidelineRuleV1>> =
      batch.subjects.associate { subject ->
        val rules =
          guidelines.subjectRules(subject.surface, subject.profile, subject.pictures.isNotEmpty())
        subject.previewId to
          (onlyRules?.get(subject.previewId)?.let { keep -> rules.filter { it.id in keep } }
            ?: rules)
      }
    val setRules = if (round == 0) guidelines.setRules(anyPicture) else emptyList()
    val asked = (perSubject.values.flatten() + setRules).distinctBy { it.id }
    val allForSurface = guidelines.subjectRules(batch.surface) + guidelines.setRules()

    val pictures = mutableListOf<GuidelinePictureV1>()
    val pictureLines = mutableMapOf<String, MutableList<String>>()
    batch.subjects.forEach { subject ->
      val alias = batch.aliases.getValue(subject.previewId)
      subject.pictures.forEach { picture ->
        val index = pictures.size + 1
        val description =
          picture.description
            ?: buildString {
              append(picture.kind).append(" render, ")
              append(picture.widthDp).append('×').append(picture.heightDp).append("dp")
              picture.theme?.let { append(", ").append(it).append(" theme") }
              picture.fontScale?.let { append(", font scale ").append(it) }
              picture.device?.let { append(", device ").append(it) }
            }
        pictures +=
          GuidelinePictureV1.Builder(picture.kind, description, picture.widthDp, picture.heightDp)
            .apply {
              subjectId = subject.previewId
              theme = picture.theme
              fontScale = picture.fontScale
              device = picture.device
              dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(picture.png)
            }
            .build()
        pictureLines.getOrPut(alias) { mutableListOf() } += "Picture $index: $description"
      }
    }

    val evidence =
      batch.subjects
        .filter { it.nodes.isNotEmpty() }
        .map { subject ->
          GuidelineEvidenceV1.Builder(
              GuidelineEvidenceNeedV1.KIND_A11Y_HIERARCHY,
              "application/json",
              nodesJson(subject.nodes).toString(),
            )
            .apply { subjectId = subject.previewId }
            .build()
        } +
        batch.subjects
          .filter { it.source != null }
          .map { subject ->
            GuidelineEvidenceV1.Builder(
                GuidelineEvidenceNeedV1.KIND_SOURCE,
                SOURCE_MEDIA_TYPE,
                subject.source!!.take(MAX_SOURCE_CHARS),
              )
              .apply {
                subjectId = subject.previewId
                description = "The @Preview function's Kotlin source"
              }
              .build()
          }

    val userText = buildString {
      append("Platform: ").append(guidelines.platform).append('\n')
      append("Guidelines: the `").append(guidelines.catalog).append("` catalog's, version ")
      append(guidelines.version).append('\n')
      if (round > 0) {
        append("Follow-up round ").append(round)
        append(": more evidence was gathered for the rules you could not decide.\n")
      }
      append("Evidence you may ask for with `needs_evidence`: ")
      append(evidenceAvailable.ifEmpty { listOf("none") }.joinToString()).append('\n')
      append("\nSubjects:\n")
      batch.subjects.forEach { subject ->
        val alias = batch.aliases.getValue(subject.previewId)
        append("\n### ").append(alias).append(": ").append(subject.label).append('\n')
        pictureLines[alias]?.forEach { append(it).append('\n') } ?: append("No picture attached.\n")
        if (subject.nodes.isNotEmpty()) {
          append("Accessibility nodes (id | role | label | bounds left,top,right,bottom px):\n")
          subject.nodes.take(MAX_NODES).forEach { node ->
            append("- ").append(node.id).append(" | ").append(node.role ?: "-").append(" | ")
            append(node.label.take(60)).append(" | ")
            append(node.left).append(',').append(node.top).append(',')
            append(node.right).append(',').append(node.bottom).append('\n')
          }
          if (subject.nodes.size > MAX_NODES) {
            append("- … ").append(subject.nodes.size - MAX_NODES).append(" more nodes\n")
          }
        }
        subject.source?.let { source ->
          append("Source:\n```kotlin\n").append(source.take(MAX_SOURCE_CHARS).trimEnd())
          append("\n```\n")
        }
        val rules = perSubject.getValue(subject.previewId)
        if (onlyRules != null) {
          append("Rules for ").append(alias).append(": ")
          append(rules.joinToString { it.id }).append('\n')
        }
      }
      val shared = perSubject.values.flatten().distinctBy { it.id }
      append("\nRules (each subject is judged against those that apply to it")
      if (onlyRules != null) append(", as listed above")
      append("):\n")
      shared.forEach { appendRule(it) }
      if (setRules.isNotEmpty()) {
        append("\nRules judged ONCE across all subjects (subjectId null):\n")
        setRules.forEach { appendRule(it) }
      }
    }

    return GuidelineRequestV1.Builder(
        revision = 0,
        rules =
          GuidelineRequestRulesV1.Builder(
              version = guidelines.version,
              source = rulesSource,
              forPlatform = allForSurface.distinctBy { it.id }.size,
              asked = asked,
            )
            .apply {
              visualSkipped =
                if (anyPicture) 0
                else allForSurface.count { it.kind == GuidelineRuleV1.KIND_VISUAL }
            }
            .build(),
        systemPrompt = SYSTEM_PROMPT,
        userText = userText,
        responseSchema = RESPONSE_SCHEMA,
      )
      .apply {
        platform = guidelines.platform
        this.pictures = pictures
        sourceAttached = batch.subjects.any { it.source != null }
        subjects =
          batch.subjects.map { subject ->
            GuidelineSubjectV1.Builder(subject.previewId, GuidelineSubjectV1.KIND_PREVIEW)
              .apply {
                renderHash = subject.renderHash
                label = batch.aliases.getValue(subject.previewId) + ": " + subject.label
              }
              .build()
          }
        this.evidence = evidence
        this.evidenceAvailable = evidenceAvailable
        this.round = round
        provenance =
          listOf(
            "The rules are the `${guidelines.catalog}` catalog's own (version " +
              "${guidelines.version}), from $rulesSource.",
            "${batch.subjects.size} rendered previews of surface `${batch.surface}`, judged in one " +
              "request, with ${pictures.size} pictures and accessibility nodes for " +
              "${evidence.size} of them.",
          )
      }
      .build()
  }

  /** The schema verdicts are held to: per subject, with evidence needs and overlay regions. */
  public val RESPONSE_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonArray("required") { add(JsonPrimitive("verdicts")) }
    putJsonObject("properties") {
      putJsonObject("verdicts") {
        put("type", "array")
        putJsonObject("items") {
          put("type", "object")
          put("additionalProperties", false)
          required(
            "subjectId",
            "ruleId",
            "verdict",
            "confidence",
            "nodeIds",
            "reason",
            "needs",
            "regions",
          )
          putJsonObject("properties") {
            putJsonObject("subjectId") { nullable("string") }
            putJsonObject("ruleId") { put("type", "string") }
            putJsonObject("verdict") {
              put("type", "string")
              putJsonArray("enum") {
                listOf("pass", "fail", "not_applicable", "needs_evidence").forEach {
                  add(JsonPrimitive(it))
                }
              }
            }
            putJsonObject("confidence") { put("type", "number") }
            putJsonObject("nodeIds") {
              put("type", "array")
              putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("reason") { put("type", "string") }
            putJsonObject("needs") {
              put("type", "array")
              putJsonObject("items") {
                put("type", "object")
                put("additionalProperties", false)
                required("kind", "theme", "fontScale", "device", "reason")
                putJsonObject("properties") {
                  putJsonObject("kind") {
                    put("type", "string")
                    putJsonArray("enum") {
                      listOf("a11y-hierarchy", "semantics", "source", "render").forEach {
                        add(JsonPrimitive(it))
                      }
                    }
                  }
                  putJsonObject("theme") { nullable("string") }
                  putJsonObject("fontScale") { nullable("number") }
                  putJsonObject("device") { nullable("string") }
                  putJsonObject("reason") { put("type", "string") }
                }
              }
            }
            putJsonObject("regions") {
              put("type", "array")
              putJsonObject("items") {
                put("type", "object")
                put("additionalProperties", false)
                required("picture", "x", "y", "width", "height", "label")
                putJsonObject("properties") {
                  putJsonObject("picture") { put("type", "integer") }
                  listOf("x", "y", "width", "height").forEach {
                    putJsonObject(it) { put("type", "number") }
                  }
                  putJsonObject("label") { nullable("string") }
                }
              }
            }
          }
        }
      }
    }
  }

  private fun StringBuilder.appendRule(rule: GuidelineRuleV1) {
    append("- ruleId: ").append(rule.id).append('\n')
    append("  guidance: ").append(rule.guidance).append('\n')
    append("  check: ").append(rule.check).append('\n')
  }

  private fun nodesJson(nodes: List<PreviewNode>): JsonArray = buildJsonArray {
    nodes.forEach { node ->
      add(
        buildJsonObject {
          put("id", node.id)
          node.role?.let { put("role", it) }
          put("label", node.label)
          putJsonArray("bounds") {
            listOf(node.left, node.top, node.right, node.bottom).forEach { add(JsonPrimitive(it)) }
          }
        }
      )
    }
  }

  private fun estimateTokens(subject: PreviewSubject): Int =
    subject.pictures.size * PICTURE_TOKENS +
      subject.nodes.take(MAX_NODES).size * 20 +
      (subject.source?.length?.coerceAtMost(MAX_SOURCE_CHARS) ?: 0) / 4 +
      subject.label.length / 4 +
      40

  private fun kotlinx.serialization.json.JsonObjectBuilder.required(vararg names: String) {
    putJsonArray("required") { names.forEach { add(JsonPrimitive(it)) } }
  }

  private fun kotlinx.serialization.json.JsonObjectBuilder.nullable(type: String) {
    putJsonArray("type") {
      add(JsonPrimitive(type))
      add(JsonPrimitive("null"))
    }
  }

  /** A rough input-token cost of one attached picture. */
  public const val PICTURE_TOKENS: Int = 1_200

  private const val MAX_NODES = 80

  /** The most source one subject carries, in characters. */
  public const val MAX_SOURCE_CHARS: Int = 8_000

  public const val SOURCE_MEDIA_TYPE: String = "text/x-kotlin"
}
