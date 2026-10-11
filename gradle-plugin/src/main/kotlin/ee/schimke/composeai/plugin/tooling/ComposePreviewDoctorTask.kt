package ee.schimke.composeai.plugin.tooling

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Writes [CompatRules] findings for this module to a JSON sidecar, for drivers that can't run
 * `BuildAction`s (VS Code); same per-module shape as the CLI's [DoctorReport]
 * (`compose-preview-doctor/v1`). Cheap: resolution results only. Config-cache safe: results arrive
 * as `Provider<ResolvedComponentResult>`.
 */
@DisableCachingByDefault(
  because =
    "Doctor findings depend on the live configuration resolution, not on a declared input set — caching across a version bump would silently stale-surface fixed issues."
)
abstract class ComposePreviewDoctorTask : DefaultTask() {

  @get:Input abstract val variant: Property<String>

  @get:Input abstract val modulePath: Property<String>

  /** Gradle version (e.g. `"9.4.1"`), as a plain string for the configuration cache. */
  @get:Input abstract val gradleVersion: Property<String>

  @get:Internal abstract val mainRuntimeRoot: Property<ResolvedComponentResult>

  @get:Internal abstract val testRuntimeRoot: Property<ResolvedComponentResult>

  /** `android.defaultConfig.minSdk`; unset means not checkable. */
  @get:Input @get:Optional abstract val moduleMinSdk: Property<Int>

  /**
   * AAR manifests on the unit-test runtime classpath, resolved lazily, for the library-minSdk
   * check.
   */
  @get:Internal abstract val testManifestArtifacts: SetProperty<ResolvedArtifactResult>

  /**
   * JSON-encoded `List<InjectedDependency>` from plugin apply time (default `"[]"`); a string keeps
   * the config-cache image simple.
   */
  @get:Input abstract val injectedDependenciesJson: Property<String>

  /**
   * Whether a preview-tooling coord is declared directly (captured in `onVariants`). Defaults
   * `true` so the check stays silent when unwired.
   */
  @get:Input abstract val previewToolingDeclared: Property<Boolean>

  /**
   * `composePreview.enforcePreviewToolingDependency`, including `-P` overrides. Defaults `true`
   * like [previewToolingDeclared].
   */
  @get:Input abstract val enforcePreviewToolingDependency: Property<Boolean>

  @get:OutputFile abstract val outputFile: RegularFileProperty

  @TaskAction
  fun run() {
    val variantName = variant.get()
    val mainRoot = mainRuntimeRoot.orNull
    val main = collectModuleVersions(mainRoot)
    val test = collectModuleVersions(testRuntimeRoot.orNull)
    // Transitive reachability is computed here from the resolved graph (#1549) rather than as an
    // `@Input`, avoiding configuration-time resolution.
    val transitivePreviewToolingDetected =
      mainRoot?.let {
        ee.schimke.composeai.plugin.ValidatePreviewToolingPresentTask.containsPreviewTooling(it)
      } ?: false
    val libraryMinSdks = runCatching {
      LibraryMinSdkCollector.collect(testManifestArtifacts.getOrElse(emptySet()))
    }
      .getOrElse { emptyList() }
    val findings =
      CompatRules.evaluate(
        main,
        test,
        gradleVersion.orNull,
        previewToolingDeclared = previewToolingDeclared.getOrElse(true),
        enforcePreviewToolingDependency = enforcePreviewToolingDependency.getOrElse(true),
        transitivePreviewToolingDetected = transitivePreviewToolingDetected,
        moduleMinSdk = moduleMinSdk.orNull,
        libraryMinSdks = libraryMinSdks,
      )
    val injections = decodeInjectedDependencys(injectedDependenciesJson.getOrElse("[]"))
    val report =
      DoctorModuleReport(
        schema = SCHEMA,
        module = modulePath.get(),
        variant = variantName,
        findings =
          findings.map { f ->
            DoctorFinding(
              id = f.id,
              severity = f.severity,
              message = f.message,
              detail = f.detail,
              remediationSummary = f.remediationSummary,
              remediationCommands = f.remediationCommands,
              docsUrl = f.docsUrl,
            )
          },
        injectedDependencies = injections,
      )
    val out = outputFile.get().asFile
    out.parentFile.mkdirs()
    out.writeText(JSON.encodeToString(report))
    printSummary(report, out)
  }

  private fun decodeInjectedDependencys(raw: String): List<InjectedDependency> = runCatching {
    JSON.decodeFromString<List<InjectedDependency>>(raw)
  }
    .getOrElse { emptyList() }

  /** Human-readable summary mirroring the CLI's text output; the JSON is authoritative. */
  private fun printSummary(report: DoctorModuleReport, out: File) {
    logger.lifecycle("compose-preview doctor — ${report.module} (variant: ${report.variant})")
    if (report.findings.isEmpty()) {
      logger.lifecycle("  ✓ no compatibility issues found")
    } else {
      for (f in report.findings) {
        val marker =
          when (f.severity) {
            "error" -> "✗"
            "warning" -> "!"
            "info" -> "∙"
            else -> "?"
          }
        logger.lifecycle("  $marker [${f.severity}] ${f.message}")
        f.remediationSummary?.let { logger.lifecycle("      → $it") }
        for (cmd in f.remediationCommands) logger.lifecycle("        \$ $cmd")
        f.docsUrl?.let { logger.lifecycle("        docs: $it") }
      }
      val errors = report.findings.count { it.severity == "error" }
      val warnings = report.findings.count { it.severity == "warning" }
      logger.lifecycle(
        "  ${report.findings.size} finding(s): $errors error(s), $warnings warning(s)"
      )
    }
    if (report.injectedDependencies.isNotEmpty()) {
      logger.lifecycle("  injected dependencies:")
      for (inj in report.injectedDependencies) {
        val config = inj.configuration.ifEmpty { "—" }
        logger.lifecycle("    ${inj.outcome} [${inj.coordinate}] → $config  (${inj.reason})")
      }
    }
    logger.lifecycle("  report: ${out.path}")
  }

  private fun collectModuleVersions(root: ResolvedComponentResult?): Map<String, String> {
    if (root == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    val seen = HashSet<ResolvedComponentResult>()
    val stack = ArrayDeque<ResolvedComponentResult>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
      val node = stack.removeLast()
      if (!seen.add(node)) continue
      val id = node.id
      if (id is ModuleComponentIdentifier) {
        out.putIfAbsent("${id.group}:${id.module}", id.version)
      }
      for (dep in node.dependencies) {
        val resolved = dep as? ResolvedDependencyResult ?: continue
        stack.addLast(resolved.selected)
      }
    }
    return out
  }

  companion object {
    internal const val SCHEMA = "compose-preview-doctor/v1"
    private val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
    }
  }
}

@Serializable
internal data class DoctorModuleReport(
  val schema: String,
  val module: String,
  val variant: String,
  val findings: List<DoctorFinding>,
  val injectedDependencies: List<InjectedDependency> = emptyList(),
)

@Serializable
internal data class DoctorFinding(
  val id: String,
  val severity: String,
  val message: String,
  val detail: String? = null,
  val remediationSummary: String? = null,
  val remediationCommands: List<String> = emptyList(),
  val docsUrl: String? = null,
)

/**
 * One injected-dependency decision, surfaced in `doctor.json` for drivers without `BuildAction`s.
 * `outcome`:
 * - `APPLIED` — unconditional injection fired.
 * - `MATCHED` — conditional injection fired on its signal.
 * - `SKIPPED` — signal absent; `configuration` is empty.
 */
@Serializable
internal data class InjectedDependency(
  val coordinate: String,
  val configuration: String,
  val outcome: String,
  val reason: String,
)
