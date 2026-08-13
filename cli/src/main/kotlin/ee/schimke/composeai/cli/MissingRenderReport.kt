package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.RenderFailureFrame
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/*
 * Why this file exists (issue #3741).
 *
 * When a preview renders nothing, `show` / `render` used to print one fixed paragraph blaming the
 * *build wiring* ("`composePreviewRender` reported NO-SOURCE, the renderer test class wasn't on
 * testClassesDirs"). That guess is wrong whenever the render task actually ran and the preview
 * threw — and in that case the renderer has already written the precise cause next to where the
 * PNG would have gone:
 *
 *   <module>/build/compose-previews/renders/<Stem>.png.error.json
 *
 * The two cases want opposite remedies (fix the classpath vs. fix the composable), so the report
 * distinguishes them explicitly: a sidecar means "rendered and threw — build wiring is fine", no
 * sidecar means "the task really was skipped" and keeps the historical NO-SOURCE guidance.
 *
 * Two things that took a second pass to get right (#3779's review):
 * - A sidecar is only *this run's* finding when the renderer actually ran — see
 *   [RenderTaskEvidence]. A skipped (NO-SOURCE) render leaves the previous run's `.error.json` in
 *   place, and quoting it as evidence would assert the opposite of what happened.
 * - A preview's outputs fail independently, so all of their sidecars are read, not just the first —
 *   see [collectMissingRenders].
 */

/**
 * The renderer's per-preview `compose-preview-error/v1` sidecar, as the CLI reads it.
 *
 * Mirrors the writer (`renderer-android/.../RenderErrorSidecar.kt`, the desktop equivalent in
 * `DesktopRendererMain.kt`) and the schema owned by the gradle plugin
 * (`gradle-plugin/.../PreviewRenderError.kt`) — `:gradle-plugin` is a separate included build, so
 * the CLI cannot depend on that type. Only the fields this report consumes are modelled;
 * `ignoreUnknownKeys` keeps a newer renderer's extra fields harmless. The frame type is the
 * serve-side [RenderFailureFrame] rather than a second `file`/`line`/`function` triple.
 */
@Serializable
data class RenderErrorSidecar(
  val schema: String = "",
  val exception: String = "",
  val message: String = "",
  val topAppFrame: RenderFailureFrame? = null,
  /**
   * The renderer's one-sentence explanation when the failure was a native-library load rather than
   * the preview's own code. Empty for an ordinary preview throw and for older sidecars.
   */
  val diagnosis: String = "",
  /** Full `Throwable.printStackTrace()` text, including any `Caused by:` chain. */
  val stackTrace: String = "",
)

/**
 * One `.error.json` found beside one of a preview's would-be outputs.
 *
 * Kept as `output` + payload rather than a bare [RenderErrorSidecar] because a multi-capture
 * preview fails per *output*: the 500ms frame and the 1000ms frame are separate render attempts and
 * can die differently (one throws from the composable, the next from a scroll data product).
 * Collapsing them to one sidecar reports the first exception against every missing coordinate,
 * which misdiagnoses the later captures.
 */
data class MissingRenderSidecar(
  /** Module-relative path of the output the sidecar sits beside, e.g. `renders/Foo_500ms.png`. */
  val output: String,
  val sidecar: RenderErrorSidecar,
)

/**
 * Whether the renderer task that **owns a missing preview's outputs** ran in *this* invocation —
 * which task that is depends on the preview's kind and the module's backend, see
 * [renderTaskStateOf].
 *
 * An `.error.json` on disk is only evidence about this run if that renderer had a chance to rewrite
 * it. It deletes the previous sidecar at the start of every render attempt, so when it runs, what's
 * left is current — but when it is skipped (NO-SOURCE: the classic "the renderer test class wasn't
 * on testClassesDirs" wiring bug) the file beside the would-be output is whatever an earlier run
 * left there. Reporting that as "this preview rendered and threw — the build wiring is fine" states
 * the exact opposite of what happened. Reading the *wrong* task's outcome makes the same mistake in
 * the other direction, which is why the owner is resolved per preview rather than per module.
 */
enum class RenderTaskEvidence {
  /** The owning renderer task executed (or was up-to-date) in this invocation. */
  RAN,
  /** It was skipped — NO-SOURCE, or skipped because a dependency failed. Sidecars are leftovers. */
  DID_NOT_RUN,
  /**
   * No task-outcome information was supplied. The sidecar is taken at face value, which is the
   * pre-#3779 behaviour; both CLI call sites (`show`, `render`) pass real evidence.
   */
  UNKNOWN,
}

/**
 * Which renderer task owns a preview's outputs, and what this invocation did with it.
 *
 * [task] travels with [evidence] because the *report* needs it as much as the freshness decision
 * does: "`composePreviewRender` did not run, check its testClassesDirs" is a precise, actionable
 * sentence — and a false one when the task that actually skipped was `composePreviewRenderLottie`,
 * which is not a `Test` task, has no `testClassesDirs`, and cannot report NO-SOURCE at all. Naming
 * the task that was really skipped is the difference between a diagnosis and a misdirection.
 */
data class RenderTaskState(
  val evidence: RenderTaskEvidence = RenderTaskEvidence.UNKNOWN,
  /**
   * Gradle task *name* (not path) of the owner. Defaults to the main renderer, which is the owner
   * for every kind on every backend except Android's Lottie / SVG split.
   */
  val task: String = RENDER_TASK_NAME,
  /**
   * Fully qualified `:module:task` path of that same task, empty when the caller supplied no build
   * to ask. Task *names* repeat across modules — every Android module has its own
   * `composePreviewRenderLottie` — so the name alone can neither group entries nor be printed: a
   * multi-module run would merge `:a:`'s outcome with `:b:`'s and then tell the reader to go
   * inspect a task that exists in both.
   */
  val path: String = "",
) {
  /** What the report calls this task: the qualified path when known, else the bare name. */
  val label: String
    get() = path.ifEmpty { task }
}

/**
 * One preview that produced no PNG, paired with whatever the renderer left beside its would-be
 * outputs. [sidecars] is empty when no `.error.json` was found — the "render was skipped" case.
 */
data class MissingRender(
  val id: String,
  val module: String,
  /** Human-readable capture coordinates that came back empty, e.g. `default`, `500ms`. */
  val coords: String,
  /** The preview's own class FQN — used to pick a stack frame in the *user's* package. */
  val className: String = "",
  val sidecars: List<MissingRenderSidecar> = emptyList(),
  /** Which renderer task owns this preview, and whether it ran — see [RenderTaskState]. */
  val renderTask: RenderTaskState = RenderTaskState(),
) {
  /** First sidecar found, if any. Convenience for callers that only want "did it throw at all". */
  val sidecar: RenderErrorSidecar?
    get() = sidecars.firstOrNull()?.sidecar

  /** Sidecars this run is entitled to present as its own findings. */
  val threwThisRun: Boolean
    get() = sidecars.isNotEmpty() && renderTask.evidence != RenderTaskEvidence.DID_NOT_RUN

  /** Sidecars that survive from an earlier run because the renderer never ran this time. */
  val hasStaleSidecars: Boolean
    get() = sidecars.isNotEmpty() && renderTask.evidence == RenderTaskEvidence.DID_NOT_RUN
}

/** One `Caused by:` entry of a stack trace. */
data class RenderErrorCause(val exception: String, val message: String)

/** Sidecar file name suffix: the would-be output path with `.error.json` appended. */
const val RENDER_ERROR_SIDECAR_SUFFIX: String = ".error.json"

private const val RENDER_ERROR_SCHEMA_PREFIX = "compose-preview-error/"

private val sidecarJson = Json { ignoreUnknownKeys = true }

/**
 * Read the `<output>.error.json` sidecar beside [expectedOutput] (the absolute path the PNG / data
 * product *would* have been written to). Returns `null` when there is no sidecar, when it is
 * unreadable, or when its schema isn't a `compose-preview-error` version — all of which mean "we
 * learned nothing here", never "the render succeeded".
 */
fun readRenderErrorSidecar(
  expectedOutput: File,
  fileSystem: FileSystem = SystemFileSystem,
): RenderErrorSidecar? {
  val path = (expectedOutput.path + RENDER_ERROR_SIDECAR_SUFFIX).toPath()
  if (!fileSystem.exists(path)) return null
  val text = runCatching { fileSystem.read(path) { readUtf8() } }.getOrNull() ?: return null
  val decoded =
    runCatching { sidecarJson.decodeFromString(RenderErrorSidecar.serializer(), text) }.getOrNull()
      ?: return null
  return decoded.takeIf { it.schema.startsWith(RENDER_ERROR_SCHEMA_PREFIX) }
}

/**
 * Pair each missing preview with its sidecars, looking beside every output the manifest says that
 * preview should have produced (each capture's `renderOutput`, then its data products). **Every**
 * sidecar found is kept, not just the first: one broken composable does fail all of its outputs
 * with the same throwable (the report groups those back into one line), but a time / scroll /
 * animation fan-out is a sequence of independent render attempts, so the 1000ms capture can fail
 * differently from the 500ms one and deserves to say so.
 *
 * Uses the manifest rather than the [PreviewResult] because a missing capture carries no path:
 * `pngPath` is null precisely because the file isn't there, so the *expected* location has to come
 * from `renderOutput` resolved against the module's `build/compose-previews` directory — the same
 * resolution [PreviewResultBuilder.build] does for outputs that exist.
 *
 * [renderTaskEvidence] answers "did the renderer that owns this preview's outputs run in this
 * invocation?", given the module's gradle path and the preview's kind; see [RenderTaskEvidence].
 * The default keeps the sidecar at face value for callers with no build to ask.
 */
fun collectMissingRenders(
  missing: List<PreviewResult>,
  manifests: List<Pair<PreviewModule, PreviewManifest>>,
  fileSystem: FileSystem = SystemFileSystem,
  renderTaskEvidence: (module: String, kind: String) -> RenderTaskState = { _, _ ->
    RenderTaskState()
  },
): List<MissingRender> {
  val moduleByPath = manifests.associate { (module, _) -> module.gradlePath to module }
  val previewsByModule = manifests.associate { (module, manifest) ->
    module.gradlePath to manifest.previews.associateBy { it.id }
  }
  return missing.map { result ->
    val module = moduleByPath[result.module]
    val preview = previewsByModule[result.module]?.get(result.id)
    val declaredOutputs =
      buildList {
          preview?.captures?.forEach { if (it.renderOutput.isNotEmpty()) add(it.renderOutput) }
          preview?.dataProducts?.forEach { if (it.output.isNotEmpty()) add(it.output) }
        }
        .distinct()
    // `renders/<id>.png` is where an output the manifest leaves *blank* lands, and where a failure
    // that hits it is recorded — but only under the **first** capture. `RobolectricRenderTest`
    // anchors the preview-level sidecar on `captures.firstOrNull()` resolved through
    // `renderOutput.substringAfterLast('/').ifEmpty { "<id>.png" }` (or the first data product when
    // there are no captures), deletes any stale file there before rendering, and writes the
    // throwable there from its outer catch. Its per-job writes cover only two fall-through cases —
    // an animated capture that produced no GIF, and a scroll product with nothing scrollable —
    // neither of which can target the default stem: both require a `.gif` extension or a
    // data-product path.
    //
    // So a *later* blank capture cannot get a fresh default-stem sidecar on Android, and probing
    // for
    // one would report whatever an older manifest left behind as this run's finding — the
    // stale-file
    // trap, re-entered through a narrower gap. The desktop backend is more permissive
    // (`RenderPreviewsTask` resolves every blank capture to `<id>.png` and forks the renderer at
    // that path), so this models Android deliberately: it is the stricter of the two, and the shape
    // where they disagree — a first capture with a path followed by a blank one — is not something
    // discovery emits. It writes a `renderOutput` for every capture, so blanks only survive in
    // manifests old enough that *all* of their captures are blank, where the first-capture rule
    // fires anyway.
    //
    // A preview with *no* captures at all (a `@ScrollingPreview` that declares only data products,
    // issue #1524) is deliberately not in the set either: the renderer anchors its sidecar on the
    // first data product, which is already a declared output.
    val defaultStemIsLive =
      preview == null ||
        declaredOutputs.isEmpty() ||
        preview.captures.firstOrNull()?.renderOutput?.isEmpty() == true
    val relativeOutputs =
      if (defaultStemIsLive) (declaredOutputs + "renders/${result.id}.png").distinct()
      else declaredOutputs
    val sidecars =
      module?.let { m ->
        relativeOutputs.mapNotNull { rel ->
          readRenderErrorSidecar(m.projectDir.resolve("build/compose-previews/$rel"), fileSystem)
            ?.let { MissingRenderSidecar(output = rel, sidecar = it) }
        }
      } ?: emptyList()
    MissingRender(
      id = result.id,
      module = result.module,
      coords = missingCaptureCoords(result),
      className = result.className,
      sidecars = sidecars,
      renderTask = renderTaskEvidence(result.module, result.params.kind),
    )
  }
}

/**
 * The renderer task every backend registers — Robolectric on Android, the JVM renderer on desktop.
 */
internal const val RENDER_TASK_NAME: String = "composePreviewRender"

/**
 * Preview kinds the **Android** backend renders from their own task rather than from
 * [RENDER_TASK_NAME], because Robolectric can inflate neither (`RobolectricRenderTest` skips both):
 * a Lottie asset needs Compottie and an SVG needs Skia, so `AndroidPreviewSupport` registers
 * `composePreviewRenderLottie` / `composePreviewRenderSvg` on the desktop renderer's classpath and
 * folds each into `composePreviewRenderAll` — writing their sidecars into their own disjoint
 * `lottie-renders/` / `svg-renders/` output dirs.
 *
 * They matter here because they run *independently* of the Robolectric task: a NO-SOURCE
 * `composePreviewRender` says nothing about a Lottie preview whose own renderer ran and threw two
 * seconds ago. Reading the wrong task's outcome would label that live exception "earlier run" and
 * send the reader off to fix `testClassesDirs` — the original bug, pointed the other way.
 *
 * The desktop backend has no such split (`RenderPreviewsTask` renders every kind), which is exactly
 * how [renderTaskStateOf] tells the two apart: no kind task in the graph ⇒ no split ⇒
 * [RENDER_TASK_NAME] owns the output.
 */
private val KIND_RENDER_TASKS =
  mapOf("LOTTIE" to "composePreviewRenderLottie", "SVG" to "composePreviewRenderSvg")

/** The kind each entry of [KIND_RENDER_TASKS] renders, for messages that name the task. */
private val KIND_BY_RENDER_TASK = KIND_RENDER_TASKS.entries.associate { (k, v) -> v to k }

/**
 * Which task owns a [kind] preview's outputs in module [modulePath], and what [taskOutcomes] says
 * it did this invocation.
 *
 * `SKIPPED` is the case that matters: Gradle reports NO-SOURCE that way, and NO-SOURCE is precisely
 * the wiring bug the historical guidance was written for — a run where the sidecar on disk cannot
 * have come from this render. Every other disposition means the task's inputs were current or its
 * action executed, so what's beside the would-be output is this run's own work.
 *
 * An **absent** entry for the owning task is [RenderTaskEvidence.UNKNOWN] rather than a guess in
 * either direction. For [RENDER_TASK_NAME] that can't happen for a module that reaches this report
 * (`readableRenderModules` already drops modules whose `composePreviewRenderAll` — which
 * `dependsOn` every renderer task — produced no outcome). For a kind task it means this module has
 * no such task at all, i.e. the non-Android backend, where [RENDER_TASK_NAME] is the owner — so the
 * lookup falls back to it rather than reporting `UNKNOWN` for every desktop Lottie preview.
 */
internal fun renderTaskStateOf(
  modulePath: String,
  kind: String,
  taskOutcomes: Map<String, GradleTaskOutcome>,
): RenderTaskState {
  val prefix = ":" + modulePath.trim(':') + ":"
  val kindTask = KIND_RENDER_TASKS[kind.uppercase()]
  val owner = kindTask?.takeIf { taskOutcomes.containsKey(prefix + it) } ?: RENDER_TASK_NAME
  val path = prefix + owner
  val outcome = taskOutcomes[path] ?: return RenderTaskState(task = owner, path = path)
  val evidence =
    if (outcome.disposition == GradleTaskDisposition.SKIPPED) RenderTaskEvidence.DID_NOT_RUN
    else RenderTaskEvidence.RAN
  return RenderTaskState(evidence = evidence, task = owner, path = path)
}

/**
 * The whole "read the sidecars, format the report" pass, as `show` and both halves of `render` use
 * it. One entry point so no caller can accidentally reintroduce a path that reports a missing
 * render without its sidecar — which is exactly what `render --output` did until it was routed
 * through here.
 */
internal fun missingRenderReport(
  missing: List<PreviewResult>,
  manifests: List<Pair<PreviewModule, PreviewManifest>>,
  total: Int,
  taskOutcomes: Map<String, GradleTaskOutcome> = emptyMap(),
  prefix: String = "",
): String =
  formatMissingRenderReport(
    collectMissingRenders(missing, manifests) { module, kind ->
      renderTaskStateOf(module, kind, taskOutcomes)
    },
    total = total,
    prefix = prefix,
  )

/** The capture coordinates of [result] that came back without a PNG, for the offender list. */
internal fun missingCaptureCoords(result: PreviewResult): String =
  result.captures
    .filter { it.pngPath == null && !it.optional }
    .joinToString(", ") { captureCoordLabel(it) }
    .ifEmpty { "default" }

/**
 * The stderr report for a run that produced no PNG for [missing] of [total] previews. Pure function
 * over already-read sidecars — the disk read lives in [readRenderErrorSidecar] — so the wording of
 * both branches is unit-testable without standing up a Gradle render.
 *
 * [prefix] carries the `missing-renders policy=…` tag when the policy opts the exit code down.
 */
fun formatMissingRenderReport(
  missing: List<MissingRender>,
  total: Int,
  prefix: String = "",
): String {
  val sb = StringBuilder()
  sb
    .append(prefix)
    .append("Render task completed but produced no PNG for ")
    .append(missing.size)
    .append(" of ")
    .append(total)
    .append(" preview(s):")
  for (entry in missing) {
    val moduleTag = if (entry.module.isNotBlank()) " (${entry.module})" else ""
    sb.append("\n  - ").append(entry.id).append(moduleTag).append(" — no PNG for: ")
    sb.append(entry.coords)
    // Identical sidecars collapse to one line — one broken composable writes the same throwable
    // beside every one of its outputs, and printing it once per capture buries the run's real
    // shape. Distinct ones are labelled with the output they came from, because that is the only
    // thing that ties an exception to the coordinate that produced it.
    val groups = entry.sidecars.groupBy({ it.sidecar }, { it.output })
    for ((sidecar, outputs) in groups) {
      sb.appendSidecarDetail(
        sidecar = sidecar,
        className = entry.className,
        // A single failure needs no output label; the entry line above already names the preview.
        outputs = if (groups.size > 1) outputs else emptyList(),
        stale = entry.hasStaleSidecars,
      )
    }
  }
  val threw = missing.filter { it.threwThisRun }
  val stale = missing.filter { it.hasStaleSidecars }
  // Everything the render task's own behaviour, rather than the composable's, has to explain: no
  // sidecar at all, or one the renderer had no chance to refresh.
  val unexplained = missing.filter { it.sidecars.isEmpty() || it.hasStaleSidecars }
  if (threw.isNotEmpty()) {
    // The whole point of issue #3741: an error sidecar proves the render task ran, so the
    // NO-SOURCE / testClassesDirs guidance below must NOT be printed for these.
    sb
      .append("\n")
      .append(threw.size)
      .append(" preview(s) rendered and then threw — the build wiring is fine. Full stack traces ")
      .append("are in the `<render>.png")
      .append(RENDER_ERROR_SIDECAR_SUFFIX)
      .append("` sidecar beside each preview's would-be output.")
  }
  // Grouped by the task that actually skipped — naming `composePreviewRender` for a preview whose
  // renderer is `composePreviewRenderLottie` is a precise sentence about the wrong task, and the
  // remedy attached to it (testClassesDirs) is one the named task doesn't even have — and by
  // module,
  // because one task *name* is many tasks in a multi-module render and their outcomes differ.
  for ((key, entries) in stale.groupBy { it.module to it.renderTask }) {
    val state = key.second
    // Never claim to have observed what this run didn't do: the renderer was skipped, so the
    // sidecar quoted above describes some earlier run and the wiring guidance below still applies.
    sb
      .append("\n")
      .append(entries.size)
      .append(" preview(s) have a `<render>.png")
      .append(RENDER_ERROR_SIDECAR_SUFFIX)
      .append("` sidecar on disk, but `")
      .append(state.label)
      .append("` did not run in this invocation ")
      // NO-SOURCE is a `Test`-task state; the kind renderers cannot report it.
      .append(if (state.task == RENDER_TASK_NAME) "(skipped / NO-SOURCE)" else "(skipped)")
      .append(" — that sidecar is left over from an earlier run and says nothing about this one.")
  }
  if (unexplained.isNotEmpty()) {
    if (threw.isNotEmpty()) {
      sb
        .append("\nNo sidecar from this run for ")
        .append(unexplained.size)
        .append(" preview(s) (")
        .append(unexplained.take(5).joinToString(", ") { it.id })
        .append(if (unexplained.size > 5) ", …): " else "): ")
    } else {
      sb.append("\n")
    }
    // The historical NO-SOURCE guidance is about the main renderer specifically — it is a `Test`
    // task, testClassesDirs is its input, and `composePreviewRender-reports` is its artifact. None
    // of that exists for the kind renderers, so previews they own get their own sentence instead of
    // being pointed at a fix that cannot apply to them.
    // Keyed by module as well as owner: task *names* repeat across modules, so grouping on the name
    // alone would fold `:a:composePreviewRenderLottie` into `:b:`'s and then quote the combined
    // count as one module's.
    val byOwner = unexplained.groupBy { it.module to it.renderTask }
    val paragraphs = buildList {
      // The main renderer's paragraph names no module and gives the same advice everywhere, so one
      // copy covers however many modules are in the group.
      if (byOwner.keys.any { (_, state) -> state.task == RENDER_TASK_NAME }) {
        add(
          "Check the Gradle output above — a common cause is the `composePreviewRender` task " +
            "reporting NO-SOURCE, which means the renderer test class wasn't found on " +
            "testClassesDirs. Per-preview stack traces are in the `composePreviewRender-reports` " +
            "artifact attached to the run."
        )
      }
      byOwner
        .filterKeys { (_, state) -> state.task != RENDER_TASK_NAME }
        .forEach { (key, entries) ->
          val (module, state) = key
          add(kindRendererGuidance(state, module, entries.size))
        }
    }
    sb.append(paragraphs.joinToString("\n"))
  }
  return sb.toString()
}

/**
 * The guidance for previews rendered by one of Android's kind-specific renderers.
 *
 * Deliberately not the NO-SOURCE paragraph: `composePreviewRenderLottie` /
 * `composePreviewRenderSvg` are `RenderPreviewsTask`s, not `Test` tasks — they have no
 * `testClassesDirs`, declare no `@SkipWhenEmpty` input (so they never report NO-SOURCE at all), and
 * write no `composePreviewRender-reports` artifact. What *does* skip them is `composePreview {
 * enabled = false }` (their `onlyIf`) or a failure in something they depend on.
 */
private fun kindRendererGuidance(state: RenderTaskState, module: String, count: Int): String {
  val kind = KIND_BY_RENDER_TASK[state.task]
  val owns = if (kind != null) "every `kind=$kind` preview" else "these previews"
  // "in this module" was a claim about scope, and a false one as soon as two modules' previews were
  // counted together — so the module is named from the entries themselves, or left out entirely.
  val where = if (module.isNotBlank()) " in $module" else ""
  // Deliberately makes no claim about what that task *did* — the stale paragraph above says that,
  // and only where it was observed. `unexplained` also holds previews whose renderer ran and simply
  // produced nothing, which "it did not run" would misdescribe.
  return "`${state.label}` renders $owns$where ($count here) — the Robolectric renderer skips that " +
    "kind — so check its outcome in the Gradle output above: `composePreview { enabled = false }` " +
    "skips it, as does a failure in a task it depends on."
}

/**
 * The `threw X: msg (at File.kt:42 in fn)` detail lines for one of a failing preview's sidecars.
 *
 * Leads with the **root** cause rather than the outermost throwable: the renderer invokes the
 * preview reflectively, so the outer exception is routinely a `InvocationTargetException` that says
 * nothing at all, while the last `Caused by:` in the trace is the real failure (issue #3741's case:
 * `NoClassDefFoundError: com/google/wear/services/ambient/AmbientComponentState`).
 *
 * [outputs] labels the line when a preview's outputs failed differently; empty for the ordinary
 * one-failure case. [stale] marks a sidecar the renderer had no chance to refresh this run.
 */
private fun StringBuilder.appendSidecarDetail(
  sidecar: RenderErrorSidecar,
  className: String,
  outputs: List<String> = emptyList(),
  stale: Boolean = false,
) {
  val chain = causeChainOf(sidecar.stackTrace)
  val root = chain.lastOrNull()
  val exception = root?.exception?.takeIf { it.isNotBlank() } ?: sidecar.exception
  val message = if (root != null) root.message else sidecar.message
  val frame = preferredAppFrame(sidecar.stackTrace, className) ?: sidecar.topAppFrame
  append("\n      ")
  if (outputs.isNotEmpty()) append(outputs.joinToString(", ")).append(" — ")
  if (stale) append("earlier run — ")
  append("threw ").append(exception.substringAfterLast('.'))
  if (message.isNotBlank()) append(": ").append(message)
  if (frame != null && frame.file.isNotBlank()) {
    append(" (at ").append(frame.file)
    if (frame.line > 0) append(':').append(frame.line)
    if (frame.function.isNotBlank()) append(" in ").append(frame.function)
    append(')')
  }
  if (chain.isNotEmpty()) {
    // The whole `Caused by:` chain, outermost first — the wrapper says *how* the renderer reached
    // the failure (reflective invoke, class initialisation), which the root cause alone hides.
    val names =
      (listOf(sidecar.exception) + chain.map { it.exception })
        .filter { it.isNotBlank() }
        .map { it.substringAfterLast('.') }
    append("\n        chain: ").append(names.joinToString(" → "))
  }
  if (sidecar.diagnosis.isNotBlank()) append("\n        ").append(sidecar.diagnosis)
}

/**
 * Every `Caused by:` entry of [stackTrace]'s **primary** chain, outermost cause first. Empty when
 * the trace carries no cause chain — the outermost throwable is then the whole story and the
 * sidecar's own `exception` / `message` already describe it.
 *
 * `Suppressed:` branches are excluded: a suppressed throwable with a cause of its own (the ordinary
 * shape for a `use {}` / try-with-resources body that threw and then failed to close) is printed by
 * `printStackTrace()` as an *indented* `Caused by:`, so trimming every line first made it
 * indistinguishable from the real chain — and, being printed last, it won the `lastOrNull()` that
 * picks the root cause. The close failure would then be reported as the render's root cause.
 */
fun causeChainOf(stackTrace: String): List<RenderErrorCause> =
  primaryTraceLines(stackTrace)
    .map { it.trim() }
    .filter { it.startsWith(CAUSED_BY_PREFIX) }
    .map { header ->
      val body = header.removePrefix(CAUSED_BY_PREFIX).trim()
      val split = body.indexOf(": ")
      if (split < 0) RenderErrorCause(body, "")
      else RenderErrorCause(body.substring(0, split), body.substring(split + 2).trim())
    }
    .toList()

/**
 * The deepest `Caused by:` entry of [stackTrace] — the failure worth leading with, since the outer
 * throwable is routinely a reflective wrapper. `null` when the trace has no cause chain.
 */
fun rootCauseOf(stackTrace: String): RenderErrorCause? = causeChainOf(stackTrace).lastOrNull()

/**
 * The first stack frame belonging to the *user's own* package, searching the deepest `Caused by:`
 * section first.
 *
 * The renderer's `topAppFrame` is computed from the outermost throwable's frames with a
 * skip-the-framework-prefixes heuristic, which lands on whichever tooling frame invoked the
 * composable — in issue #3741 that was `KeyboardDataProduct.kt:148`, a data-product frame in *this*
 * project, while the frame worth showing was the consumer's `AmbientAwareActivity.kt:76`. Anchoring
 * on the preview class's own package instead makes the one-line summary point at a file the user
 * can open. Package prefixes are tried longest-first (exact package, then parents down to two
 * segments) so a sibling package of the preview still counts, but `com.` never does.
 *
 * Returns `null` when nothing matches, leaving the sidecar's `topAppFrame` as the fallback.
 */
fun preferredAppFrame(stackTrace: String, previewClassName: String): RenderFailureFrame? {
  val prefixes = packagePrefixesOf(previewClassName)
  if (prefixes.isEmpty() || stackTrace.isBlank()) return null
  val sections = traceSections(stackTrace)
  for (section in sections.asReversed()) {
    for (prefix in prefixes) {
      val frame = section.firstNotNullOfOrNull { line ->
        parseFrame(line)?.takeIf { it.className.startsWith("$prefix.") }
      }
      if (frame != null) {
        return RenderFailureFrame(file = frame.file, line = frame.line, function = frame.function)
      }
    }
  }
  return null
}

private const val CAUSED_BY_PREFIX = "Caused by:"
private const val SUPPRESSED_PREFIX = "Suppressed:"

/**
 * [stackTrace]'s lines with every `Suppressed:` branch removed, indentation preserved.
 *
 * `Throwable.printStackTrace()` nests by indentation and nothing else: a suppressed throwable's
 * caption, frames, **and its own `Caused by:` chain** are printed one tab deeper than the throwable
 * that suppressed it (`printEnclosedStackTrace` passes `prefix + "\t"` for suppressed and the
 * unchanged `prefix` for causes). So a block that starts at indent *n* runs until the first
 * non-blank line indented less than *n* — everything in between belongs to the suppressed branch,
 * not to the chain the report walks. Concretely:
 * ```
 * Caused by: java.io.IOException: disk gone      <- primary chain, indent 0
 * 	at App.write(App.kt:3)
 * 	Suppressed: java.lang.RuntimeException: close failed
 * 		at App.close(App.kt:4)
 * 	Caused by: java.net.SocketException: reset    <- the *suppressed* one's cause, indent 1
 * ```
 */
private fun primaryTraceLines(stackTrace: String): List<String> {
  val out = mutableListOf<String>()
  var suppressedIndent: Int? = null
  for (line in stackTrace.lineSequence()) {
    if (line.isBlank()) {
      if (suppressedIndent == null) out += line
      continue
    }
    val indent = line.takeWhile { it == ' ' || it == '\t' }.length
    suppressedIndent?.let { if (indent < it) suppressedIndent = null }
    if (line.trimStart().startsWith(SUPPRESSED_PREFIX)) {
      // An outer block's bound wins: a suppressed-of-a-suppressed stays inside the outer one.
      suppressedIndent = minOf(suppressedIndent ?: indent, indent)
      continue
    }
    if (suppressedIndent != null) continue
    out += line
  }
  return out
}

/**
 * Split a printed stack trace into its throwable sections: the outermost throwable first, then one
 * per `Caused by:`. `Suppressed:` branches are dropped entirely (see [primaryTraceLines]) so
 * neither the cause chain nor the frame search can wander into one — the frame the report prints
 * has to belong to the failure it names.
 */
private fun traceSections(stackTrace: String): List<List<String>> {
  val sections = mutableListOf<MutableList<String>>(mutableListOf())
  for (line in primaryTraceLines(stackTrace)) {
    if (line.trim().startsWith(CAUSED_BY_PREFIX)) sections += mutableListOf<String>()
    sections.last() += line
  }
  return sections
}

private data class ParsedFrame(
  val className: String,
  val function: String,
  val file: String,
  val line: Int,
)

/** `\tat com.example.Foo$bar.invoke(Foo.kt:42)` → its parts; `null` for any other line. */
private fun parseFrame(line: String): ParsedFrame? {
  val match = FRAME_REGEX.find(line) ?: return null
  val (qualified, location) = match.destructured
  val className = qualified.substringBeforeLast('.', "")
  val function = qualified.substringAfterLast('.')
  if (className.isEmpty()) return null
  val colon = location.lastIndexOf(':')
  val file = if (colon > 0) location.substring(0, colon) else location
  val lineNumber = if (colon > 0) location.substring(colon + 1).toIntOrNull() ?: 0 else 0
  // `(Unknown Source)` / `(Native Method)` carry no file — useless as a "open this file" pointer.
  if (lineNumber <= 0) return null
  return ParsedFrame(className, function, file, lineNumber)
}

/**
 * `at [<module>/]<class>.<method>(<file>:<line>)`. The optional leading group swallows the
 * classloader / module qualifier a JPMS-aware JVM prints (`app//com.example.Foo.bar(...)`,
 * `java.base@17/java.lang.reflect.Method.invoke(...)`) — `/` never appears in a class name, so it
 * is unambiguous.
 */
private val FRAME_REGEX = Regex("""^\s*at\s+(?:[\w.@$]*/{1,2})?([\w$.<>-]+)\(([^()]*)\)""")

/**
 * Package prefixes to accept as "the user's own code", longest first: the preview class's package,
 * then each parent down to two segments. Two is the floor because a one-segment prefix (`com`,
 * `org`) would match every library on the classpath.
 */
private fun packagePrefixesOf(className: String): List<String> {
  val pkg = className.substringBeforeLast('.', "")
  if (pkg.isEmpty()) return emptyList()
  val segments = pkg.split('.')
  if (segments.size < 2) return emptyList()
  return (segments.size downTo 2).map { segments.take(it).joinToString(".") }
}
