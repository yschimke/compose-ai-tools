package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.overrides.OverrideVariantInteraction
import ee.schimke.composeai.data.overrides.OverrideVariantSpec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The `compose/figma-svg` export's Material interaction states, run against the **forward** Compose
 * Multiplatform runtime — the material3 line the catalogs actually render on.
 *
 * This test is deliberately not on the ordinary `test` task: it asserts behaviour that only exists
 * once material3 ships its forked ripple node, and `forwardComposeInteractionExportTest` is the
 * only task that puts that runtime under it. Read `build.gradle.kts` for the configuration, and
 * `:renderer-desktop`'s `forwardComposeSystemThemeTest` for the pattern it copies.
 *
 * ### Why it exists
 *
 * Material's ripple node has been forked twice — `androidx.compose.material.ripple.RippleNode` is
 * the original, material3 1.5.0-alpha took a copy into `androidx.compose.material3.ripple` and CMP
 * material3 1.12 took another into `androidx.compose.material3.internal.ripple`. The export matched
 * the first name only, so on every catalog running the newer lines it recognised no ripple node at
 * all and silently dropped every interaction state: the focus ring, the focus/hover state layer and
 * the press ripple alike. A `focus-ring` sticker exported byte-identical to its resting one
 * (issue #4980).
 *
 * Nothing went red, because the whole repository renders at CMP 1.11 — the line that still uses the
 * original node, where the old match was correct. [OverrideIntegrationTest] covers exactly this
 * surface and stayed green throughout. That is the gap this closes: the same seam, one runtime
 * forward.
 */
class ForwardMaterialInteractionExportTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  /**
   * The forward runtime has to actually *be* forward, or every assertion below is vacuous.
   *
   * A pin that quietly resolved back to the production line would leave this class asserting the
   * pre-fork behaviour and passing — the precise failure mode the task exists to prevent. So the
   * fixture's reflective lookup is checked first, and separately, with its own message.
   */
  @Test
  fun theForwardRuntimeCarriesMaterialsInsetFocusRingApi() {
    assertNotNull(
      "material3 on this runtime has no LocalRippleThemeConfiguration / " +
        "RippleDefaults.InsetFocusRing…ThemeConfiguration, so it is not the forked-ripple line " +
        "this task exists to cover. Check `composeForwardTestRuntime` in build.gradle.kts — this " +
        "test is meaningless on the production pin and must not be run there.",
      insetFocusRing,
    )
    val forkedRippleNode =
      sequenceOf(
          "androidx.compose.material3.internal.ripple.RippleNode",
          "androidx.compose.material3.ripple.RippleNode",
        )
        .firstOrNull { runCatching { Class.forName(it) }.isSuccess }
    assertNotNull(
      "no forked ripple node on the classpath — material3 here still delegates to " +
        "`androidx.compose.material.ripple`, which the export always matched. Whatever this task " +
        "is running, it is not the line the regression lived on.",
      forkedRippleNode,
    )
  }

  /**
   * A focused button under Material's inset focus ring exports the ring as editable stroked layers.
   *
   * Two bands, not one: the ring is an outer `secondary` stroke on the component's own edge over an
   * inner `onSecondary` stroke just inside it, which is what leaves the gap between the ring and
   * the container. Both are asserted, because exporting one of them is what a half-applied fix
   * looks like.
   */
  @Test
  fun focusedButtonExportsMaterialsInsetFocusRing() {
    val outputDir = tempFolder.newFolder("renders-forward-inset-focus-ring")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val baseId = "InsetFocusRingButton_Light"
    val focusedId = "InsetFocusRingButton_Light_VARIANT_focused"
    val host = router("InsetFocusRingButtonInteractionState", baseId, focusedId)
    host.start()
    try {
      for (id in listOf(baseId, focusedId)) {
        host.submit(RenderRequest.Render(payload = "previewId=$id"), timeoutMs = 60_000)
      }
      val dataDir = outputDir.parentFile!!.resolve("data")
      fun svg(id: String) = dataDir.resolve(id).resolve("compose-figma.svg").readText()
      val focused = svg(focusedId)
      // The render dir is a `TemporaryFolder`, so keep the export where it can be looked at: the
      // same courtesy the `FigmaSvg*RenderTest`s do, and what the before/after in
      // `docs/evidence/material-focus-ring-svg/` was captured from.
      File("build/forward-inset-focus-ring").mkdirs()
      File("build/forward-inset-focus-ring/focused.svg").writeText(focused)

      assertNotEquals(
        "the focused export must not be the resting one — that is issue #4980 exactly",
        svg(baseId),
        focused,
      )
      assertEquals(
        "the ring exports as its two concentric bands:\n$focused",
        2,
        Regex("""id="Material Focus Ring"""").findAll(focused).count(),
      )
      assertTrue(
        "the outer band is a `secondary` stroke, not a fill:\n$focused",
        focused.contains("""stroke="#625B71""""),
      )
      assertTrue(
        "the inner band is an `onSecondary` stroke — the gap between ring and container:\n$focused",
        focused.contains("""stroke="#FFFFFF""""),
      )
      assertTrue(
        "the export keeps the button's own editable container fill under the ring:\n$focused",
        focused.contains("#6750A4"),
      )
      assertTrue(
        "the ring paints after the content it surrounds",
        focused.indexOf("Material Focus Ring") > focused.indexOf("#6750A4"),
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * The other half of the same regression, and the reason this is not a focus-ring-only test.
   *
   * The state layer and the press ripple were read off the same unrecognised node, so they vanished
   * from the export on the forked line too — with no ring drawn to make it obvious. This is
   * [OverrideIntegrationTest.materialButtonInteractionVariantsExportTheStateTheyName]'s assertion,
   * one runtime forward.
   */
  @Test
  fun opacityFocusAndPressStillExportOnTheForkedRippleNode() {
    val outputDir = tempFolder.newFolder("renders-forward-opacity-interactions")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val baseId = "MaterialButton_Light"
    val focusedId = "MaterialButton_Light_VARIANT_focused"
    val pressedId = "MaterialButton_Light_VARIANT_pressed"
    val host = router("MaterialButtonInteractionState", baseId, focusedId, pressedId)
    host.start()
    try {
      for (id in listOf(baseId, focusedId, pressedId)) {
        host.submit(RenderRequest.Render(payload = "previewId=$id"), timeoutMs = 60_000)
      }
      val dataDir = outputDir.parentFile!!.resolve("data")
      fun svg(id: String) = dataDir.resolve(id).resolve("compose-figma.svg").readText()

      assertTrue(
        "focused SVG emits the state layer after content:\n${svg(focusedId)}",
        svg(focusedId).contains("""id="Material State Layer""""),
      )
      assertTrue(
        "pressed SVG emits the press ripple after content:\n${svg(pressedId)}",
        svg(pressedId).contains("""id="Material Press Ripple""""),
      )
      assertNotEquals("focused must not stay resting", svg(baseId), svg(focusedId))
      assertNotEquals("pressed must not stay resting", svg(baseId), svg(pressedId))
    } finally {
      host.shutdown()
    }
  }

  /**
   * A router over [function], with the first id resting and the rest named for the interaction
   * their `_VARIANT_<name>` suffix carries — the shape the gradle plugin's manifest produces.
   */
  private fun router(function: String, vararg ids: String): PreviewManifestRouter {
    fun entry(id: String) =
      PreviewManifestEntry(
        id = id,
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = function,
        widthPx = 96,
        heightPx = 48,
        density = 1.0f,
        outputBaseName = id,
        overrides =
          id
            .substringAfter("_VARIANT_", "")
            .takeIf { it.isNotEmpty() }
            ?.let { name ->
              OverrideVariantSpec(
                name = name,
                interaction =
                  OverrideVariantInteraction.entries.first {
                    it.name.equals(name, ignoreCase = true)
                  },
              )
            },
      )
    return PreviewManifestRouter(
      manifest = PreviewManifest(previews = ids.map(::entry)),
      engine =
        RenderEngine(
          previewOverrideExtensions =
            PreviewOverrideExtensions(listOf(FocusPreviewOverrideExtension()))
        ),
    )
  }
}
