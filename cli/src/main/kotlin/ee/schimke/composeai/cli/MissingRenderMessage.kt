package ee.schimke.composeai.cli

import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.previewdata.PreviewResult
import ee.schimke.composeai.previewdriver.GradleTaskDisposition
import ee.schimke.composeai.previewdriver.GradleTaskOutcome

/*
 * Turns `PreviewDiagnosis.kt`'s facts into sentences. Rule: every sentence is a function taking the
 * evidence it asserts, so it can't claim more than was observed — `staleSidecarSentence` needs the
 * observed skip, `threwSentence` gets `wiringIsFine` only from an observed run, remedies hang off
 * [RendererTaskKind], and a module is named only from a single-module group.
 * `MissingRenderMessageInvariantTest` checks these over the whole diagnosis space.
 */

/**
 * The stderr report for a run that produced no PNG for [diagnoses] of [total] previews. Pure over
 * facts resolved by [diagnoseMissingRenders]. [prefix] carries the `missing-renders policy=…` tag
 * when the policy lowers the exit code.
 */
fun formatMissingRenderReport(
  diagnoses: List<PreviewDiagnosis>,
  total: Int,
  prefix: String = "",
): String {
  val sb = StringBuilder()
  sb
    .append(prefix)
    .append("Render task completed but produced no PNG for ")
    .append(diagnoses.size)
    .append(" of ")
    .append(total)
    .append(" preview(s):")
  for (entry in diagnoses) sb.append(offenderLines(entry))

  // Each group is the set of entries one sentence is allowed to speak for, and nothing else.
  val threwThisRun = diagnoses.filter { it.threwThisRun }
  val threwUndated = diagnoses.filter { it.threwUndated }
  val unexplained = diagnoses.filter { it.unexplained }

  if (threwThisRun.isNotEmpty()) {
    // Licensed by the observed run: the renderer reached these previews, so no wiring advice for
    // them.
    sb.append("\n").append(threwSentence(threwThisRun.size, wiringIsFine = true))
  }
  if (threwUndated.isNotEmpty()) {
    // A sidecar proves a renderer wrote it; nothing here proves *when*, so the run isn't described.
    sb.append("\n").append(threwSentence(threwUndated.size, wiringIsFine = false))
  }
  val scannedRows = diagnoses.count { d ->
    d.sidecars.any {
      it.discovery == OutputDiscovery.SCANNED && d.dating(it) == SidecarDating.UNDATED
    }
  }
  if (scannedRows > 0) sb.append("\n").append(scannedRowSentence(scannedRows))
  // Grouped by module and owner: one task name is many tasks in a multi-module render.
  for ((key, entries) in diagnoses.filter { it.staleSidecars }.groupBy { it.module to it.owner }) {
    val disposition =
      entries.first().ownerRun.valueOrNull() ?: continue // unreachable: staleSidecars implies it
    sb.append("\n").append(staleSidecarSentence(key.second, disposition, entries.size))
  }
  if (unexplained.isNotEmpty()) {
    if (threwThisRun.isNotEmpty() || threwUndated.isNotEmpty()) {
      sb
        .append("\nNo sidecar from this run for ")
        .append(unexplained.size)
        .append(" preview(s) (")
        .append(unexplained.take(5).joinToString(", ") { it.id })
        .append(if (unexplained.size > 5) ", …): " else "): ")
    } else {
      sb.append("\n")
    }
    sb.append(remedyParagraphs(unexplained).joinToString("\n"))
  }
  return sb.toString()
}

/** The `- id (:module) — no PNG for: …` line for one preview, plus its sidecar details. */
private fun offenderLines(entry: PreviewDiagnosis): String {
  val sb = StringBuilder()
  val moduleTag = if (entry.module.isNotBlank()) " (${entry.module})" else ""
  sb.append("\n  - ").append(entry.id).append(moduleTag).append(" — no PNG for: ")
  sb.append(entry.coords)
  // Identical sidecars collapse to one line; distinct ones are labelled with their output.
  val groups = entry.sidecars.groupBy({ it.sidecar to entry.dating(it) }, { it.output })
  for ((key, outputs) in groups) {
    val (sidecar, dating) = key
    sb.append(
      sidecarDetail(
        sidecar = sidecar,
        className = entry.className,
        // A single failure needs no output label; the entry line above already names the preview.
        outputs = if (groups.size > 1) outputs else emptyList(),
        // Each marker comes from the finding's own dating (observed skip, or scanned fan-out row).
        dating = dating,
        scanned =
          entry.sidecars.any { it.output in outputs && it.discovery == OutputDiscovery.SCANNED },
      )
    )
  }
  return sb.toString()
}

/**
 * "N preview(s) rendered and then threw …", evidenced by the sidecars. [wiringIsFine] ("the build
 * wiring is fine") may only be `true` from an observed run.
 */
private fun threwSentence(count: Int, wiringIsFine: Boolean): String = buildString {
  append(count).append(" preview(s) rendered and then threw")
  if (wiringIsFine) append(" — the build wiring is fine")
  append(". Full stack traces are in the `<render>.png")
  append(RENDER_ERROR_SIDECAR_SUFFIX)
  append("` sidecar beside each preview's would-be output.")
}

/**
 * Why a `@PreviewParameter` row's exception is undated: fan-out files are found by scanning, and
 * stale fan-out `.error.json`s are never cleaned up, so the row may not have been attempted this
 * run.
 */
private fun scannedRowSentence(count: Int): String =
  "$count preview(s) are reported from a `@PreviewParameter` fan-out sidecar found by scanning. " +
    "Nothing deletes a fan-out `<render>.png$RENDER_ERROR_SIDECAR_SUFFIX` when its provider value " +
    "is renamed or removed, so this run may not have attempted that row."

/**
 * "N preview(s) have a sidecar on disk, but `<task>` did not run …". Requires the [disposition]
 * proving the skip and the actual [owner], so NO-SOURCE is only offered where it can occur.
 */
private fun staleSidecarSentence(
  owner: RendererTask,
  disposition: GradleTaskDisposition,
  count: Int,
): String {
  require(disposition == GradleTaskDisposition.SKIPPED) {
    "a stale sidecar is only explained by a skipped renderer, not $disposition"
  }
  return buildString {
    append(count)
    append(" preview(s) have a `<render>.png")
    append(RENDER_ERROR_SIDECAR_SUFFIX)
    append("` sidecar on disk, but `")
    append(owner.label)
    append("` did not run in this invocation ")
    append(if (owner.canReportNoSource) "(skipped / NO-SOURCE)" else "(skipped)")
    append(" — that sidecar is left over from an earlier run and says nothing about this one.")
  }
}

/**
 * "What to check" paragraphs for unexplained previews, one per owning renderer kind
 * ([RendererTaskKind]): the main renderer's remedy is about a `Test` classpath, the kind renderers'
 * about `onlyIf` and dependencies.
 */
private fun remedyParagraphs(unexplained: List<PreviewDiagnosis>): List<String> {
  val byOwner = unexplained.groupBy { it.module to it.owner }
  return buildList {
    // The main renderer's advice is module-independent, so one copy suffices.
    if (byOwner.keys.any { (_, owner) -> owner.kind == RendererTaskKind.MAIN }) {
      add(mainRendererRemedy())
    }
    byOwner
      .filterKeys { (_, owner) -> owner.kind == RendererTaskKind.KIND_SPECIFIC }
      .forEach { (key, entries) -> add(kindRendererRemedy(key.second, key.first, entries.size)) }
  }
}

/**
 * The fallback hypothesis ("a common cause"), only for [RendererTaskKind.MAIN]: `testClassesDirs`
 * and `composePreviewRender-reports` belong to its `Test` task.
 */
private fun mainRendererRemedy(): String =
  "Check the Gradle output above — a common cause is the `composePreviewRender` task " +
    "reporting NO-SOURCE, which means the renderer test class wasn't found on " +
    "testClassesDirs. Per-preview stack traces are in the `composePreviewRender-reports` " +
    "artifact attached to the run."

/**
 * Guidance for Android's kind-specific renderers (`RenderPreviewsTask`s): no NO-SOURCE paragraph,
 * since they have no `testClassesDirs` or `@SkipWhenEmpty` input. They are skipped by
 * `composePreview { enabled = false }` or a failed dependency. Claims nothing about whether the
 * task ran; the module is named only from a single-module group.
 */
private fun kindRendererRemedy(owner: RendererTask, module: String, count: Int): String {
  val owns =
    if (owner.rendersKind != null) "every `kind=${owner.rendersKind}` preview" else "these previews"
  val where = if (module.isNotBlank()) " in $module" else ""
  return "`${owner.label}` renders $owns$where ($count here) — the Robolectric renderer skips that " +
    "kind — so check its outcome in the Gradle output above: `composePreview { enabled = false }` " +
    "skips it, as does a failure in a task it depends on."
}

/**
 * The `threw X: msg (at File.kt:42 in fn)` lines for one sidecar, leading with the root cause (the
 * outer throwable is usually a reflective `InvocationTargetException`). [outputs] labels lines when
 * outputs failed differently; [earlierRun] marks a sidecar not refreshed this run, licensed only by
 * an observed skip.
 */
private fun sidecarDetail(
  sidecar: RenderErrorSidecar,
  className: String,
  outputs: List<String> = emptyList(),
  dating: SidecarDating = SidecarDating.THIS_RUN,
  scanned: Boolean = false,
): String {
  val earlierRun = dating == SidecarDating.EARLIER_RUN
  val undatedRow = dating == SidecarDating.UNDATED && scanned
  val sb = StringBuilder()
  val chain = causeChainOf(sidecar.stackTrace)
  val root = chain.lastOrNull()
  val exception = root?.exception?.takeIf { it.isNotBlank() } ?: sidecar.exception
  val message = if (root != null) root.message else sidecar.message
  val frame = preferredAppFrame(sidecar.stackTrace, className) ?: sidecar.topAppFrame
  sb.append("\n      ")
  if (outputs.isNotEmpty()) sb.append(outputs.joinToString(", ")).append(" — ")
  if (earlierRun) sb.append("earlier run — ")
  if (undatedRow) sb.append("undated parameter row — ")
  sb.append("threw ").append(exception.substringAfterLast('.'))
  if (message.isNotBlank()) sb.append(": ").append(message)
  if (frame != null && frame.file.isNotBlank()) {
    sb.append(" (at ").append(frame.file)
    if (frame.line > 0) sb.append(':').append(frame.line)
    if (frame.function.isNotBlank()) sb.append(" in ").append(frame.function)
    sb.append(')')
  }
  if (chain.isNotEmpty()) {
    // The whole `Caused by:` chain, outermost first: the wrappers show how the failure was reached.
    val names =
      (listOf(sidecar.exception) + chain.map { it.exception })
        .filter { it.isNotBlank() }
        .map { it.substringAfterLast('.') }
    sb.append("\n        chain: ").append(names.joinToString(" → "))
  }
  if (sidecar.diagnosis.isNotBlank()) sb.append("\n        ").append(sidecar.diagnosis)
  return sb.toString()
}

/**
 * The diagnose-then-report pass shared by `show` and `render`, so no caller can report a missing
 * render without its sidecar.
 */
internal fun missingRenderReport(
  missing: List<PreviewResult>,
  manifests: List<Pair<PreviewModule, PreviewManifest>>,
  total: Int,
  taskOutcomes: Map<String, GradleTaskOutcome> = emptyMap(),
  prefix: String = "",
): String =
  formatMissingRenderReport(
    diagnoseMissingRenders(missing, manifests, taskOutcomes),
    total = total,
    prefix = prefix,
  )
