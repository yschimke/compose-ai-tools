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
 * [states] are the hierarchy's own (`clickable`, `scrollable`, …): a `scrollable` node tells the
 * model that content past the viewport along it is scrolled away, not clipped.
 */
public data class PreviewNode(
  val id: String,
  val role: String? = null,
  val label: String = "",
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
  val states: List<String> = emptyList(),
) {
  public companion object {
    /** `left,top,right,bottom`, as the accessibility hierarchy writes `boundsInScreen`. */
    public fun parseBounds(
      id: String,
      bounds: String,
      role: String? = null,
      label: String = "",
      states: List<String> = emptyList(),
    ): PreviewNode? {
      val parts = bounds.split(',').mapNotNull { it.trim().toIntOrNull() }
      if (parts.size != 4) return null
      return PreviewNode(id, role, label, parts[0], parts[1], parts[2], parts[3], states)
    }
  }
}

/**
 * A measured accessibility check on a preview — an Accessibility Test Framework result from the
 * same render the nodes come from: [type] is the check (`TouchTargetSizeCheck`,
 * `TextContrastCheck`, …), [level] its outcome (`ERROR`, `WARNING`, `INFO`), [bounds] the
 * `left,top,right,bottom` pixels of the element it is about. Deterministic evidence: a rule about
 * touch targets or contrast is decided by it rather than by the model's estimate from the picture.
 */
public data class PreviewCheck(
  val type: String,
  val level: String,
  val message: String,
  val element: String? = null,
  val bounds: String? = null,
)

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
  /** Measured accessibility checks on the render ([PreviewCheck]); empty when none ran. */
  val checks: List<PreviewCheck> = emptyList(),
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
  /**
   * How many verdicts one reply may be asked for: each subject's rules, summed. This bounds the
   * reply rather than the request, and the reply is what takes the time — twelve Wear screens asked
   * 24 rules each is 288 verdicts, a reply that ran past the 300 s request timeout twice while
   * three components in their own request answered in seconds. At the default a screen batch of
   * that catalog holds five.
   */
  val maxVerdicts: Int = DEFAULT_MAX_VERDICTS,
) {
  /** The budget before [maxVerdicts]: binary compatibility for callers built against it. */
  @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
  public constructor(
    maxPictures: Int,
    maxInputTokens: Int,
    maxSubjects: Int,
    maxSourceChars: Int,
  ) : this(maxPictures, maxInputTokens, maxSubjects, maxSourceChars, DEFAULT_MAX_VERDICTS)

  public companion object {
    /** [maxVerdicts] unless told otherwise. */
    public const val DEFAULT_MAX_VERDICTS: Int = 120
  }
}

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
      "one verdict per listed rule, its `ruleId` copied exactly from that subject's list; never " +
      "invent a rule id, rename one, or report a problem under a rule that is not listed for " +
      "that subject (a verdict for any other id is discarded). " +
      "Verdict `pass` when the rule's yes/no `check` is answered yes, `fail` when it is no, " +
      "`not_applicable` when the rule does not apply to that subject. When you cannot decide " +
      "from what is given but more evidence would decide it, answer `needs_evidence` and list " +
      "what you need in `needs` (only kinds listed as available) instead of guessing. Judge only " +
      "from what is provided; do not assume content that is not there; answer `fail` only when " +
      "the subject clearly breaks the rule. Every verdict names its `subjectId` (e.g. `s1`); a " +
      "verdict for a rule judged once across all subjects has `subjectId` null. `nodeIds` cite " +
      "node ids from that subject's accessibility nodes for a `fail` (empty when none fits). " +
      "When a problem is visible but no single node holds it, or the subject has no " +
      "accessibility nodes, add a `regions` entry for every visible `fail`: the picture " +
      "number and a rough box as fractions (0 to 1) of that picture, so it can be marked on the " +
      "render. Scrolling is not clipping: content cut at the viewport edge ALONG the axis of a " +
      "scrollable container (a node with state `scrollable`, or a picture captured mid-scroll) " +
      "is scrolled out of view, but only while the container can still scroll towards it. At " +
      "either end of the scroll it is clipped: the first item cut at the start edge when the " +
      "content is scrolled to its start, the last item cut at the end edge when it is scrolled to " +
      "its end, and anything cut at the start or end of a long screenshot can never be scrolled " +
      "clear. A round screen's curve slicing the sides of an item near a scrolling edge follows " +
      "the same rule. Content cut across that axis anywhere else, or inside its own bounds, is " +
      "clipped. When `scroll-capture` may be asked for a subject (its " +
      "long screenshot: the whole scrolling content, already rendered) and you cannot tell " +
      "scrolled-away content from clipped content, answer `needs_evidence` asking for it " +
      "rather than guess. " +
      "Accessibility data may be evidence you ask for rather than something given: when `a11y` " +
      "may be asked for a subject, an `Accessibility:` line under it summarises what it holds. " +
      "`a11y` is that subject's accessibility nodes (id, role, label, bounds, states such as " +
      "`clickable`, `scrollable`, `heading`) and the Accessibility Test Framework's measured " +
      "checks on its render (touch target size, contrast, missing labels). Answer " +
      "`needs_evidence` asking for `a11y` when a rule turns on touch target size, contrast, " +
      "content descriptions, traversal order or headings and the summary does not settle it, or " +
      "when a `fail` should cite the node it is about; do not estimate those from the picture. " +
      "When measured accessibility checks are listed for a subject, they decide the rules they " +
      "measure (touch target size, contrast) over your estimate from the picture; cite the " +
      "node or region they name. `confidence` is your " +
      "probability (0 to 1) that the verdict is right. `reason` is one short sentence a designer " +
      "can act on. Reply with JSON only, held to the response schema."

  /**
   * Splits [subjects] into batches by surface, each within [budget]. A subject's text is estimated
   * at four characters a token and a picture at [PICTURE_TOKENS]; its verdicts are the rules it is
   * asked ([GuidelineBudget.maxVerdicts]).
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
        var verdicts = 0
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
          val subjectVerdicts =
            guidelines.subjectRules(surface, subject.profile, subject.pictures.isNotEmpty()).size
          val full =
            current.isNotEmpty() &&
              (current.size >= budget.maxSubjects ||
                pictures + subjectPictures > budget.maxPictures ||
                tokens + subjectTokens > budget.maxInputTokens ||
                verdicts + subjectVerdicts > budget.maxVerdicts)
          if (full) {
            out += GuidelineBatch(surface, current)
            current = mutableListOf()
            pictures = 0
            tokens = fixedTokens
            sourceChars = 0
            verdicts = 0
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
          verdicts += subjectVerdicts
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
  ): GuidelineRequestV1 =
    request(guidelines, batch, rulesSource, evidenceAvailable, round, onlyRules, emptyMap())

  /**
   * [request], also saying per subject what may be asked for it where that differs from
   * [evidenceAvailable] ([subjectEvidence], by preview id): an already-rendered capture only some
   * previews have.
   */
  public fun request(
    guidelines: CatalogGuidelinesV1,
    batch: GuidelineBatch,
    rulesSource: String,
    evidenceAvailable: List<String>,
    round: Int,
    onlyRules: Map<String, Set<String>>?,
    subjectEvidence: Map<String, List<String>>,
  ): GuidelineRequestV1 =
    request(
      guidelines,
      batch,
      rulesSource,
      evidenceAvailable,
      round,
      onlyRules,
      subjectEvidence,
      emptyMap(),
    )

  /**
   * [request], also showing under each subject the host's one-line summary of evidence it may be
   * asked for ([subjectSummaries], by preview id; [GuidelineEvidenceHost.summary]): for
   * accessibility data not attached up front, how many nodes, whether one scrolls, which checks
   * reported — a few dozen tokens standing in for the hundreds the data itself would cost.
   */
  public fun request(
    guidelines: CatalogGuidelinesV1,
    batch: GuidelineBatch,
    rulesSource: String,
    evidenceAvailable: List<String>,
    round: Int,
    onlyRules: Map<String, Set<String>>?,
    subjectEvidence: Map<String, List<String>>,
    subjectSummaries: Map<String, String>,
  ): GuidelineRequestV1 {
    val anyPicture = batch.subjects.any { it.pictures.isNotEmpty() }
    val (perSubject, setRules) = askedRules(guidelines, batch, round, onlyRules)
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
      unrenderedFrames(guidelines, batch)
        .takeIf { it.isNotEmpty() }
        ?.let { frames ->
          append("Not rendered here: the catalog's ").append(frames.joinToString())
          append(" picture(s). Each subject has only the pictures listed under it. Where a rule ")
          append(
            "asks you to look at one of those pictures, judge it on the pictures you have and "
          )
          append("name the one you judged; never cite a picture that is not attached.\n")
        }
      append("\nSubjects:\n")
      batch.subjects.forEach { subject ->
        val alias = batch.aliases.getValue(subject.previewId)
        append("\n### ").append(alias).append(": ").append(subject.label).append('\n')
        pictureLines[alias]?.forEach { append(it).append('\n') } ?: append("No picture attached.\n")
        subjectEvidence[subject.previewId]
          ?.takeIf { it.toSet() != evidenceAvailable.toSet() }
          ?.let { kinds ->
            append("Evidence that may be asked for ").append(alias).append(": ")
            append(kinds.ifEmpty { listOf("none") }.joinToString()).append('\n')
          }
        subjectSummaries[subject.previewId]?.let { summary ->
          append("Accessibility: ").append(summary.take(MAX_SUMMARY_CHARS)).append('\n')
        }
        if (subject.nodes.isNotEmpty()) {
          val viewport = subject.pictures.firstOrNull()?.let { pngSize(it.png) }
          viewport?.let { (w, h) ->
            append("Viewport (its first picture): ").append(w).append('×').append(h).append(" px. ")
            append("`off:` lists the edges a node extends past it.\n")
          }
          append(
            "Accessibility nodes (id | role | label | bounds left,top,right,bottom px | states):\n"
          )
          subject.nodes.take(MAX_NODES).forEach { node ->
            append("- ").append(node.id).append(" | ").append(node.role ?: "-").append(" | ")
            append(node.label.take(60)).append(" | ")
            append(node.left).append(',').append(node.top).append(',')
            append(node.right).append(',').append(node.bottom).append(" | ")
            val off = viewport?.let { (w, h) -> offEdges(node, w, h) }.orEmpty()
            val states =
              node.states +
                off
                  .takeIf { it.isNotEmpty() }
                  ?.let { listOf("off:" + it.joinToString("+")) }
                  .orEmpty()
            append(states.joinToString(" ").ifEmpty { "-" }).append('\n')
          }
          if (subject.nodes.size > MAX_NODES) {
            append("- … ").append(subject.nodes.size - MAX_NODES).append(" more nodes\n")
          }
        }
        if (subject.checks.isNotEmpty()) {
          append("Measured accessibility checks (Accessibility Test Framework, on this render; ")
          append("type | level | element | bounds px | message):\n")
          subject.checks.take(MAX_CHECKS).forEach { check ->
            append("- ").append(check.type).append(" | ").append(check.level).append(" | ")
            append(check.element?.take(60) ?: "-").append(" | ").append(check.bounds ?: "-")
            append(" | ").append(check.message.take(240)).append('\n')
          }
          if (subject.checks.size > MAX_CHECKS) {
            append("- … ").append(subject.checks.size - MAX_CHECKS).append(" more checks\n")
          }
        }
        subject.source?.let { source ->
          append("Source:\n```kotlin\n").append(source.take(MAX_SOURCE_CHARS).trimEnd())
          append("\n```\n")
        }
        // Every subject names its own rule ids, so a reply can be held to exactly those.
        val rules = perSubject.getValue(subject.previewId)
        append("Rules for ").append(alias).append(": ")
        append(rules.joinToString { it.id }.ifEmpty { "none" }).append('\n')
      }
      val shared = perSubject.values.flatten().distinctBy { it.id }
      append("\nRules (each subject is judged against those listed for it above):\n")
      shared.forEach { appendRule(it) }
      if (setRules.isNotEmpty()) {
        append("\nRules judged ONCE across all subjects (subjectId null):\n")
        setRules.forEach { appendRule(it) }
      }
      append("\nThe only valid ruleIds are the ones listed here, spelled exactly as listed: ")
      append((shared + setRules).distinctBy { it.id }.joinToString { it.id })
      append(". A verdict for any other ruleId, or for a rule not listed for its subject, is ")
      append("discarded.\n")
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

  /**
   * The rules [request] asks of [batch]: per subject (by preview id), and those judged once across
   * the set. A verdict naming any other rule, or a subject outside the batch, answers a question
   * nobody asked; the engine drops it.
   */
  internal fun askedRules(
    guidelines: CatalogGuidelinesV1,
    batch: GuidelineBatch,
    round: Int,
    onlyRules: Map<String, Set<String>>?,
  ): Pair<Map<String, List<GuidelineRuleV1>>, List<GuidelineRuleV1>> {
    val perSubject =
      batch.subjects.associate { subject ->
        val rules =
          guidelines.subjectRules(subject.surface, subject.profile, subject.pictures.isNotEmpty())
        subject.previewId to
          (onlyRules?.get(subject.previewId)?.let { keep -> rules.filter { it.id in keep } }
            ?: rules)
      }
    val anyPicture = batch.subjects.any { it.pictures.isNotEmpty() }
    val setRules = if (round == 0) guidelines.setRules(anyPicture) else emptyList()
    return perSubject to setRules
  }

  /**
   * The catalog's frames for [batch]'s surface ([CatalogGuidelinesV1.frames]: a widget in each
   * launcher's host container, a list unrolled, a fixed size) that no subject has a picture of,
   * named as the catalog names them. A host that renders only each preview's own capture (a CI
   * publish job) draws none of them, and a rule pointing at "the Samsung picture" must not be
   * answered as if there were one.
   */
  internal fun unrenderedFrames(
    guidelines: CatalogGuidelinesV1,
    batch: GuidelineBatch,
  ): List<String> {
    val drawn = batch.subjects.flatMap { subject -> subject.pictures.map { it.kind } }.toSet()
    return guidelines.frames
      .filter { it.surface == null || it.surface == batch.surface }
      .filter { it.kind != GuidelinePictureV1.KIND_DEVICE && it.kind !in drawn }
      .map { frame ->
        (frame.label ?: frame.kind) +
          (frame.hostShape?.let { " ($it ${frame.kind})" } ?: " (${frame.kind})")
      }
      .distinct()
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
                      listOf(
                          "a11y-hierarchy",
                          "semantics",
                          "source",
                          "render",
                          KIND_SCROLL_CAPTURE,
                          KIND_A11Y,
                        )
                        .forEach { add(JsonPrimitive(it)) }
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
      subject.checks.take(MAX_CHECKS).size * 60 +
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

  private const val MAX_CHECKS = 40

  /** A PNG's pixel size from its IHDR chunk, or null when [png] is not one. */
  internal fun pngSize(png: ByteArray): Pair<Int, Int>? {
    if (png.size < 24 || png[1] != 'P'.code.toByte() || png[12] != 'I'.code.toByte()) return null
    fun int(at: Int) =
      ((png[at].toInt() and 0xff) shl 24) or
        ((png[at + 1].toInt() and 0xff) shl 16) or
        ((png[at + 2].toInt() and 0xff) shl 8) or
        (png[at + 3].toInt() and 0xff)
    return (int(16) to int(20)).takeIf { (w, h) -> w > 0 && h > 0 }
  }

  /** The edges of a [width]×[height] viewport [node] extends past. */
  internal fun offEdges(node: PreviewNode, width: Int, height: Int): List<String> = buildList {
    if (node.top < 0) add("top")
    if (node.bottom > height) add("bottom")
    if (node.left < 0) add("left")
    if (node.right > width) add("right")
  }

  /** The most source one subject carries, in characters. */
  public const val MAX_SOURCE_CHARS: Int = 8_000

  public const val SOURCE_MEDIA_TYPE: String = "text/x-kotlin"

  /**
   * An evidence kind beside the protocol's: the long screenshot of a preview's whole scrolling
   * content, already rendered beside its capture. A host offers it only for previews that have one
   * ([GuidelineEvidenceHost.available]) and serves it from [GuidelineEvidenceHost.render], so a
   * host holding no build (a CI publish job) never renders anything to answer it.
   */
  public const val KIND_SCROLL_CAPTURE: String = "scroll-capture"

  /**
   * An evidence kind beside the protocol's: a preview's accessibility data — its nodes with bounds,
   * roles, labels and states (`scrollable`, `clickable`, …) and the Accessibility Test Framework's
   * measured checks on its render ([PreviewCheck]). Not sent with every subject: a host offers it
   * where it has it (or can make it) and serves it in a follow-up round through
   * [GuidelineEvidenceHost.nodes] and [GuidelineEvidenceHost.checks], showing only its
   * [GuidelineEvidenceHost.summary] up front. A need for the protocol's `a11y-hierarchy` or
   * `semantics` is served as this kind by a host offering it.
   */
  public const val KIND_A11Y: String = "a11y"

  /** The most of a host's evidence summary one subject shows. */
  internal const val MAX_SUMMARY_CHARS: Int = 240

  /**
   * The summary a host holding [nodes] and [checks] shows for them up front: counts, the scrolling
   * containers (whose edges are scrolled, not clipped) and which checks reported at which level.
   * About 30 tokens, against roughly 20 per node and 60 per check for the data itself.
   */
  public fun a11ySummary(nodes: List<PreviewNode>, checks: List<PreviewCheck>): String =
    buildString {
      append(nodes.size).append(" node(s)")
      val scrollable = nodes.count { "scrollable" in it.states }
      append(", ")
      append(if (scrollable == 0) "none scrollable" else "$scrollable scrollable")
      val reported = checks.filter { it.level.uppercase() != "INFO" }
      append("; ATF: ")
      if (reported.isEmpty()) {
        append(if (checks.isEmpty()) "no findings" else "no errors or warnings")
      } else {
        append(
          reported
            .groupBy { it.level.uppercase() to it.type }
            .entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .joinToString { (key, list) -> "${list.size} ${key.first} ${key.second}" }
        )
      }
      append(". Ask for `").append(KIND_A11Y).append("` for the nodes and checks.")
    }
}
