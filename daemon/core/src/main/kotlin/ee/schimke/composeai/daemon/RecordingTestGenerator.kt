package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RecordingProbeNode
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.SemanticsInputTarget

/**
 * Turns a `record_preview` script timeline into a runnable Compose UI test (issue #1786) — the
 * Compose analogue of Playwright's `codegen`. The recorded exploration becomes durable regression
 * coverage: each pointer event that carried a semantic target (issue #1784) emits a stable
 * `onNodeWith…().performClick()` step rather than a pixel coordinate, and each `recording.probe`
 * marker becomes an assertion.
 *
 * **Probe assertions.** When a probe carried a [RecordingProbeNode] snapshot of the live semantics
 * (captured host-side from the same tree target resolution walks), the generator diffs it against
 * the previous probe's snapshot and emits the strongest stable assertion it can: a node that
 * appeared since the last probe becomes `assertExists()`, one that disappeared becomes
 * `assertDoesNotExist()`, and the anchors present at the first probe are asserted to exist. Probes
 * without a snapshot (older daemons, or a probe that captured nothing assertable) fall back to a
 * labelled `// TODO assert state` stub so the spot is still marked.
 *
 * Steps are built from what the recording **actually did**: a [Step] whose `applied` is false (the
 * daemon reported `unsupported` script evidence — e.g. a target that matched no node) is emitted as
 * a skipped-step comment, never a `performClick`, so the generated test reflects the captured flow.
 *
 * Pure string generation — no Compose / daemon runtime — so it is golden-testable in isolation.
 */
object RecordingTestGenerator {

  /**
   * One recorded event plus whether the recording reported it as applied (vs `unsupported`).
   * [probeSemantics] is the host-captured semantics snapshot for a `recording.probe` event (null
   * for every other kind, and for probes from daemons that predate the capture).
   */
  data class Step(
    val event: RecordingScriptEvent,
    val applied: Boolean = true,
    val probeSemantics: List<RecordingProbeNode>? = null,
  )

  /** Inputs for one generated test. */
  data class Spec(
    /** Generated class name, e.g. `GeneratedTogglePreviewTest`. */
    val className: String,
    /** Generated `@Test` method name, e.g. `togglePreviewInteraction`. */
    val methodName: String,
    /** Composable call placed in `setContent { … }`, e.g. `TogglePreview()`. */
    val composableInvocation: String,
    /** Optional package declaration for the generated file. */
    val packageName: String? = null,
    val steps: List<Step>,
  )

  /** Wrap raw events as all-applied steps — for callers without recording evidence. */
  fun stepsOf(events: List<RecordingScriptEvent>): List<Step> = events.map { Step(it) }

  /**
   * Build a [Spec] for a recording captured against [previewId] (issue #2047, the record-live
   * bridge), deriving sensible identifiers so a client holding only the captured timeline (the VS
   * Code Record toggle) gets a compilable test without authoring names by hand.
   *
   * [functionName] / [classFqn] come from the daemon's preview catalog when available —
   * load-bearing for named/variant previews, whose synthetic id (`…WeatherForecast_Light`) is not
   * the function name. When the id isn't in the catalog, the base name is sanitised out of
   * [previewId]. Any `*Override` wins over the derived default, so a caller that knows better can
   * pin exact names. Events are wrapped as all-applied steps (panel clicks dispatched); callers
   * with per-event evidence build [Step]s themselves and call [generate] directly.
   */
  fun defaultSpec(
    previewId: String,
    functionName: String?,
    classFqn: String?,
    events: List<RecordingScriptEvent>,
    classNameOverride: String? = null,
    methodNameOverride: String? = null,
    composableInvocationOverride: String? = null,
    packageNameOverride: String? = null,
  ): Spec {
    val base = sanitizeIdentifier(functionName ?: previewId.substringAfterLast('.')) ?: "Preview"
    val pascal = base.replaceFirstChar { it.uppercaseChar() }
    val camel = base.replaceFirstChar { it.lowercaseChar() }
    val derivedPackage =
      classFqn?.substringBeforeLast('.', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    return Spec(
      className = classNameOverride?.takeIf { it.isNotBlank() } ?: "Generated${pascal}Test",
      methodName = methodNameOverride?.takeIf { it.isNotBlank() } ?: "${camel}Interaction",
      composableInvocation = composableInvocationOverride?.takeIf { it.isNotBlank() } ?: "$base()",
      packageName = packageNameOverride?.takeIf { it.isNotBlank() } ?: derivedPackage,
      steps = stepsOf(events),
    )
  }

  /**
   * Reduce an arbitrary string to a legal Kotlin identifier (letters/digits/underscore, not
   * starting with a digit) or `null` when nothing usable remains. Strips the variant suffix marker
   * (`#…`) and any FQN/package punctuation a synthetic preview id carries.
   */
  private fun sanitizeIdentifier(raw: String): String? {
    val cleaned =
      raw.substringAfterLast('.').substringBefore('#').filter { it.isLetterOrDigit() || it == '_' }
    val trimmed = cleaned.trimStart { it.isDigit() }
    return trimmed.takeIf { it.isNotBlank() }
  }

  fun generate(spec: Spec): String = buildString {
    val sortedSteps = spec.steps.sortedBy { it.event.tMs }
    // Probe snapshots opt the assertion finders/imports in; without any, the output stays
    // byte-identical to the pre-#1786-assertions stub form so older recordings are unaffected.
    val hasProbeAssertions = sortedSteps.any {
      it.event.kind == "recording.probe" && it.probeSemantics != null
    }
    spec.packageName?.takeIf { it.isNotBlank() }?.let { appendLine("package $it").appendLine() }
    appendLine("import androidx.compose.ui.test.junit4.createComposeRule")
    if (hasProbeAssertions) {
      appendLine("import androidx.compose.ui.test.assertDoesNotExist")
      appendLine("import androidx.compose.ui.test.assertExists")
      appendLine("import androidx.compose.ui.test.onNodeWithContentDescription")
    }
    appendLine("import androidx.compose.ui.test.onNodeWithTag")
    appendLine("import androidx.compose.ui.test.onNodeWithText")
    appendLine("import androidx.compose.ui.test.performClick")
    appendLine("import org.junit.Rule")
    appendLine("import org.junit.Test")
    appendLine()
    appendLine("// Generated by compose-preview from a record_preview interaction (issue #1786).")
    appendLine(
      "// Confirm the setContent call below and add the composable's import before running"
    )
    appendLine("// (named/variant previews share their base @Composable function).")
    if (hasProbeAssertions) {
      appendLine("// Probe assertions are inferred from the captured semantics — review them.")
    } else {
      appendLine("// Fill in the assertions marked TODO at each recording.probe marker.")
    }
    appendLine("class ${spec.className} {")
    appendLine()
    appendLine("  @get:Rule val composeTestRule = createComposeRule()")
    appendLine()
    appendLine("  @Test")
    appendLine("  fun ${spec.methodName}() {")
    appendLine("    composeTestRule.setContent { ${spec.composableInvocation} }")
    // The previous probe's snapshot — diffed against the next probe to detect appeared/disappeared
    // nodes. Null until the first probe with a snapshot is seen.
    var previousProbe: List<RecordingProbeNode>? = null
    sortedSteps.forEach { step ->
      appendStep(step, previousProbe)
      if (step.event.kind == "recording.probe" && step.probeSemantics != null) {
        previousProbe = step.probeSemantics
      }
    }
    appendLine("  }")
    append("}")
    appendLine()
  }

  private fun StringBuilder.appendStep(step: Step, previousProbe: List<RecordingProbeNode>?) {
    val event = step.event
    when {
      event.kind == "input.click" && !step.applied ->
        appendLine(
          "    // input.click ${describeTarget(event)} — not resolved during the recording; " +
            "step skipped"
        )
      event.kind == "input.click" -> appendLine("    ${clickStep(event)}")
      event.kind == "recording.probe" -> appendProbe(step, previousProbe)
      else ->
        appendLine(
          "    // ${event.kind}${event.label?.let { " \"$it\"" } ?: ""} — not yet generated; " +
            "drive it by hand"
        )
    }
  }

  private fun clickStep(event: RecordingScriptEvent): String {
    val selector = nodeSelector(event.target)
    if (selector != null) return "composeTestRule.$selector.performClick()"
    val x = event.pixelX
    val y = event.pixelY
    return if (x != null && y != null) {
      "// click at ($x, $y) — re-record with a testTag/role target for a stable, " +
        "coordinate-free step"
    } else {
      "// click — no target or coordinates recorded; cannot generate a step"
    }
  }

  /** Map a semantic target onto the strongest available Compose test finder. */
  private fun nodeSelector(target: SemanticsInputTarget?): String? {
    if (target == null) return null
    target.testTag
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "onNodeWithTag(${it.quote()})"
      }
    target.text
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "onNodeWithText(${it.quote()})"
      }
    // ref / role alone have no direct Compose-test finder; surface them so the author can refine.
    return null
  }

  private fun describeTarget(event: RecordingScriptEvent): String {
    val t = event.target ?: return "(no target)"
    return listOfNotNull(
        t.testTag?.let { "testTag=$it" },
        t.role?.let { "role=$it" },
        t.text?.let { "text=\"$it\"" },
        t.ref?.let { "ref=$it" },
      )
      .joinToString(" ")
      .ifBlank { "(no target)" }
  }

  /**
   * Emit the assertion block for one `recording.probe`. With a semantics snapshot, diff it against
   * [previousProbe] and emit `assertExists()` for nodes that appeared (or, at the first probe, the
   * anchors present) and `assertDoesNotExist()` for nodes that disappeared. Without a snapshot — or
   * when the diff yields nothing assertable — fall back to the `// TODO assert state` stub so the
   * probe is still visible in the generated test.
   */
  private fun StringBuilder.appendProbe(step: Step, previousProbe: List<RecordingProbeNode>?) {
    val label = step.event.label?.takeIf { it.isNotBlank() }
    val labelSuffix = label?.let { " at probe \"$it\"" } ?: ""
    val snapshot = step.probeSemantics
    if (snapshot == null) {
      appendLine("    // TODO assert state$labelSuffix")
      return
    }
    val current = snapshot.mapNotNull { node -> nodeFinder(node)?.let { it to node } }
    val assertions =
      buildList {
          if (previousProbe == null) {
            // First probe: no prior snapshot to diff against, so assert that the interaction
            // anchors
            // (test-tagged or clickable nodes) are present — the stable handles worth pinning.
            current
              .filter { (_, node) -> node.testTag != null || node.clickable }
              .forEach { (finder, _) -> add("composeTestRule.$finder.assertExists()") }
          } else {
            val before = previousProbe.mapNotNull(::nodeFinder).toSet()
            val after = current.map { it.first }.toSet()
            current
              .filter { (finder, _) -> finder !in before }
              .forEach { (finder, _) -> add("composeTestRule.$finder.assertExists()") }
            (before - after).forEach { finder ->
              add("composeTestRule.$finder.assertDoesNotExist()")
            }
          }
        }
        .distinct()
    if (assertions.isEmpty()) {
      appendLine("    // TODO assert state$labelSuffix")
      return
    }
    val probeName = label?.let { "probe \"$it\"" } ?: "probe"
    appendLine("    // $probeName — assertions inferred from captured semantics")
    assertions.forEach { appendLine("    $it") }
  }

  /** Strongest stable Compose-test finder for a captured probe node, or null when it has none. */
  private fun nodeFinder(node: RecordingProbeNode): String? {
    node.testTag
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "onNodeWithTag(${it.quote()})"
      }
    node.text
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "onNodeWithText(${it.quote()})"
      }
    node.contentDescription
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "onNodeWithContentDescription(${it.quote()})"
      }
    return null
  }

  private fun String.quote(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
