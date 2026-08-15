package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import okio.FileSystem
import okio.Path.Companion.toPath

/*
 * Why this file exists (issue #3796).
 *
 * `MissingRenderReport`'s diagnostic took five review rounds, and every finding was the same bug in
 * a new costume: a sentence that outran its evidence. "rendered and then threw — the build wiring is
 * fine" without checking the renderer ran; "did not run" about a task that isn't the one that
 * renders Lottie; a sidecar quoted from a path this run's renderer could never have written; "N
 * previews in this module" of a count spanning two modules. Three of the five were introduced by the
 * previous round's patch, which is the tell that the shape was wrong rather than the details.
 *
 * The shape that caused it: facts and prose were interleaved. The formatter reached for whatever
 * fields were in scope and wrote a sentence, and nothing checked the sentence against what had
 * actually been observed. Backend knowledge — which task owns which preview, where the renderer can
 * write a sidecar — was spread across a predicate, a lookup and two comment blocks, so each round
 * re-derived it and one of them got it subtly wrong.
 *
 * So: this file holds *what is known* about a missing preview and nothing about how to say it.
 * [diagnose] is the single place that knows the backends. [PreviewDiagnosis] carries provenance —
 * [Evidence.Observed] or [Evidence.Unobserved] — rather than bare values, so a caller cannot read a
 * fact without also seeing how it was learned. `MissingRenderMessage.kt` turns diagnoses into
 * sentences, each function taking the specific evidence its sentence asserts.
 */

/**
 * A fact, together with whether this invocation actually learned it.
 *
 * The point is that [Unobserved] is not a value with a default — it is a distinct case a caller has
 * to handle, so "we didn't look" can never be silently rendered as "it isn't so".
 */
sealed interface Evidence<out T> {
  /** [value] was learned from [source] during this invocation. */
  data class Observed<out T>(val value: T, val source: String) : Evidence<T>

  /** Nothing was learned. No sentence may assert a value here. */
  data object Unobserved : Evidence<Nothing>
}

/** The observed value, or `null` when nothing was observed. */
fun <T> Evidence<T>.valueOrNull(): T? = (this as? Evidence.Observed<T>)?.value

/**
 * Which *sort* of Gradle task a renderer is, which is what decides the remedy a message may offer.
 *
 * The distinction is not cosmetic. The historical guidance ("`composePreviewRender` reported
 * NO-SOURCE — the renderer test class wasn't on testClassesDirs") describes a `Test` task's failure
 * mode. `composePreviewRenderLottie` / `composePreviewRenderSvg` are `RenderPreviewsTask`s: no
 * `testClassesDirs`, no `@SkipWhenEmpty` input (so NO-SOURCE is not a state they can report at
 * all), no `composePreviewRender-reports` artifact. Attaching the remedy to this enum is what makes
 * a wrong-task remedy unwriteable rather than reviewable.
 */
enum class RendererTaskKind {
  /** `composePreviewRender` — the module's main renderer, and the only NO-SOURCE-capable one. */
  MAIN,
  /** `composePreviewRenderLottie` / `composePreviewRenderSvg` — Android's per-kind renderers. */
  KIND_SPECIFIC,
}

/**
 * The renderer task that owns a preview's outputs.
 *
 * [path] is qualified (`:app:composePreviewRenderLottie`) because task *names* repeat across
 * modules: every Android module registers its own, with independently different outcomes, so a bare
 * name can neither group entries nor be printed as something to go and inspect.
 */
data class RendererTask(
  val name: String,
  /** `:module:name`, or empty when the module isn't known. */
  val path: String,
  val kind: RendererTaskKind,
  /** The `params.kind` this task exists to render, for [RendererTaskKind.KIND_SPECIFIC]. */
  val rendersKind: String? = null,
) {
  /** What a message calls this task: the qualified path when known, else the bare name. */
  val label: String
    get() = path.ifEmpty { name }

  /** Whether Gradle can report NO-SOURCE for it — a `Test`-task state the kind renderers lack. */
  val canReportNoSource: Boolean
    get() = kind == RendererTaskKind.MAIN
}

/**
 * How this run learned that an output exists, which bounds what may be said about its sidecar.
 *
 * The distinction is load-bearing for `@PreviewParameter` fan-outs. A declared output is one the
 * manifest names, so the renderer targeted it this invocation. A scanned one was found by globbing
 * the renders directory, and the row it belongs to may not exist any more: a provider value that is
 * renamed or removed leaves its `.error.json` behind, because both renderers'
 * `deleteStaleFanoutFiles` match the template's `png` / `gif` extension and never the sidecar
 * companion.
 */
enum class OutputDiscovery {
  /** Named by the manifest — the owning renderer targeted it this run. */
  DECLARED,
  /** Found by scanning for fan-out files; this run may not have attempted that row. */
  SCANNED,
}

/** How confidently this invocation can date a finding — see [PreviewDiagnosis.dating]. */
enum class SidecarDating {
  /** The owning renderer ran and targeted this output, so the failure is this run's. */
  THIS_RUN,
  /** The owning renderer was skipped, so everything beside its outputs predates this run. */
  EARLIER_RUN,
  /** Nothing observed, or a scanned row this run may not have attempted. */
  UNDATED,
}

/** One `.error.json` found beside one of a preview's would-be outputs. */
data class SidecarFinding(
  /** Module-relative path of the output it sits beside, e.g. `renders/Foo_500ms.png`. */
  val output: String,
  val sidecar: RenderErrorSidecar,
  /** Whether the manifest named this output or it was found by scanning. */
  val discovery: OutputDiscovery = OutputDiscovery.DECLARED,
)

/**
 * Everything known about one preview that produced no PNG — and, for each fact, how it was learned.
 *
 * [owner] is identity, derivable from the manifest, so it is a plain value. [ownerRun] is
 * behaviour, knowable only from the build, so it carries provenance. That split is the whole
 * design: a sentence about what a task *did* has to reach through [ownerRun] and therefore cannot
 * be written when nothing was observed.
 */
data class PreviewDiagnosis(
  val id: String,
  val module: String,
  /** Human-readable capture coordinates that came back empty, e.g. `default`, `500ms`. */
  val coords: String,
  /** The preview's own class FQN — used to pick a stack frame in the *user's* package. */
  val className: String = "",
  /** Which renderer task owns this preview's outputs. */
  val owner: RendererTask = RendererTask(MAIN_RENDER_TASK, "", RendererTaskKind.MAIN),
  /** What this invocation saw that task do. */
  val ownerRun: Evidence<GradleTaskDisposition> = Evidence.Unobserved,
  /** Sidecars found beside the outputs [owner] could have written this run. */
  val sidecars: List<SidecarFinding> = emptyList(),
) {
  /**
   * `true` when the owning renderer ran (or was up-to-date, i.e. its outputs are current), `false`
   * when it was skipped, `null` when this run observed nothing about it.
   *
   * Gradle reports NO-SOURCE as a skip, and NO-SOURCE is exactly the wiring bug the historical
   * guidance was written for — a run where the file on disk cannot have come from this render.
   */
  val ownerRan: Boolean?
    get() = ownerRun.valueOrNull()?.let { it != GradleTaskDisposition.SKIPPED }

  /**
   * How confidently this run can date [finding].
   *
   * Two independent things have to hold before a sidecar is this invocation's work: the owning
   * renderer has to have run, **and** it has to have targeted that output. A scanned fan-out row
   * fails the second even when the first holds — nothing deletes a fan-out `.error.json` when its
   * provider value goes away, so the file may describe a row this run never attempted.
   */
  fun dating(finding: SidecarFinding): SidecarDating =
    when {
      ownerRan == false -> SidecarDating.EARLIER_RUN
      ownerRan == null -> SidecarDating.UNDATED
      finding.discovery == OutputDiscovery.SCANNED -> SidecarDating.UNDATED
      else -> SidecarDating.THIS_RUN
    }

  /** Sidecars this run is entitled to present as its own findings. */
  val threwThisRun: Boolean
    get() = sidecars.any { dating(it) == SidecarDating.THIS_RUN }

  /** Sidecars that survive from an earlier run because the owning renderer never ran this time. */
  val staleSidecars: Boolean
    get() = sidecars.isNotEmpty() && sidecars.all { dating(it) == SidecarDating.EARLIER_RUN }

  /** Sidecars this run cannot date — nothing observed, or a row it may not have attempted. */
  val threwUndated: Boolean
    get() = sidecars.any { dating(it) == SidecarDating.UNDATED }

  /**
   * Whether the render task's own behaviour — rather than the composable's — still has to explain
   * this entry: no sidecar at all, or one the renderer had no chance to refresh.
   */
  val unexplained: Boolean
    get() = sidecars.isEmpty() || staleSidecars
}

/**
 * The renderer task every backend registers — Robolectric on Android, the JVM renderer elsewhere.
 */
internal const val MAIN_RENDER_TASK: String = "composePreviewRender"

/**
 * Preview kinds the **Android** backend renders from their own task rather than from
 * [MAIN_RENDER_TASK], because Robolectric can inflate neither (`RobolectricRenderTest` skips both):
 * a Lottie asset needs Compottie and an SVG needs Skia, so `AndroidPreviewSupport` registers
 * `composePreviewRenderLottie` / `composePreviewRenderSvg` on the desktop renderer's classpath,
 * each writing into its own disjoint `lottie-renders/` / `svg-renders/` dir, and folds both into
 * `composePreviewRenderAll`.
 *
 * They matter because they run *independently* of the Robolectric task: a NO-SOURCE
 * `composePreviewRender` says nothing about a Lottie preview whose own renderer ran and threw two
 * seconds ago.
 */
private val KIND_RENDER_TASKS =
  mapOf("LOTTIE" to "composePreviewRenderLottie", "SVG" to "composePreviewRenderSvg")

/**
 * The task that owns a [kind] preview's outputs in [modulePath].
 *
 * The desktop backend has no per-kind split (`RenderPreviewsTask` renders every kind from
 * [MAIN_RENDER_TASK]), which is exactly how the two are told apart: the kind tasks are
 * unconditional `composePreviewRenderAll` dependencies on Android, so a run that reached this
 * report has an outcome for them — an `onlyIf`-disabled task still reports SKIPPED. No kind task in
 * [taskOutcomes] therefore means no split in this module, and the main renderer owns the output.
 */
internal fun ownerTaskFor(
  modulePath: String,
  kind: String,
  taskOutcomes: Map<String, GradleTaskOutcome>,
): RendererTask {
  val prefix = if (modulePath.isBlank()) "" else ":" + modulePath.trim(':') + ":"
  val normalisedKind = kind.uppercase()
  val kindTask = KIND_RENDER_TASKS[normalisedKind]
  if (kindTask != null && prefix.isNotEmpty() && taskOutcomes.containsKey(prefix + kindTask)) {
    return RendererTask(
      name = kindTask,
      path = prefix + kindTask,
      kind = RendererTaskKind.KIND_SPECIFIC,
      rendersKind = normalisedKind,
    )
  }
  return RendererTask(
    name = MAIN_RENDER_TASK,
    path = if (prefix.isEmpty()) "" else prefix + MAIN_RENDER_TASK,
    kind = RendererTaskKind.MAIN,
  )
}

/**
 * Diagnose every preview in [missing]: who owns it, what that owner did, and which sidecars sit
 * beside the outputs that owner could have written *this run*.
 *
 * The single place that knows the backends. Everything downstream is prose over these facts.
 */
fun diagnoseMissingRenders(
  missing: List<PreviewResult>,
  manifests: List<Pair<PreviewModule, PreviewManifest>>,
  taskOutcomes: Map<String, GradleTaskOutcome> = emptyMap(),
  fileSystem: FileSystem = SystemFileSystem,
): List<PreviewDiagnosis> {
  val moduleByPath = manifests.associate { (module, _) -> module.gradlePath to module }
  val previewsByModule = manifests.associate { (module, manifest) ->
    module.gradlePath to manifest.previews.associateBy { it.id }
  }
  val declaredByModule = manifests.associate { (module, manifest) ->
    module.gradlePath to declaredOutputsOf(manifest)
  }
  return missing.map { result ->
    diagnose(
      result = result,
      module = moduleByPath[result.module],
      preview = previewsByModule[result.module]?.get(result.id),
      siblingOutputs = declaredByModule[result.module].orEmpty(),
      taskOutcomes = taskOutcomes,
      fileSystem = fileSystem,
    )
  }
}

/**
 * One preview's diagnosis.
 *
 * [siblingOutputs] is every output the module's manifest claims, used to keep a `@PreviewParameter`
 * fan-out glob from adopting a *different* preview's file.
 */
internal fun diagnose(
  result: PreviewResult,
  module: PreviewModule?,
  preview: PreviewInfo?,
  siblingOutputs: Set<String> = emptySet(),
  taskOutcomes: Map<String, GradleTaskOutcome> = emptyMap(),
  fileSystem: FileSystem = SystemFileSystem,
): PreviewDiagnosis {
  val owner = ownerTaskFor(result.module, result.params.kind, taskOutcomes)
  val ownerRun =
    taskOutcomes[owner.path]?.let {
      Evidence.Observed(it.disposition, source = "gradle task outcome for ${owner.path}")
    } ?: Evidence.Unobserved
  val outputs = refreshableOutputs(result, preview, module, siblingOutputs, fileSystem)
  val sidecars =
    module?.let { m ->
      outputs.mapNotNull { (rel, discovery) ->
        readRenderErrorSidecar(m.projectDir.resolve("build/compose-previews/$rel"), fileSystem)
          ?.let { SidecarFinding(output = rel, sidecar = it, discovery = discovery) }
      }
    } ?: emptyList()
  return PreviewDiagnosis(
    id = result.id,
    module = result.module,
    coords = missingCaptureCoords(result),
    className = result.className,
    owner = owner,
    ownerRun = ownerRun,
    sidecars = sidecars,
  )
}

/** Every output path the manifest claims for [manifest]'s previews. */
private fun declaredOutputsOf(manifest: PreviewManifest): Set<String> =
  manifest.previews
    .flatMap { p -> p.captures.map { it.renderOutput } + p.dataProducts.map { it.output } }
    .filter { it.isNotEmpty() }
    .toSet()

/**
 * The module-relative outputs this run's renderer **could have written a sidecar to** for [result].
 *
 * This is the list that decides what the report is allowed to quote, so it models the renderers
 * rather than guessing:
 * - Every output the manifest declares — each capture's `renderOutput` and each data product's — is
 *   an independent render attempt, and both renderers write the throwable beside the artefact they
 *   were producing. All of them are read, not just the first: a time / scroll fan-out can die
 *   differently at 1000ms than at 500ms.
 * - `renders/<id>.png` (the default stem) only when the **first** capture declares no path, or the
 *   manifest declares nothing at all, or doesn't describe the preview. `RobolectricRenderTest`
 *   anchors the preview-level sidecar on `captures.firstOrNull()` resolved through
 *   `renderOutput.substringAfterLast('/').ifEmpty { "<id>.png" }` (or the first data product when
 *   there are no captures), deletes any stale file there before rendering, and writes the throwable
 *   there from its outer catch; its two per-job writes need a `.gif` extension or a data-product
 *   path, so neither can land on the default stem. A blank capture in any *later* position
 *   therefore cannot produce a fresh default-stem sidecar, and quoting one would report whatever an
 *   older manifest left behind — nothing deletes a stale `.error.json`, since `cleanStaleRenders`
 *   walks `png`/`gif` only. The desktop backend is more permissive (`RenderPreviewsTask` resolves
 *   every blank capture to `<id>.png` and forks the renderer there); modelling Android is
 *   deliberate — it is the stricter of the two, so the risk it takes is "we didn't look" rather
 *   than "we asserted something false", and the shape where they disagree (a declared first capture
 *   followed by a blank one) is not something discovery emits, since it writes a `renderOutput` for
 *   every capture.
 * - Each `@PreviewParameter` fan-out file. The renderer writes one output per provider value
 *   (`<stem>_<label>.png`) and its sidecar beside *that*, which neither the declared template
 *   output nor the default stem ever pointed at — so a per-value failure used to be invisible to
 *   the CLI.
 */
private fun refreshableOutputs(
  result: PreviewResult,
  preview: PreviewInfo?,
  module: PreviewModule?,
  siblingOutputs: Set<String>,
  fileSystem: FileSystem,
): List<Pair<String, OutputDiscovery>> {
  val declared = buildList {
    preview?.captures?.forEach { if (it.renderOutput.isNotEmpty()) add(it.renderOutput) }
    preview?.dataProducts?.forEach { if (it.output.isNotEmpty()) add(it.output) }
  }
    .distinct()
  val defaultStem = "renders/${result.id}.png"
  val defaultStemIsLive =
    preview == null ||
      declared.isEmpty() ||
      preview.captures.firstOrNull()?.renderOutput?.isEmpty() == true
  // Every template the renderer inserts a parameter suffix into: each declared capture, each
  // declared data product (`RenderPreviewsTask` forks the renderer per product with the product's
  // own path, and the renderer suffixes whatever path it is handed), and the effective default for
  // a blank capture (the plugin resolves it to `renders/<id>.png` *before* the suffix goes in).
  val fanoutTemplates =
    if (preview?.params?.previewParameterProviderClassName == null) emptyList()
    else
      buildList {
        preview.captures.forEach { add(it.renderOutput.ifEmpty { defaultStem }) }
        preview.dataProducts.forEach { if (it.output.isNotEmpty()) add(it.output) }
      }
        .distinct()
  val fanout =
    if (module == null) emptyList()
    else paramFanoutOutputs(fanoutTemplates, module, siblingOutputs, fileSystem)
  return buildList {
    declared.forEach { add(it to OutputDiscovery.DECLARED) }
    if (defaultStemIsLive) add(defaultStem to OutputDiscovery.DECLARED)
    fanout.forEach { add(it to OutputDiscovery.SCANNED) }
  }
    .distinctBy { it.first }
}

/**
 * The `<stem>_<label>.<ext>` fan-out outputs of a `@PreviewParameter` preview that currently have a
 * sidecar on disk, found by listing each of [templates]' directories.
 *
 * A glob rather than a computed list because only the provider knows its values — the same reason
 * `PreviewResultBuilder.expandParamCaptures` globs for the PNGs. Two exclusions, both about not
 * attributing one preview's failure to another: a name another preview declares as its own output,
 * and a name a *more specific sibling template* owns — with `Foo.png` and `Foo_Dark.png` in one
 * directory, `Foo_Dark_Alice.png` is `Foo_Dark`'s row even though it matches `Foo_`. That second
 * rule is [parameterFanoutOwnedBySibling], shared with the builder that expands the same glob for
 * files that exist, because two copies of it would drift.
 */
private fun paramFanoutOutputs(
  templates: List<String>,
  module: PreviewModule,
  siblingOutputs: Set<String>,
  fileSystem: FileSystem,
): List<String> =
  templates
    .flatMap { template ->
      val dir = template.substringBeforeLast('/', "")
      val leaf = template.substringAfterLast('/')
      val dot = leaf.lastIndexOf('.')
      if (dot <= 0) return@flatMap emptyList()
      val stemPrefix = leaf.substring(0, dot) + "_"
      val suffix = leaf.substring(dot) + RENDER_ERROR_SIDECAR_SUFFIX
      val absoluteDir =
        module.projectDir.resolve(
          if (dir.isEmpty()) "build/compose-previews" else "build/compose-previews/$dir"
        )
      val dirPrefix = if (dir.isEmpty()) "" else "$dir/"
      (fileSystem.listOrNull(absoluteDir.path.toPath()) ?: emptyList())
        .map { it.name }
        .filter { it.startsWith(stemPrefix) && it.endsWith(suffix) }
        .map { dirPrefix + it.removeSuffix(RENDER_ERROR_SIDECAR_SUFFIX) }
        .filter { candidate ->
          candidate !in siblingOutputs &&
            siblingOutputs.none { sibling ->
              parameterFanoutOwnedBySibling(
                templateOutput = template,
                siblingOutput = sibling,
                candidateOutput = candidate,
              )
            }
        }
        .sorted()
    }
    .distinct()
