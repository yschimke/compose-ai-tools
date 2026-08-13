package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #3786 — selecting a `@PreviewParameter` **row id** must not drop the module before the rows
 * exist.
 *
 * `serve` hosts one entry per row (#3772) with ids of the shape `<baseId>_<row>`, and accepts a row
 * id as a selector. But the ids are synthesised late, from the fan-out the render pass wrote to
 * disk — discovery emits one entry per parameterized *function*, so the manifest holds `Foo` and
 * has never heard of `Foo_PARAM_1`. Module selection and render narrowing both run against that
 * manifest, *before* the expansion, so `serve --id Foo_PARAM_1` used to exit with "no previews
 * discovered": the row-selecting branch in `ServeCommand` was unreachable on the Gradle path.
 *
 * The substring form accidentally worked from the other direction (`--filter Foo` keeps the module,
 * then serves all of Foo's rows), so this only bit when someone named a row precisely — exactly
 * when they were being most specific.
 */
class PreviewRowSelectorTest {

  private fun module(path: String) =
    PreviewModule(path, File("/tmp/compose-preview-test/${path.replace(':', '/')}"))

  private fun preview(id: String, parameterized: Boolean = false) =
    PreviewInfo(
      id = id,
      functionName = id.substringAfterLast('.'),
      className = "com.example.PreviewsKt",
      params =
        PreviewParams(
          kind = "COMPOSE",
          previewParameterProviderClassName =
            if (parameterized) "com.example.SwatchProvider" else null,
        ),
    )

  private fun manifest(module: PreviewModule, vararg previews: PreviewInfo) =
    module to
      PreviewManifest(module = module.gradlePath, variant = "debug", previews = previews.toList())

  // ---------- module selection: the bug from the issue ----------

  /** The reproduce case: `compose-preview serve --module :app --id Foo_PARAM_1`. */
  @Test
  fun `a row id keeps the module that owns its base preview`() {
    val app = module(":app")

    val selected =
      modulesMatchingPreviewRequest(
        modules = listOf(app),
        manifests = listOf(manifest(app, preview("Foo", parameterized = true))),
        exactId = "Foo_PARAM_1",
        filter = null,
      )

    assertEquals(listOf(":app"), selected.map { it.gradlePath })
  }

  /**
   * `--filter` and `--preview` name the same row and were broken the same way (#3744 widened it).
   */
  @Test
  fun `filter and preview accept a row id too`() {
    val app = module(":app")
    val manifests = listOf(manifest(app, preview("Foo", parameterized = true)))

    assertEquals(
      listOf(":app"),
      modulesMatchingPreviewRequest(listOf(app), manifests, exactId = null, filter = "Foo_PARAM_1")
        .map { it.gradlePath },
    )
    assertEquals(
      listOf(":app"),
      modulesMatchingPreviewRequest(
          listOf(app),
          manifests,
          exactId = null,
          filter = null,
          previewRef = "Foo_PARAM_1",
        )
        .map { it.gradlePath },
    )
  }

  /**
   * The row lane must not become a blanket "keep everything on a miss". A preview with no provider
   * has no rows, so a selector that matches nothing there is still definitively wrong — and the "no
   * previews discovered" diagnostic for a typo depends on it.
   */
  @Test
  fun `a row-shaped selector still drops a module whose preview has no provider`() {
    val app = module(":app")

    val selected =
      modulesMatchingPreviewRequest(
        modules = listOf(app),
        manifests = listOf(manifest(app, preview("Foo", parameterized = false))),
        exactId = "Foo_PARAM_1",
        filter = null,
      )

    assertTrue(selected.isEmpty(), "a non-parameterized Foo cannot own Foo_PARAM_1: $selected")
  }

  /** And an unrelated module is still dropped — the narrowing is preserved where it's valid. */
  @Test
  fun `a row id does not keep modules that own no matching base`() {
    val app = module(":app")
    val wear = module(":wear")

    val selected =
      modulesMatchingPreviewRequest(
        modules = listOf(app, wear),
        manifests =
          listOf(
            manifest(app, preview("Foo", parameterized = true)),
            manifest(wear, preview("Tile", parameterized = true)),
          ),
        exactId = "Foo_PARAM_1",
        filter = null,
      )

    assertEquals(listOf(":app"), selected.map { it.gradlePath })
  }

  /**
   * The prefix test is anchored on a real manifest id *plus* its provider, so it doesn't mis-handle
   * the case the issue flagged against a naive `substringBeforeLast('_')`: a declared preview whose
   * id genuinely ends in `_1` is matched under its own name.
   */
  @Test
  fun `a declared preview whose id ends in an underscore digit matches as itself`() {
    val app = module(":app")

    val selected =
      modulesMatchingPreviewRequest(
        modules = listOf(app),
        manifests = listOf(manifest(app, preview("Foo_1"), preview("Bar"))),
        exactId = "Foo_1",
        filter = null,
      )

    assertEquals(listOf(":app"), selected.map { it.gradlePath })
  }

  /** The selectors intersect, so a row id on one must not cancel a normal match on another. */
  @Test
  fun `an intersecting id and filter both still apply`() {
    val app = module(":app")
    val manifests = listOf(manifest(app, preview("Foo", parameterized = true)))

    assertEquals(
      listOf(":app"),
      modulesMatchingPreviewRequest(listOf(app), manifests, exactId = "Foo_PARAM_1", filter = "Foo")
        .map { it.gradlePath },
    )
    // A filter that Foo cannot satisfy still drops it, row id or not.
    assertTrue(
      modulesMatchingPreviewRequest(
          listOf(app),
          manifests,
          exactId = "Foo_PARAM_1",
          filter = "Unrelated",
        )
        .isEmpty()
    )
  }

  // ---------- render narrowing: keep #3730's optimisation for row requests ----------

  /**
   * Keeping the module is only half the answer. Without this the scope would select nothing and
   * fall back to `FULL`, paying for every preview in the module — the exact cost #3730 removed, and
   * the cost the issue expected option (3) to incur. Selecting the *base* preserves it.
   */
  @Test
  fun `a row id narrows the gradle render to its base preview`() {
    val app = module(":app")

    val scope =
      PreviewRenderScope.forRequest(
        manifests = listOf(manifest(app, preview("Foo", parameterized = true), preview("Other"))),
        exactId = "Foo_PARAM_1",
        filter = null,
      )

    assertEquals(
      listOf("-P${PreviewRenderScope.GRADLE_PROPERTY}=${PreviewRenderScope.ANCHOR}Foo"),
      scope.gradleArgs,
    )
  }
}
