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
    filmstrip: Boolean = true,
    filmstripFractions: List<Float> = DEFAULT_RESOURCE_FILMSTRIP_FRACTIONS,
  ): List<ResourcePreview> =
    ResourceDiscovery.discover(
      ResourceDiscovery.Config(
        resSourceRoots = listOf(File(temp.root, "res")),
        densities = densities,
        shapes = shapes,
        styles = styles,
        stretches = stretches,
        filmstrip = filmstrip,
        filmstripFractions = filmstripFractions,
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
    writeXml(
      "mipmap-anydpi-v26",
      "ic_launcher.xml",
      "<adaptive-icon><monochrome /></adaptive-icon>",
    )
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
    writeXml(
      "mipmap-anydpi-v26",
      "ic_launcher.xml",
      "<adaptive-icon><monochrome /></adaptive-icon>",
    )
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
  fun `adaptive icon without monochrome layer omits themed captures`() {
    // Plain background+foreground icon (the Android Studio template default) — no <monochrome>.
    writeXml(
      "mipmap-anydpi-v26",
      "ic_launcher.xml",
      "<adaptive-icon><background /><foreground /></adaptive-icon>",
    )
    val preview =
      discover(
          shapes = listOf(AdaptiveShape.CIRCLE),
          styles =
            listOf(
              AdaptiveStyle.FULL_COLOR,
              AdaptiveStyle.THEMED_LIGHT,
              AdaptiveStyle.THEMED_DARK,
              AdaptiveStyle.LEGACY,
            ),
        )
        .single()
    // Themed captures (which need a <monochrome> layer to render) are dropped; FULL_COLOR + LEGACY
    // stay because they render from background/foreground alone.
    assertThat(preview.captures.map { it.renderOutput })
      .containsExactly(
        "renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle.png",
        "renders/resources/mipmap/ic_launcher_xhdpi_LEGACY.png",
      )
      .inOrder()
    assertThat(preview.captures.map { it.variant?.style })
      .doesNotContain(AdaptiveStyle.THEMED_LIGHT)
    assertThat(preview.captures.map { it.variant?.style }).doesNotContain(AdaptiveStyle.THEMED_DARK)
  }

  @Test
  fun `adaptive icon with monochrome layer keeps themed captures`() {
    writeXml(
      "mipmap-anydpi-v26",
      "ic_launcher.xml",
      "<adaptive-icon><background /><foreground /><monochrome /></adaptive-icon>",
    )
    val preview =
      discover(
          shapes = listOf(AdaptiveShape.CIRCLE),
          styles = listOf(AdaptiveStyle.FULL_COLOR, AdaptiveStyle.THEMED_LIGHT),
        )
        .single()
    assertThat(preview.captures.map { it.renderOutput })
      .containsExactly(
        "renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle.png",
        "renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle_themed-light.png",
      )
      .inOrder()
  }

  @Test
  fun `animated vector emits gif renderOutput`() {
    writeXml("drawable", "avd_pulse.xml", "<animated-vector />")
    val preview = discover(filmstrip = false).single()
    assertThat(preview.type).isEqualTo(ResourceType.ANIMATED_VECTOR)
    assertThat(preview.captures.single().renderOutput)
      .isEqualTo("renders/resources/drawable/avd_pulse_xhdpi.gif")
    assertThat(preview.captures.single().cost).isEqualTo(RESOURCE_ANIMATED_COST)
  }

  @Test
  fun `animated vector filmstrip emits one extra capture per qualifier`() {
    writeXml("drawable", "avd_pulse.xml", "<animated-vector />")
    val preview = discover().single()
    assertThat(preview.captures.map { it.renderOutput })
      .containsExactly(
        "renders/resources/drawable/avd_pulse_xhdpi.gif",
        "renders/resources/drawable/avd_pulse_xhdpi_filmstrip.png",
      )
      .inOrder()
    val filmstripCapture = preview.captures[1]
    assertThat(filmstripCapture.variant?.filmstrip).isTrue()
    assertThat(filmstripCapture.cost).isEqualTo(RESOURCE_ANIMATED_FILMSTRIP_COST)
    assertThat(filmstripCapture.filmstripFractions)
      .containsExactly(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
      .inOrder()
  }

  @Test
  fun `animated vector filmstrip false suppresses the filmstrip capture`() {
    writeXml("drawable", "avd_pulse.xml", "<animated-vector />")
    val preview = discover(filmstrip = false).single()
    assertThat(preview.captures.map { it.renderOutput })
      .containsExactly("renders/resources/drawable/avd_pulse_xhdpi.gif")
    assertThat(preview.captures.single().variant?.filmstrip).isFalse()
    assertThat(preview.captures.single().filmstripFractions).isEmpty()
  }

  @Test
  fun `animated vector filmstripFractions controls cell count and fraction list`() {
    writeXml("drawable", "avd_pulse.xml", "<animated-vector />")
    val preview = discover(filmstripFractions = listOf(0.0f, 0.5f, 1.0f)).single()
    val filmstripCapture = preview.captures.single { it.variant?.filmstrip == true }
    assertThat(filmstripCapture.filmstripFractions).containsExactly(0.0f, 0.5f, 1.0f).inOrder()
  }

  @Test
  fun `animated vector filmstrip fans out across qualifiers`() {
    writeXml("drawable", "avd_pulse.xml", "<animated-vector />")
    writeXml("drawable-night", "avd_pulse.xml", "<animated-vector />")
    val preview = discover().single()
    assertThat(preview.captures.map { it.renderOutput })
      .containsExactly(
        "renders/resources/drawable/avd_pulse_xhdpi.gif",
        "renders/resources/drawable/avd_pulse_xhdpi_filmstrip.png",
        "renders/resources/drawable/avd_pulse_night-xhdpi.gif",
        "renders/resources/drawable/avd_pulse_night-xhdpi_filmstrip.png",
      )
      .inOrder()
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
      )
    assertThat(out).hasSize(1)
    assertThat(out.single().variant?.qualifiers).isEqualTo("xhdpi")
  }
}
