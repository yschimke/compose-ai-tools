package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Coverage for direct SVG-asset discovery: a `.svg` file under a resource root becomes a
 * [PreviewKind.SVG] preview with no `@Preview` / consumer composable, sized from its `viewBox` or
 * explicit `width`/`height`, while files that merely end in `.svg` without an `<svg` root are
 * ignored. Sibling of [LottieAssetDiscoveryTest].
 */
class SvgAssetDiscoveryTest {

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
  fun `svg with viewBox is discovered with dimensions, non-svg is ignored`() {
    val res = tempDir.newFolder("resources")
    res.resolve("icons").mkdirs()
    res
      .resolve("icons/badge.svg")
      .writeText(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 120"><rect width="240" height="120"/></svg>"""
      )
    // A file that ends in `.svg` but carries no `<svg` root — must be skipped.
    res.resolve("not-really.svg").writeText("just some text, not markup")

    val previews = discover(res).previews
    assertThat(previews).hasSize(1)
    val p = previews.single()
    assertThat(p.params.kind).isEqualTo(PreviewKind.SVG)
    assertThat(p.params.assetPath).isEqualTo("icons/badge.svg")
    assertThat(p.params.widthDp).isEqualTo(240)
    assertThat(p.params.heightDp).isEqualTo(120)
    // Id + render output are filename-safe (no `:` / `/` to corrupt zip paths).
    assertThat(p.id).doesNotContain("/")
    assertThat(p.id).doesNotContain(":")
    assertThat(p.id).startsWith("svg__")
    // SVG is static — a single required still PNG, no animated companion (unlike Lottie).
    assertThat(p.captures).hasSize(1)
    val still = p.captures.single()
    assertThat(still.renderOutput.substringAfterLast('.')).isEqualTo("png")
    assertThat(still.optional).isFalse()
  }

  @Test
  fun `explicit width and height win over viewBox`() {
    val res = tempDir.newFolder("resources")
    res
      .resolve("logo.svg")
      .writeText(
        """<svg xmlns="http://www.w3.org/2000/svg" width="64px" height="48px" viewBox="0 0 240 120"/>"""
      )

    val p = discover(res).previews.single()
    assertThat(p.params.widthDp).isEqualTo(64)
    assertThat(p.params.heightDp).isEqualTo(48)
  }

  @Test
  fun `stroke-width on the root does not masquerade as an explicit width`() {
    val res = tempDir.newFolder("resources")
    // A common icon shape: no explicit width/height, a 24-unit viewBox, and a stroke-width. The
    // canvas must come from the viewBox (24x24), not the stroke (2).
    res
      .resolve("icon.svg")
      .writeText(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke-width="2"><path d="M4 12h16"/></svg>"""
      )

    val p = discover(res).previews.single()
    assertThat(p.params.widthDp).isEqualTo(24)
    assertThat(p.params.heightDp).isEqualTo(24)
  }

  @Test
  fun `svg with neither dimensions nor viewBox still discovers with null size`() {
    val res = tempDir.newFolder("resources")
    res.resolve("bare.svg").writeText("""<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>""")

    val p = discover(res).previews.single()
    assertThat(p.params.kind).isEqualTo(PreviewKind.SVG)
    assertThat(p.params.widthDp).isNull()
    assertThat(p.params.heightDp).isNull()
  }

  @Test
  fun `no resource dirs yields no svg previews`() {
    val res = tempDir.newFolder("empty")
    assertThat(discover(res).previews).isEmpty()
  }
}
