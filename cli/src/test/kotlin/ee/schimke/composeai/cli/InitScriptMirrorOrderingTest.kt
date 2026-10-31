package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * Issue #6361: the CLI passes its init script with `-I`, which runs before `~/.gradle/init.d`. A
 * mirror / proxy init script there that rewrites repositories as they are added (`whenObjectAdded`)
 * must still see — and route — the repositories the auto-inject script adds to each buildscript,
 * and a host another init script already declared must not be declared again.
 *
 * The "mirror" here is a second init script passed after ours, which is where Gradle runs an init.d
 * script: it rewrites any repository on a public host to a local repository holding stub plugins.
 * Only a routed classpath resolves, so the plugin being applied proves the routing.
 */
class InitScriptMirrorOrderingTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun tempDir(prefix: String = "compose-preview-mirror-"): File =
    Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

  private val publicHosts = listOf("dl.google.com", "plugins.gradle.org", "repo.maven.apache.org")

  /** A root project with an `:app` that applies the (stub) Android plugin from [repo]. */
  private fun appProject(repo: File): File {
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement { repositories { maven { url = uri("${repo.toURI()}") } } }
        rootProject.name = "mirror-repro"
        include(":app")
        """
          .trimIndent()
      )
    File(root, "build.gradle.kts").writeText("// root build script — intentionally empty\n")
    val app = File(root, "app").apply { mkdirs() }
    File(app, "build.gradle.kts")
      .writeText("""plugins { id("com.android.application") version "1.0" }""")
    return root
  }

  @Test
  fun `a mirror init script that rewrites added repositories routes the injected ones`() {
    val repo = tempDir("compose-preview-mirror-repo-")
    publishStubPlugins(repo, tempDir("compose-preview-mirror-stubs-"))
    val project = appProject(repo)
    val mirror =
      File(tempDir(), "mirror.init.gradle.kts").apply {
        writeText(
          """
          val publicHosts = setOf(${publicHosts.joinToString { "\"$it\"" }})
          allprojects {
              buildscript.repositories.whenObjectAdded {
                  val maven = this as? MavenArtifactRepository ?: return@whenObjectAdded
                  if (maven.url.host in publicHosts) maven.url = uri("${repo.toURI()}")
              }
              afterEvaluate {
                  val urls = buildscript.repositories
                      .mapNotNull { (it as? MavenArtifactRepository)?.url }
                  println("BUILDSCRIPT-REPOS ${'$'}path ${'$'}urls")
              }
          }
          """
            .trimIndent()
        )
      }
    val initScript = materializeInitScript(tempDir(), "1.0")

    val result =
      GradleRunner.create()
        .withProjectDir(project)
        .withArguments(
          ":app:help",
          "--offline",
          "--init-script",
          initScript.absolutePath,
          "--init-script",
          mirror.absolutePath,
          "--stacktrace",
        )
        .forwardOutput()
        .build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":app:help")?.outcome, result.output)
    assertTrue(
      result.output.contains("COMPOSE-PREVIEW-APPLIED to :app"),
      "expected the plugin to resolve through the mirror and apply; output:\n${result.output}",
    )
    val repos = result.output.lineSequence().filter { it.startsWith("BUILDSCRIPT-REPOS") }.toList()
    assertTrue(repos.isNotEmpty(), result.output)
    for (line in repos) {
      for (host in publicHosts) {
        assertFalse(line.contains(host), "an injected repository bypassed the mirror: $line")
      }
    }
  }

  @Test
  fun `a host another init script declared is not declared again`() {
    val project = tempDir()
    File(project, "settings.gradle.kts").writeText("rootProject.name = \"dedup\"\n")
    File(project, "build.gradle.kts").writeText("// intentionally empty\n")
    val other =
      File(tempDir(), "other.init.gradle.kts").apply {
        writeText(
          """
          allprojects {
              buildscript.repositories.mavenCentral()
              beforeEvaluate {
                  val urls = buildscript.repositories
                      .mapNotNull { (it as? MavenArtifactRepository)?.url?.toString() }
                  println("BUILDSCRIPT-REPOS " + urls.joinToString(" "))
              }
          }
          """
            .trimIndent()
        )
      }
    val initScript = materializeInitScript(tempDir(), "1.0")

    // Offline, and the coordinate is not cached, so the build itself may fail resolving the
    // classpath; the repository list is printed before that, which is all this test reads.
    val result =
      GradleRunner.create()
        .withProjectDir(project)
        .withArguments(
          "help",
          "--offline",
          "--init-script",
          initScript.absolutePath,
          "--init-script",
          other.absolutePath,
        )
        .run()

    val line =
      assertNotNull(
        result.output.lineSequence().firstOrNull { it.startsWith("BUILDSCRIPT-REPOS") },
        result.output,
      )
    val urls = line.removePrefix("BUILDSCRIPT-REPOS").trim().split(" ")
    assertEquals(1, urls.count { it.contains("repo.maven.apache.org") }, line)
    // The other defaults are still added, after the repository the other script declared.
    assertEquals(1, urls.count { it.contains("dl.google.com") }, line)
    assertEquals(1, urls.count { it.contains("plugins.gradle.org") }, line)
    assertTrue(urls.first().contains("repo.maven.apache.org"), line)
  }
}
