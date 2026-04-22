package ee.schimke.composeai.plugin.tooling

import ee.schimke.composeai.plugin.PluginVersion
import ee.schimke.composeai.plugin.PreviewExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.testing.Test
import org.gradle.tooling.provider.model.ToolingModelBuilder
import java.io.Serializable

/**
 * Builds a build-wide [ComposePreviewModel] snapshot — resolves
 * `${variant}RuntimeClasspath` and `${variant}UnitTestRuntimeClasspath` on
 * every project where the compose-preview plugin applied, and packs the
 * result into interfaces the CLI consumes over the Tooling API.
 *
 * Registered once per build from
 * [ee.schimke.composeai.plugin.ComposePreviewPlugin.apply]. [canBuild]
 * accepts the build-root path regardless of which project the caller hit —
 * we always return the build-wide snapshot so the CLI doesn't have to iterate.
 */
internal class ComposePreviewModelBuilder : ToolingModelBuilder {

    override fun canBuild(modelName: String): Boolean =
        modelName == ComposePreviewModel::class.java.name

    override fun buildAll(modelName: String, project: Project): Any {
        // Builder runs per-project under Isolated Projects — we can only
        // inspect the project we were invoked on. The CLI walks the project
        // tree with `GradleProject` and asks for this model on each leaf;
        // projects where the plugin wasn't applied return an empty
        // `modules` map, which the CLI filters out.
        val hasPlugin = project.tasks.findByName("discoverPreviews") != null
        if (!hasPlugin) {
            return ComposePreviewModelData(PluginVersion.value, emptyMap())
        }
        val variant = resolveVariant(project)
        val main = resolveConfiguration(project, "${variant}RuntimeClasspath")
        val test = resolveConfiguration(project, "${variant}UnitTestRuntimeClasspath")
        val gradleVersion = org.gradle.util.GradleVersion.current().version
        val findings: List<ModuleFinding> = CompatRules.evaluate(main, test, gradleVersion)
        val info: ModuleInfo = ModuleInfoData(
            variant = variant,
            mainRuntimeDependencies = main,
            testRuntimeDependencies = test,
            findings = findings,
            agpVersion = resolveAgpVersion(),
            kotlinVersion = resolveKotlinVersion(project),
            renderPreviewsTask = resolveRenderPreviewsTask(project),
        )
        return ComposePreviewModelData(PluginVersion.value, mapOf(project.path to info))
    }

    /**
     * Reads AGP's embedded version constant via reflection so the plugin
     * doesn't take a hard compile-time dependency on AGP internals. Returns
     * `null` on any failure — doctor treats that as "unknown" rather than
     * an error.
     */
    private fun resolveAgpVersion(): String? {
        return try {
            Class.forName("com.android.Version")
                .getField("ANDROID_GRADLE_PLUGIN_VERSION")
                .get(null) as? String
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Reads the Kotlin Gradle Plugin version via its documented helper
     * (`KotlinPluginWrapperKt.getKotlinPluginVersion(Project)`). Reflective
     * call to avoid a hard KGP compile dep on this plugin. Returns `null`
     * if KGP isn't applied or its API moved.
     */
    private fun resolveKotlinVersion(project: Project): String? {
        return try {
            Class.forName("org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt")
                .getMethod("getKotlinPluginVersion", Project::class.java)
                .invoke(null, project) as? String
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Snapshots the `renderPreviews` Test task's forked-JVM configuration
     * so doctor can flag the #142-class footgun (test worker silently
     * forking on a different JDK than the Gradle daemon).
     *
     * `javaLauncher.isPresent` is always `true` at resolution time — Gradle
     * fills in a convention value from the project toolchain or the daemon
     * JVM. We can't observe "user never touched this" via the API, so we
     * report the effective launcher and leave the mismatch check to the
     * doctor side, which compares against the daemon JVM.
     */
    private fun resolveRenderPreviewsTask(project: Project): RenderPreviewsTaskInfo? {
        val task = project.tasks.findByName("renderPreviews") as? Test ?: return null
        val launcher = try {
            task.javaLauncher.orNull
        } catch (_: Throwable) {
            null
        }
        val metadata = launcher?.metadata
        val classpathSize = try { task.classpath.files.size } catch (_: Throwable) { -1 }
        val bootstrapSize = try { task.bootstrapClasspath.files.size } catch (_: Throwable) { -1 }
        val args = try { task.jvmArgs?.toList() ?: emptyList() } catch (_: Throwable) { emptyList() }
        return RenderPreviewsTaskInfoData(
            javaLauncherPinned = launcher != null,
            javaLauncherVersion = metadata?.languageVersion?.asInt()?.toString(),
            javaLauncherVendor = metadata?.vendor,
            javaLauncherPath = metadata?.installationPath?.asFile?.absolutePath,
            classpathSize = classpathSize,
            bootstrapClasspathSize = bootstrapSize,
            jvmArgs = args,
        )
    }

    /**
     * Reads the `composePreview { variant = … }` setting, falling back to
     * `"debug"` if the extension isn't present (plugin not applied on this
     * project). The `Property` itself carries a `"debug"` convention, so
     * `.getOrElse` is belt-and-braces for the unconfigured case.
     */
    private fun resolveVariant(project: Project): String {
        val ext = project.extensions.findByType(PreviewExtension::class.java) ?: return "debug"
        return ext.variant.getOrElse("debug")
    }

    /**
     * Resolves `config` on [project] and returns `group:name → version`.
     * Swallows failures — doctor treats empty as "not checkable" rather than
     * erroring. Uses the resolution-result API instead of
     * `resolvedConfiguration.resolvedArtifacts` because we only need version
     * metadata, not the downloaded artifacts.
     */
    private fun resolveConfiguration(project: Project, name: String): Map<String, String> {
        val config = project.configurations.findByName(name) ?: return emptyMap()
        if (!config.isCanBeResolved) return emptyMap()
        return try {
            val out = linkedMapOf<String, String>()
            for (dep in config.incoming.resolutionResult.allDependencies) {
                val resolved = dep as? org.gradle.api.artifacts.result.ResolvedDependencyResult ?: continue
                val id = resolved.selected.id
                if (id is ModuleComponentIdentifier) {
                    // First occurrence wins — resolutionResult already
                    // deduplicates to Gradle's conflict-resolved version.
                    out.putIfAbsent("${id.group}:${id.module}", id.version)
                }
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }
}

// --- Wire impls ------------------------------------------------------------
//
// Serializable because Gradle marshals these across the daemon/tooling
// boundary. Data classes for cheap equality/toString in tests; nothing else
// depends on that.

private data class ComposePreviewModelData(
    override val pluginVersion: String,
    override val modules: Map<String, ModuleInfo>,
) : ComposePreviewModel, Serializable

private data class ModuleInfoData(
    override val variant: String,
    override val mainRuntimeDependencies: Map<String, String>,
    override val testRuntimeDependencies: Map<String, String>,
    override val findings: List<ModuleFinding>,
    override val agpVersion: String?,
    override val kotlinVersion: String?,
    override val renderPreviewsTask: RenderPreviewsTaskInfo?,
) : ModuleInfo, Serializable

private data class RenderPreviewsTaskInfoData(
    override val javaLauncherPinned: Boolean,
    override val javaLauncherVersion: String?,
    override val javaLauncherVendor: String?,
    override val javaLauncherPath: String?,
    override val classpathSize: Int,
    override val bootstrapClasspathSize: Int,
    override val jvmArgs: List<String>,
) : RenderPreviewsTaskInfo, Serializable
