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
    // nesting `com.android.library` inside KMP) ships a single variant, named
    // after its main source set (`androidMain`) rather than a build type, via
    // `KotlinMultiplatformAndroidComponentsExtension`.
    //
    // Its DEFAULT lane is the Compose Multiplatform Desktop renderer, which is
    // what issue #248 settled on and what every such module has rendered
    // through since: for the canonical CMP-on-Android layout (UI under
    // `:shared/src/commonMain/kotlin/...`) the previews are pure-Compose
    // composables that `ImageComposeScene` captures on the host JVM with no
    // Android infrastructure at all. Done in
    // [ComposePreviewTasks.registerDesktopTasks], gated on `org.jetbrains.compose`
    // actually being applied (which the standard CMP sample plugin block —
    // `composeMultiplatform` — applies).
    //
    // A module whose UI is Android-only can ask for the Robolectric lane
    // instead with `composePreview { kmpAndroidRobolectric = true }`; see that
    // property for why the choice is explicit. [AndroidPreviewSupport.configure]
    // makes it, because only `onVariants` knows whether the consumer also
    // declared the `withHostTest { }` compilation the lane needs — and hands
    // back to `desktopHandler` when the answer is no, so the default path is
    // reached by exactly the same code as before.
    //
    // Two flags, not one. `androidConfigured` means "classic AGP owns task registration, the
    // desktop branch must never run". `kmpAndroidRouting` means "the decision is deferred to
    // `onVariants`, so the desktop branch must not run YET" — it is suppressed exactly until the
    // fallback fires, which is the one caller allowed through the guard.
    var kmpAndroidRouting = false
    var desktopRegistered = false
    val registerDesktop: () -> Unit = {
      if (!desktopRegistered) {
        desktopRegistered = true
        ComposePreviewTasks.registerDesktopTasks(project, extension)
      }
    }
    val desktopHandler: () -> Unit = {
      if (!androidConfigured && !kmpAndroidRouting) registerDesktop()
    }
    // Apply order isn't guaranteed: a downstream `:shared` build may declare
    // `androidKotlinMultiplatformLibrary` before `composeMultiplatform` or
    // vice-versa. Both withPlugin hooks fire when their plugin lands, and
    // the idempotent handlers only run once — whichever fires second is a no-op.
    project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
      // `desktopRegistered` is the third condition and it is load-bearing. Plugin apply order is
      // the consumer's, and a convention plugin can apply `org.jetbrains.compose` BEFORE
      // `com.android.kotlin.multiplatform.library` — in which case the compose hook has already
      // committed to the Desktop lane and registered `composePreviewDiscover` /
      // `composePreviewRender`
      // by the time this one fires. Taking the Robolectric lane on top of that would try to
      // register those same names a second time and fail configuration outright. The module keeps
      // Desktop instead, which is the pre-existing behaviour for every apply order.
      if (!androidConfigured && !kmpAndroidRouting && !desktopRegistered) {
        kmpAndroidRouting = true
        // `registerDesktop`, not `desktopHandler`: this IS the fallback, and it has to get past
        // the `kmpAndroidRouting` guard it just set.
        AndroidPreviewSupport.configure(project, extension, kmpAndroidFallback = registerDesktop)
      } else if (desktopRegistered) {
        // Losing the race is silent otherwise, and silence is the worst outcome here: the
        // consumer's `kmpAndroidRobolectric = true` is simply ignored and their Android-only
        // previews fail to render with nothing pointing at why. The flag cannot be read at THIS
        // moment — `withPlugin` callbacks run while the `plugins { }` block is still applying, so
        // the `composePreview { }` block has not been evaluated yet — hence the deferred check.
        // Warning only; it wires no tasks.
        project.afterEvaluate {
          if (extension.kmpAndroidRobolectric.getOrElse(false)) {
            logger.warn(
              "compose-preview: `composePreview { kmpAndroidRobolectric = true }` is set on " +
                "'$path', but `org.jetbrains.compose` was applied before " +
                "`com.android.kotlin.multiplatform.library`, so the Desktop renderer was already " +
                "wired up by the time the Robolectric lane could claim it. The module is " +
                "rendering on Desktop. Apply `com.android.kotlin.multiplatform.library` first, or " +
                "apply `ee.schimke.composeai.preview` after both, to get the Robolectric lane."
            )
          }
        }
      }
    }

    project.pluginManager.withPlugin("org.jetbrains.compose") {
      if (androidConfigured || kmpAndroidRouting) return@withPlugin
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
      "compose-preview: Isolated Projects is enabled (org.gradle.isolated-projects on Gradle 9.7+, " +
        "org.gradle.unsafe.isolated-projects before that). " +
        "The compose-preview CLI, MCP server, and VS Code extension auto-inject this plugin through " +
        "an init script that configures projects via `allprojects { }`, which Isolated Projects " +
        "rejects — so those tools turn Isolated Projects off for their own invocations. A build you " +
        "drive yourself with the init script has to do the same."
    )
  }
}
