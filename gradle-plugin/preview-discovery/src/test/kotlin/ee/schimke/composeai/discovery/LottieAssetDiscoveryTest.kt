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
    // companion GIF spanning the asset's intrinsic timeline.
    assertThat(p.captures.map { it.renderOutput.substringAfterLast('.') })
      .containsExactly("png", "gif")
    val still = p.captures.first { it.renderOutput.endsWith(".png") }
    val animated = p.captures.first { it.renderOutput.endsWith(".gif") }
    // The still PNG is the required baseline; the GIF is best-effort so a headless env that can't
    // encode it never fails the required-render gate.
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
}
