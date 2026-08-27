package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ExplodedSvg
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden generator + drift guard for the exploded 3D view's *picture*.
 *
 * [ExplodedSvgTest][ee.schimke.composeai.data.layoutinspector] asserts the structure — which
 * element lands on which plane, which resources survive. This asserts the **rendering**, and does
 * it the way the repo covers every other visual surface: by committing an artefact the
 * preview-harness screenshots and the `vscode-preview-diff` bot diffs on every PR.
 *
 * The chain is:
 * 1. [LAYERED] — a committed layered SVG shaped exactly like a real `compose/figma-svg` export (a
 *    `<g id="…">` per composable, nested as the composables nest, inside a device clip).
 * 2. This test — runs the **production** [ExplodedSvg] over it and commits the projection as
 *    [EXPLODED].
 * 3. `pages-snapshot.spec.mjs` — serves [EXPLODED] as the `?exploded=1` render lane's stub, so the
 *    `serve-viewer-exploded` fixture's screenshot contains the real exploded drawing rather than a
 *    hand-drawn stand-in.
 *
 * The point of step 2 being a *committed* file rather than something the harness computes: a change
 * to the camera, the sheet split, the plate outlines or the labels shows up here as a reviewable
 * text diff **and** downstream as moved pixels, and neither can happen without the other. Nobody
 * has to remember to capture the exploded view — it is captured because this file exists.
 *
 * Regenerate with:
 * ```
 * UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ExplodedSvgFixtureTest*'
 * ```
 *
 * (The same env var as [ServeWebFixtureTest], deliberately: both write into the same fixtures
 * directory, and a viewer change usually moves both.)
 */
class ExplodedSvgFixtureTest {

  private companion object {
    const val PAGES = "preview-server/preview-harness/fixtures/pages"
    const val LAYERED = "_render-placeholder-layered.svg"
    const val EXPLODED = "_render-placeholder-exploded.svg"
    const val EVIDENCE = "renders/exploded-view"
  }

  @Test
  fun `the exploded placeholder is in sync with the production renderer`() {
    val pages = File(repoRoot(), PAGES)
    val layered = File(pages, LAYERED)
    assertTrue(layered.isFile, "missing $LAYERED — the exploded lane's committed input")
    val exploded = File(pages, EXPLODED)

    val rendered = ExplodedSvg.render(layered.readText()) + "\n"
    val update =
      System.getenv("UPDATE_SERVE_WEB_FIXTURES") == "true" ||
        System.getProperty("updateServeWebFixtures") == "true"
    if (update) {
      exploded.writeText(rendered)
      return
    }
    assertTrue(
      exploded.isFile,
      "missing $EXPLODED — regenerate with UPDATE_SERVE_WEB_FIXTURES=true",
    )
    assertEquals(
      exploded.readText(),
      rendered,
      "$EXPLODED is stale. If the exploded view changed on purpose, regenerate with " +
        "UPDATE_SERVE_WEB_FIXTURES=true and review the harness screenshot that moves with it.",
    )
  }

  /**
   * The second golden, and the one drawn from **real** data: `renders/material-icon-refs/`'s
   * `compose-figma.svg` is a genuine export from a Robolectric render of `MaterialIconRowPreview`,
   * committed as this repo's evidence for the Material-icon reference path. Exploding it here
   * proves the split survives what a real export actually contains — a `<defs>` of hoisted icon
   * geometry that every placement `<use>`s, `ReusableComposeNode` layer ids the labels have to see
   * through, and a component row rather than a full screen.
   *
   * It is deliberately a *different shape* of input from the phone-screen placeholder: a 160×48
   * strip, where the auto-derived separation and the label gutter are both proportioned off a wide,
   * short drawing instead of a tall one.
   */
  @Test
  fun `the real material-icon export explodes, and its committed picture is in sync`() {
    val source = File(repoRoot(), "renders/material-icon-refs/compose-figma.svg")
    assertTrue(source.isFile, "missing the committed material-icon export")
    val out = File(repoRoot(), "$EVIDENCE/material-icon-row.exploded.svg")

    val rendered = ExplodedSvg.render(source.readText()) + "\n"
    // The hoisted icon `<defs>` must ride along exactly once and still resolve: the placements are
    // `<use href="#material-icon-…">`, so losing the defs would silently blank every icon.
    assertTrue(rendered.contains("id=\"material-icon-materialicons-menu\""), "kept the icon defs")
    assertTrue(rendered.contains("href=\"#material-icon-materialicons-menu\""), "kept the uses")
    // `ReusableComposeNode` names no composable; the icon annotation is what the label falls back
    // to, so a real export's plane reads "menu icon · account_circle icon" rather than the same
    // placeholder name four times.
    assertTrue(rendered.contains("menu icon"), "labels see through the fallback layer id")

    val update =
      System.getenv("UPDATE_SERVE_WEB_FIXTURES") == "true" ||
        System.getProperty("updateServeWebFixtures") == "true"
    if (update) {
      out.parentFile.mkdirs()
      out.writeText(rendered)
      return
    }
    assertTrue(out.isFile, "missing ${out.name} — regenerate with UPDATE_SERVE_WEB_FIXTURES=true")
    assertEquals(out.readText(), rendered, "${out.name} is stale — see $EVIDENCE/README.md")
  }

  /**
   * The default the *server* hands [ExplodedSvg] for a bare `?exploded=1` must be the same one this
   * golden is drawn with, or the committed picture stops describing what a visitor sees. Asserted
   * rather than assumed, because the two live in different modules.
   */
  @Test
  fun `a bare exploded request uses the same options as the golden`() {
    val none = { _: String -> null }
    assertEquals(ExplodedSvg.Options(), ServeExplodedSvg.optionsFrom(none))
  }

  /**
   * The drawer's sliders start at `ExplodedSvg`'s own defaults — that is what lets the viewer JS
   * omit an untouched axis from the URL and reset it on a Back that drops the param. They are
   * hand-written HTML in one module and Kotlin defaults in another, so pin them together here.
   */
  @Test
  fun `the drawer sliders default to the renderer's own camera`() {
    val page =
      ServeWeb.viewerPage(
        ServePreview("com.example.ProfileCardPreview", "Profile card"),
        "t",
        hasSvgExport = true,
      )
    val defaults = ExplodedSvg.Options()
    fun sliderDefault(id: String): String =
      Regex("id=\"cp-explode-$id\"[\\s\\S]*?data-cp-default=\"([^\"]*)\"")
        .find(page)
        ?.groupValues
        ?.get(1) ?: error("no cp-explode-$id slider in the viewer")
    assertEquals(defaults.tiltDeg, sliderDefault("tilt").toDouble())
    assertEquals(defaults.spinDeg, sliderDefault("spin").toDouble())
    assertEquals(defaults.maxDepth, sliderDefault("depth").toInt())
    // Separation has no fixed default — 0 on the slider means "derive one from the preview's size",
    // which is what a null `gap` asks the renderer for.
    assertEquals(0.0, sliderDefault("gap").toDouble())
    assertEquals(null, defaults.gap)
  }

  private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("could not locate repo root from ${System.getProperty("user.dir")}")
  }
}
