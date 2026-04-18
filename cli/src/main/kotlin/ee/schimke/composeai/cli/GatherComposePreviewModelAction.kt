package ee.schimke.composeai.cli

import ee.schimke.composeai.plugin.tooling.ComposePreviewModel
import ee.schimke.composeai.plugin.tooling.ModuleFinding
import ee.schimke.composeai.plugin.tooling.ModuleInfo
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.gradle.GradleBuild
import java.io.Serializable

/**
 * Aggregates [ComposePreviewModel] across every project in the build.
 *
 * Runs in the Gradle daemon (serialised by the Tooling API). Walks every
 * project via the [GradleBuild] model and asks for the per-project
 * `ComposePreviewModel` on each — projects that don't apply the plugin
 * return an empty `modules` map, which we drop. Aggregation happens inside
 * the daemon so each per-project `findModel` call is cross-project-safe
 * under Isolated Projects, where a single root-scoped builder wouldn't be
 * allowed to poke at subprojects.
 *
 * The return type is a [ComposePreviewModel] implementation defined on the
 * CLI side so the Tooling API can ship it back without proxy round-tripping
 * per field access.
 */
class GatherComposePreviewModelAction : BuildAction<AggregatedComposePreviewModel> {
    override fun execute(controller: BuildController): AggregatedComposePreviewModel {
        val build = controller.getModel(GradleBuild::class.java)
        val aggregated = linkedMapOf<String, SerializableModuleInfo>()
        var pluginVersion = ""
        for (project in build.projects) {
            val model = try {
                controller.findModel(project, ComposePreviewModel::class.java)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (model.pluginVersion.isNotEmpty()) pluginVersion = model.pluginVersion
            for ((path, info) in model.modules) {
                aggregated[path] = SerializableModuleInfo(
                    variant = info.variant,
                    mainRuntimeDependencies = info.mainRuntimeDependencies.toMap(),
                    testRuntimeDependencies = info.testRuntimeDependencies.toMap(),
                    findings = info.findings.map { f ->
                        SerializableModuleFinding(
                            id = f.id,
                            severity = f.severity,
                            message = f.message,
                            detail = f.detail,
                            remediationSummary = f.remediationSummary,
                            remediationCommands = f.remediationCommands.toList(),
                            docsUrl = f.docsUrl,
                        )
                    },
                )
            }
        }
        return AggregatedComposePreviewModel(pluginVersion, aggregated)
    }
}

/**
 * CLI-side [ComposePreviewModel] implementation. Tooling API serialises this
 * class by value, so both sides of the daemon boundary see the same object
 * shape without dynamic proxies — easier to debug and cheaper to iterate.
 */
data class AggregatedComposePreviewModel(
    override val pluginVersion: String,
    override val modules: Map<String, SerializableModuleInfo>,
) : ComposePreviewModel, Serializable

data class SerializableModuleInfo(
    override val variant: String,
    override val mainRuntimeDependencies: Map<String, String>,
    override val testRuntimeDependencies: Map<String, String>,
    override val findings: List<SerializableModuleFinding>,
) : ModuleInfo, Serializable

data class SerializableModuleFinding(
    override val id: String,
    override val severity: String,
    override val message: String,
    override val detail: String?,
    override val remediationSummary: String?,
    override val remediationCommands: List<String>,
    override val docsUrl: String?,
) : ModuleFinding, Serializable
