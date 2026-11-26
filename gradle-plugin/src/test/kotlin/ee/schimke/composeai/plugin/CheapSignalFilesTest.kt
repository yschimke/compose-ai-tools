package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.plugin.daemon.SourceWatchInputs
import java.io.File
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CheapSignalFilesTest {

  @get:Rule val tempDir = TemporaryFolder()

  private fun writeFile(root: File, relativePath: String, content: String = ""): File =
    File(root, relativePath).apply {
      parentFile?.mkdirs()
      writeText(content)
    }

  @Test
  fun `collects buildSrc convention plugin files and excludes build and hidden outputs`() {
    val root = tempDir.root
    val appDir = File(root, "app").apply { mkdirs() }
    val settings = writeFile(root, "settings.gradle.kts", """include(":app")""")
    val rootBuild = writeFile(root, "build.gradle.kts")
    val appBuild = writeFile(appDir, "build.gradle.kts")
    val catalog = writeFile(root, "gradle/libs.versions.toml")

    val buildSrcBuild = writeFile(root, "buildSrc/build.gradle.kts")
    val buildSrcSettings = writeFile(root, "buildSrc/settings.gradle.kts")
    val buildSrcCatalog = writeFile(root, "buildSrc/gradle/build.versions.toml")
    val buildSrcKt =
      writeFile(root, "buildSrc/src/main/kotlin/ComposeConventionPlugin.kt", "class P")
    val buildSrcKts = writeFile(root, "buildSrc/src/main/kotlin/my.compose.gradle.kts", "// plugin")
    val buildSrcJava = writeFile(root, "buildSrc/src/main/java/Helper.java", "class Helper {}")
    val buildSrcGroovy =
      writeFile(root, "buildSrc/src/main/groovy/LegacyPlugin.groovy", "class LegacyPlugin {}")

    val buildOutputKt = writeFile(root, "buildSrc/build/generated-sources/Generated.kt")
    val buildOutputClass = writeFile(root, "buildSrc/build/classes/kotlin/main/P.class")
    val gradleCacheKt = writeFile(root, "buildSrc/.gradle/8.0/cache.kt")
    val kotlinCacheKt = writeFile(root, "buildSrc/.kotlin/sessions/tmp.kt")
    val nonBuildAsset = writeFile(root, "buildSrc/src/main/resources/icon.png")

    val collected = CheapSignalFiles.collect(rootDir = root, moduleDir = appDir)

    assertThat(collected)
      .containsAtLeast(
        settings,
        rootBuild,
        appBuild,
        catalog,
        buildSrcBuild,
        buildSrcSettings,
        buildSrcCatalog,
        buildSrcKt,
        buildSrcKts,
        buildSrcJava,
        buildSrcGroovy,
      )
    assertThat(collected).doesNotContain(buildOutputKt)
    assertThat(collected).doesNotContain(buildOutputClass)
    assertThat(collected).doesNotContain(gradleCacheKt)
    assertThat(collected).doesNotContain(kotlinCacheKt)
    assertThat(collected).doesNotContain(nonBuildAsset)
  }

  @Test
  fun `collects included builds from gradle includedBuilds and pluginManagement settings`() {
    val root = tempDir.root
    val appDir = File(root, "app").apply { mkdirs() }
    writeFile(
      root,
      "settings.gradle.kts",
      """
      pluginManagement {
          includeBuild("build-logic")
          // includeBuild("commented-out-logic")
          /* includeBuild("block-commented-logic") */
      }
      include(":app")
      """
        .trimIndent(),
    )
    writeFile(appDir, "build.gradle.kts")

    // Plugin-management included build discovered via settings.gradle.kts.
    val buildLogicSettings = writeFile(root, "build-logic/settings.gradle.kts")
    val buildLogicBuild = writeFile(root, "build-logic/convention/build.gradle.kts")
    val buildLogicPlugin =
      writeFile(root, "build-logic/convention/src/main/kotlin/PreviewConventionPlugin.kt")
    val buildLogicOutput =
      writeFile(root, "build-logic/convention/build/generated/GeneratedPlugin.kt")

    // Commented-out builds must not be walked.
    val commentedPlugin = writeFile(root, "commented-out-logic/src/main/kotlin/Ignored.kt")
    val blockCommentedPlugin =
      writeFile(root, "block-commented-logic/src/main/kotlin/AlsoIgnored.kt")

    // Separate included build passed via `gradle.includedBuilds`.
    val extraIncludedDir = File(root, "extra-conventions").apply { mkdirs() }
    val extraBuild = writeFile(extraIncludedDir, "build.gradle.kts")
    val extraPlugin = writeFile(extraIncludedDir, "src/main/kotlin/ExtraConvention.kt")
    val extraBuildOutput = writeFile(extraIncludedDir, "build/tmp/Generated.kt")

    val collected =
      CheapSignalFiles.collect(
        rootDir = root,
        moduleDir = appDir,
        includedBuildDirs = listOf(extraIncludedDir, File(root, "build-logic")),
      )

    assertThat(collected)
      .containsAtLeast(
        buildLogicSettings,
        buildLogicBuild,
        buildLogicPlugin,
        extraBuild,
        extraPlugin,
      )
    assertThat(collected).doesNotContain(buildLogicOutput)
    assertThat(collected).doesNotContain(extraBuildOutput)
    assertThat(collected).doesNotContain(commentedPlugin)
    assertThat(collected).doesNotContain(blockCommentedPlugin)
    // Deduplicated even when `build-logic` is discovered via both settings and includedBuildDirs.
    assertThat(collected.count { it == buildLogicPlugin }).isEqualTo(1)
  }

  @Test
  fun `bounds convention build file collection and prioritizes shallow build scripts`() {
    val root = tempDir.root
    val appDir = File(root, "app").apply { mkdirs() }
    writeFile(root, "settings.gradle.kts", """pluginManagement { includeBuild("build-logic") }""")
    writeFile(appDir, "build.gradle.kts")

    val shallowSettings = writeFile(root, "build-logic/settings.gradle.kts")
    val shallowBuild = writeFile(root, "build-logic/build.gradle.kts")
    for (i in 1..20) {
      writeFile(root, "build-logic/src/main/kotlin/Plugin$i.kt")
    }

    val collected =
      CheapSignalFiles.collect(
        rootDir = root,
        moduleDir = appDir,
        maxConventionFiles = 5,
      )

    val buildLogicFiles = collected.filter { it.path.contains("build-logic") }
    assertThat(buildLogicFiles).hasSize(5)
    assertThat(buildLogicFiles).containsAtLeast(shallowSettings, shallowBuild)
  }

  @Test
  fun `SourceWatchInputs buildFiles exposes buildSrc and included build convention plugins`() {
    val root = tempDir.root
    writeFile(root, "settings.gradle.kts", """pluginManagement { includeBuild("build-logic") }""")
    writeFile(root, "build.gradle.kts")
    val buildSrcPlugin = writeFile(root, "buildSrc/src/main/kotlin/RootConvention.kt")
    val buildSrcOutput = writeFile(root, "buildSrc/build/generated/Ignored.kt")
    val buildLogicPlugin = writeFile(root, "build-logic/src/main/kotlin/PreviewPlugin.kt")
    val buildLogicOutput = writeFile(root, "build-logic/build/generated/Ignored.kt")

    val project = ProjectBuilder.builder().withProjectDir(root).build()
    val buildFiles = SourceWatchInputs.buildFiles(project)

    assertThat(buildFiles)
      .containsAtLeast(buildSrcPlugin.absolutePath, buildLogicPlugin.absolutePath)
    assertThat(buildFiles).doesNotContain(buildSrcOutput.absolutePath)
    assertThat(buildFiles).doesNotContain(buildLogicOutput.absolutePath)
  }

  @Test
  fun `preserves source packages named build or out while still excluding output directories`() {
    val root = tempDir.root
    val appDir = File(root, "app").apply { mkdirs() }
    writeFile(root, "settings.gradle.kts", """pluginManagement { includeBuild("build-logic") }""")
    writeFile(appDir, "build.gradle.kts")

    val buildPackagePlugin =
      writeFile(
        root,
        "buildSrc/src/main/kotlin/com/example/build/ComposeConventionPlugin.kt",
        "package com.example.build",
      )
    val outPackagePlugin =
      writeFile(
        root,
        "buildSrc/src/main/kotlin/com/example/out/OutConventionPlugin.kt",
        "package com.example.out",
      )
    val subprojectBuildPackagePlugin =
      writeFile(
        root,
        "build-logic/convention/src/main/kotlin/com/example/build/SubprojectPlugin.kt",
        "package com.example.build",
      )

    val buildSrcOutputWithSrc =
      writeFile(root, "buildSrc/build/generated-sources/src/main/kotlin/Generated.kt")
    val buildSrcOutDir = writeFile(root, "buildSrc/out/production/classes/Output.kt")
    val subprojectBuildOutput =
      writeFile(root, "build-logic/convention/build/generated/GeneratedPlugin.kt")
    val nodeModulesFile = writeFile(root, "build-logic/node_modules/pkg/Helper.kt")

    val collected = CheapSignalFiles.collect(rootDir = root, moduleDir = appDir)

    assertThat(collected)
      .containsAtLeast(buildPackagePlugin, outPackagePlugin, subprojectBuildPackagePlugin)
    assertThat(collected).doesNotContain(buildSrcOutputWithSrc)
    assertThat(collected).doesNotContain(buildSrcOutDir)
    assertThat(collected).doesNotContain(subprojectBuildOutput)
    assertThat(collected).doesNotContain(nodeModulesFile)
  }
}
