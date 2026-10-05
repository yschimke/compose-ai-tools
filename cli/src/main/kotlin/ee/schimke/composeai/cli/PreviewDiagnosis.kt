package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.previewdata.PreviewResult
import ee.schimke.composeai.previewdata.parameterFanoutOwnedBySibling
import ee.schimke.composeai.previewdriver.GradleTaskDisposition
import ee.schimke.composeai.previewdriver.GradleTaskOutcome
import okio.FileSystem
import okio.Path.Companion.toPath

/*
 * What is known about a preview that produced no PNG, and nothing about how to say it (issue #3796).
 * [diagnose] is the single place that knows the backends; facts carry [Evidence] provenance so a
 * sentence in `MissingRenderMessage.kt` cannot assert something this run never observed.
 */

/**
 * A fact, together with whether this invocation learned it. [Unobserved] is a distinct case, so "we
 * didn't look" can never render as "it isn't so".
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
 * Which sort of renderer task owns a preview, which decides the remedy a message may offer: the
 * kind-specific tasks are `RenderPreviewsTask`s with no `testClassesDirs` and can't report
 * NO-SOURCE.
 */
enum class RendererTaskKind {
  /** `composePreviewRender` — the module's main renderer, and the only NO-SOURCE-capable one. */
  MAIN,
  /** `composePreviewRenderLottie` / `composePreviewRenderSvg` — Android's per-kind renderers. */
  KIND_SPECIFIC,
}

/** The renderer task that owns a preview's outputs; [path] is qualified since names repeat. */
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
 * How this run learned an output exists. A scanned `@PreviewParameter` row may no longer exist: a
 * removed provider value leaves its `.error.json` behind (`deleteStaleFanoutFiles` only matches
 * `png` / `gif`).
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
 * Everything known about one preview that produced no PNG. [owner] is identity from the manifest;
 * [ownerRun] is behaviour observed from the build and so carries provenance.
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
   * `true` when the owning renderer ran or was up to date, `false` when skipped (including
   * NO-SOURCE), `null` when unobserved.
   */
  val ownerRan: Boolean?
    get() = ownerRun.valueOrNull()?.let { it != GradleTaskDisposition.SKIPPED }

  /**
   * How confidently this run can date [finding]: only a declared output of a renderer that ran is
   * this run's.
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

/** The renderer task every backend registers. */
internal const val MAIN_RENDER_TASK: String = "composePreviewRender"

/**
 * Preview kinds the Android backend renders from their own desktop-classpath task, since
 * Robolectric can't inflate them. They run independently of [MAIN_RENDER_TASK], so its NO-SOURCE
 * says nothing about them.
 */
private val KIND_RENDER_TASKS =
  mapOf("LOTTIE" to "composePreviewRenderLottie", "SVG" to "composePreviewRenderSvg")

/**
 * The task that owns a [kind] preview's outputs in [modulePath]. Kind tasks always report an
 * outcome on Android (even SKIPPED), so their absence from [taskOutcomes] means no split here.
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
 * The module-relative outputs this run's renderer could have written a sidecar to for [result]:
 * - every declared capture and data-product output (each is an independent attempt);
 * - `renders/<id>.png` only when the first capture declares no path or nothing is declared. This
 *   models `RobolectricRenderTest`, the stricter backend, so a stale default-stem sidecar from an
 *   older manifest is never quoted;
 * - each `@PreviewParameter` fan-out file (`<stem>_<label>.png`), found by scanning.
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
  // Every template the renderer suffixes: each capture (blank resolved to the default stem first)
  // and each data product.
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
 * The `<stem>_<label>.<ext>` fan-out outputs of [templates] that have a sidecar on disk, found by
 * listing (only the provider knows its values). Excludes names another preview declares and rows a
 * more specific sibling template owns ([parameterFanoutOwnedBySibling]).
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
