package ee.schimke.composeai.plugin.tooling

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
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
 *
 * Configuration-cache safe: the runtime classpath `ResolutionResult`s are
 * wired in as `Provider<ResolvedComponentResult>` at registration time so
 * the action never reaches back to `task.project`.
 */
@DisableCachingByDefault(because = "Doctor findings depend on the live configuration resolution, not on a declared input set — caching across a version bump would silently stale-surface fixed issues.")
abstract class ComposePreviewDoctorTask : DefaultTask() {

    @get:Input
    abstract val variant: Property<String>

    @get:Input
    abstract val modulePath: Property<String>

    @get:Internal
    abstract val mainRuntimeRoot: Property<ResolvedComponentResult>

    @get:Internal
    abstract val testRuntimeRoot: Property<ResolvedComponentResult>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val variantName = variant.get()
        val main = collectModuleVersions(mainRuntimeRoot.orNull)
        val test = collectModuleVersions(testRuntimeRoot.orNull)
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
