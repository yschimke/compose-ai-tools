package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.*
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

  /**
   * Discovery looks at file extensions, not at PNG chunks — an empty file is fine for testing the
   * walk + capture fan-out. Real `.9.png` rendering is covered by the Robolectric renderer test.
   */
  private fun writeBinary(dir: String, name: String, content: ByteArray = ByteArray(0)): File {
    val resDir = File(temp.root, "res")
    val target = File(resDir, dir)
    target.mkdirs()
    val file = File(target, name)
    file.writeBytes(content)
    return file
  }

  private fun discover(
    densities: List<String> = listOf("xhdpi"),
    shapes: List<AdaptiveShape> = listOf(AdaptiveShape.CIRCLE, AdaptiveShape.SQUARE),
    styles: List<AdaptiveStyle> = listOf(AdaptiveStyle.FULL_COLOR),
    stretches: List<NinePatchStretch> = NinePatchStretch.entries.toList(),
    contactSheet: Boolean = false,
  ): List<ResourcePreview> =
    ResourceDiscovery.discover(
      ResourceDiscovery.Config(
        resSourceRoots = listOf(File(temp.root, "res")),
        densities = densities,
        shapes = shapes,
        styles = styles,
        stretches = stretches,
        contactSheet = contactSheet,
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
  fun `adaptive icon fans out shape x style and emits a single LEGACY capture`() {
    writeXml("mipmap-anydpi-v26", "ic_launcher.xml", "<adaptive-icon />")
    val preview =
      discover(
          shapes = listOf(AdaptiveShape.CIRCLE),
          styles =
            listOf(AdaptiveStyle.FULL_COLOR, AdaptiveStyle.THEMED_LIGHT, AdaptiveStyle.LEGACY),
        )
        .single()
    assertThat(preview.id).isEqualTo("mipmap/ic_launcher")
    assertThat(preview.type).isEqualTo(ResourceType.ADAPTIVE_ICON)
    assertThat(preview.captures.map { it.renderOutput })
      .containsExactly(
        "renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle.png",
        "renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle_themed-light.png",
        "renders/resources/mipmap/ic_launcher_xhdpi_LEGACY.png",
      )
      .inOrder()
    assertThat(preview.captures.last().variant?.shape).isNull()
    assertThat(preview.captures.last().variant?.style).isEqualTo(AdaptiveStyle.LEGACY)
    assertThat(preview.captures.map { it.cost })
      .containsExactly(RESOURCE_ADAPTIVE_COST, RESOURCE_ADAPTIVE_COST, RESOURCE_ADAPTIVE_COST)
  }

  @Test
  fun `adaptive icon LEGACY appears once per qualifier regardless of shape count`() {
    writeXml("mipmap-anydpi-v26", "ic_launcher.xml", "<adaptive-icon />")
    val preview =
      discover(
          shapes =
            listOf(AdaptiveShape.CIRCLE, AdaptiveShape.SQUIRCLE, AdaptiveShape.ROUNDED_SQUARE),
          styles = listOf(AdaptiveStyle.FULL_COLOR, AdaptiveStyle.LEGACY),
        )
        .single()
    val legacyCaptures = preview.captures.filter { it.variant?.style == AdaptiveStyle.LEGACY }
    assertThat(legacyCaptures).hasSize(1)
  }

  @Test
  fun `themed styles produce themed-light and themed-dark filename suffixes`() {
    writeXml("mipmap-anydpi-v26", "ic_launcher.xml", "<adaptive-icon />")
    val preview =
      discover(
          shapes = listOf(AdaptiveShape.SQUIRCLE),
          styles = listOf(AdaptiveStyle.THEMED_LIGHT, AdaptiveStyle.THEMED_DARK),
        )
        .single()
    assertThat(preview.captures.map { it.renderOutput })
      .containsExactly(
        "renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_squircle_themed-light.png",
        "renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_squircle_themed-dark.png",
      )
      .inOrder()
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
        style = null,
        extension = "png",
      )
    assertThat(path).isEqualTo("renders/resources/drawable/foo_bar_night-xhdpi.png")
  }

  @Test
  fun `nine-patch with default qualifier produces one capture per stretch`() {
    writeBinary("drawable", "bg_button.9.png")
    val preview = discover().single()
    assertThat(preview.id).isEqualTo("drawable/bg_button")
    assertThat(preview.type).isEqualTo(ResourceType.NINE_PATCH)
    assertThat(preview.captures.map { it.variant?.stretch })
      .containsExactly(
        NinePatchStretch.INTRINSIC,
        NinePatchStretch.HORIZONTAL,
        NinePatchStretch.VERTICAL,
        NinePatchStretch.BOTH,
      )
      .inOrder()
    assertThat(preview.captures.map { it.renderOutput })
      .containsExactly(
        "renders/resources/drawable/bg_button_xhdpi_STRETCH_intrinsic.png",
        "renders/resources/drawable/bg_button_xhdpi_STRETCH_horizontal.png",
        "renders/resources/drawable/bg_button_xhdpi_STRETCH_vertical.png",
        "renders/resources/drawable/bg_button_xhdpi_STRETCH_both.png",
      )
      .inOrder()
    assertThat(preview.captures.map { it.cost })
      .containsExactly(
        RESOURCE_NINE_PATCH_COST,
        RESOURCE_NINE_PATCH_COST,
        RESOURCE_NINE_PATCH_COST,
        RESOURCE_NINE_PATCH_COST,
      )
  }

  @Test
  fun `nine-patch source file recorded with module-relative path`() {
    writeBinary("drawable", "bg_button.9.png")
    val preview = discover().single()
    assertThat(preview.sourceFiles).containsExactly("", "res/drawable/bg_button.9.png")
  }

  @Test
  fun `nine-patch stretches list trims fan-out`() {
    writeBinary("drawable", "bg_button.9.png")
    val preview =
      discover(stretches = listOf(NinePatchStretch.INTRINSIC, NinePatchStretch.BOTH)).single()
    assertThat(preview.captures.map { it.variant?.stretch })
      .containsExactly(NinePatchStretch.INTRINSIC, NinePatchStretch.BOTH)
      .inOrder()
  }

  @Test
  fun `nine-patch fan-out multiplies with density and qualifier`() {
    writeBinary("drawable", "bg_button.9.png")
    writeBinary("drawable-night", "bg_button.9.png")
    val preview =
      discover(
          densities = listOf("mdpi", "xhdpi"),
          stretches = listOf(NinePatchStretch.INTRINSIC, NinePatchStretch.HORIZONTAL),
        )
        .single()
    // 2 qualifiers (default, night) × 2 densities × 2 stretches = 8 captures.
    assertThat(preview.captures).hasSize(8)
    assertThat(preview.captures.map { it.variant?.qualifiers }.distinct())
      .containsExactly("mdpi", "xhdpi", "night-mdpi", "night-xhdpi")
      .inOrder()
  }

  @Test
  fun `plain png files are ignored - only 9-patch raster is in scope`() {
    writeBinary("drawable", "photo.png")
    writeBinary("drawable", "bg_button.9.png")
    assertThat(discover().map { it.id }).containsExactly("drawable/bg_button")
  }

  @Test
  fun `nine-patch and vector with different names coexist`() {
    writeBinary("drawable", "bg_button.9.png")
    writeXml("drawable", "ic_foo.xml", "<vector />")
    val previews = discover().sortedBy { it.id }
    assertThat(previews.map { it.id }).containsExactly("drawable/bg_button", "drawable/ic_foo")
    assertThat(previews.map { it.type })
      .containsExactly(ResourceType.NINE_PATCH, ResourceType.VECTOR)
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
        contactSheet = false,
      )
    assertThat(out).hasSize(1)
    assertThat(out.single().variant?.qualifiers).isEqualTo("xhdpi")
  }

  @Test
  fun `vector contactSheet emits one extra capture per source qualifier`() {
    writeXml("drawable", "ic_foo.xml", "<vector />")
    writeXml("drawable-night", "ic_foo.xml", "<vector />")
    val preview =
      discover(densities = listOf("mdpi", "xhdpi", "xxxhdpi"), contactSheet = true).single()
    // 2 source qualifiers × 3 densities = 6 per-density captures + 2 contact-sheet captures.
    assertThat(preview.captures).hasSize(8)
    val contactSheets = preview.captures.filter { it.variant?.contactSheet == true }
    assertThat(contactSheets).hasSize(2)
    assertThat(contactSheets.map { it.renderOutput })
      .containsExactly(
        "renders/resources/drawable/ic_foo_contact-sheet.png",
        "renders/resources/drawable/ic_foo_night_contact-sheet.png",
      )
      .inOrder()
    assertThat(contactSheets.map { it.contactSheetDensities })
      .containsExactly(listOf("mdpi", "xhdpi", "xxxhdpi"), listOf("mdpi", "xhdpi", "xxxhdpi"))
    // Cost scales linearly with the density count — three cells per sheet.
    assertThat(contactSheets.map { it.cost })
      .containsExactly(
        RESOURCE_CONTACT_SHEET_COST_PER_CELL * 3,
        RESOURCE_CONTACT_SHEET_COST_PER_CELL * 3,
      )
  }

  @Test
  fun `vector contactSheet=false suppresses the extra capture`() {
    writeXml("drawable", "ic_foo.xml", "<vector />")
    val preview =
      discover(densities = listOf("mdpi", "xhdpi", "xxxhdpi"), contactSheet = false).single()
    // No contact-sheet capture — only the three per-density vectors.
    assertThat(preview.captures).hasSize(3)
    assertThat(preview.captures.map { it.variant?.contactSheet })
      .containsExactly(false, false, false)
  }

  @Test
  fun `vector contactSheet skipped when fewer than 2 densities`() {
    writeXml("drawable", "ic_foo.xml", "<vector />")
    val preview = discover(densities = listOf("xhdpi"), contactSheet = true).single()
    // Single density → contact sheet would be one cell, which is silly. Skipped.
    assertThat(preview.captures).hasSize(1)
    assertThat(preview.captures.single().variant?.contactSheet).isFalse()
  }

  @Test
  fun `contactSheet only fires for VECTOR resources`() {
    writeXml("drawable", "ic_foo.xml", "<vector />")
    writeXml("drawable", "avd_pulse.xml", "<animated-vector />")
    writeBinary("drawable", "bg_button.9.png")
    writeXml("mipmap-anydpi-v26", "ic_launcher.xml", "<adaptive-icon />")
    val previews =
      discover(densities = listOf("mdpi", "xhdpi"), contactSheet = true).associateBy { it.id }
    assertThat(previews["drawable/ic_foo"]!!.captures.count { it.variant?.contactSheet == true })
      .isEqualTo(1)
    assertThat(previews["drawable/avd_pulse"]!!.captures.count { it.variant?.contactSheet == true })
      .isEqualTo(0)
    assertThat(previews["drawable/bg_button"]!!.captures.count { it.variant?.contactSheet == true })
      .isEqualTo(0)
    assertThat(previews["mipmap/ic_launcher"]!!.captures.count { it.variant?.contactSheet == true })
      .isEqualTo(0)
  }
}
