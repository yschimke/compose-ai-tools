package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invariant five review rounds each violated exactly once (issue #3796): **no sentence may
 * outrun its evidence.**
 *
 * Every other test in this area pins one message for one scenario, which is why each round's fix
 * left the next round's bug writeable — the scenario nobody had thought to pin was always the one
 * that broke. This one goes the other way: it enumerates the diagnosis space and asserts, for every
 * point in it, that the claims in the rendered message are backed by the evidence that produced it.
 *
 * The space is the cross product of what the resolver can produce:
 * - owner: the main renderer, the Lottie renderer, the SVG renderer,
 * - module: `:app`, `:feature` (so multi-module grouping is exercised),
 * - run evidence: [Evidence.Unobserved] and each [GradleTaskDisposition],
 * - sidecars: none, one, two distinct, a scanned `@PreviewParameter` row, and declared + scanned
 *   together.
 *
 * Enumerated rather than randomised: the same 180 single-entry cases and their combinations run
 * identically on every machine, so a failure is always reproducible.
 */
class MissingRenderMessageInvariantTest {

  /** Sentences that assert something about **this invocation's** renderer behaviour. */
  private val thisRunClaims =
    listOf("the build wiring is fine", "did not run in this invocation", "earlier run — ")

  private fun sidecar(exception: String) =
    RenderErrorSidecar(
      schema = "compose-preview-error/v1",
      exception = exception,
      message = "boom",
      stackTrace = "$exception: boom\n\tat com.example.App.render(App.kt:1)",
    )

  private val sidecarSets =
    mapOf(
      "none" to emptyList(),
      "one" to listOf(SidecarFinding("renders/A.png", sidecar("java.lang.IllegalStateException"))),
      "two" to
        listOf(
          SidecarFinding("renders/A_500ms.png", sidecar("java.lang.IllegalStateException")),
          SidecarFinding("renders/A_1000ms.png", sidecar("java.lang.NullPointerException")),
        ),
      // A `@PreviewParameter` row found by scanning: the renderer may never have attempted it this
      // run, because nothing deletes a fan-out sidecar when its provider value goes away.
      "scanned" to
        listOf(
          SidecarFinding(
            "renders/A_Alice.png",
            sidecar("java.lang.IllegalStateException"),
            OutputDiscovery.SCANNED,
          )
        ),
      // The mixed shape: one output the manifest names, one row only a scan found.
      "declared+scanned" to
        listOf(
          SidecarFinding("renders/A.png", sidecar("java.lang.IllegalStateException")),
          SidecarFinding(
            "renders/A_Alice.png",
            sidecar("java.lang.NullPointerException"),
            OutputDiscovery.SCANNED,
          ),
        ),
    )

  private fun owners(module: String) =
    listOf(
      ownerTaskFor(module, "COMPOSE", emptyMap()),
      ownerTaskFor(
        module,
        "LOTTIE",
        mapOf(
          "$module:composePreviewRenderLottie" to
            GradleTaskOutcome("$module:composePreviewRenderLottie", GradleTaskDisposition.SUCCESS)
        ),
      ),
      ownerTaskFor(
        module,
        "SVG",
        mapOf(
          "$module:composePreviewRenderSvg" to
            GradleTaskOutcome("$module:composePreviewRenderSvg", GradleTaskDisposition.SUCCESS)
        ),
      ),
    )

  private val runEvidence: List<Evidence<GradleTaskDisposition>> =
    listOf(Evidence.Unobserved) +
      GradleTaskDisposition.entries.map { Evidence.Observed(it, source = "test") }

  /** Every diagnosis the resolver can produce, as far as the message is concerned. */
  private fun space(): List<PreviewDiagnosis> = buildList {
    var n = 0
    for (module in listOf(":app", ":feature")) {
      for (owner in owners(module)) {
        for (run in runEvidence) {
          for ((label, sidecars) in sidecarSets) {
            add(
              PreviewDiagnosis(
                id = "com.example.PreviewsKt.P${n++}_$label",
                module = module,
                coords = "default",
                className = "com.example.PreviewsKt",
                owner = owner,
                ownerRun = run,
                sidecars = sidecars,
              )
            )
          }
        }
      }
    }
  }

  /** Every report the space can produce: each point alone, plus a spread of combinations. */
  private fun reports(): List<List<PreviewDiagnosis>> {
    val space = space()
    val singles = space.map { listOf(it) }
    // Pairs across a stride so every owner × evidence × sidecar combination meets several others
    // without running 108² reports.
    val pairs = space.indices.map { i -> listOf(space[i], space[(i * 7 + 11) % space.size]) }
    val triples =
      space.indices.step(3).map { i ->
        listOf(space[i], space[(i + 37) % space.size], space[(i + 71) % space.size])
      }
    return singles + pairs + triples
  }

  @Test
  fun `nothing about this run is claimed when nothing about this run was observed`() {
    // The issue's own formulation. A report built entirely from Unobserved evidence may describe
    // the exceptions it found — the sidecars are real files — but may not say when they were
    // written, whether the renderer ran, or that the build wiring is fine.
    for (report in reports().filter { r -> r.all { it.ownerRun == Evidence.Unobserved } }) {
      val message = formatMissingRenderReport(report, total = report.size)
      for (claim in thisRunClaims) {
        assertTrue(!message.contains(claim), "claimed \"$claim\" with no observed run:\n$message")
      }
    }
  }

  @Test
  fun `the build-wiring verdict requires an observed run that reached the preview`() {
    // Round #3789's bug, as a property — and the #3815 fan-out finding too. Stated in *primitive*
    // terms (the raw evidence on the diagnosis), never via a derived flag: an invariant phrased in
    // terms of `threwThisRun` would move with any change to `threwThisRun` and so could never
    // contradict it. Two independent things license the sentence — the renderer was observed
    // running, and it targeted an output the manifest names. A scanned fan-out row satisfies only
    // the first, because nothing deletes a fan-out sidecar when its provider value goes away.
    for (report in reports()) {
      val message = formatMissingRenderReport(report, total = report.size)
      val licensed = report.count { d ->
        d.ownerRan == true && d.sidecars.any { it.discovery == OutputDiscovery.DECLARED }
      }
      assertEquals(
        licensed > 0,
        message.contains("the build wiring is fine"),
        "build-wiring verdict vs. $licensed licensed entr(ies):\n$message",
      )
      if (licensed > 0) {
        assertTrue(
          message.contains(
            "$licensed preview(s) rendered and then threw — the build wiring is fine"
          ),
          "verdict counted $licensed entries but said otherwise:\n$message",
        )
      }
    }
  }

  @Test
  fun `a scanned parameter row is never dated to this run`() {
    // The #3815 fan-out finding as a property: a sidecar found by scanning is reported — the file
    // is real — but this run may not have attempted that row, so it is marked undated and the
    // caution explaining why is printed exactly when one is reported.
    for (report in reports()) {
      val message = formatMissingRenderReport(report, total = report.size)
      val scannedUndated = report.count { d ->
        d.sidecars.any {
          it.discovery == OutputDiscovery.SCANNED && d.dating(it) == SidecarDating.UNDATED
        }
      }
      assertEquals(
        scannedUndated > 0,
        message.contains("this run may not have attempted that row"),
        "scanned-row caution vs. $scannedUndated scanned entr(ies):\n$message",
      )
      // No scanned finding may ever be dated to this invocation, whatever the renderer did.
      for (d in report) {
        for (finding in d.sidecars.filter { it.discovery == OutputDiscovery.SCANNED }) {
          assertTrue(
            d.dating(finding) != SidecarDating.THIS_RUN,
            "dated a scanned row to this run: ${finding.output}",
          )
        }
      }
    }
  }

  @Test
  fun `a skipped-renderer sentence names only tasks observed to have skipped`() {
    // Rounds #3793 / #3794: the sentence must belong to the task that actually skipped, in the
    // module it skipped in, and may only offer NO-SOURCE for a task that can report it.
    val didNotRun =
      Regex("`([^`]+)` did not run in this invocation \\((skipped / NO-SOURCE|skipped)\\)")
    for (report in reports()) {
      val message = formatMissingRenderReport(report, total = report.size)
      val skipped = report.filter { it.staleSidecars }
      val quoted =
        didNotRun.findAll(message).map { it.groupValues[1] to it.groupValues[2] }.toList()
      assertEquals(
        skipped.map { it.module to it.owner }.distinct().size,
        quoted.size,
        "one sentence per (module, owner) that skipped:\n$message",
      )
      for ((label, parenthetical) in quoted) {
        val owner =
          skipped.map { it.owner }.firstOrNull { it.label == label }
            ?: error("named `$label`, which nothing observed skipping:\n$message")
        assertEquals(
          if (owner.canReportNoSource) "skipped / NO-SOURCE" else "skipped",
          parenthetical,
          "NO-SOURCE offered for a task that cannot report it:\n$message",
        )
      }
      assertEquals(
        skipped.isNotEmpty(),
        message.contains("earlier run — "),
        "the earlier-run label and the skipped-renderer sentence must agree:\n$message",
      )
    }
  }

  @Test
  fun `the testClassesDirs remedy is offered only for the task that has one`() {
    // Round #3794's finding as a property: the historical NO-SOURCE guidance belongs to the main
    // renderer, and a report with no unexplained main-renderer preview must not print it.
    for (report in reports()) {
      val message = formatMissingRenderReport(report, total = report.size)
      val mainOwned = report.any { it.unexplained && it.owner.kind == RendererTaskKind.MAIN }
      assertEquals(
        mainOwned,
        message.contains("testClassesDirs"),
        "testClassesDirs remedy vs. main-renderer ownership:\n$message",
      )
      val kindOwned = report.any {
        it.unexplained && it.owner.kind == RendererTaskKind.KIND_SPECIFIC
      }
      assertEquals(
        kindOwned,
        message.contains("composePreview { enabled = false }"),
        "kind-renderer remedy vs. kind ownership:\n$message",
      )
    }
  }

  @Test
  fun `a module is named only for a group that is entirely in it`() {
    // Round #3794's second finding: "in :app (N here)" is a claim about scope. Every such phrase
    // must match the entries of exactly one (module, owner) group.
    val scoped = Regex("`([^`]+)` renders [^(]*in (\\S+) \\((\\d+) here\\)")
    for (report in reports()) {
      val message = formatMissingRenderReport(report, total = report.size)
      for (match in scoped.findAll(message)) {
        val (label, module, count) = match.destructured
        val group = report.filter {
          it.unexplained && it.module == module && it.owner.label == label
        }
        assertEquals(
          group.size,
          count.toInt(),
          "counted ${count} previews for $label in $module, group holds ${group.size}:\n$message",
        )
        assertTrue(group.isNotEmpty(), "named a module with no entries:\n$message")
      }
    }
  }

  @Test
  fun `a task the reader is sent to inspect is always module-qualified`() {
    // Round #3794 again: task names repeat across modules, so any task the message tells the reader
    // to go and look at must carry its module. (The generic "a common cause is the
    // `composePreviewRender` task…" hypothesis names no specific invocation and is exempt.)
    val inspectable =
      listOf(Regex("`([^`]+)` did not run in this invocation"), Regex("`([^`]+)` renders "))
    for (report in reports()) {
      val message = formatMissingRenderReport(report, total = report.size)
      for (pattern in inspectable) {
        for (match in pattern.findAll(message)) {
          val label = match.groupValues[1]
          assertTrue(
            label.startsWith(":") && label.count { it == ':' } >= 2,
            "sent the reader to unqualified `$label`:\n$message",
          )
        }
      }
    }
  }

  @Test
  fun `every sidecar the report quotes belongs to an entry that has one`() {
    // The resolver decides which sidecars exist; the message may not invent, drop or reattribute
    // them. Each distinct sidecar of each entry appears exactly once.
    for (report in reports()) {
      val message = formatMissingRenderReport(report, total = report.size)
      val expected = report.sumOf { entry -> entry.sidecars.map { it.sidecar }.distinct().size }
      assertEquals(
        expected,
        message.lines().count { it.trimStart().startsWith("threw ") || it.contains(" — threw ") },
        "one detail line per distinct sidecar:\n$message",
      )
    }
  }
}
