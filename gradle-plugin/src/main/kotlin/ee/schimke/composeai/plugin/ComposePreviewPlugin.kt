package ee.schimke.composeai.plugin

import ee.schimke.composeai.plugin.tooling.ComposePreviewAppliedTask
import ee.schimke.composeai.plugin.tooling.ComposePreviewModelBuilder
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.util.GradleVersion

abstract class ComposePreviewPlugin
@Inject
constructor(
  // Gradle injects build-scoped services into plugin constructors. This is
  // the documented way to get at `ToolingModelBuilderRegistry`; accessing
  // `project.services` directly is internal API and not stable.
  private val toolingRegistry: ToolingModelBuilderRegistry
) : Plugin<Project> {
  override fun apply(project: Project) {
    GradleVersionCheck.problem(GradleVersion.current())?.let { throw GradleException(it) }

    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    // ToolingModelBuilderRegistry is a build-scoped service — registering
    // from any applying project makes the model available on every
    // Tooling-API connection for the build. `register` accepts multiple
    // builders for the same model (Gradle iterates on `canBuild`), so
    // registering once per applying subproject is safe even if
    // `buildAll` only ever gets called on the first one that matches.
    // Cross-project state (`rootProject.extras`) would trip Isolated
    // Projects, so we just let every applying project register.
    //
    // Consumed by the CLI / VS Code extension via
    // `connection.model(ComposePreviewModel::class.java)`.
    toolingRegistry.register(ComposePreviewModelBuilder())

    // Sidecar-JSON applied-marker task. The VS Code extension goes through
    // `vscjava.vscode-gradle`, which only exposes `runTask` — it can't reach
    // the Tooling-API model above. Running `gradle composePreviewApplied`
    // (no module prefix) fans out to every applying project and writes a
    // tiny JSON at `<module>/build/compose-previews/applied.json`; the
    // extension scans for those markers to discover applied modules
    // authoritatively. Independent of `discoverPreviews` so it runs even
    // in modules that never compile previews (e.g. library modules whose
    // only preview usage is compile-time annotations).
    project.tasks.register("composePreviewApplied", ComposePreviewAppliedTask::class.java) {
      pluginVersion.set(PluginVersion.value)
      modulePath.set(project.path)
      moduleName.set(project.name)
      outputFile.set(project.layout.buildDirectory.file("compose-previews/applied.json"))
      group = "compose preview"
      description =
        "Write a marker JSON advertising that this module applies the Compose Preview plugin."
    }

    // `pluginManager.withPlugin` replaces the old `project.afterEvaluate { ... }`
    // block. `afterEvaluate` is discouraged under Gradle's Isolated Projects mode
    // (and in general for plugin wiring); the plugin-manager hook fires as soon
    // as the target plugin is applied, which is the right moment to wire up.
    //
    // The AGP-facing code (finalizeDsl / onVariants / cross-project dependency
    // declaration) is isolated in [AndroidPreviewSupport]. Gradle decorates this
    // plugin class at apply time and resolves all class references it sees in
    // the plugin's bytecode — so keeping AGP types *out* of ComposePreviewPlugin
    // is what lets the plugin load cleanly on non-Android projects (Compose
    // Multiplatform consumers, functional tests, etc.). AGP classes only get
    // loaded when `AndroidPreviewSupport.configure` actually runs.
    var androidConfigured = false
    val androidHandler: () -> Unit = {
      if (!androidConfigured) {
        androidConfigured = true
        AndroidPreviewSupport.configure(project, extension)
      }
    }
    project.pluginManager.withPlugin("com.android.application") { androidHandler() }
    project.pluginManager.withPlugin("com.android.library") { androidHandler() }

    // `com.android.kotlin.multiplatform.library` (the AGP 9 replacement for
    // nesting `com.android.library` inside KMP) ships a single `android`
    // variant via `KotlinMultiplatformAndroidComponentsExtension` — there are
    // no classic `debug`/`release` build types and no AGP unit-test pipeline
    // unless the consumer opts in via `withHostTest { … }`. Wiring the
    // Robolectric renderer through that path would mean replicating most of
    // [AndroidPreviewSupport] for a different DSL surface (issue #248).
    //
    // The simpler answer for the canonical CMP-on-Android layout (UI under
    // `:shared/src/androidMain/kotlin/...`) is to render through the Compose
    // Multiplatform Desktop renderer instead: `androidMain` previews are
    // pure-Compose composables that `ImageComposeScene` can capture once we
    // point discovery at the KMP-Android compile output and runtime
    // classpath. Done in [ComposePreviewTasks.registerDesktopTasks], gated on
    // `org.jetbrains.compose` actually being applied (which the standard CMP
    // sample plugin block — `composeMultiplatform` — applies).
    //
    // We deliberately do NOT set `androidConfigured = true` here: that flag
    // exists to suppress the desktop branch on classic Android modules where
    // the AGP path owns task registration. KMP-Android wants the desktop
    // branch.
    var desktopRegistered = false
    val desktopHandler: () -> Unit = {
      if (!androidConfigured && !desktopRegistered) {
        desktopRegistered = true
        ComposePreviewTasks.registerDesktopTasks(project, extension)
      }
    }
    // Apply order isn't guaranteed: a downstream `:shared` build may declare
    // `androidKotlinMultiplatformLibrary` before `composeMultiplatform` or
    // vice-versa. Both withPlugin hooks fire when their plugin lands, and
    // the idempotent `desktopHandler` only runs once — whichever fires
    // second is a no-op.
    project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
      desktopHandler()
    }

    project.pluginManager.withPlugin("org.jetbrains.compose") {
      if (androidConfigured) return@withPlugin
      if (
        project.plugins.hasPlugin("com.android.application") ||
          project.plugins.hasPlugin("com.android.library")
      ) {
        return@withPlugin
      }
      desktopHandler()
    }
  }
}
