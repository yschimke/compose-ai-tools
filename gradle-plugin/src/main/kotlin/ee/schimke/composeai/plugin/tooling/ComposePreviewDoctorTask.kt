package ee.schimke.composeai.plugin.tooling

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Writes [CompatRules] findings for the current module to a sidecar JSON.
 * Parallels the `ComposePreviewModel` path used by the CLI — this task
 * exists so drivers that can't run Gradle `BuildAction`s (notably the
 * VS Code extension, which uses the vscode-gradle task API) still get the
 * same output shape via a plain task invocation.
 *
 * Output shape matches [DoctorReport] in
 * `cli/src/main/kotlin/ee/schimke/composeai/cli/DoctorCommand.kt` at the
 * per-module level — the CLI and the extension converge on the same JSON
 * schema (`compose-preview-doctor/v1`).
 *
 * Cheap to run: resolves the two configurations' `resolutionResult` (no
 * artifact downloads), applies rules, writes a small JSON file.
 */
@DisableCachingByDefault(because = "Doctor findings depend on the live configuration resolution, not on a declared input set — caching across a version bump would silently stale-surface fixed issues.")
abstract class ComposePreviewDoctorTask : DefaultTask() {

    @get:Input
    abstract val variant: Property<String>

    @get:Input
    abstract val modulePath: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val variantName = variant.get()
        val main = resolveConfiguration("${variantName}RuntimeClasspath")
        val test = resolveConfiguration("${variantName}UnitTestRuntimeClasspath")
        val findings = CompatRules.evaluate(main, test)
        val report = DoctorModuleReport(
            schema = SCHEMA,
            module = modulePath.get(),
            variant = variantName,
            findings = findings.map { f ->
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
        )
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(JSON.encodeToString(report))
        logger.lifecycle("Wrote compose-preview doctor report for ${modulePath.get()}: ${findings.size} finding(s) → ${out.path}")
    }

    private fun resolveConfiguration(name: String): Map<String, String> {
        val config = project.configurations.findByName(name) ?: return emptyMap()
        if (!config.isCanBeResolved) return emptyMap()
        return try {
            val out = LinkedHashMap<String, String>()
            for (dep in config.incoming.resolutionResult.allDependencies) {
                val resolved = dep as? org.gradle.api.artifacts.result.ResolvedDependencyResult ?: continue
                val id = resolved.selected.id
                if (id is ModuleComponentIdentifier) {
                    out.putIfAbsent("${id.group}:${id.module}", id.version)
                }
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    companion object {
        internal const val SCHEMA = "compose-preview-doctor/v1"
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}

@Serializable
internal data class DoctorModuleReport(
    val schema: String,
    val module: String,
    val variant: String,
    val findings: List<DoctorFinding>,
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
