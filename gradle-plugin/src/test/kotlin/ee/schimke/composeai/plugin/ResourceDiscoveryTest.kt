package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceDiscoveryTest {

  @get:Rule val temp = TemporaryFolder()

  private fun writeXml(dir: String, name: String, content: String): File {
    val resDir = File(temp.root, "res")
    val target = File(resDir, dir)
    target.mkdirs()
    val file = File(target, name)
    file.writeText(content)
    return file
  }

  private fun discover(
    densities: List<String> = listOf("xhdpi"),
    shapes: List<AdaptiveShape> = listOf(AdaptiveShape.CIRCLE, AdaptiveShape.SQUARE),
  ): List<ResourcePreview> =
    ResourceDiscovery.discover(
      ResourceDiscovery.Config(
        resSourceRoots = listOf(File(temp.root, "res")),
        densities = densities,
        shapes = shapes,
        sourceRootRelativePath = { "res" },
      )
    )

  @Test
  fun `vector with default qualifier produces one capture per density`() {
    writeXml("drawable", "ic_foo.xml", "<vector />")
    val resources = discover(densities = listOf("mdpi", "xhdpi"))
    assertThat(resources).hasSize(1)
    val preview = resources.single()
    assertThat(preview.id).isEqualTo("drawable/ic_foo")
    assertThat(preview.type).isEqualTo(ResourceType.VECTOR)
    assertThat(preview.captures.map { it.variant?.qualifiers })
      .containsExactly("mdpi", "xhdpi")
      .inOrder()
    assertThat(preview.captures.map { it.renderOutput })
      .containsExactly(
        "renders/resources/drawable/ic_foo_mdpi.png",
        "renders/resources/drawable/ic_foo_xhdpi.png",
      )
  }

  @Test
  fun `night source qualifier emits a separate capture combined with density`() {
    writeXml("drawable", "ic_foo.xml", "<vector />")
    writeXml("drawable-night", "ic_foo.xml", "<vector />")
    val preview = discover().single()
    assertThat(preview.sourceFiles.keys).containsExactly("", "night")
    assertThat(preview.captures.map { it.variant?.qualifiers })
      .containsExactly("xhdpi", "night-xhdpi")
      .inOrder()
  }

  @Test
  fun `adaptive icon fans out across shapes for each density`() {
    writeXml("mipmap-anydpi-v26", "ic_launcher.xml", "<adaptive-icon />")
    val preview = discover(shapes = listOf(AdaptiveShape.CIRCLE, AdaptiveShape.LEGACY)).single()
    assertThat(preview.id).isEqualTo("mipmap/ic_launcher")
    assertThat(preview.type).isEqualTo(ResourceType.ADAPTIVE_ICON)
    assertThat(preview.captures.map { it.renderOutput })
      .containsExactly(
        "renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle.png",
        "renders/resources/mipmap/ic_launcher_xhdpi_LEGACY.png",
      )
      .inOrder()
    assertThat(preview.captures.map { it.cost })
      .containsExactly(RESOURCE_ADAPTIVE_COST, RESOURCE_ADAPTIVE_COST)
  }

  @Test
  fun `animated vector emits gif renderOutput`() {
    writeXml("drawable", "avd_pulse.xml", "<animated-vector />")
    val preview = discover().single()
    assertThat(preview.type).isEqualTo(ResourceType.ANIMATED_VECTOR)
    assertThat(preview.captures.single().renderOutput)
      .isEqualTo("renders/resources/drawable/avd_pulse_xhdpi.gif")
    assertThat(preview.captures.single().cost).isEqualTo(RESOURCE_ANIMATED_COST)
  }

  @Test
  fun `unrecognised root tags are dropped`() {
    writeXml("drawable", "shape_bg.xml", "<shape />")
    writeXml("drawable", "selector_bg.xml", "<selector />")
    writeXml("drawable", "ic_real.xml", "<vector />")
    assertThat(discover().map { it.id }).containsExactly("drawable/ic_real")
  }

  @Test
  fun `non-resource directories are ignored`() {
    writeXml("values", "strings.xml", "<resources />")
    writeXml("layout", "main.xml", "<LinearLayout xmlns:android='x' />")
    writeXml("drawable", "ic_foo.xml", "<vector />")
    assertThat(discover().map { it.id }).containsExactly("drawable/ic_foo")
  }

  @Test
  fun `source files are recorded with module-relative paths`() {
    writeXml("drawable", "ic_foo.xml", "<vector />")
    writeXml("drawable-night", "ic_foo.xml", "<vector />")
    val preview = discover().single()
    assertThat(preview.sourceFiles)
      .containsExactly("", "res/drawable/ic_foo.xml", "night", "res/drawable-night/ic_foo.xml")
  }

  @Test
  fun `renderOutputPath sanitises non-whitelist characters`() {
    val path =
      ResourceDiscovery.renderOutputPath(
        resourceId = "drawable/foo bar",
        qualifier = "night-xhdpi",
        shape = null,
        extension = "png",
      )
    assertThat(path).isEqualTo("renders/resources/drawable/foo_bar_night-xhdpi.png")
  }

  @Test
  fun `captures helper handles empty qualifier set as default`() {
    val out =
      ResourceDiscovery.captures(
        type = ResourceType.VECTOR,
        qualifierSuffixes = emptySet(),
        densities = listOf("xhdpi"),
        shapes = emptyList(),
        resourceId = "drawable/x",
      )
    assertThat(out).hasSize(1)
    assertThat(out.single().variant?.qualifiers).isEqualTo("xhdpi")
  }
}
