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
    // The allprojects-based injection can't run under IP, so the script must detect IP at
    // settingsEvaluated (before the violation aborts the build) and warn the user.
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
  fun `init script gates the buildscript classpath injection on per-project pre-applied detection`() {
    // Regression for #305 (homeassistant-remotecompose): the original gate was a single global
    // boolean, so a mixed-shape project where some modules declare the plugin via
    // `alias(libs.plugins.compose.preview)` and others don't would skip buildscript injection
    // *everywhere* and then `pluginManager.apply` from the withPlugin hooks would fail in the
    // modules without the catalog alias ("Plugin with id 'ee.schimke.composeai.preview' not
    // found."). The gate is now a per-project set of project directories that declare the
    // plugin themselves.
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
      script.contains("projectDir !in composeAiPreviewPreAppliedDirs"),
      "expected the buildscript block to be guarded per-project on the directory set",
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
  fun `init script seeds settings-level mavenLocal automatically for SNAPSHOT versions`() {
    // wear-os-samples WearTilesKotlin (and any consumer that sets
    // `RepositoriesMode.FAIL_ON_PROJECT_REPOS` in settings.gradle.kts) refuses per-project repos —
    // a per-project `mavenLocal()` is not enough for renderer-android AAR resolution. The
    // settings-level seeding inside `gradle.settingsEvaluated { ... }` is the path that survives
    // restrictive `RepositoriesMode`s and lets integration CI resolve our SNAPSHOT runtime deps
    // from `~/.m2`. `pluginManagement.repositories.mavenLocal()` covers the plugins-DSL resolution
    // path for the catalog-alias / literal-`id(...) version "..."` case where we skip our own
    // buildscript classpath injection.
    //
    // SNAPSHOT versions enable the seed unconditionally — an unpublished SNAPSHOT plugin can only
    // live in `~/.m2`, so a SNAPSHOT CLI that doesn't add mavenLocal is unusable against
    // consumers that don't already have it in their settings. Released versions still gate on the
    // `COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1` env var (asserted in the sibling test below).
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
    // The gradle-plugin functional tests publish the CLI's own release version to `~/.m2` and
    // resolve from there rather than Maven Central. Releasing the SNAPSHOT auto-seed regression
    // shouldn't take the env-var path with it.
    val script = renderInitScript("0.11.10")
    assertTrue(
      script.contains("System.getenv(\"COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL\") == \"1\""),
      "expected the env-var escape hatch to survive for release builds",
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
    // Auto-inject must scan for the KMP-Android plugin id, mark those modules, and skip both
    // the buildscript classpath injection AND the apply hooks for them — partial skipping
    // leaves the other half active and still fails. Pins the wire shape of both halves.
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
      script.contains(
        "val composeAiPreviewSkipKmpAndroid = projectDir in composeAiPreviewKmpAndroidDirs"
      ),
      "expected the per-project skip flag",
    )
    assertTrue(
      script.contains("!composeAiPreviewSkipKmpAndroid &&") &&
        script.contains("projectDir !in composeAiPreviewPreAppliedDirs"),
      "expected the buildscript classpath injection to be gated on the KMP-Android skip flag " +
        "and the pre-applied dir set",
    )
    assertTrue(
      script.contains("if (composeAiPreviewSkipKmpAndroid) return@allprojects"),
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
    // Regression for the Confetti report: with `includeBuild("build-logic")` whose
    // settings.gradle.kts declares `exclusiveContent { ... }` in `pluginManagement.repositories`,
    // Gradle 9.3+ rejects any project that adds to `buildscript.repositories`. The init script
    // is evaluated once per build in a composite, so the unguarded `allprojects { buildscript
    // { repositories { ... } } }` previously fired against the included build and tripped the
    // validation. Pins the early-return shape so it doesn't regress. An included build's
    // `gradle.parent` is non-null; the root build's is null, so the guard is a one-liner that
    // costs the root build nothing.
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
    // Successor to PR #1483 (reverted): when `pluginManagement.repositories` in the settings
    // file declares `exclusiveContent { ... }` (the Confetti shape, issues #1470/#1482), Gradle
    // 9.3+ rejects *adding* to `buildscript.repositories` — but adding to
    // `buildscript.dependencies.classpath` is still fine. So we gate just the repositories
    // sub-block and keep the classpath dependency + apply hooks: if the consumer's existing
    // buildscript repositories can resolve the plugin coordinate (cached locally, or declared
    // in their own `buildscript { repositories { ... } }`), auto-inject still works. Otherwise
    // Gradle fails naturally with a clear "Could not resolve" message.
    //
    // We *cannot* dodge the validation by loading the plugin via initscript classpath (the
    // failed approach from #1483 — the plugin lives on a sibling classloader of AGP and
    // immediately `NoClassDefFoundError`s on AGP types). Keeping the plugin on the project's
    // buildscript classloader preserves AGP visibility.
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
        "if (!composeAiPreviewSettingsHasExclusiveContent) {\n                repositories {"
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
  fun `settingsDeclaresExclusiveContentInPluginManagement matches the Confetti shape (listOf with shared repos)`() {
    // Reproducer for the Confetti `main` settings file (https://github.com/joreilly/Confetti). The
    // `pluginManagement { listOf(repositories, dependencyResolutionManagement.repositories)
    // .forEach { ... exclusiveContent ... } }` pattern declares exclusiveContent in
    // pluginManagement.repositories transitively — Gradle 9.3+ rejects buildscript.repositories
    // mutations as a result, so our scanner must report `true` here so the init script skips
    // injection (issue #1482).
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
    // exclusiveContent inside `dependencyResolutionManagement.repositories` ONLY (not
    // pluginManagement) is fine — the validation only fires for the pluginManagement variant.
    // A bare-buildscript exclusiveContent (no pluginManagement block at all) is also fine.
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
    // Used in the exclusiveContent branch — modules that don't already have their own
    // `buildscript { repositories { ... } }` would crash configuration if we still injected
    // the classpath dep (Confetti's :backend shape; 0.11.8 regression).
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
    // Confetti's :backend and friends — modern projects route everything through settings'
    // pluginManagement / dependencyResolutionManagement. With no per-project buildscript
    // repos, our classpath dep can't resolve in the exclusiveContent branch.
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
    // A `repositories { ... }` at the project level (for runtime deps) is different from
    // `buildscript { repositories { ... } }` (for plugin classpath). The scanner must scope
    // the check to inside the buildscript block.
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
    // Confetti's :backend has no build.gradle.kts at all — it's a parent project with
    // `include(":backend")` and `include(":backend:foo")` declared in settings, but no build
    // script of its own.
    val dir = tempDir()
    assertFalse(projectHasBuildscriptRepositories(dir))
  }

  @Test
  fun `init script gates buildscript classpath dep injection on per-project buildscript repos in exclusiveContent branch`() {
    // Successor to the 0.11.8 follow-up regression: in the exclusiveContent branch, the
    // classpath dep is unresolvable on modules without their own buildscript repos. Pins the
    // wire shape so that the scanner and the per-project skip both survive future refactors.
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
        "val composeAiPreviewSkipExclusiveContentClasspathDep =\n        composeAiPreviewSettingsHasExclusiveContent &&\n            projectDir !in composeAiPreviewProjectsWithOwnBuildscriptRepos"
      ),
      "expected the per-project skip flag in allprojects",
    )
    assertTrue(
      script.contains(
        "if (composeAiPreviewSkipExclusiveContentClasspathDep &&\n        projectDir !in composeAiPreviewPreAppliedDirs) return@allprojects"
      ),
      "expected the apply hooks to short-circuit for skipped modules (otherwise " +
        "pluginManager.apply would fail with 'Plugin with id ... not found')",
    )
    assertFalse(
      script.contains("[compose-preview] settings.gradle.kts declares exclusiveContent in"),
      "init script should not emit lifecycle logs nudging the user to apply the plugin",
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
}
