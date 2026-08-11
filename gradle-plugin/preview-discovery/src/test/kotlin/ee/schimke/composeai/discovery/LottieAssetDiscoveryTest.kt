package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Coverage for direct Lottie-asset discovery: a `.json` Lottie document or a `.lottie` archive
 * under a resource root becomes a [PreviewKind.LOTTIE] preview with no `@Preview` / consumer
 * composable, while ordinary `.json` data files are ignored.
 */
class LottieAssetDiscoveryTest {

  @get:Rule val tempDir = TemporaryFolder()

  private fun discover(resourceDir: java.io.File): PreviewManifest {
    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = emptyList(),
          dependencyJars = emptyList(),
          sourceFiles = emptyList(),
          moduleName = ":app",
          variantName = "desktop",
          projectDirectory = resourceDir,
          failOnEmpty = false,
          resourceDirs = listOf(resourceDir),
        )
      )
    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    return (outcome as PreviewDiscovery.Outcome.Success).manifest
  }

  @Test
  fun `lottie json is discovered with dimensions, non-lottie json is ignored`() {
    val res = tempDir.newFolder("resources")
    res.resolve("anim").mkdirs()
    res
      .resolve("anim/loading.json")
      .writeText("""{"v":"5.7.0","fr":30,"ip":0,"op":60,"w":240,"h":120,"layers":[]}""")
    // Ordinary config JSON — has no Lottie marker keys, must be skipped.
    res.resolve("config.json").writeText("""{"name":"app","version":3}""")

    val previews = discover(res).previews
    assertThat(previews).hasSize(1)
    val p = previews.single()
    assertThat(p.params.kind).isEqualTo(PreviewKind.LOTTIE)
    assertThat(p.params.assetPath).isEqualTo("anim/loading.json")
    assertThat(p.params.widthDp).isEqualTo(240)
    assertThat(p.params.heightDp).isEqualTo(120)
    // Id + render output are filename-safe (no `:` / `/` to corrupt zip paths).
    assertThat(p.id).doesNotContain("/")
    assertThat(p.id).doesNotContain(":")
    // A Lottie preview ships two captures by default: the still PNG baseline plus the animated
    // companion, an APNG (`_animated.png`) spanning the asset's intrinsic timeline. Both are `.png`
    // so the animated one is served as `image/png` and autoplays inline everywhere.
    val animated = p.captures.first { it.renderOutput.endsWith("_animated.png") }
    val still = p.captures.first { it.renderOutput.endsWith(".png") && it != animated }
    // The still PNG is the required baseline; the animated APNG is best-effort so a headless env
    // that can't encode it never fails the required-render gate.
    assertThat(still.optional).isFalse()
    assertThat(animated.optional).isTrue()
  }

  @Test
  fun `dotlottie archive is accepted by extension`() {
    val res = tempDir.newFolder("resources")
    res.resolve("hero.lottie").writeText("not-really-a-zip-but-extension-wins")

    val previews = discover(res).previews
    assertThat(previews.map { it.params.assetPath }).containsExactly("hero.lottie")
    assertThat(previews.single().params.kind).isEqualTo(PreviewKind.LOTTIE)
  }

  @Test
  fun `no resource dirs yields no lottie previews`() {
    val res = tempDir.newFolder("empty")
    assertThat(discover(res).previews).isEmpty()
  }

  @Test
  fun `asset preview cannot mask empty compiled outputs`() {
    val project = tempDir.newFolder("empty-compile")
    val classes = project.resolve("classes").apply { mkdirs() }
    val source =
      project.resolve("src/main/kotlin/Previews.kt").apply {
        parentFile.mkdirs()
        writeText("@Preview\nfun preview() = Unit")
      }
    val resources = project.resolve("resources").apply { mkdirs() }
    resources.resolve("spin.json").writeText("""{"v":"5.7.0","layers":[]}""")

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(classes),
          dependencyJars = emptyList(),
          sourceFiles = listOf(source),
          moduleName = ":app",
          variantName = "desktop",
          projectDirectory = project,
          failOnEmpty = false,
          resourceDirs = listOf(resources),
        )
      ) as PreviewDiscovery.Outcome.Success

    assertThat(outcome.manifest.previews).hasSize(1)
    val warning = outcome.warnings.joinToString("\n")
    assertThat(warning).contains("active class outputs contain 0 .class files")
    assertThat(warning).contains("--no-build-cache --rerun-tasks")
    assertThat(warning).contains("classFiles=0")
  }

  @Test
  fun `failOnEmpty rejects asset-only discovery when compiled outputs are empty`() {
    val project = tempDir.newFolder("asset-only-failure")
    val classes = project.resolve("classes").apply { mkdirs() }
    val source =
      project.resolve("src/main/kotlin/Previews.kt").apply {
        parentFile.mkdirs()
        writeText("@Preview\nfun preview() = Unit")
      }
    val resources = project.resolve("resources").apply { mkdirs() }
    resources.resolve("spin.json").writeText("""{"v":"5.7.0","layers":[]}""")

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(classes),
          dependencyJars = emptyList(),
          sourceFiles = listOf(source),
          moduleName = ":app",
          variantName = "desktop",
          projectDirectory = project,
          failOnEmpty = true,
          resourceDirs = listOf(resources),
        )
      )

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Failure::class.java)
    val failure = outcome as PreviewDiscovery.Outcome.Failure
    assertThat(failure.reason).contains("empty compiled outputs")
    assertThat(failure.reason).contains("0 code previews; 1 total")
  }

  @Test
  fun `source-only declarations do not flag an intentional asset-only module`() {
    val project = tempDir.newFolder("intentional-asset-only")
    val classes = project.resolve("classes").apply { mkdirs() }
    val source =
      project.resolve("src/main/kotlin/Aliases.kt").apply {
        parentFile.mkdirs()
        writeText("typealias PreviewName = String\nexpect class PlatformValue")
      }
    val resources = project.resolve("resources").apply { mkdirs() }
    resources.resolve("spin.json").writeText("""{"v":"5.7.0","layers":[]}""")

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(classes),
          dependencyJars = emptyList(),
          sourceFiles = listOf(source),
          moduleName = ":assets",
          variantName = "desktop",
          projectDirectory = project,
          failOnEmpty = false,
          resourceDirs = listOf(resources),
        )
      ) as PreviewDiscovery.Outcome.Success

    assertThat(outcome.manifest.previews).hasSize(1)
    assertThat(outcome.warnings.joinToString("\n")).doesNotContain("empty build-cache entry")
  }

  @Test
  fun `stale fallback classes do not mask an empty active compilation output`() {
    val project = tempDir.newFolder("stale-fallback")
    val activeClasses = project.resolve("classes/kotlin/desktop/main").apply { mkdirs() }
    val staleClasses = project.resolve("classes/kotlin/jvm/main").apply { mkdirs() }
    staleClasses.resolve("Stale.class").writeBytes(byteArrayOf())
    val source =
      project.resolve("src/main/kotlin/Previews.kt").apply {
        parentFile.mkdirs()
        writeText("@Preview\nfun preview() = Unit")
      }
    val resources = project.resolve("resources").apply { mkdirs() }
    resources.resolve("spin.json").writeText("""{"v":"5.7.0","layers":[]}""")

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(staleClasses, activeClasses),
          activeClassDirs = listOf(activeClasses),
          dependencyJars = emptyList(),
          sourceFiles = listOf(source),
          moduleName = ":app",
          variantName = "desktop",
          projectDirectory = project,
          failOnEmpty = false,
          resourceDirs = listOf(resources),
        )
      ) as PreviewDiscovery.Outcome.Success

    val warning = outcome.warnings.joinToString("\n")
    assertThat(warning).contains("active class outputs contain 0 .class files")
    assertThat(warning).contains(activeClasses.absolutePath)
    assertThat(warning).doesNotContain(staleClasses.absolutePath)
  }

  @Test
  fun `inactive preview sources do not flag an empty active compilation`() {
    val project = tempDir.newFolder("inactive-preview-source")
    val classes = project.resolve("classes/kotlin/desktop/main").apply { mkdirs() }
    val commonSource =
      project.resolve("src/commonMain/kotlin/Aliases.kt").apply {
        parentFile.mkdirs()
        writeText("typealias PreviewName = String")
      }
    val inactiveSource =
      project.resolve("src/androidMain/kotlin/AndroidPreview.kt").apply {
        parentFile.mkdirs()
        writeText("@NotificationPreview\nfun preview() = Unit")
      }
    val resources = project.resolve("resources").apply { mkdirs() }
    resources.resolve("spin.json").writeText("""{"v":"5.7.0","layers":[]}""")

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(classes),
          activeClassDirs = listOf(classes),
          dependencyJars = emptyList(),
          sourceFiles = listOf(commonSource, inactiveSource),
          activeSourceFiles = listOf(commonSource),
          moduleName = ":app",
          variantName = "desktop",
          projectDirectory = project,
          failOnEmpty = false,
          resourceDirs = listOf(resources),
        )
      ) as PreviewDiscovery.Outcome.Success

    assertThat(outcome.warnings.joinToString("\n")).doesNotContain("empty build-cache entry")
  }

  @Test
  fun `supported preview annotation names flag empty compiled outputs`() {
    val project = tempDir.newFolder("supported-preview-names")
    val classes = project.resolve("classes").apply { mkdirs() }
    val resources = project.resolve("resources").apply { mkdirs() }
    resources.resolve("spin.json").writeText("""{"v":"5.7.0","layers":[]}""")

    listOf("NotificationPreview", "XrSubspacePreview", "GlancePreview", "WearPreviewDevices")
      .forEach { annotation ->
        val source =
          project.resolve("src/main/kotlin/$annotation.kt").apply {
            parentFile.mkdirs()
            writeText("@$annotation\nfun preview() = Unit")
          }
        val outcome =
          PreviewDiscovery.discover(
            PreviewDiscovery.Input(
              classDirs = listOf(classes),
              dependencyJars = emptyList(),
              sourceFiles = listOf(source),
              moduleName = ":app",
              variantName = "desktop",
              projectDirectory = project,
              failOnEmpty = false,
              resourceDirs = listOf(resources),
            )
          ) as PreviewDiscovery.Outcome.Success

        assertThat(outcome.warnings.joinToString("\n"))
          .contains("active class outputs contain 0 .class files")
      }
  }
}
