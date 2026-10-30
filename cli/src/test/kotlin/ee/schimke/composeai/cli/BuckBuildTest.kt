package ee.schimke.composeai.cli

import ee.schimke.composeai.buildhost.BuildHostProtocol
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    // A Bazel route keeps --build-system bazel.
    assertEquals(
      listOf("--build-system", "bazel", "--project", root.absolutePath),
      routedBuildArgs(
        ProjectBuild(ProjectBuildKind.BAZEL, root),
        listOf("--build-system", "bazel"),
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
  fun `the bundled BXL files are installed, upgraded, and a local edit is kept`() =
    project { root ->
      val bxl = root.resolve(".compose-preview/buck/compose_preview.bxl")
      val link = root.resolve(".compose-preview/buck/link_sh.bxl")
      assertEquals(emptyList(), installBuckBxl(root, strict = true))
      assertTrue("bxl_main" in bxl.readText())
      assertTrue("load(\":link_sh.bxl\", \"LINK_SH\")" in bxl.readText())
      // The bundled link.sh, byte for byte, as the raw string the script writes out.
      val linkSh =
        requireNotNull(BazelCommand::class.java.getResourceAsStream("bazel/android/link.sh"))
          .reader()
          .use { it.readText() }
      assertEquals("${LINK_SH_BXL_HEADER}LINK_SH = r\"\"\"$linkSh\"\"\"\n", link.readText())
      val bundled = bxl.readText()

      // A copy this CLI wrote is its own: a later install overwrites it.
      bxl.writeText("# from an older CLI\n")
      root.resolve(".compose-preview/buck/installed.json").let {
        it.writeText(it.readText().replace(sha256Hex(bundled), sha256Hex(bxl.readText())))
      }
      assertEquals(emptyList(), installBuckBxl(root))
      assertEquals(bundled, bxl.readText())

      // An edit nobody recorded is kept, and fails an explicit init.
      bxl.appendText("# local\n")
      assertEquals(listOf(bxl), installBuckBxl(root))
      assertTrue(bxl.readText().endsWith("# local\n"))
      assertFailsWith<IllegalArgumentException> { installBuckBxl(root, strict = true) }
    }

  @Test
  fun `the JVM-only script 2_65 and 2_66 installed is upgraded without a record`() =
    project { root ->
      val dir = root.resolve(".compose-preview/buck").apply { mkdirs() }
      val shipped =
        requireNotNull(javaClass.getResourceAsStream("/buck/compose_preview-2.65.bxl"))
          .reader()
          .use { it.readText() }
      dir.resolve("compose_preview.bxl").writeText(shipped)
      assertEquals(emptyList(), installBuckBxl(root))
      assertTrue("android_library" in dir.resolve("compose_preview.bxl").readText())
    }

  @Test
  fun `an Android document without a package takes its manifest's`() = project { root ->
    root.resolve("app").mkdirs()
    root.resolve("app/AndroidManifest.xml").writeText("<manifest package=\"com.example.app\"/>")
    val document =
      """{"target":"root//app:app","android":{"packageName":"","manifest":"app/AndroidManifest.xml"}}"""
    fun packageOf(text: String) =
      (((bazelJson.parseToJsonElement(text) as JsonObject)["android"] as JsonObject)["packageName"]
          as JsonPrimitive)
        .content
    assertEquals("com.example.app", packageOf(withManifestPackage(document, root)))
    val named = document.replace("\"packageName\":\"\"", "\"packageName\":\"com.example.res\"")
    assertEquals(named, withManifestPackage(named, root))
    val jvm = """{"target":"root//app:lib"}"""
    assertEquals(jvm, withManifestPackage(jvm, root))
    root.resolve("app/AndroidManifest.xml").writeText("<manifest/>")
    assertFailsWith<IllegalArgumentException> { withManifestPackage(document, root) }
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

  private fun sha256Hex(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
      "%02x".format(it)
    }
}
