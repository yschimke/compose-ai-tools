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
  fun `pluginAppliedInBuildScripts finds the literal id form in a root build script`() {
    val root = tempDir()
    File(root, "build.gradle.kts")
      .writeText(
        """
        plugins {
            id("com.android.library") version "9.2.0"
            id("ee.schimke.composeai.preview") version "0.10.0"
        }
        """
          .trimIndent()
      )
    assertTrue(pluginAppliedInBuildScripts(root))
  }

  @Test
  fun `pluginAppliedInBuildScripts finds the Groovy DSL form in a nested module`() {
    val root = tempDir()
    val module = File(root, "app").apply { mkdirs() }
    File(module, "build.gradle")
      .writeText(
        """
        plugins {
            id 'com.android.application'
            id 'ee.schimke.composeai.preview' version '0.10.0'
        }
        """
          .trimIndent()
      )
    assertTrue(pluginAppliedInBuildScripts(root))
  }

  @Test
  fun `pluginAppliedInBuildScripts ignores plugin id appearing inside a line comment`() {
    // Synthetic test fixtures document *what they removed* in a comment so the next reader
    // understands the intent:
    //
    //     // No id("ee.schimke.composeai.preview") on purpose — auto-inject handles it.
    //     plugins { ... }
    //
    // Without comment stripping the detector matches the literal id inside the comment and
    // misclassifies the project as having the plugin pre-applied — silencing the warning the
    // tests then assert on.
    val root = tempDir()
    File(root, "build.gradle.kts")
      .writeText(
        """
        // No id("ee.schimke.composeai.preview") on purpose — auto-inject handles it.
        plugins {
            id("com.android.library") version "9.2.0"
        }
        """
          .trimIndent()
      )
    assertFalse(pluginAppliedInBuildScripts(root))
  }

  @Test
  fun `pluginAppliedInBuildScripts ignores plugin id inside a block comment`() {
    val root = tempDir()
    File(root, "build.gradle.kts")
      .writeText(
        """
        /**
         * Apply id("ee.schimke.composeai.preview") manually if you don't want auto-inject.
         */
        plugins {
            id("com.android.library") version "9.2.0"
        }
        """
          .trimIndent()
      )
    assertFalse(pluginAppliedInBuildScripts(root))
  }

  @Test
  fun `pluginAppliedInBuildScripts matches Groovy 'apply plugin' legacy form`() {
    // Codex P2 review on PR #1171: legacy Groovy `apply plugin: '...'` is still common in
    // long-lived consumer projects. The detector must not misclassify these as "not pre-applied".
    val root = tempDir()
    val module = File(root, "app").apply { mkdirs() }
    File(module, "build.gradle")
      .writeText(
        """
        apply plugin: 'com.android.library'
        apply plugin: 'ee.schimke.composeai.preview'
        """
          .trimIndent()
      )
    assertTrue(pluginAppliedInBuildScripts(root))
  }

  @Test
  fun `pluginAppliedInBuildScripts matches Kotlin DSL apply with named plugin argument`() {
    val root = tempDir()
    File(root, "build.gradle.kts")
      .writeText(
        """
        apply(plugin = "com.android.library")
        apply(plugin = "ee.schimke.composeai.preview")
        """
          .trimIndent()
      )
    assertTrue(pluginAppliedInBuildScripts(root))
  }

  @Test
  fun `pluginAppliedInBuildScripts skips apply false lines (root-build subprojects pattern)`() {
    val root = tempDir()
    File(root, "build.gradle.kts")
      .writeText(
        """
        plugins {
            id("ee.schimke.composeai.preview") version "0.10.0" apply false
        }
        """
          .trimIndent()
      )
    assertFalse(pluginAppliedInBuildScripts(root))
  }

  @Test
  fun `pluginAppliedInBuildScripts returns false when only host plugins are applied`() {
    val root = tempDir()
    File(root, "build.gradle.kts")
      .writeText(
        """
        plugins {
            id("com.android.library") version "9.2.0"
            id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
        }
        """
          .trimIndent()
      )
    assertFalse(pluginAppliedInBuildScripts(root))
  }

  @Test
  fun `pluginAppliedInBuildScripts finds catalog alias via libs versions toml inline table`() {
    // The reported regression (homeassistant-remotecompose): the user declares the plugin in
    // gradle/libs.versions.toml as an inline-table entry and references it with
    // `alias(libs.plugins.compose.preview)` in app/build.gradle.kts. The literal-id scan misses
    // this and the CLI then mistakenly auto-injects, which conflicts with the plugins DSL.
    val root = tempDir()
    val gradleDir = File(root, "gradle").apply { mkdirs() }
    File(gradleDir, "libs.versions.toml")
      .writeText(
        """
        [plugins]
        compose-preview = { id = "ee.schimke.composeai.preview", version = "0.10.8" }
        """
          .trimIndent()
      )
    val module = File(root, "app").apply { mkdirs() }
    File(module, "build.gradle.kts")
      .writeText(
        """
        plugins {
            alias(libs.plugins.android.application)
            alias(libs.plugins.compose.preview)
        }
        """
          .trimIndent()
      )
    assertTrue(pluginAppliedInBuildScripts(root))
  }

  @Test
  fun `pluginAppliedInBuildScripts finds catalog alias via libs versions toml short form`() {
    val root = tempDir()
    val gradleDir = File(root, "gradle").apply { mkdirs() }
    File(gradleDir, "libs.versions.toml")
      .writeText(
        """
        [plugins]
        compose_preview = "ee.schimke.composeai.preview:0.10.8"
        """
          .trimIndent()
      )
    val module = File(root, "app").apply { mkdirs() }
    File(module, "build.gradle.kts")
      .writeText(
        """
        plugins {
            alias(libs.plugins.compose.preview)
        }
        """
          .trimIndent()
      )
    assertTrue(pluginAppliedInBuildScripts(root))
  }

  @Test
  fun `pluginAppliedInBuildScripts does not match catalog accessors for unrelated plugins`() {
    val root = tempDir()
    val gradleDir = File(root, "gradle").apply { mkdirs() }
    File(gradleDir, "libs.versions.toml")
      .writeText(
        """
        [plugins]
        kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version = "2.3.20" }
        """
          .trimIndent()
      )
    val module = File(root, "app").apply { mkdirs() }
    File(module, "build.gradle.kts")
      .writeText(
        """
        plugins {
            alias(libs.plugins.kotlin.jvm)
        }
        """
          .trimIndent()
      )
    assertFalse(pluginAppliedInBuildScripts(root))
  }

  @Test
  fun `catalogPluginAccessorRegexes returns empty list when catalog is missing`() {
    val root = tempDir()
    assertEquals(emptyList<Regex>(), catalogPluginAccessorRegexes(root))
  }

  @Test
  fun `init script gates the buildscript classpath injection on pre-applied detection`() {
    val script = renderInitScript("0.10.15")
    assertTrue(
      script.contains("var composeAiPreviewPreApplied = false"),
      "expected the pre-applied flag declaration",
    )
    assertTrue(
      script.contains(
        "composeAiPreviewPreApplied = scanForComposeAiPreviewDeclaration(rootDir, projectDirs)"
      ),
      "expected the flag to be set during settingsEvaluated",
    )
    assertTrue(
      script.contains("if (!composeAiPreviewPreApplied) {"),
      "expected the buildscript block to be guarded by the flag",
    )
    assertTrue(
      script.contains("gradle/libs.versions.toml"),
      "expected the catalog accessor scanner to read libs.versions.toml so alias(...) declarations are detected",
    )
  }

  @Test
  fun `init script scopes the scan to settings rootProject descriptors`() {
    // Codex P1 review on PR #1183: scanning every subdirectory under rootDir is too broad — an
    // unrelated nested build (e.g., a tooling build or sample app checked into the workspace but
    // not part of this settings file) can flip the pre-applied flag and break auto-inject for the
    // real build. The descriptor-based walk only inspects modules included by this build.
    val script = renderInitScript("1.0.0")
    assertTrue(
      script.contains("fun collect(descriptor: org.gradle.api.initialization.ProjectDescriptor)"),
      "expected a recursive collect() over ProjectDescriptor children",
    )
    assertTrue(
      script.contains("collect(rootProject)"),
      "expected the scan to seed from settings.rootProject",
    )
    assertFalse(
      script.contains("\"node_modules\""),
      "expected the filesystem-walk skipDirs set to be gone (legacy artefact)",
    )
  }

  @Test
  fun `init script strips comments before matching plugin declarations`() {
    // Codex P2 review on PR #1183: a documentation line like
    //   // id("ee.schimke.composeai.preview") version "..."
    // must not flip the pre-applied flag and disable classpath injection.
    val script = renderInitScript("1.0.0")
    assertTrue(
      script.contains("fun composeAiPreviewStripComments(source: String): String"),
      "expected a comment-stripper helper inside the rendered script",
    )
    assertTrue(
      script.contains("composeAiPreviewStripComments(raw)"),
      "expected the scanner to run text through the comment stripper",
    )
  }

  @Test
  fun `pluginAppliedInBuildScripts skips build directories`() {
    val root = tempDir()
    val staleBuild = File(root, "build/some-cache").apply { mkdirs() }
    File(staleBuild, "build.gradle.kts").writeText("""id("ee.schimke.composeai.preview")""")
    assertFalse(pluginAppliedInBuildScripts(root), "build/ scratch directories must not be scanned")
  }

  @Test
  fun `warnIfPluginNotPreApplied emits when plugin is not in any build script`() {
    val root = tempDir()
    File(root, "build.gradle.kts")
      .writeText("plugins { id(\"com.android.library\") version \"9.2.0\" }")
    val warnings = mutableListOf<String>()
    warnIfPluginNotPreApplied(
      args = emptyList(),
      projectRoot = root,
      autoInjectActive = true,
      pluginVersion = "0.10.0",
      env = { null },
      stderr = { warnings += it },
      resetFlag = true,
    )
    assertEquals(1, warnings.size)
    assertTrue(
      warnings.single().contains("plugin not applied"),
      "unexpected warning text: ${warnings.single()}",
    )
    assertTrue(
      warnings.single().contains("0.10.0"),
      "warning should include the plugin version to copy/paste; got: ${warnings.single()}",
    )
  }

  @Test
  fun `warnIfPluginNotPreApplied stays silent when plugin is applied literally`() {
    val root = tempDir()
    File(root, "build.gradle.kts")
      .writeText(
        """
        plugins {
            id("com.android.library")
            id("ee.schimke.composeai.preview") version "0.10.0"
        }
        """
          .trimIndent()
      )
    val warnings = mutableListOf<String>()
    warnIfPluginNotPreApplied(
      args = emptyList(),
      projectRoot = root,
      autoInjectActive = true,
      env = { null },
      stderr = { warnings += it },
      resetFlag = true,
    )
    assertTrue(warnings.isEmpty(), "expected no warning when plugin is applied; got $warnings")
  }

  @Test
  fun `warnIfPluginNotPreApplied honours --no-plugin-warning`() {
    val root = tempDir()
    File(root, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")
    val warnings = mutableListOf<String>()
    warnIfPluginNotPreApplied(
      args = listOf("--no-plugin-warning"),
      projectRoot = root,
      autoInjectActive = true,
      env = { null },
      stderr = { warnings += it },
      resetFlag = true,
    )
    assertTrue(warnings.isEmpty())
  }

  @Test
  fun `warnIfPluginNotPreApplied honours COMPOSE_PREVIEW_NO_PLUGIN_WARNING`() {
    val root = tempDir()
    File(root, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")
    val warnings = mutableListOf<String>()
    warnIfPluginNotPreApplied(
      args = emptyList(),
      projectRoot = root,
      autoInjectActive = true,
      env = { name -> if (name == "COMPOSE_PREVIEW_NO_PLUGIN_WARNING") "1" else null },
      stderr = { warnings += it },
      resetFlag = true,
    )
    assertTrue(warnings.isEmpty())
  }

  @Test
  fun `warnIfPluginNotPreApplied stays silent when auto-inject is disabled`() {
    val root = tempDir()
    File(root, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")
    val warnings = mutableListOf<String>()
    warnIfPluginNotPreApplied(
      args = listOf("--no-auto-inject"),
      projectRoot = root,
      autoInjectActive = false,
      env = { null },
      stderr = { warnings += it },
      resetFlag = true,
    )
    assertTrue(
      warnings.isEmpty(),
      "no auto-inject ⇒ user has opted out of the bundled flow; don't nag them",
    )
  }

  @Test
  fun `warnIfPluginNotPreApplied stays silent when auto-inject is on but materialisation failed`() {
    // Codex P2 review on PR #1171: when `autoInjectInitScriptArgs` returns an empty list (e.g.
    // unwritable cache), the CLI is not actually running via auto-inject. The warning text claims
    // "running via auto-inject", so it must not fire — the inject-failure stderr line covers it.
    val root = tempDir()
    File(root, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")
    val warnings = mutableListOf<String>()
    warnIfPluginNotPreApplied(
      args = emptyList(),
      projectRoot = root,
      autoInjectActive = false,
      env = { null },
      stderr = { warnings += it },
      resetFlag = true,
    )
    assertTrue(
      warnings.isEmpty(),
      "no init script materialised ⇒ no auto-inject claim; got $warnings",
    )
  }

  @Test
  fun `warnIfPluginNotPreApplied stays silent in the compose-ai-tools dev loop`() {
    val root = tempDir()
    File(root, "settings.gradle.kts").writeText("includeBuild(\"gradle-plugin\")")
    File(root, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")
    val warnings = mutableListOf<String>()
    warnIfPluginNotPreApplied(
      args = emptyList(),
      projectRoot = root,
      autoInjectActive = true,
      env = { null },
      stderr = { warnings += it },
      resetFlag = true,
    )
    assertTrue(warnings.isEmpty())
  }

  @Test
  fun `warnIfPluginNotPreApplied prints at most once per process`() {
    val root = tempDir()
    File(root, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")
    val warnings = mutableListOf<String>()
    warnIfPluginNotPreApplied(
      args = emptyList(),
      projectRoot = root,
      autoInjectActive = true,
      env = { null },
      stderr = { warnings += it },
      resetFlag = true,
    )
    warnIfPluginNotPreApplied(
      args = emptyList(),
      projectRoot = root,
      autoInjectActive = true,
      env = { null },
      stderr = { warnings += it },
    )
    assertEquals(1, warnings.size, "second call should not re-emit; got $warnings")
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
