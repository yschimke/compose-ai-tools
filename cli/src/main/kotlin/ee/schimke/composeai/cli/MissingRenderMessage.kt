package ee.schimke.composeai.cli

/*
 * The prose half of issue #3796. `PreviewDiagnosis.kt` holds what is known; this file turns it into
 * sentences, and its one rule is that every sentence is a function taking the evidence it asserts.
 *
 * That is not a style preference. Five review rounds on this diagnostic each landed the same bug —
 * a sentence stating more than had been observed — and each fix repaired one sentence while leaving
 * the next one writeable. Here, `staleSidecarSentence` takes the observed `GradleTaskDisposition`
 * that proves the skip, so "did not run in this invocation" cannot be written without one;
 * `threwSentence` takes `wiringIsFine` only from `ownerRan == true`; the remedy hangs off
 * [RendererTaskKind], so the `testClassesDirs` advice cannot attach to a task that has no
 * `testClassesDirs`; and a module is named only from a group that contains exactly one.
 *
 * `MissingRenderMessageInvariantTest` asserts these over the whole diagnosis space.
 */

/**
 * The stderr report for a run that produced no PNG for [diagnoses] of [total] previews.
 *
 * Pure function over already-resolved facts — the disk reads and the backend rules live in
 * [diagnoseMissingRenders] — so the wording is unit-testable without standing up a Gradle render.
 *
 * [prefix] carries the `missing-renders policy=…` tag when the policy opts the exit code down.
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
    // `wiringIsFine` is licensed by the observed run: the renderer reached these previews, so the
    // NO-SOURCE / testClassesDirs guidance below must not be printed for them.
    sb.append("\n").append(threwSentence(threwThisRun.size, wiringIsFine = true))
  }
  if (threwUndated.isNotEmpty()) {
    // A sidecar proves a renderer wrote it; nothing here proves *when*, so the run isn't described.
    sb.append("\n").append(threwSentence(threwUndated.size, wiringIsFine = false))
  }
  // Grouped by module and owner: one task *name* is many tasks in a multi-module render, with
  // independently different outcomes, and each sentence quotes the skip it was given.
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
  // Identical sidecars collapse to one line — one broken composable writes the same throwable
  // beside every one of its outputs, and printing it once per capture buries the run's real shape.
  // Distinct ones are labelled with the output they came from, because that is the only thing that
  // ties an exception to the coordinate that produced it.
  val groups = entry.sidecars.groupBy({ it.sidecar }, { it.output })
  for ((sidecar, outputs) in groups) {
    sb.append(
      sidecarDetail(
        sidecar = sidecar,
        className = entry.className,
        // A single failure needs no output label; the entry line above already names the preview.
        outputs = if (groups.size > 1) outputs else emptyList(),
        // "earlier run" is a claim about this invocation, so it is licensed by the observed skip.
        earlierRun = entry.staleSidecars,
      )
    )
  }
  return sb.toString()
}

/**
 * "N preview(s) rendered and then threw …" — evidenced by the sidecars themselves, which only exist
 * because a renderer wrote them.
 *
 * [wiringIsFine] is the part that describes *this* invocation ("the renderer reached them, so the
 * build wiring is fine"), so callers may only pass `true` from an observed run. Without that
 * observation the sentence still reports the exception — the sidecar is real — but says nothing
 * about the build.
 */
private fun threwSentence(count: Int, wiringIsFine: Boolean): String = buildString {
  append(count).append(" preview(s) rendered and then threw")
  if (wiringIsFine) append(" — the build wiring is fine")
  append(". Full stack traces are in the `<render>.png")
  append(RENDER_ERROR_SIDECAR_SUFFIX)
  append("` sidecar beside each preview's would-be output.")
}

/**
 * "N preview(s) have a sidecar on disk, but `<task>` did not run …".
 *
 * Takes the [disposition] that proves the skip rather than a boolean, so the sentence cannot be
 * written without it, and [owner] rather than a name, so it names the task that actually skipped
 * and only offers NO-SOURCE for a task that can report it.
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
 * The "what to check" paragraphs for previews the composable's own behaviour doesn't explain, one
 * per owning renderer.
 *
 * Split by [RendererTaskKind] because the two remedies are about different things and neither is
 * transferable: the main renderer's is a `Test` task's classpath, the kind renderers' is an
 * `onlyIf` and a task dependency.
 */
private fun remedyParagraphs(unexplained: List<PreviewDiagnosis>): List<String> {
  val byOwner = unexplained.groupBy { it.module to it.owner }
  return buildList {
    // The main renderer's paragraph names no module and gives the same advice everywhere, so one
    // copy covers however many modules are in the group.
    if (byOwner.keys.any { (_, owner) -> owner.kind == RendererTaskKind.MAIN }) {
      add(mainRendererRemedy())
    }
    byOwner
      .filterKeys { (_, owner) -> owner.kind == RendererTaskKind.KIND_SPECIFIC }
      .forEach { (key, entries) -> add(kindRendererRemedy(key.second, key.first, entries.size)) }
  }
}

/**
 * The historical guidance, and the reason this whole diagnostic exists: it is the *hypothesis* to
 * check when nothing better is known, stated as "a common cause" rather than as a finding. It
 * belongs only to [RendererTaskKind.MAIN] — `testClassesDirs` is a `Test` task's input and
 * `composePreviewRender-reports` is its artifact.
 */
private fun mainRendererRemedy(): String =
  "Check the Gradle output above — a common cause is the `composePreviewRender` task " +
    "reporting NO-SOURCE, which means the renderer test class wasn't found on " +
    "testClassesDirs. Per-preview stack traces are in the `composePreviewRender-reports` " +
    "artifact attached to the run."

/**
 * The guidance for previews rendered by one of Android's kind-specific renderers.
 *
 * Deliberately not the NO-SOURCE paragraph: these are `RenderPreviewsTask`s, so they have no
 * `testClassesDirs`, declare no `@SkipWhenEmpty` input (they never report NO-SOURCE at all), and
 * write no `composePreviewRender-reports`. What *does* skip them is `composePreview { enabled =
 * false }` (their `onlyIf`) or a failure in something they depend on.
 *
 * Makes no claim about what the task *did*: this group also holds previews whose renderer ran and
 * simply produced nothing, which "it did not run" would misdescribe. The module is named from the
 * group — which is one module by construction — or omitted.
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
 * The `threw X: msg (at File.kt:42 in fn)` detail lines for one of a failing preview's sidecars.
 *
 * Leads with the **root** cause rather than the outermost throwable: the renderer invokes the
 * preview reflectively, so the outer exception is routinely an `InvocationTargetException` that
 * says nothing at all, while the last `Caused by:` in the trace is the real failure (issue #3741's
 * case: `NoClassDefFoundError: com/google/wear/services/ambient/AmbientComponentState`).
 *
 * [outputs] labels the line when a preview's outputs failed differently; empty for the ordinary
 * one-failure case. [earlierRun] marks a sidecar the renderer had no chance to refresh this run —
 * licensed by an observed skip, never by silence.
 */
private fun sidecarDetail(
  sidecar: RenderErrorSidecar,
  className: String,
  outputs: List<String> = emptyList(),
  earlierRun: Boolean = false,
): String {
  val sb = StringBuilder()
  val chain = causeChainOf(sidecar.stackTrace)
  val root = chain.lastOrNull()
  val exception = root?.exception?.takeIf { it.isNotBlank() } ?: sidecar.exception
  val message = if (root != null) root.message else sidecar.message
  val frame = preferredAppFrame(sidecar.stackTrace, className) ?: sidecar.topAppFrame
  sb.append("\n      ")
  if (outputs.isNotEmpty()) sb.append(outputs.joinToString(", ")).append(" — ")
  if (earlierRun) sb.append("earlier run — ")
  sb.append("threw ").append(exception.substringAfterLast('.'))
  if (message.isNotBlank()) sb.append(": ").append(message)
  if (frame != null && frame.file.isNotBlank()) {
    sb.append(" (at ").append(frame.file)
    if (frame.line > 0) sb.append(':').append(frame.line)
    if (frame.function.isNotBlank()) sb.append(" in ").append(frame.function)
    sb.append(')')
  }
  if (chain.isNotEmpty()) {
    // The whole `Caused by:` chain, outermost first — the wrapper says *how* the renderer reached
    // the failure (reflective invoke, class initialisation), which the root cause alone hides.
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
 * The whole "diagnose, then say it" pass, as `show` and both halves of `render` use it. One entry
 * point so no caller can reintroduce a path that reports a missing render without its sidecar —
 * which is exactly what `render --output` did until it was routed through here.
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
