package ee.schimke.composeai.plugin.tooling

import ee.schimke.composeai.plugin.AndroidPreviewSupport
import ee.schimke.composeai.plugin.AndroidVariantNaming
import ee.schimke.composeai.plugin.PluginVersion
import ee.schimke.composeai.plugin.PreviewExtension
import java.io.Serializable
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.testing.Test
import org.gradle.tooling.provider.model.ToolingModelBuilder

/**
 * Builds the [ComposePreviewModel] snapshot (resolved runtime and unit-test classpaths per module)
 * the CLI reads over the Tooling API. Registered once per build from
 * [ee.schimke.composeai.plugin.ComposePreviewPlugin.apply].
 */
internal class ComposePreviewModelBuilder : ToolingModelBuilder {

  override fun canBuild(modelName: String): Boolean =
    modelName == ComposePreviewModel::class.java.name

  override fun buildAll(modelName: String, project: Project): Any {
    // Under Isolated Projects only the invoked project is visible; the CLI asks each project, and
    // ones without the plugin return empty `modules`.
    val hasPlugin = project.tasks.findByName("composePreviewDiscover") != null
    if (!hasPlugin) {
      return ComposePreviewModelData(PluginVersion.value, emptyMap())
    }
    val variant = resolveVariant(project)
    // Not `${variant}RuntimeClasspath`: KMP-Android on the Robolectric lane has variant
    // `androidMain` but configurations `androidRuntimeClasspath` /
    // `androidHostTestRuntimeClasspath`, and a wrong name silently yields empty maps.
    val naming = resolveNaming(project, variant)
    val main = resolveConfiguration(project, naming.runtimeClasspath)
    val test =
      naming.unitTestRuntimeClasspath?.let { resolveConfiguration(project, it) } ?: emptyMap()
    val gradleVersion = org.gradle.util.GradleVersion.current().version
    val (toolingDeclared, enforceTooling) = androidPreviewToolingSignals(project, variant)
    val findings: List<ModuleFinding> =
      CompatRules.evaluate(
        main,
        test,
        gradleVersion,
        previewToolingDeclared = toolingDeclared,
        enforcePreviewToolingDependency = enforceTooling,
        moduleMinSdk = resolveModuleMinSdk(project),
        libraryMinSdks =
          naming.unitTestRuntimeClasspath?.let { resolveLibraryMinSdks(project, it) }
            ?: emptyList(),
      )
    val info: ModuleInfo =
      ModuleInfoData(
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
   * AGP's version via reflection (no compile dependency on AGP internals); `null` means unknown.
   */
  private fun resolveAgpVersion(): String? {
    return try {
      Class.forName("com.android.Version").getField("ANDROID_GRADLE_PLUGIN_VERSION").get(null)
        as? String
    } catch (_: Throwable) {
      null
    }
  }

  /** KGP's version via its reflective helper; `null` if KGP isn't applied or the API moved. */
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
   * Snapshots the render Test task's forked-JVM config so doctor can flag a worker forking on a
   * different JDK (#142). `javaLauncher` always has a convention value, so the effective launcher
   * is reported and doctor compares it with the daemon JVM.
   */
  private fun resolveRenderPreviewsTask(project: Project): RenderPreviewsTaskInfo? {
    val task = project.tasks.findByName("composePreviewRender") as? Test ?: return null
    val launcher =
      try {
        task.javaLauncher.orNull
      } catch (_: Throwable) {
        null
      }
    val metadata = launcher?.metadata
    val classpathSize =
      try {
        task.classpath.files.size
      } catch (_: Throwable) {
        -1
      }
    val bootstrapSize =
      try {
        task.bootstrapClasspath.files.size
      } catch (_: Throwable) {
        -1
      }
    val args =
      try {
        task.jvmArgs?.toList() ?: emptyList()
      } catch (_: Throwable) {
        emptyList()
      }
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
   * The resolved variant (snapped by AndroidPreviewSupport, set explicitly, or the `debug`
   * default). If no `${variant}RuntimeClasspath` exists (flavored modules), falls back to the first
   * configuration matching [AndroidPreviewSupport.variantMatchesTarget]'s build-type rule.
   */
  /**
   * The AGP name mapping for the lane the project actually renders through. KMP-Android uses its
   * mapping only on the Robolectric lane; on Desktop, pointing doctor at `androidRuntimeClasspath`
   * would snapshot the wrong backend and trigger Android-only checks in
   * [androidPreviewToolingSignals].
   */
  private fun resolveNaming(project: Project, variant: String): AndroidVariantNaming {
    // Keyed on the task, not the property: the request can be refused (no `withHostTest`, or
    // Desktop already committed). `composePreviewGenerateRobolectricProperties` only exists on the
    // Robolectric lane.
    val robolectricLane =
      project.tasks.findByName("composePreviewGenerateRobolectricProperties") != null
    return if (robolectricLane) AndroidVariantNaming.forProject(project, variant)
    else AndroidVariantNaming.classic(variant)
  }

  private fun resolveVariant(project: Project): String {
    val ext = project.extensions.findByType(PreviewExtension::class.java) ?: return "debug"
    val target = ext.variant.getOrElse("debug")
    if (project.configurations.findByName("${target}RuntimeClasspath") != null) return target
    val match =
      project.configurations.names.firstOrNull { name ->
        name.endsWith("RuntimeClasspath") &&
          AndroidPreviewSupport.variantMatchesTarget(name.removeSuffix("RuntimeClasspath"), target)
      } ?: return target
    return match.removeSuffix("RuntimeClasspath")
  }

  /**
   * Android signals for [CompatRules.checkUndeclaredPreviewTooling], or `(null, null)` for
   * non-Android modules, detected by whether a `${variant}RuntimeClasspath` exists.
   */
  private fun androidPreviewToolingSignals(
    project: Project,
    variant: String,
  ): Pair<Boolean?, Boolean?> {
    val isAndroid =
      project.configurations.findByName(resolveNaming(project, variant).runtimeClasspath) != null
    if (!isAndroid) return null to null
    val ext = project.extensions.findByType(PreviewExtension::class.java) ?: return null to null
    val declared =
      runCatching { AndroidPreviewSupport.hasPreviewDependency(project, variant) }.getOrNull()
        ?: return null to null
    val enforce = ext.enforcePreviewToolingDependency.getOrElse(true)
    return declared to enforce
  }

  /**
   * `android.defaultConfig.minSdk` via reflection; `null` when unavailable, which
   * [CompatRules.checkLibraryMinSdk] treats as not checkable.
   */
  private fun resolveModuleMinSdk(project: Project): Int? = runCatching {
    val android = project.extensions.findByName("android") ?: return kmpAndroidMinSdk(project)
    val defaultConfig = android.javaClass.getMethod("getDefaultConfig").invoke(android)
    defaultConfig?.javaClass?.getMethod("getMinSdk")?.invoke(defaultConfig) as? Int
  }
    .getOrNull()

  /**
   * `minSdk` from a KMP-Android module's `kotlin { android { … } }` target (no `defaultConfig`), as
   * `AndroidPreviewSupport`'s `finalizeDsl` reads it; otherwise doctor would skip library-minSdk
   * checks.
   */
  private fun kmpAndroidMinSdk(project: Project): Int? = runCatching {
    val kotlin = project.extensions.findByName("kotlin") as? ExtensionAware ?: return null
    val android = kotlin.extensions.findByName("android") ?: return null
    android.javaClass.getMethod("getMinSdk").invoke(android) as? Int
  }
    .getOrNull()

  /** Each AAR manifest's `minSdkVersion` on [configName]. Lenient; empty means nothing to check. */
  private fun resolveLibraryMinSdks(project: Project, configName: String): List<LibraryMinSdk> {
    val config = project.configurations.findByName(configName) ?: return emptyList()
    if (!config.isCanBeResolved) return emptyList()
    return runCatching {
      val artifactType = org.gradle.api.attributes.Attribute.of("artifactType", String::class.java)
      val artifacts =
        config.incoming
          .artifactView {
            lenient(true)
            attributes.attribute(artifactType, "android-manifest")
          }
          .artifacts
          .artifacts
      LibraryMinSdkCollector.collect(artifacts)
    }
      .getOrElse { emptyList() }
  }

  /**
   * `group:name → version` for [name], via the resolution result (versions only, no downloads).
   * Failures yield empty.
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
          // resolutionResult is already conflict-resolved.
          out.putIfAbsent("${id.group}:${id.module}", id.version)
        }
      }
      out
    } catch (_: Throwable) {
      emptyMap()
    }
  }
}

// --- Wire impls --- Serializable for the daemon/tooling boundary.

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
