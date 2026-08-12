package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the `--id` / `--filter` → `-PcomposePreview.idFilter` narrowing (issue #3730).
 *
 * The bug this pins: `compose-preview show --filter FontScale200Preview` on a 64-preview module
 * drove `composePreviewRenderAll` at full width and threw the other 63 rows away afterwards — 317s
 * of rendering for a 3s request, which also overran the CLI's own timeout and made the single
 * -preview render *fail*. The plugin has honoured the property on both backends since #2977.
 */
class PreviewRenderScopeTest {

  private fun module(path: String): PreviewModule =
    PreviewModule(path, File("/tmp/compose-preview-test/${path.replace(':', '/')}"))

  private fun manifest(
    module: PreviewModule,
    vararg ids: String,
  ): Pair<PreviewModule, PreviewManifest> =
    module to
      PreviewManifest(
        module = module.gradlePath,
        variant = "debug",
        previews =
          ids.map { id ->
            PreviewInfo(
              id = id,
              functionName = id.substringAfterLast('.'),
              className = "com.example.PreviewsKt",
              params = PreviewParams(kind = "COMPOSE"),
            )
          },
      )

  @Test
  fun `no request renders everything`() {
    val app = module("app")
    val scope =
      PreviewRenderScope.forRequest(
        manifests = listOf(manifest(app, "HomePreview", "SettingsPreview")),
        exactId = null,
        filter = null,
      )

    assertEquals(emptyList(), scope.gradleArgs)
    assertNull(scope.renderedIds)
  }

  @Test
  fun `exact id forwards a single anchored pattern`() {
    val app = module("app")
    val scope =
      PreviewRenderScope.forRequest(
        manifests = listOf(manifest(app, "HomePreview", "SettingsPreview", "HomePreview_Dark")),
        exactId = "HomePreview",
        filter = null,
      )

    // Anchored: an unanchored `HomePreview` would also select `HomePreview_Dark`, re-widening the
    // render this exists to narrow.
    assertEquals(listOf("-PcomposePreview.idFilter==HomePreview"), scope.gradleArgs)
    assertEquals(setOf("HomePreview"), scope.renderedIds)
  }

  @Test
  fun `case-insensitive filter is resolved to exact ids the plugin can match`() {
    val app = module("app")
    val scope =
      PreviewRenderScope.forRequest(
        manifests = listOf(manifest(app, "HomeScreenPreview", "SettingsPreview")),
        exactId = null,
        filter = "homescreen",
      )

    // The CLI's `--filter` is case-INSENSITIVE; `composePreview.idFilter` is case-sensitive.
    // Forwarding the pattern verbatim would render nothing and report a missing PNG, so the CLI
    // resolves the request against the manifest it already read and forwards the id it found.
    assertEquals(listOf("-PcomposePreview.idFilter==HomeScreenPreview"), scope.gradleArgs)
  }

  @Test
  fun `a request that selects everything is not forwarded`() {
    val app = module("app")
    val scope =
      PreviewRenderScope.forRequest(
        manifests = listOf(manifest(app, "HomePreview", "HomeSettingsPreview")),
        exactId = null,
        filter = "Home",
      )

    // Nothing to save, and a filtered `composePreviewRender` is deliberately not build-cacheable
    // (`RenderPreviewsTask.outputs.cacheIf`) — so forwarding a no-op filter would cost the cache
    // for no gain.
    assertEquals(emptyList(), scope.gradleArgs)
    assertNull(scope.renderedIds)
  }

  @Test
  fun `ids are unioned across the modules that will render`() {
    val app = module("app")
    val wear = module("wear")
    val scope =
      PreviewRenderScope.forRequest(
        manifests =
          listOf(
            manifest(app, "LowBatteryPreview", "HomePreview"),
            manifest(wear, "LowBatteryTilePreview", "ComplicationPreview"),
          ),
        exactId = null,
        filter = "LowBattery",
      )

    // One Gradle invocation carries one property, so the list is the union — safe because the
    // caller has already dropped any module with no match (`modulesMatchingPreviewRequest`), and a
    // filter matching nothing in a module it *does* render would fail that render.
    assertEquals(
      listOf("-PcomposePreview.idFilter==LowBatteryPreview,=LowBatteryTilePreview"),
      scope.gradleArgs,
    )
  }

  @Test
  fun `permutation siblings are matched on the expanded id and rendered via the base id`() {
    val app = module("app")
    val scope =
      PreviewRenderScope.forRequest(
        manifests = listOf(manifest(app, "HomePreview", "SettingsPreview")),
        exactId = "HomePreview_dark",
        filter = null,
        permutations = listOf("accessibility"),
      )

    // `--permutations accessibility` synthesizes `HomePreview_dark` client-side, but the render
    // applies its id filter BEFORE expanding (`RenderPreviewsTask.render`), so the property has to
    // name the base id.
    assertEquals(listOf("-PcomposePreview.idFilter==HomePreview"), scope.gradleArgs)
    // Every id the run will actually produce, for the `.cli-state.json` bookkeeping.
    assertEquals(
      setOf("HomePreview", "HomePreview_dark", "HomePreview_rtl", "HomePreview_fontscale-2x"),
      scope.renderedIds,
    )
  }

  @Test
  fun `an id carrying a comma declines the narrowing rather than breaking the render`() {
    val app = module("app")
    val scope =
      PreviewRenderScope.forRequest(
        manifests = listOf(manifest(app, "Home,Preview", "SettingsPreview")),
        exactId = null,
        filter = "Home",
      )

    assertEquals(emptyList(), scope.gradleArgs)
    assertTrue(
      scope.note!!.contains("comma"),
      "the declined narrowing explains itself: ${scope.note}",
    )
  }

  @Test
  fun `a request that matches nothing renders wide`() {
    val app = module("app")
    val scope =
      PreviewRenderScope.forRequest(
        manifests = listOf(manifest(app, "HomePreview")),
        exactId = "NoSuchPreview",
        filter = null,
      )

    // The caller drops every module in this case, so nothing renders at all — forwarding a filter
    // that matches nothing would turn "no previews matched" (exit 3) into a hard render failure.
    assertEquals(emptyList(), scope.gradleArgs)
    assertNull(scope.renderedIds)
  }
}
