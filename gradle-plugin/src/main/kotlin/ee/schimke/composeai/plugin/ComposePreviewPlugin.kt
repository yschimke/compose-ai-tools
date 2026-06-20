package ee.schimke.composeai.plugin

import ee.schimke.composeai.plugin.tooling.ComposePreviewModelBuilder
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.configuration.BuildFeatures
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.util.GradleVersion

abstract class ComposePreviewPlugin
@Inject
constructor(
  // Gradle injects build-scoped services into plugin constructors. This is
  // the documented way to get at `ToolingModelBuilderRegistry`; accessing
  // `project.services` directly is internal API and not stable.
  private val toolingRegistry: ToolingModelBuilderRegistry,
  // `BuildFeatures` (Gradle 8.5+) is the supported way to ask whether Isolated
  // Projects / the configuration cache are active for this build, without
  // reading internal start-parameter state. Used to warn when IP is on (see
  // `warnIfIsolatedProjectsEnabled`).
  private val buildFeatures: BuildFeatures,
) : Plugin<Project> {
  override fun apply(project: Project) {
    GradleVersionCheck.problem(GradleVersion.current())?.let { throw GradleException(it) }
    warnIfIsolatedProjectsEnabled(project)

    // Create-or-find: the config-only plugin (`ee.schimke.composeai.preview.config`) may already
    // have registered the `composePreview` extension and its convention chain. Reuse it so the two
    // plugins coexist and user-written `composePreview { … }` config flows into one set of
    // `Property` objects. Convention wiring (`-PcomposePreview.variant=…` etc.) lives in
    // [ComposePreviewDsl.createOrFindExtension].
    val extension = ComposePreviewDsl.createOrFindExtension(project)

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
    // authoritatively. Independent of `composePreviewDiscover` so it runs even
    // in modules that never compile previews (e.g. library modules whose
    // only preview usage is compile-time annotations).
    //
    // Register-if-absent: the config-only plugin may already have registered this
    // marker (its primary job is to make a module discoverable). See [ComposePreviewDsl].
    ComposePreviewDsl.registerAppliedTaskIfAbsent(project, PluginVersion.value)

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

  /**
   * Surfaces a warning when the build runs with Isolated Projects active.
   *
   * IP is fundamentally incompatible with the compose-preview CLI / MCP server / VS Code extension:
   * those auto-inject this plugin through an init script that configures every project via
   * `allprojects { buildscript { … } }`, which IP rejects ("Project ':' cannot access
   * 'Project.buildscript' functionality on subprojects via 'allprojects'"). Manual application —
   * this code path — stays IP-clean for discovery, but the broader tooling cannot run, so we make
   * the misconfiguration loud the moment the plugin is applied under IP rather than letting a
   * downstream `compose-preview` invocation fail with a raw Gradle IP error.
   *
   * `buildFeatures.isolatedProjects.active` is a CC-safe provider; reading it at apply time does
   * not itself trip IP. The warning is intentionally per-applying-project — it only ever fires when
   * someone has (re-)enabled IP, which is a misconfiguration we want to be hard to miss.
   */
  private fun warnIfIsolatedProjectsEnabled(project: Project) {
    if (!buildFeatures.isolatedProjects.active.get()) return
    project.logger.warn(
      "compose-preview: Isolated Projects is enabled (org.gradle.unsafe.isolated-projects=true). " +
        "The compose-preview CLI, MCP server, and VS Code extension auto-inject this plugin through " +
        "an init script that configures projects via `allprojects { }`, which Isolated Projects " +
        "rejects — those runs will fail. Disable Isolated Projects when using compose-preview tooling."
    )
  }
}
