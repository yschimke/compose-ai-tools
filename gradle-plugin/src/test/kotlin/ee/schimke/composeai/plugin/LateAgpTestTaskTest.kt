package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaInstallationMetadata
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * The render tasks inherit AGP's unit-test JVM args, toolchain launcher and test classes even when
 * an upstream `tasks.withType<Test>().all {}` configures them before AGP registers
 * `test<Variant>UnitTest` (follow-up to #5703).
 */
class LateAgpTestTaskTest {

  @get:Rule val tmp = TemporaryFolder()

  private class FakeLauncher(val name: String) : JavaLauncher {
    override fun getMetadata(): JavaInstallationMetadata = error("unused")

    override fun getExecutablePath(): RegularFile = error("unused")

    override fun toString() = name
  }

  // Not a `-D` flag: Gradle files those under systemProperties rather than jvmArgs.
  private val agpJvmArg = "--add-opens=java.base/java.io=ALL-UNNAMED"
  private val agpLauncher = FakeLauncher("agp-toolchain")
  private val fallbackLauncher = FakeLauncher("fallback")

  private fun launcherFor(project: Project): (Test?) -> Provider<JavaLauncher>? = { agp ->
    agp?.javaLauncher ?: project.provider { fallbackLauncher }
  }

  private fun registerRender(project: Project, late: LateAgpTestTask) =
    project.tasks.register("composePreviewRender", Test::class.java) {
      testClassesDirs = late.testClassesDirs
      classpath = late.classpath
      late.inheritJvmSettings(this, launcherFor(project))
    }

  private fun registerAgpTest(project: Project) =
    project.tasks.register("testDebugUnitTest", Test::class.java) {
      jvmArgs(agpJvmArg)
      javaLauncher.set(agpLauncher)
      testClassesDirs = project.files("agp-test-classes")
      classpath = project.files("agp-R.jar")
    }

  @org.junit.Test
  fun `eagerly configured render task inherits AGP settings registered later`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("eager")).build()
    project.tasks.withType(Test::class.java).all {}
    val late = LateAgpTestTask(project, "testDebugUnitTest")
    val render = registerRender(project, late)
    registerAgpTest(project)

    (project as ProjectInternal).evaluate()

    val task = render.get()
    assertThat(task.jvmArgs).contains(agpJvmArg)
    assertThat(task.javaLauncher.get()).isSameInstanceAs(agpLauncher)
    assertThat(task.testClassesDirs.files).containsExactly(project.file("agp-test-classes"))
    assertThat(task.classpath.files).containsExactly(project.file("agp-R.jar"))
  }

  @org.junit.Test
  fun `lazily configured render task inherits AGP settings directly`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("lazy")).build()
    val late = LateAgpTestTask(project, "testDebugUnitTest")
    val render = registerRender(project, late)
    registerAgpTest(project)

    (project as ProjectInternal).evaluate()

    val task = render.get()
    assertThat(task.jvmArgs).containsExactly(agpJvmArg)
    assertThat(task.javaLauncher.get()).isSameInstanceAs(agpLauncher)
  }

  @org.junit.Test
  fun `render task keeps the fallback launcher when AGP never registers a unit-test task`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("none")).build()
    project.tasks.withType(Test::class.java).all {}
    val late = LateAgpTestTask(project, "")
    val render = registerRender(project, late)

    (project as ProjectInternal).evaluate()

    val task = render.get()
    assertThat(task.jvmArgs).isEmpty()
    assertThat(task.javaLauncher.get()).isSameInstanceAs(fallbackLauncher)
    assertThat(task.testClassesDirs.files).isEmpty()
  }
}
