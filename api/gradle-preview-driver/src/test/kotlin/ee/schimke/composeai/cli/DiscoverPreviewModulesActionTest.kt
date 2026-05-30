package ee.schimke.composeai.cli

import ee.schimke.composeai.plugin.tooling.ComposePreviewModel
import ee.schimke.composeai.plugin.tooling.ModuleInfo
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.GradleBuild

/**
 * Verifies [DiscoverPreviewModulesAction] discovers plugin modules through the lightweight
 * `GradleBuild` + per-project `ComposePreviewModel` path, isolates per-module failures, and — the
 * crux of issue #1620 — never requests the heavyweight `GradleProject` model (which would realize
 * the whole task graph and trigger unrelated modules' configuration-time side effects).
 */
class DiscoverPreviewModulesActionTest {

  /** A `BasicGradleProject` answering only the two getters the action reads. */
  private fun project(path: String, dir: File): BasicGradleProject =
    Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BasicGradleProject::class.java)) {
      _,
      method,
      _ ->
      when (method.name) {
        "getPath" -> path
        "getProjectDirectory" -> dir
        "toString" -> "BasicGradleProject($path)"
        "hashCode" -> path.hashCode()
        else -> error("unexpected BasicGradleProject.${method.name}")
      }
    } as BasicGradleProject

  /** A `GradleBuild` whose `projects` iterates [projects]. */
  private fun build(projects: List<BasicGradleProject>): GradleBuild =
    Proxy.newProxyInstance(javaClass.classLoader, arrayOf(GradleBuild::class.java)) { _, method, _
      ->
      when (method.name) {
        "getProjects" ->
          Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(org.gradle.tooling.model.DomainObjectSet::class.java),
          ) { _, m, _ ->
            when (m.name) {
              "iterator" -> projects.iterator()
              "size" -> projects.size
              "isEmpty" -> projects.isEmpty()
              else -> error("unexpected DomainObjectSet.${m.name}")
            }
          }
        else -> error("unexpected GradleBuild.${method.name}")
      }
    } as GradleBuild

  /** Build a [ComposePreviewModel] whose `modules` is keyed by [appliedPath] (plugin-applied). */
  private fun model(appliedPath: String?): ComposePreviewModel =
    object : ComposePreviewModel {
      override val pluginVersion = "test"
      override val modules: Map<String, ModuleInfo> =
        if (appliedPath == null) emptyMap() else mapOf(appliedPath to dummyModuleInfo())
    }

  private fun dummyModuleInfo(): ModuleInfo =
    object : ModuleInfo {
      override val variant = "debug"
      override val mainRuntimeDependencies = emptyMap<String, String>()
      override val testRuntimeDependencies = emptyMap<String, String>()
      override val findings = emptyList<ee.schimke.composeai.plugin.tooling.ModuleFinding>()
      override val agpVersion: String? = null
      override val kotlinVersion: String? = null
      override val renderPreviewsTask: ee.schimke.composeai.plugin.tooling.RenderPreviewsTaskInfo? =
        null
    }

  /**
   * Reflective [BuildController] that serves [GradleBuild] from [buildModel] and per-project
   * [ComposePreviewModel] via [findModelFor]. Fails loudly if anyone asks for [GradleProject] — the
   * regression guard for #1620.
   */
  private fun controller(
    buildModel: GradleBuild,
    findModelFor: (BasicGradleProject) -> ComposePreviewModel?,
  ): BuildController {
    val handler = InvocationHandler { _: Any, method: Method, args: Array<Any?>? ->
      when (method.name) {
        "getModel" -> {
          val type = args?.lastOrNull()
          assertFalse(
            type == GradleProject::class.java,
            "must not query GradleProject — that realizes the whole task graph (#1620)",
          )
          if (type == GradleBuild::class.java) buildModel else error("unexpected getModel($type)")
        }
        "findModel" -> {
          val type = args?.getOrNull(1)
          assertFalse(
            type == GradleProject::class.java,
            "must not query GradleProject — that realizes the whole task graph (#1620)",
          )
          if (type == ComposePreviewModel::class.java) {
            findModelFor(args!![0] as BasicGradleProject)
          } else error("unexpected findModel($type)")
        }
        "toString" -> "FakeBuildController"
        "hashCode" -> 0
        else -> error("unexpected BuildController.${method.name}")
      }
    }
    return Proxy.newProxyInstance(
      javaClass.classLoader,
      arrayOf(BuildController::class.java),
      handler,
    ) as BuildController
  }

  @Test
  fun `discovers only plugin-applied modules and resolves their project dirs`() {
    val root = project(":", File("/tmp/root"))
    val ui = project(":ui", File("/tmp/root/ui"))
    val cli = project(":cli", File("/tmp/root/cli"))
    val controller =
      controller(build(listOf(root, ui, cli))) { p ->
        // Only :ui applies the plugin; :cli is the unrelated native-image module.
        when (p.path) {
          ":ui" -> model(":ui")
          else -> model(null)
        }
      }

    val modules = DiscoverPreviewModulesAction().execute(controller)

    assertEquals(listOf("ui"), modules.map { it.gradlePath })
    assertEquals(File("/tmp/root/ui"), modules.single().projectDir)
  }

  @Test
  fun `a module whose findModel throws is skipped, not fatal`() {
    val ui = project(":ui", File("/tmp/root/ui"))
    val poison = project(":native-cli", File("/tmp/root/native-cli"))
    val controller =
      controller(build(listOf(ui, poison))) { p ->
        when (p.path) {
          ":ui" -> model(":ui")
          // Simulates an unrelated module that blows up during configuration (e.g. failed
          // toolchain provisioning). It must not sink discovery of :ui.
          else -> throw RuntimeException("toolchain download blocked")
        }
      }

    val modules = DiscoverPreviewModulesAction().execute(controller)

    assertEquals(listOf("ui"), modules.map { it.gradlePath })
  }

  @Test
  fun `the root project is never emitted even if it reports applied`() {
    val root = project(":", File("/tmp/root"))
    val controller = controller(build(listOf(root))) { model(":") }

    val modules = DiscoverPreviewModulesAction().execute(controller)

    assertTrue(modules.isEmpty())
  }
}
