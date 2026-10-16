package ee.schimke.composeai.cli

import ee.schimke.composeai.buildhost.BuildHostProtocol
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Buck2 driver up to the shared artifacts resolution: detection, routing, target selection, the
 * BXL invocation and its output. `buck2` itself is replaced by a fake; the contrib fixture's CI
 * builds a real Compose target.
 */
class BuckBuildTest {
  private fun project(block: (File) -> Unit) {
    val root = Files.createTempDirectory("buck-project").toFile().canonicalFile
    try {
      root.resolve(".git").mkdir()
      root.resolve(".buckconfig").writeText("[cells]\n  root = .\n")
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `the outermost buckconfig is the root, not a cell's, and buckroot wins`() = project { root ->
    val cell = root.resolve("prelude").apply { mkdirs() }
    cell.resolve(".buckconfig").writeText("")
    val pkg = cell.resolve("java").apply { mkdirs() }
    assertEquals(root, detectBuckRoot(pkg))
    cell.resolve(".buckroot").writeText("")
    assertEquals(cell, detectBuckRoot(pkg))
  }

  @Test
  fun `a Buck2 project routes to the Buck driver unless Gradle owns the same root`() =
    project { root ->
      val app = root.resolve("app/src").apply { mkdirs() }
      assertEquals(ProjectBuild(ProjectBuildKind.BUCK, root), detectProjectBuild(app))
      assertEquals(
        ProjectBuild(ProjectBuildKind.BUCK, root),
        previewBuildRoute(listOf("--build-system", "buck"), app),
      )
      root.resolve("gradlew").writeText("")
      assertEquals(ProjectBuild(ProjectBuildKind.GRADLE, root), detectProjectBuild(app))
      root.resolve("settings.gradle.kts").writeText("")
      assertEquals(ProjectBuild(ProjectBuildKind.GRADLE, root), detectProjectBuild(app))
    }

  @Test
  fun `a project without Buck markers is refused for --build-system buck`() {
    val root = Files.createTempDirectory("not-buck").toFile()
    try {
      root.resolve(".git").mkdir()
      assertFailsWith<IllegalStateException> {
        previewBuildRoute(listOf("--build-system", "buck"), root)
      }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `routing names the Buck driver once, replacing auto`() = project { root ->
    val routed =
      routedBuildArgs(
        ProjectBuild(ProjectBuildKind.BUCK, root),
        listOf("--build-system", "auto", "--project", "app", "--filter", "x"),
      )
    assertEquals(
      listOf("--filter", "x", "--project", root.absolutePath, "--build-system", "buck"),
      routed,
    )
    assertEquals("buck", routed.flagValue("--build-system"))
    // A Bazel route keeps --build-system blaze.
    assertEquals(
      listOf("--build-system", "blaze", "--project", root.absolutePath),
      routedBuildArgs(
        ProjectBuild(ProjectBuildKind.BAZEL, root),
        listOf("--build-system", "blaze"),
      ),
    )
  }

  @Test
  fun `target selection takes the nearest package, else everything below`() = project { root ->
    val config = BazelBuildConfig(buildCommand = listOf("buck2"))
    val src = root.resolve("app/src/main").apply { mkdirs() }
    assertEquals("//app/src/main/...", BuckBuild().selectTargets(root, src, config).label)
    assertEquals("//...", BuckBuild().selectTargets(root, root, config).label)
    root.resolve("BUCK").writeText("")
    assertEquals("//:", BuckBuild().selectTargets(root, src, config).label)
    root.resolve("app/BUCK").writeText("")
    assertEquals("//app:", BuckBuild().selectTargets(root, src, config).label)
    val tools = root.resolve("tools/gen").apply { mkdirs() }
    root.resolve("BUCK").delete()
    assertEquals("//tools/gen/...", BuckBuild().selectTargets(root, tools, config).label)
    assertEquals(
      listOf("//lib:a", "//lib:b"),
      BuckBuild()
        .selectTargets(root, src, config.copy(targets = listOf("//lib:a", "//lib:b")))
        .targets,
    )
    assertEquals(
      "//one:x",
      BuckBuild().selectTargets(root, src, config.copy(target = "//one:x")).label,
    )
  }

  @Test
  fun `the BXL script runs with startup and build args in place`() {
    val config =
      BazelBuildConfig(
        buildCommand = listOf("buck2"),
        startupArgs = listOf("--isolation-dir", "preview"),
        buildArgs = listOf("@mode/desktop"),
      )
    assertEquals(
      listOf(
        "buck2",
        "--isolation-dir",
        "preview",
        "bxl",
        "@mode/desktop",
        DEFAULT_BUCK_BXL,
        "--",
        "--kinds",
        DEFAULT_BUCK_TARGET_KINDS,
        "--target",
        "//app:app",
        "--target",
        "//lib/...",
      ),
      BuckBuild().argv(listOf("//app:app", "//lib/..."), config),
    )
    assertEquals(
      "kt_.*",
      BuckBuild().argv(listOf("//a:b"), config.copy(targetKinds = "kt_.*")).let {
        it[it.indexOf("--kinds") + 1]
      },
    )
  }

  @Test
  fun `the BXL output resolves against the project root`() = project { root ->
    val out = root.resolve("buck-out/v2/art/root/cfg").apply { mkdirs() }
    fun jar(name: String) = out.resolve(name).apply { writeText("") }
    jar("app.jar")
    jar("runtime.jar")
    root.resolve("app").mkdirs()
    root.resolve("app/Greeting.kt").writeText("")
    val document =
      """
      {
        "targets": [{
          "schemaVersion": 1,
          "target": "root//app:app",
          "configuration": "prelude//platforms:default#cfg",
          "projectJars": ["buck-out/v2/art/root/cfg/app.jar"],
          "runtimeJars": ["buck-out/v2/art/root/cfg/app.jar", "buck-out/v2/art/root/cfg/runtime.jar"],
          "sourceFiles": ["app/Greeting.kt"],
          "dataFiles": [],
          "runtimeJarOwners": {"buck-out/v2/art/root/cfg/runtime.jar": "root//third_party:runtime"}
        }]
      }
      """
        .trimIndent()
    val calls = mutableListOf<List<String>>()
    val build = BuckBuild { argv, directory, _ ->
      calls += argv
      assertEquals(root, directory)
      "Starting new buck2 daemon...\n$document"
    }
    val config = BazelBuildConfig(buildCommand = listOf("buck2"))
    val resolved = build.collectAll(root, listOf("//app:app"), config, 60, false).single()
    assertEquals("root//app:app", resolved.metadata.target)
    assertEquals(listOf(out.resolve("app.jar")), resolved.projectJars)
    assertEquals(2, resolved.runtimeJars.size)
    assertEquals(listOf(root.resolve("app/Greeting.kt")), resolved.sourceFiles)
    assertEquals(
      mapOf(out.resolve("runtime.jar").absoluteFile to "root//third_party:runtime"),
      resolved.runtimeJarOwners,
    )
    assertEquals(1, calls.size)
  }

  @Test
  fun `a named target that is no JVM library fails, a pattern's empty match does not`() =
    project { root ->
      val build = BuckBuild { _, _, _ -> """{"targets": []}""" }
      val config = BazelBuildConfig(buildCommand = listOf("buck2"))
      val failure =
        assertFailsWith<IllegalStateException> {
          build.collectAll(root, listOf("//app:res"), config, 60, false)
        }
      assertTrue("targetKinds" in failure.message.orEmpty(), failure.message)
      assertEquals(emptyList(), build.collectAll(root, listOf("//app:"), config, 60, true))
      assertFailsWith<IllegalArgumentException> {
        build.collectAll(root, listOf("//app/...", "-//app/legacy/..."), config, 60, true)
      }
    }

  @Test
  fun `the bundled BXL script is installed once and a local copy is kept`() = project { root ->
    installBuckBxl(root)
    val file = root.resolve(".compose-preview/buck/compose_preview.bxl")
    assertTrue("bxl_main" in file.readText())
    installBuckBxl(root)
    file.appendText("# local\n")
    assertFailsWith<IllegalArgumentException> { installBuckBxl(root) }
  }

  @Test
  fun `package patterns are candidates and read as Bazel's all`() {
    assertTrue(isBuckTargetPattern("//app:"))
    assertTrue(isBuckTargetPattern("//app/..."))
    assertEquals(false, isBuckTargetPattern("//app:app"))
    assertEquals("app:all", BazelBuildHost.modulePath("//app:"))
    assertEquals("all", BazelBuildHost.modulePath("//:"))
  }

  @Test
  fun `the build host drives a Buck2 project through the same driver`() = project { root ->
    val calls = mutableListOf<List<String>>()
    val args = listOf(BuildHostProtocol.STDIO_FLAG, "--project", root.path)
    assertNotNull(BuildHostCommand(args).bazel)
    val host =
      BazelBuildHost(root, args + listOf("--build-system", "buck")) { operation ->
        calls += operation
        error("not built")
      }
    host.discoverAndBuild()
    assertEquals("buck", calls.single().flagValue("--build-system"))
  }
}
