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
  fun `init script bakes the plugin version into the initscript classpath coordinate`() {
    val script = renderInitScript("9.9.9-test")
    assertTrue(
      script.contains(
        "classpath(\"ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:9.9.9-test\")"
      ),
      "expected the pinned coordinate baked into the initscript classpath dependency",
    )
  }

  @Test
  fun `init script loads the plugin via initscript classpath instead of buildscript injection`() {
    // Regression for the Confetti follow-up (#1482): Gradle 9.3+ rejects mutating
    // `buildscript.repositories` in *any* build whose `pluginManagement.repositories` declares
    // `exclusiveContent { ... }`. The previous fix (#1470) only guarded composite-included
    // builds; the same shape at the root build still tripped the validation. Switching to an
    // initscript-level classpath load means we never touch `buildscript.repositories` on any
    // consumer project at any level, sidestepping the validation entirely.
    val script = renderInitScript("0.11.7")
    assertTrue(
      script.contains("initscript {"),
      "expected an initscript { ... } block that loads the plugin into the init classloader",
    )
    assertTrue(
      script.contains(
        "classpath(\"ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:0.11.7\")"
      ),
      "expected the initscript dependencies block to declare the plugin classpath",
    )
    // The previous shape was `allprojects { ... buildscript { repositories { ... } } }`. The
    // explanatory header comment legitimately mentions buildscript by name, so check for the
    // injection-call wire shape rather than the word — the literal `add("classpath", ...)`
    // form the old code used, and the surrounding indentation that marks it as inside
    // allprojects.
    assertFalse(
      script.contains("add(\n                    \"classpath\","),
      "init script must not add buildscript classpath dependencies in allprojects { ... } — " +
        "that's the shape Gradle 9.3+ rejects when exclusiveContent is present in " +
        "pluginManagement.repositories at any level",
    )
    assertFalse(
      script.contains("        buildscript {\n            repositories {"),
      "init script must not declare per-project buildscript { repositories { ... } } injection",
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
    assertTrue(
      onDisk.contains(
        "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:2.0.0"
      )
    )
    assertFalse(
      onDisk.contains(
        "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:1.0.0"
      )
    )
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
  fun `init script gates the apply hooks on per-project pre-applied detection`() {
    // Successor to the #305 regression coverage: the per-project pre-applied scan used to gate
    // the buildscript classpath injection; that injection is gone (replaced by initscript
    // classpath, #1482) so the scan now gates the apply hooks instead. Skipping the hooks for
    // a project that already declares the plugin via `plugins { id(...) version "..." }`
    // avoids class-identity confusion: the user's plugins-DSL resolution loads the plugin
    // into a project-scoped classloader, while pluginManager.apply from the hook would
    // resolve via the init-script classloader.
    val script = renderInitScript("0.10.15")
    assertTrue(
      script.contains("var composeAiPreviewPreAppliedDirs: Set<java.io.File> = emptySet()"),
      "expected the per-project pre-applied directory set declaration",
    )
    assertTrue(
      script.contains(
        "composeAiPreviewPreAppliedDirs = scanForComposeAiPreviewDeclaration(rootDir, projectDirs)"
      ),
      "expected the set to be populated during settingsEvaluated",
    )
    assertTrue(
      script.contains("if (projectDir in composeAiPreviewPreAppliedDirs) return@allprojects"),
      "expected the apply hooks to short-circuit per-project on the directory set",
    )
    assertTrue(
      script.contains("gradle/libs.versions.toml"),
      "expected the catalog accessor scanner to read libs.versions.toml so alias(...) declarations are detected",
    )
  }

  @Test
  fun `init script's scanForComposeAiPreviewDeclaration returns the matching project dirs`() {
    // Pins the per-project return shape so a future refactor doesn't silently drop back to a
    // global Boolean (which is the #305 regression mode).
    val script = renderInitScript("1.0.0")
    assertTrue(
      script.contains(
        "fun scanForComposeAiPreviewDeclaration(\n    rootDir: java.io.File,\n    projectDirs: List<java.io.File>,\n): Set<java.io.File> {"
      ),
      "expected scanForComposeAiPreviewDeclaration to return Set<File> of pre-applied project dirs",
    )
    assertFalse(
      script.contains("): Boolean {\n    val catalogAccessors = composeAiPreviewCatalogAccessors"),
      "expected scanForComposeAiPreviewDeclaration to no longer return a single global Boolean",
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
  fun `init script seeds settings-level mavenLocal when COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL is set`() {
    // wear-os-samples WearTilesKotlin (and any consumer that sets
    // `RepositoriesMode.FAIL_ON_PROJECT_REPOS` in settings.gradle.kts) refuses per-project repos —
    // a per-project `mavenLocal()` is not enough for renderer-android AAR resolution. The
    // settings-level seeding inside `gradle.settingsEvaluated { ... }` is the path that survives
    // restrictive `RepositoriesMode`s and lets integration CI resolve our SNAPSHOT runtime deps
    // from `~/.m2`. `pluginManagement.repositories.mavenLocal()` covers the plugins-DSL resolution
    // path for the catalog-alias / literal-`id(...) version "..."` case where we skip our own
    // apply hooks. The initscript block also seeds `mavenLocal()` so the plugin itself can
    // resolve from ~/.m2 when functional tests publish to local-maven.
    val script = renderInitScript("0.1.0-SNAPSHOT")
    assertTrue(
      script.contains("if (useMavenLocal) mavenLocal()"),
      "expected the initscript repositories block to gate mavenLocal on the env flag",
    )
    assertTrue(
      script.contains("if (useMavenLocal) {"),
      "expected the settingsEvaluated mavenLocal seeding to be gated on the env flag too",
    )
    assertTrue(
      script.contains("pluginManagement.repositories.mavenLocal()"),
      "expected pluginManagement-level mavenLocal seeding for plugins-DSL resolution",
    )
    assertTrue(
      script.contains("dependencyResolutionManagement.repositories.mavenLocal()"),
      "expected dependencyResolutionManagement-level mavenLocal seeding for runtime AAR resolution",
    )
  }

  @Test
  fun `init script restores default plugin repositories when seeding mavenLocal into an empty pluginManagement`() {
    // `gradle.settingsEvaluated` fires for every included build, including composite `build-logic`
    // modules (e.g. androidchka's). Gradle only auto-applies its `gradlePluginPortal()` default
    // when `pluginManagement.repositories` is empty after settings evaluation — so blindly
    // appending `mavenLocal()` from the init script turns a build that relied on the implicit
    // default into a build with mavenLocal as the *only* plugin repo, breaking resolution of
    // `kotlin-dsl` (whose plugin marker lives on the Gradle Plugin Portal). The integration
    // matrix's `androidchka (compose:material3 samples)` job exposed this. Restore the defaults
    // explicitly when the consumer didn't declare any of its own.
    val script = renderInitScript("0.1.0-SNAPSHOT")
    assertTrue(
      script.contains("pluginManagement.repositories.isEmpty()"),
      "expected the script to detect an empty pluginManagement repo list before seeding defaults",
    )
    assertTrue(
      script.contains("pluginManagement.repositories.gradlePluginPortal()"),
      "expected the script to restore gradlePluginPortal when seeding into empty repos",
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
  fun `init script skips auto-inject for KMP-Android modules`() {
    // Regression coverage for the meshcore-mobile report: applying compose-preview to a module
    // that uses `com.android.kotlin.multiplatform.library` trips an AGP-KMP variant-ambiguity
    // error on `androidRuntimeClasspath` once the renderer's artifact view kicks in, which
    // breaks `compose-preview show` for any project where the plugin landed via auto-inject.
    // Auto-inject must scan for the KMP-Android plugin id, mark those modules, and skip the
    // apply hooks for them. (The buildscript-classpath injection that used to be gated
    // alongside this is gone — replaced by initscript classpath in #1482.)
    val script = renderInitScript("0.11.4")
    assertTrue(
      script.contains("var composeAiPreviewKmpAndroidDirs: Set<java.io.File> = emptySet()"),
      "expected the KMP-Android skip set declaration",
    )
    assertTrue(
      script.contains(
        "composeAiPreviewKmpAndroidDirs = scanForKmpAndroidDeclaration(rootDir, projectDirs)"
      ),
      "expected settingsEvaluated to populate the KMP-Android skip set",
    )
    assertTrue(
      script.contains("if (projectDir in composeAiPreviewKmpAndroidDirs) return@allprojects"),
      "expected the withPlugin apply hooks to short-circuit for KMP-Android modules",
    )
  }

  @Test
  fun `init script's scanForKmpAndroidDeclaration matches the literal id form`() {
    // Pins the regex contract so a future refactor doesn't drop literal-id detection. The
    // canonical KMP-Android module shape is `id("com.android.kotlin.multiplatform.library")`
    // in a `plugins { ... }` block; the regex anchors on the `id (` / `id "` prefix and the
    // exact plugin coordinate.
    val script = renderInitScript("1.0.0")
    assertTrue(
      script.contains(
        "fun scanForKmpAndroidDeclaration(\n    rootDir: java.io.File,\n    projectDirs: List<java.io.File>,\n): Set<java.io.File> {"
      ),
      "expected scanForKmpAndroidDeclaration to return Set<File> of KMP-Android project dirs",
    )
    assertTrue(
      script.contains(
        "\"\\\\bid\\\\s*[(\\\\s]\\\\s*[\\\"']com\\\\.android\\\\.kotlin\\\\.multiplatform\\\\.library[\\\"']\""
      ),
      "expected the literal-id regex for com.android.kotlin.multiplatform.library",
    )
  }

  @Test
  fun `init script skips composite-included builds in settingsEvaluated and allprojects`() {
    // Originally the Confetti regression (#1470): we touched `buildscript.repositories` in
    // every project of every build in a composite, and Gradle 9.3+ rejects that once
    // exclusiveContent is on settings.pluginManagement.repositories. The fix moved plugin
    // resolution to initscript classpath (#1482), so this guard is no longer load-bearing for
    // that validation — but the early return stays so we don't waste time scanning and
    // applying the plugin in plugin-only included builds (`build-logic`,
    // `gradle-conventions`) that never host @Preview composables. An included build's
    // `gradle.parent` is non-null; the root build's is null.
    val script = renderInitScript("0.11.6")
    assertTrue(
      script.contains("val composeAiPreviewIsIncludedBuild = gradle.parent != null"),
      "expected the included-build flag derived from gradle.parent",
    )
    assertTrue(
      script.contains("if (composeAiPreviewIsIncludedBuild) return@settingsEvaluated"),
      "expected settingsEvaluated to short-circuit for composite-included builds",
    )
    assertTrue(
      script.contains("if (composeAiPreviewIsIncludedBuild) return@allprojects"),
      "expected allprojects to short-circuit for composite-included builds",
    )
  }

  @Test
  fun `init script's KMP-Android scan recognises catalog aliases`() {
    // Mirrors the compose-preview catalog-accessor path so a project that declares the
    // KMP-Android plugin in `gradle/libs.versions.toml` (e.g.
    // `androidKotlinMultiplatformLibrary = { id = "com.android.kotlin.multiplatform.library",
    // version = "..." }`) and references it via
    // `alias(libs.plugins.androidKotlinMultiplatformLibrary)`
    // is still detected — that's the shape the meshcore-mobile reproducer uses.
    val script = renderInitScript("1.0.0")
    assertTrue(
      script.contains(
        "fun composeAiPreviewKmpAndroidCatalogAccessors(rootDir: java.io.File): List<Regex>"
      ),
      "expected a catalog-accessor scanner pinned to the KMP-Android plugin id",
    )
    assertTrue(
      script.contains("com\\\\.android\\\\.kotlin\\\\.multiplatform\\\\.library"),
      "expected the catalog-accessor regex to anchor on the KMP-Android plugin id",
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
