package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AutoInjectTest {
  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun tempDir(prefix: String = "compose-preview-autoinject-"): File =
    Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

  @Test
  fun `init script bakes the plugin version into the source`() {
    val script = renderInitScript("9.9.9-test")
    assertTrue(
      script.contains("val pluginVersion = \"9.9.9-test\""),
      "expected the plugin version to be interpolated into the script",
    )
    assertTrue(
      script.contains(
        "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:\$pluginVersion"
      ),
      "expected the buildscript classpath coordinate to reference the pinned coordinate",
    )
  }

  @Test
  fun `init script applies on each injectable host plugin`() {
    val script = renderInitScript("1.0.0")
    for (id in listOf("com.android.application", "com.android.library", "org.jetbrains.compose")) {
      assertTrue(
        script.contains("pluginManager.withPlugin(\"$id\") { applyComposeAiPreview() }"),
        "expected withPlugin hook for $id",
      )
    }
  }

  @Test
  fun `init script guards against double-apply with hasPlugin check`() {
    val script = renderInitScript("1.0.0")
    assertTrue(
      script.contains("if (plugins.hasPlugin(\"ee.schimke.composeai.preview\")) return"),
      "expected an idempotent hasPlugin guard so manually-applied projects stay no-op",
    )
  }

  @Test
  fun `init script avoids afterEvaluate which would miss AGP DSL lock`() {
    val script = renderInitScript("1.0.0")
    assertFalse(
      script.contains("afterEvaluate("),
      "init script must not call afterEvaluate(...) — AGP's finalizeDsl runs first",
    )
    assertFalse(
      script.contains("afterEvaluate {"),
      "init script must not use the afterEvaluate { ... } block form",
    )
  }

  @Test
  fun `materializeInitScript writes script to storage dir`() {
    val dir = tempDir()
    val target = materializeInitScript(dir, "1.2.3")
    assertEquals(File(dir, INIT_SCRIPT_FILENAME).absolutePath, target.absolutePath)
    assertEquals(renderInitScript("1.2.3"), target.readText())
  }

  @Test
  fun `materializeInitScript creates the storage dir if missing`() {
    val dir = File(tempDir(), "nested/storage/compose-preview")
    assertFalse(dir.exists())
    val target = materializeInitScript(dir, "1.0.0")
    assertTrue(dir.isDirectory)
    assertTrue(target.isFile)
  }

  @Test
  fun `materializeInitScript is idempotent — same version leaves the file untouched`() {
    val dir = tempDir()
    val first = materializeInitScript(dir, "1.0.0")
    // Bump mtime forward so a rewrite would be observable.
    val futureMs = first.lastModified() + 5_000L
    assertTrue(first.setLastModified(futureMs))
    val mtimeBefore = first.lastModified()
    val second = materializeInitScript(dir, "1.0.0")
    assertEquals(first.absolutePath, second.absolutePath)
    assertEquals(
      mtimeBefore,
      second.lastModified(),
      "expected no rewrite when contents are unchanged",
    )
  }

  @Test
  fun `materializeInitScript rewrites when the plugin version changes`() {
    val dir = tempDir()
    materializeInitScript(dir, "1.0.0")
    val target = materializeInitScript(dir, "2.0.0")
    val onDisk = target.readText()
    assertTrue(onDisk.contains("val pluginVersion = \"2.0.0\""))
    assertFalse(onDisk.contains("val pluginVersion = \"1.0.0\""))
  }

  @Test
  fun `initScriptDigest is stable across calls`() {
    assertEquals(initScriptDigest("1.0.0"), initScriptDigest("1.0.0"))
  }

  @Test
  fun `initScriptDigest differs across plugin versions`() {
    assertNotEquals(initScriptDigest("1.0.0"), initScriptDigest("1.0.1"))
  }

  @Test
  fun `initScriptDigest is 16 hex chars`() {
    val digest = initScriptDigest("1.0.0")
    assertEquals(16, digest.length)
    assertTrue(digest.matches(Regex("^[0-9a-f]{16}$")))
  }

  @Test
  fun `autoInjectInitScriptArgs returns --init-script flag pair by default`() {
    val storage = tempDir()
    val args =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "1.0.0",
        storageDir = storage,
        env = { null },
      )
    assertEquals(2, args.size)
    assertEquals("--init-script", args[0])
    assertEquals(File(storage, INIT_SCRIPT_FILENAME).absolutePath, args[1])
    assertTrue(File(args[1]).isFile)
  }

  @Test
  fun `autoInjectInitScriptArgs honours --no-auto-inject`() {
    val storage = tempDir()
    val args =
      autoInjectInitScriptArgs(
        args = listOf("--no-auto-inject"),
        pluginVersion = "1.0.0",
        storageDir = storage,
        env = { null },
      )
    assertTrue(args.isEmpty())
    assertFalse(
      File(storage, INIT_SCRIPT_FILENAME).exists(),
      "should not materialise script when auto-inject is disabled",
    )
  }

  @Test
  fun `autoInjectInitScriptArgs honours COMPOSE_PREVIEW_NO_AUTO_INJECT env var`() {
    val storage = tempDir()
    val args =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "1.0.0",
        storageDir = storage,
        env = { name -> if (name == "COMPOSE_PREVIEW_NO_AUTO_INJECT") "1" else null },
      )
    assertTrue(args.isEmpty())
  }

  @Test
  fun `autoInjectInitScriptArgs skips when project root includeBuilds gradle-plugin (Kotlin DSL, double quotes)`() {
    val storage = tempDir()
    val projectRoot = tempDir()
    File(projectRoot, "settings.gradle.kts")
      .writeText(
        """
        rootProject.name = "demo"
        includeBuild("gradle-plugin")
        include(":app")
        """
          .trimIndent()
      )
    val out =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "1.0.0",
        storageDir = storage,
        env = { null },
        projectRoot = projectRoot,
      )
    assertTrue(
      out.isEmpty(),
      "expected no --init-script when the plugin is supplied via includeBuild; got $out",
    )
    assertFalse(File(storage, INIT_SCRIPT_FILENAME).exists())
  }

  @Test
  fun `autoInjectInitScriptArgs skips when project root includeBuilds gradle-plugin (Groovy DSL, single quotes)`() {
    val storage = tempDir()
    val projectRoot = tempDir()
    File(projectRoot, "settings.gradle").writeText("includeBuild 'gradle-plugin'\n")
    // settings.gradle (Groovy) uses no parens for single-arg method calls — fall through to the
    // negative case; auto-inject stays on. This documents the heuristic's known scope: parens are
    // mandatory in our regex. Bare-call Groovy users hit the env-var or flag opt-outs instead.
    val out =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "1.0.0",
        storageDir = storage,
        env = { null },
        projectRoot = projectRoot,
      )
    assertEquals(listOf("--init-script", File(storage, INIT_SCRIPT_FILENAME).absolutePath), out)

    // Same root with parens — should skip.
    File(projectRoot, "settings.gradle").writeText("includeBuild('gradle-plugin')\n")
    val out2 =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "1.0.0",
        storageDir = tempDir(),
        env = { null },
        projectRoot = projectRoot,
      )
    assertTrue(out2.isEmpty())
  }

  @Test
  fun `autoInjectInitScriptArgs stays on when project root includeBuilds something else`() {
    val storage = tempDir()
    val projectRoot = tempDir()
    File(projectRoot, "settings.gradle.kts")
      .writeText(
        """
        rootProject.name = "demo"
        pluginManagement { includeBuild("build-logic") }
        include(":app")
        """
          .trimIndent()
      )
    val out =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "1.0.0",
        storageDir = storage,
        env = { null },
        projectRoot = projectRoot,
      )
    assertEquals(listOf("--init-script", File(storage, INIT_SCRIPT_FILENAME).absolutePath), out)
  }

  @Test
  fun `hasIncludedPluginBuild matches the compose-ai-tools repo's own settings file shape`() {
    val projectRoot = tempDir()
    File(projectRoot, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
          includeBuild("build-logic")
        }
        rootProject.name = "compose-ai-tools"
        includeBuild("gradle-plugin")
        include(":cli")
        include(":samples:android")
        """
          .trimIndent()
      )
    assertTrue(hasIncludedPluginBuild(projectRoot))
  }

  @Test
  fun `hasIncludedPluginBuild returns false when no settings file mentions gradle-plugin`() {
    val projectRoot = tempDir()
    File(projectRoot, "settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
    assertFalse(hasIncludedPluginBuild(projectRoot))
  }

  @Test
  fun `autoInjectInitScriptArgs swallows materialise failures and downgrades to no-inject`() {
    // Point storage at a path that can't be created: a regular file masquerading as a parent dir.
    val parent = tempDir()
    val blocker = File(parent, "blocker").apply { writeText("not a directory") }
    val unwritable = File(blocker, "child")
    val warnings = mutableListOf<String>()
    val args =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "1.0.0",
        storageDir = unwritable,
        env = { null },
        stderr = { warnings += it },
      )
    assertTrue(args.isEmpty())
    assertTrue(
      warnings.any { it.contains("auto-inject disabled") },
      "expected a stderr note about the disabled auto-inject path; got $warnings",
    )
  }
}
