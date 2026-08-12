package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ExplodedSvg

/**
 * The `?exploded=1` lane on `/render/<id>.svg`: serve the layered `compose/figma-svg` export as an
 * **exploded axonometric view** — the same vector drawing tilted back and pulled apart into one
 * sheet per level of composable nesting, each labelled with the composables that drew it.
 *
 * It is a *post-process of bytes the SVG lane already produced*, exactly like `?mode=web`, which is
 * what makes it cheap and universally available: no second render, no new data product, no daemon
 * capability to negotiate. Every host that can answer `.svg` at all can answer this, including a
 * fully static catalog serving a baked `figma/<slug>.svg` off a delivery branch.
 *
 * Because the result is still one static SVG, the whole feature inherits the export lane's reach:
 * the viewer shows it in the same `<img>`, "Copy link"/"Download" hand it over unchanged, an agent
 * can `curl` it, and the visual-diff bot rasterizes it like any other picture. That is the reason
 * this is a server-side SVG rewrite rather than a WebGL scene in the viewer — a canvas would be
 * visible only to someone already looking at the page.
 */
internal object ServeExplodedSvg {

  /** The query parameters this lane reads. Everything else on the URL still means what it did. */
  const val PARAM_ENABLED = "exploded"
  private const val PARAM_TILT = "explodeTilt"
  private const val PARAM_SPIN = "explodeSpin"
  private const val PARAM_GAP = "explodeGap"
  private const val PARAM_DEPTH = "explodeDepth"
  private const val PARAM_LABELS = "explodeLabels"

  /** Every parameter name this lane owns — used to keep them out of unrelated request matching. */
  val PARAMS: Set<String> =
    setOf(PARAM_ENABLED, PARAM_TILT, PARAM_SPIN, PARAM_GAP, PARAM_DEPTH, PARAM_LABELS)

  /**
   * `?exploded=` as a boolean. A bare `?exploded` (no value) counts as on, because that is what a
   * hand-typed URL looks like; `0` / `false` / `off` / `no` turn it off so a bookmarked URL can
   * carry the axis explicitly in either state.
   */
  fun enabled(params: (String) -> String?): Boolean {
    val raw = params(PARAM_ENABLED) ?: return false
    return raw.isEmpty() || raw.lowercase() in setOf("1", "true", "on", "yes")
  }

  /**
   * The exploded-view options a request asks for.
   *
   * Out-of-range and unparseable values fall back to the default rather than 400ing: this is a
   * *view* axis on an export URL, not a render override, so a stale bookmark or a slider that
   * over-shoots should still produce a picture. ([ExplodedSvg] clamps the angles it is handed for
   * the same reason.) Depth is the exception that needs clamping here rather than there — its
   * constructor rejects an out-of-range value outright, since a caller passing 40 planes is asking
   * for 40 copies of the drawing.
   */
  fun optionsFrom(params: (String) -> String?): ExplodedSvg.Options {
    val defaults = ExplodedSvg.Options()
    return ExplodedSvg.Options(
      spinDeg = params(PARAM_SPIN)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: defaults.spinDeg,
      tiltDeg = params(PARAM_TILT)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: defaults.tiltDeg,
      gap = params(PARAM_GAP)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 },
      maxDepth =
        params(PARAM_DEPTH)?.toIntOrNull()?.coerceIn(1, ExplodedSvg.MAX_PLANES)
          ?: defaults.maxDepth,
      labels = params(PARAM_LABELS)?.lowercase() !in setOf("0", "false", "off", "no"),
    )
  }

  /**
   * Apply the lane to [svg] when the request asked for it. A no-op otherwise, and — because
   * [ExplodedSvg.render] returns its input for anything it can't project — never a failure mode of
   * its own: the worst case is the ordinary flat export.
   */
  fun applyTo(svg: String, params: (String) -> String?): String =
    if (!enabled(params)) svg else ExplodedSvg.render(svg, optionsFrom(params))
}
