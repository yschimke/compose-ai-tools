package ee.schimke.composeai.plugin

import ee.schimke.composeai.plugin.tooling.ComposePreviewModelBuilder
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.configuration.BuildFeatures
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.util.GradleVersion

/** The configuration a module names its shared preview-source modules in. */
const val PREVIEW_SOURCE_CONFIGURATION = "composePreviewSource"

abstract class ComposePreviewPlugin
@Inject
constructor(
  // Injected: `project.services` is internal API.
  private val toolingRegistry: ToolingModelBuilderRegistry,
  // The supported way to ask whether Isolated Projects is active.
  private val buildFeatures: BuildFeatures,
) : Plugin<Project> {
  override fun apply(project: Project) {
    GradleVersionCheck.problem(GradleVersion.current())?.let { throw GradleException(it) }
    warnIfIsolatedProjectsEnabled(project)
    // Opt-in only (`composePreview.themePinning=true`); a no-op for every first-party build.
    ThemePinning.apply(project)

    // The config-only plugin (`ee.schimke.composeai.preview.config`) may already have created the
    // extension; reuse it so both plugins share one set of properties.
    val extension = ComposePreviewDsl.createOrFindExtension(project)

    // `composePreviewSource`: modules whose `@Preview`s are discovered and rendered on this
    // module's lane, so a catalog can declare its previews once in a plain library and render them
    // on several lanes. Discovery otherwise only walks the module's own classes.
    //
    //     dependencies {
    //       implementation(project(":catalog-shared"))        // the classes, at runtime
    //       composePreviewSource(project(":catalog-shared"))  // + discover its @Previews
    //     }
    //
    // Not tied to `implementation`, so a library's own sticker-sheet previews never leak in. A
    // plain
    // bucket: [ComposePreviewTasks.registerDiscoverTask] derives the resolvable view with the
    // runtime classpath's attributes, without which a KMP producer silently resolves to nothing.
    project.configurations.create(PREVIEW_SOURCE_CONFIGURATION) {
      isCanBeConsumed = false
      isCanBeResolved = false
      description =
        "Modules whose @Preview functions are discovered and rendered by this module's " +
          "compose-preview lane. See composePreview.previewSourceRoots for their sources."
    }

    // Build-scoped registry; every applying project registers (Gradle iterates `canBuild`), since
    // cross-project state would trip Isolated Projects. Consumed by the CLI / VS Code extension.
    toolingRegistry.register(ComposePreviewModelBuilder())

    // `composePreviewApplied` writes `build/compose-previews/applied.json` so the VS Code extension
    // (which can only run tasks, not query models) can find applying modules. The config-only
    // plugin may already have registered it.
    ComposePreviewDsl.registerAppliedTaskIfAbsent(project, PluginVersion.value)

    // AGP types stay inside [AndroidPreviewSupport] so this class loads on non-Android projects.
    var androidConfigured = false
    val androidHandler: () -> Unit = {
      if (!androidConfigured) {
        androidConfigured = true
        AndroidPreviewSupport.configure(project, extension)
      }
    }
    project.pluginManager.withPlugin("com.android.application") { androidHandler() }
    project.pluginManager.withPlugin("com.android.library") { androidHandler() }

    // `com.android.kotlin.multiplatform.library` defaults to the CMP Desktop lane (issue #248),
    // registered in [ComposePreviewTasks.registerDesktopTasks] once `org.jetbrains.compose` is
    // applied. `kmpAndroidRobolectric = true` opts into Robolectric;
    // [AndroidPreviewSupport.configure]
    // decides in `onVariants` and calls back into `registerDesktop` when it can't.
    //
    // `androidConfigured`: classic AGP owns registration. `kmpAndroidRouting`: the KMP-Android lane
    // is deciding, so desktop must wait for its fallback. `desktopDeferred`: desktop would have run
    // but KMP-Android may still be applied.
    var kmpAndroidRouting = false
    var desktopRegistered = false
    var desktopDeferred = false
    val registerDesktop: () -> Unit = {
      if (!desktopRegistered) {
        desktopRegistered = true
        ComposePreviewTasks.registerDesktopTasks(project, extension)
      }
    }
    val desktopHandler: () -> Unit = {
      if (!androidConfigured && !kmpAndroidRouting) registerDesktop()
    }

    // A convention plugin may apply `org.jetbrains.compose` before the KMP-Android plugin;
    // committing to Desktop then would make the Robolectric lane's registration fail. So when KMP
    // is
    // applied but KMP-Android isn't yet, the commit waits for `afterEvaluate`
    // (`:samples:cmp-android-robolectric` pins this order). Every other shape registers
    // immediately.
    fun kmpAndroidStillPossible(): Boolean =
      project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") &&
        !project.pluginManager.hasPlugin("com.android.kotlin.multiplatform.library")

    project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
      // `desktopRegistered` covers compose applied before KMP itself, which the deferral can't
      // predict: it degrades to Desktop plus the warning below.
      if (!androidConfigured && !kmpAndroidRouting && !desktopRegistered) {
        kmpAndroidRouting = true
        // `registerDesktop`, not `desktopHandler`: the fallback must pass the guard just set.
        AndroidPreviewSupport.configure(project, extension, kmpAndroidFallback = registerDesktop)
      } else if (desktopRegistered) {
        // The flag can't be read yet (`composePreview { }` hasn't been evaluated), hence
        // afterEvaluate. Warning only.
        project.afterEvaluate {
          if (extension.kmpAndroidRobolectric.getOrElse(false)) {
            logger.warn(
              "compose-preview: `composePreview { kmpAndroidRobolectric = true }` is set on " +
                "'$path', but `org.jetbrains.compose` was applied before both " +
                "`org.jetbrains.kotlin.multiplatform` and " +
                "`com.android.kotlin.multiplatform.library`, so the Desktop renderer was already " +
                "wired up by the time the Robolectric lane could claim it. The module is " +
                "rendering on Desktop. Apply the Kotlin Multiplatform plugin before " +
                "`org.jetbrains.compose` to get the Robolectric lane."
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
      if (kmpAndroidStillPossible()) {
        desktopDeferred = true
        return@withPlugin
      }
      desktopHandler()
    }

    // The deferred commit; `desktopHandler` re-checks the lane in case KMP-Android claimed it.
    project.afterEvaluate { if (desktopDeferred) desktopHandler() }
  }

  /**
   * Warns when Isolated Projects is active: the CLI / MCP / VS Code init script configures projects
   * via `allprojects { }`, which IP rejects, so the tooling turns IP off for its own builds.
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
