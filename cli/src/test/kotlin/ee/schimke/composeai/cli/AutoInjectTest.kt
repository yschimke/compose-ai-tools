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
  fun `init script warns when Isolated Projects is enabled`() {
    val script = renderInitScript("1.0.0")
    // The allprojects injection can't run under IP, so the script must detect IP at
    // settingsEvaluated and warn.
    assertTrue(
      script.contains("import org.gradle.kotlin.dsl.support.serviceOf"),
      "expected the serviceOf import used to probe BuildFeatures",
    )
    assertTrue(
      script.contains("serviceOf<BuildFeatures>().isolatedProjects.active"),
      "expected the script to probe whether Isolated Projects is active",
    )
    assertTrue(
      script.contains("Isolated Projects is enabled"),
      "expected a warning message when IP is on",
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
  fun `materializeInitScript is idempotent - same version leaves the file untouched`() {
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
    val scriptPath = File(storage, INIT_SCRIPT_FILENAME).absolutePath
    assertEquals(listOf("--init-script", scriptPath) + ISOLATED_PROJECTS_OFF_ARGS, args)
    assertTrue(File(scriptPath).isFile)
  }

  @Test
  fun `autoInjectInitScriptArgs turns Isolated Projects off under both property names`() {
    val storage = tempDir()
    val args =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "1.0.0",
        storageDir = storage,
        env = { null },
      )
    // The injected `allprojects { buildscript { ... } }` can't run under IP, so every auto-injected
    // invocation opts out, under both the pre- and post-9.7 property names.
    assertTrue(
      args.contains("-Dorg.gradle.isolated-projects=false"),
      "expected the Gradle 9.7+ property name to be disabled",
    )
    assertTrue(
      args.contains("-Dorg.gradle.unsafe.isolated-projects=false"),
      "expected the pre-9.7 property name to be disabled",
    )
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
    // Groovy without parens isn't matched (parens are required by the regex), so auto-inject stays
    // on; those users use the env-var or flag opt-outs.
    val out =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "1.0.0",
        storageDir = storage,
        env = { null },
        projectRoot = projectRoot,
      )
    assertEquals(
      listOf("--init-script", File(storage, INIT_SCRIPT_FILENAME).absolutePath) +
        ISOLATED_PROJECTS_OFF_ARGS,
      out,
    )

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
    assertEquals(
      listOf("--init-script", File(storage, INIT_SCRIPT_FILENAME).absolutePath) +
        ISOLATED_PROJECTS_OFF_ARGS,
      out,
    )
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
  fun `init script gates the buildscript classpath injection on per-project pre-applied detection`() {
    // The pre-applied gate is per project, not one global boolean: mixed projects where only some
    // modules declare the plugin must still inject into the others.
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
      script.contains(
        "val composeAiPreviewIsPreApplied = projectDir in composeAiPreviewPreAppliedDirs"
      ),
      "expected the buildscript block to be guarded per-project on the directory set",
    )
    assertTrue(
      script.contains("gradle/libs.versions.toml"),
      "expected the catalog accessor scanner to read libs.versions.toml so alias(...) declarations are detected",
    )
  }

  @Test
  fun `init script's scanForComposeAiPreviewDeclaration returns the matching project dirs`() {
    // Pins the per-project return shape against a regression to a global Boolean.
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
    // Only modules included by this build are inspected; an unrelated nested build in the workspace
    // must not flip the pre-applied flag.
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
  fun `init script seeds settings-level mavenLocal automatically for SNAPSHOT versions`() {
    // Projects with `RepositoriesMode.FAIL_ON_PROJECT_REPOS` refuse per-project repos, so
    // `mavenLocal()` is seeded at settings level (`gradle.settingsEvaluated`) and in
    // `pluginManagement.repositories`. SNAPSHOT versions seed it unconditionally (an unpublished
    // SNAPSHOT can only be in `~/.m2`); releases require `COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1`
    // (next test).
    val script = renderInitScript("0.1.0-SNAPSHOT")
    assertTrue(
      script.contains("if (useMavenLocal) {"),
      "expected the mavenLocal seeding to live under a runtime `useMavenLocal` gate",
    )
    assertTrue(
      script.contains("pluginVersion.endsWith(\"-SNAPSHOT\")"),
      "expected SNAPSHOT versions to auto-enable useMavenLocal",
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
  fun `init script keeps COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL escape hatch for non-SNAPSHOT runs`() {
    // Functional tests publish the release version to `~/.m2`, so the env-var path must keep
    // working.
    val script = renderInitScript("0.11.10")
    assertTrue(
      script.contains("System.getenv(\"COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL\") == \"1\""),
      "expected the env-var escape hatch to survive for release builds",
    )
  }

  @Test
  fun `init script restores default plugin repositories when seeding mavenLocal into an empty pluginManagement`() {
    // `settingsEvaluated` also fires for included builds. Gradle only adds its
    // `gradlePluginPortal()` default when `pluginManagement.repositories` is empty, so appending
    // `mavenLocal()` would make it the only plugin repo; restore the defaults when the consumer
    // declared none.
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
    // A commented-out `id("ee.schimke.composeai.preview") version "..."` must not count as
    // pre-applied.
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
  fun `init script applies auto-inject to KMP-Android modules via withPlugin`() {
    // KMP-Android modules are auto-injected like any Compose module; the plugin routes them through
    // the Desktop pipeline and fails soft without a desktop target.
    val script = renderInitScript("0.15.1")
    assertTrue(
      script.contains(
        "pluginManager.withPlugin(\"com.android.kotlin.multiplatform.library\") { applyComposeAiPreview() }"
      ),
      "expected an apply hook for the KMP-Android library plugin id",
    )
  }

  @Test
  fun `init script no longer carries the KMP-Android skip machinery`() {
    // Guards against a half-revert: the old KMP skip set, scanner and flag must all be gone.
    val script = renderInitScript("0.15.1")
    assertFalse(
      script.contains("composeAiPreviewKmpAndroidDirs"),
      "expected the KMP-Android skip set to be removed",
    )
    assertFalse(
      script.contains("scanForKmpAndroidDeclaration"),
      "expected the KMP-Android scanner to be removed",
    )
    assertFalse(
      script.contains("composeAiPreviewSkipKmpAndroid"),
      "expected the per-project KMP-Android skip flag to be removed",
    )
  }

  @Test
  fun `init script skips composite-included builds in settingsEvaluated and allprojects`() {
    // An included build whose settings declare `exclusiveContent` makes Gradle 9.3+ reject
    // `buildscript.repositories` additions, so the script returns early for included builds
    // (`gradle.parent != null`).
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
      "expected allprojects to short-circuit for composite-included builds so " +
        "buildscript.repositories isn't touched in included builds (would conflict with " +
        "exclusiveContent in settings.pluginManagement.repositories)",
    )
  }

  @Test
  fun `init script skips only the buildscript repositories add when settings declares exclusiveContent`() {
    // With `exclusiveContent` in `pluginManagement.repositories`, Gradle 9.3+ rejects adding to
    // `buildscript.repositories` but allows `buildscript.dependencies.classpath`, so only the
    // repositories block is gated. The plugin must stay on the project's buildscript classloader
    // (alongside AGP); loading it via the initscript classpath fails with `NoClassDefFoundError`.
    val script = renderInitScript("0.11.8")
    assertTrue(
      script.contains("var composeAiPreviewSettingsHasExclusiveContent: Boolean = false"),
      "expected the exclusiveContent flag declaration",
    )
    assertTrue(
      script.contains(
        "fun composeAiPreviewSettingsDeclaresExclusiveContent(settingsDir: java.io.File): Boolean {"
      ),
      "expected the scanner function in the rendered script",
    )
    assertTrue(
      script.contains(
        "composeAiPreviewSettingsHasExclusiveContent =\n        composeAiPreviewSettingsDeclaresExclusiveContent(settingsDir)"
      ),
      "expected settingsEvaluated to populate the flag from the scanner",
    )
    assertTrue(
      script.contains(
        "if (!composeAiPreviewSettingsHasExclusiveContent) {\n                    repositories {"
      ),
      "expected the buildscript repositories add to be guarded — must keep the dependency add " +
        "and the apply hooks reachable when exclusiveContent is present",
    )
    assertFalse(
      script.contains("if (composeAiPreviewSettingsHasExclusiveContent) return@allprojects"),
      "the early-return for exclusiveContent is too aggressive — it throws away the classpath " +
        "dep and apply hooks, but those can still work via the consumer's existing buildscript " +
        "repositories. Only the repositories add must be skipped.",
    )
  }

  @Test
  fun `settingsDeclaresExclusiveContentInPluginManagement matches the Confetti shape (listOf with shared repos)`() {
    // Confetti's settings declare `exclusiveContent` in pluginManagement transitively via
    // `listOf(repositories, dependencyResolutionManagement.repositories).forEach { ... }`; the
    // scanner must report `true`.
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            listOf(repositories, dependencyResolutionManagement.repositories).forEach {
                it.apply {
                    google { content { } }
                    mavenCentral()
                    maven("https://maven.pkg.jetbrains.space/kotlin/p/wasm/experimental")
                    exclusiveContent {
                        forRepository { it.maven("https://storage.googleapis.com/apollo-snapshots/m2") }
                        filter { includeVersionByRegex("com.apollographql.execution", ".*", ".*SNAPSHOT.*") }
                    }
                }
            }
        }
        rootProject.name = "confetti"
        include(":app")
        """
          .trimIndent()
      )
    assertTrue(
      settingsDeclaresExclusiveContentInPluginManagement(root),
      "expected the Confetti listOf-shared-repos shape to be detected",
    )
  }

  @Test
  fun `settingsDeclaresExclusiveContentInPluginManagement matches a direct declaration`() {
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                exclusiveContent {
                    forRepository { maven("https://example.com/m2") }
                    filter { includeGroup("com.example") }
                }
            }
        }
        rootProject.name = "demo"
        """
          .trimIndent()
      )
    assertTrue(settingsDeclaresExclusiveContentInPluginManagement(root))
  }

  @Test
  fun `settingsDeclaresExclusiveContentInPluginManagement ignores exclusiveContent outside pluginManagement`() {
    // `exclusiveContent` only in `dependencyResolutionManagement`, or in a bare buildscript, is
    // fine.
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        dependencyResolutionManagement {
            repositories {
                google()
                mavenCentral()
                exclusiveContent {
                    forRepository { maven("https://example.com/m2") }
                    filter { includeGroup("com.example") }
                }
            }
        }
        rootProject.name = "demo"
        """
          .trimIndent()
      )
    assertFalse(settingsDeclaresExclusiveContentInPluginManagement(root))
  }

  @Test
  fun `settingsDeclaresExclusiveContentInPluginManagement ignores commented-out declarations`() {
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            // exclusiveContent {
            //     forRepository { maven("https://example.com/m2") }
            // }
            repositories { gradlePluginPortal() }
        }
        """
          .trimIndent()
      )
    assertFalse(settingsDeclaresExclusiveContentInPluginManagement(root))
  }

  @Test
  fun `settingsDeclaresExclusiveContentInPluginManagement returns false for a settings file without exclusiveContent`() {
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories { gradlePluginPortal(); google(); mavenCentral() }
        }
        rootProject.name = "demo"
        include(":app")
        """
          .trimIndent()
      )
    assertFalse(settingsDeclaresExclusiveContentInPluginManagement(root))
  }

  @Test
  fun `settingsDeclaresExclusiveContentInPluginManagement returns false when settings file is missing`() {
    val root = tempDir()
    assertFalse(settingsDeclaresExclusiveContentInPluginManagement(root))
  }

  @Test
  fun `projectHasBuildscriptRepositories detects an explicit buildscript repositories block`() {
    // In the exclusiveContent branch, modules without their own buildscript repos can't resolve a
    // plain classpath coordinate.
    val dir = tempDir()
    File(dir, "build.gradle.kts")
      .writeText(
        """
        buildscript {
            repositories { mavenCentral() }
            dependencies { classpath("com.example:some-plugin:1.0") }
        }
        plugins { kotlin("jvm") }
        """
          .trimIndent()
      )
    assertTrue(projectHasBuildscriptRepositories(dir))
  }

  @Test
  fun `projectHasBuildscriptRepositories returns false for a modern plugins-DSL-only build script`() {
    // Modern projects route everything through settings, so there are no per-project buildscript
    // repos.
    val dir = tempDir()
    File(dir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.2.21"
        }
        """
          .trimIndent()
      )
    assertFalse(projectHasBuildscriptRepositories(dir))
  }

  @Test
  fun `projectHasBuildscriptRepositories ignores a top-level repositories block outside buildscript`() {
    // A project-level `repositories { ... }` isn't `buildscript { repositories { ... } }`; the
    // scanner must scope to the buildscript block.
    val dir = tempDir()
    File(dir, "build.gradle.kts")
      .writeText(
        """
        plugins { kotlin("jvm") }
        repositories { mavenCentral() }
        """
          .trimIndent()
      )
    assertFalse(projectHasBuildscriptRepositories(dir))
  }

  @Test
  fun `projectHasBuildscriptRepositories ignores commented-out blocks`() {
    val dir = tempDir()
    File(dir, "build.gradle.kts")
      .writeText(
        """
        // buildscript {
        //     repositories { mavenCentral() }
        // }
        plugins { kotlin("jvm") }
        """
          .trimIndent()
      )
    assertFalse(projectHasBuildscriptRepositories(dir))
  }

  @Test
  fun `projectHasBuildscriptRepositories returns false when no build script exists`() {
    // A parent project included in settings may have no build script at all.
    val dir = tempDir()
    assertFalse(projectHasBuildscriptRepositories(dir))
  }

  @Test
  fun `init script forks the exclusiveContent branch on per-project buildscript repos`() {
    // In the exclusiveContent branch, modules without their own buildscript repos resolve the
    // classpath via a detached configuration and inject `files()`; modules with repos use the
    // coordinate. Pins the wire shape.
    val script = renderInitScript("0.11.9")
    assertTrue(
      script.contains(
        "var composeAiPreviewProjectsWithOwnBuildscriptRepos: Set<java.io.File> = emptySet()"
      ),
      "expected the per-project buildscript-repos set declaration",
    )
    assertTrue(
      script.contains(
        "fun scanForProjectsWithBuildscriptRepos(\n    projectDirs: List<java.io.File>,\n): Set<java.io.File> {"
      ),
      "expected the scanner function in the rendered script",
    )
    assertTrue(
      script.contains(
        "composeAiPreviewProjectsWithOwnBuildscriptRepos =\n            scanForProjectsWithBuildscriptRepos(projectDirs)"
      ),
      "expected the set to be populated inside the exclusiveContent branch at settingsEvaluated time",
    )
    assertTrue(
      script.contains(
        "val composeAiPreviewNeedsResolvedClasspathInject =\n        composeAiPreviewSettingsHasExclusiveContent &&\n            projectDir !in composeAiPreviewProjectsWithOwnBuildscriptRepos"
      ),
      "expected the per-project fork flag in allprojects",
    )
    assertFalse(
      script.contains("composeAiPreviewSkipExclusiveContentClasspathDep"),
      "the old skip-and-drop flag must be gone — the branch now resolves + injects",
    )
    assertFalse(
      script.contains("[compose-preview] settings.gradle.kts declares exclusiveContent in"),
      "init script should not emit lifecycle logs nudging the user to apply the plugin",
    )
  }

  @Test
  fun `init script resolves and injects the plugin classpath as files in the repo-less exclusiveContent branch`() {
    // Modules without their own buildscript repos resolve the plugin through the project's
    // settings-managed repos via a detached configuration and inject the JARs as `files()`, putting
    // the plugin on the module's own buildscript classloader without touching
    // `buildscript.repositories`.
    val script = renderInitScript("0.11.9")
    assertTrue(
      script.contains(
        "fun org.gradle.api.Project.composeAiPreviewResolvePluginClasspath(): Set<java.io.File> {"
      ),
      "expected the detached-configuration classpath resolver helper",
    )
    assertTrue(
      script.contains("configurations.detachedConfiguration(composeAiPreviewMarker).files.toSet()"),
      "expected resolution via a detached configuration (not a buildscript.repositories add)",
    )
    assertTrue(
      script.contains("add(\"classpath\", composeAiPreviewClasspathFiles)"),
      "expected the resolved files to be injected onto the buildscript classpath",
    )
    assertTrue(
      script.contains("composeAiPreviewCachedPluginClasspath?.let { return it }"),
      "expected the resolved classpath to be memoised across modules",
    )
  }

  @Test
  fun `init script skips classpath injection for ancestors of a pre-applied module`() {
    // Subprojects inherit their ancestors' buildscript classpath into `plugins {}` resolution, so
    // injecting onto an ancestor of a module that applies the plugin via the versioned DSL fails it
    // with "already on the classpath with an unknown version". Skip injection (and apply hooks) for
    // any project with a pre-applied descendant.
    val script = renderInitScript("0.15.5")
    assertTrue(
      script.contains(
        "val composeAiPreviewHasPreAppliedDescendant =\n        subprojects.any { it.projectDir in composeAiPreviewPreAppliedDirs }"
      ),
      "expected the pre-applied-descendant scan in allprojects",
    )
    assertTrue(
      script.contains(
        "if (!composeAiPreviewIsPreApplied && !composeAiPreviewHasPreAppliedDescendant) {"
      ),
      "expected the buildscript classpath injection to be gated on the pre-applied + descendant flags",
    )
    assertTrue(
      script.contains(
        "if (composeAiPreviewHasPreAppliedDescendant && !composeAiPreviewIsPreApplied) {\n        return@allprojects\n    }"
      ),
      "expected the apply hooks to short-circuit for ancestors of pre-applied modules too",
    )
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

  // --- Convention-plugin provision detection (issue #3) -----------------------------------------

  /**
   * Lays out the androidchka shape: root settings `includeBuild`s build-logic, whose build script
   * stages the ee.schimke.composeai.preview plugin marker on its classpath.
   */
  private fun seedConventionPluginBuild(
    projectRoot: File,
    includeIn: String = "pluginManagement { includeBuild(\"build-logic\") }",
    buildLogicScript: String =
      "implementation(\"ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:0.15.12\")",
  ) {
    File(projectRoot, "settings.gradle.kts")
      .writeText(
        """
        $includeIn
        rootProject.name = "androidx-mini"
        include(":app")
        """
          .trimIndent()
      )
    File(projectRoot, "build-logic").mkdirs()
    File(projectRoot, "build-logic/build.gradle.kts")
      .writeText(
        """
        plugins { `kotlin-dsl` }
        dependencies {
          $buildLogicScript
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun `includedBuildProvidesComposeAiPreviewPlugin detects convention-plugin classpath provision`() {
    val projectRoot = tempDir()
    seedConventionPluginBuild(projectRoot)
    assertTrue(includedBuildProvidesComposeAiPreviewPlugin(projectRoot))
  }

  @Test
  fun `includedBuildProvidesComposeAiPreviewPlugin is false when build-logic does not reference the plugin`() {
    val projectRoot = tempDir()
    // A real build-logic dir that doesn't supply the plugin.
    File(projectRoot, "settings.gradle.kts")
      .writeText("pluginManagement { includeBuild(\"build-logic\") }\ninclude(\":app\")\n")
    File(projectRoot, "build-logic").mkdirs()
    File(projectRoot, "build-logic/build.gradle.kts")
      .writeText(
        "plugins { `kotlin-dsl` }\ndependencies { implementation(\"com.gradleup.tapmoc:tapmoc-gradle-plugin:0.4.2\") }\n"
      )
    assertFalse(includedBuildProvidesComposeAiPreviewPlugin(projectRoot))
  }

  @Test
  fun `includedBuildProvidesComposeAiPreviewPlugin is false when build-logic supplies only the config-only plugin`() {
    // A convention build staging only the configuration-only marker
    // (`…preview.config.gradle.plugin`) doesn't supply the runtime; the runtime id is a prefix of
    // it, so a naive scan would wrongly disable auto-inject.
    val projectRoot = tempDir()
    seedConventionPluginBuild(
      projectRoot,
      buildLogicScript =
        "implementation(\"ee.schimke.composeai.preview.config:ee.schimke.composeai.preview.config.gradle.plugin:0.15.12\")",
    )
    assertFalse(includedBuildProvidesComposeAiPreviewPlugin(projectRoot))
  }

  @Test
  fun `includedBuildProvidesComposeAiPreviewPlugin still detects runtime when both plugins are referenced`() {
    // Both config-only and runtime: still detected as providing the runtime.
    val projectRoot = tempDir()
    seedConventionPluginBuild(
      projectRoot,
      buildLogicScript =
        "implementation(\"ee.schimke.composeai.preview.config:ee.schimke.composeai.preview.config.gradle.plugin:0.15.12\")\n" +
          "  implementation(\"ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:0.15.12\")",
    )
    assertTrue(includedBuildProvidesComposeAiPreviewPlugin(projectRoot))
  }

  @Test
  fun `includedBuildProvidesComposeAiPreviewPlugin detects the dep in an included-build subproject`() {
    // Multi-project convention build: the dependency is in `build-logic/conventions`; the recursive
    // scan must find it.
    val projectRoot = tempDir()
    File(projectRoot, "settings.gradle.kts")
      .writeText("pluginManagement { includeBuild(\"build-logic\") }\ninclude(\":app\")\n")
    File(projectRoot, "build-logic").mkdirs()
    File(projectRoot, "build-logic/build.gradle.kts").writeText("// aggregator, no deps here\n")
    File(projectRoot, "build-logic/conventions").mkdirs()
    File(projectRoot, "build-logic/conventions/build.gradle.kts")
      .writeText(
        "plugins { `kotlin-dsl` }\ndependencies { implementation(\"ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:0.15.12\") }\n"
      )
    assertTrue(includedBuildProvidesComposeAiPreviewPlugin(projectRoot))
  }

  @Test
  fun `includedBuildProvidesComposeAiPreviewPlugin ignores matches under a build output dir`() {
    // A stale copy under `build-logic/build/` must not count; output trees are pruned.
    val projectRoot = tempDir()
    File(projectRoot, "settings.gradle.kts")
      .writeText("pluginManagement { includeBuild(\"build-logic\") }\ninclude(\":app\")\n")
    File(projectRoot, "build-logic").mkdirs()
    File(projectRoot, "build-logic/build.gradle.kts").writeText("plugins { `kotlin-dsl` }\n")
    File(projectRoot, "build-logic/build/generated").mkdirs()
    File(projectRoot, "build-logic/build/generated/build.gradle.kts")
      .writeText("implementation(\"ee.schimke.composeai.preview:...\")\n")
    assertFalse(includedBuildProvidesComposeAiPreviewPlugin(projectRoot))
  }

  @Test
  fun `includedBuildProvidesComposeAiPreviewPlugin is false with no included build at all`() {
    val projectRoot = tempDir()
    File(projectRoot, "settings.gradle.kts")
      .writeText("rootProject.name = \"demo\"\ninclude(\":app\")\n")
    assertFalse(includedBuildProvidesComposeAiPreviewPlugin(projectRoot))
  }

  @Test
  fun `includedBuildProvidesComposeAiPreviewPlugin ignores a commented-out plugin reference`() {
    val projectRoot = tempDir()
    seedConventionPluginBuild(
      projectRoot,
      buildLogicScript =
        "// implementation(\"ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:0.15.12\")",
    )
    assertFalse(
      includedBuildProvidesComposeAiPreviewPlugin(projectRoot),
      "a commented-out classpath dep must not count as provision",
    )
  }

  @Test
  fun `autoInjectInitScriptArgs disables auto-inject when a convention plugin supplies the plugin`() {
    val storage = tempDir()
    val projectRoot = tempDir()
    seedConventionPluginBuild(projectRoot)
    val warnings = mutableListOf<String>()
    val out =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "0.15.12",
        storageDir = storage,
        env = { null },
        projectRoot = projectRoot,
        stderr = { warnings += it },
      )
    assertTrue(
      out.isEmpty(),
      "expected no --init-script when a convention plugin supplies the plugin; got $out",
    )
    assertFalse(File(storage, INIT_SCRIPT_FILENAME).exists())
    assertTrue(
      warnings.any { it.contains("auto-inject disabled") && it.contains("convention plugin") },
      "expected an explanatory stderr note; got $warnings",
    )
  }

  @Test
  fun `autoInjectInitScriptArgs stays on when build-logic is present but plugin-free`() {
    val storage = tempDir()
    val projectRoot = tempDir()
    File(projectRoot, "settings.gradle.kts")
      .writeText("pluginManagement { includeBuild(\"build-logic\") }\ninclude(\":app\")\n")
    File(projectRoot, "build-logic").mkdirs()
    File(projectRoot, "build-logic/build.gradle.kts").writeText("plugins { `kotlin-dsl` }\n")
    val out =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "0.15.12",
        storageDir = storage,
        env = { null },
        projectRoot = projectRoot,
      )
    assertEquals(
      listOf("--init-script", File(storage, INIT_SCRIPT_FILENAME).absolutePath) +
        ISOLATED_PROJECTS_OFF_ARGS,
      out,
    )
  }
}
