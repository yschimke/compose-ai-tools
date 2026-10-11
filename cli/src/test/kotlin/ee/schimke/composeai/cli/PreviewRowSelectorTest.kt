package ee.schimke.composeai.cli

import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.previewdata.PreviewParams
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Selecting a `@PreviewParameter` row id must not drop the module before the rows exist. `serve`
 * hosts one entry per row (`<baseId>_<row>`), but those ids are synthesised from the rendered
 * fan-out, while module selection and render narrowing run earlier against a manifest that only
 * knows the function (`Foo`, not `Foo_PARAM_1`).
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

  /** `--filter` and `--preview` naming the same row. */
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
   * The row lane must not become "keep everything on a miss": a preview with no provider has no
   * rows, and the typo diagnostic depends on that.
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

  /** A declared preview whose id genuinely ends in `_1` matches under its own name. */
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

  /**
   * A real `Foo_Dark` in one module and a parameterized `Foo` in another: `--id Foo_Dark` must
   * resolve only to the real one, or `serve` aborts on two modules. A direct hit switches the row
   * lane off, as the daemon only consults `PreviewRowAddress.split` on an exact miss.
   */
  @Test
  fun `an exact hit anywhere wins over a hypothetical row of a parameterized preview`() {
    val app = module(":app")
    val wear = module(":wear")

    val selected =
      modulesMatchingPreviewRequest(
        modules = listOf(app, wear),
        manifests =
          listOf(
            manifest(app, preview("Foo", parameterized = true)),
            manifest(wear, preview("Foo_Dark")),
          ),
        exactId = "Foo_Dark",
        filter = null,
      )

    assertEquals(listOf(":wear"), selected.map { it.gradlePath })
  }

  /**
   * `--filter` is a case-insensitive substring of the final id, so these are all ordinary row
   * requests that `ServeCommand.matches(row.id)` accepts; module selection must agree.
   */
  @Test
  fun `filter keeps a parameterized preview for any row-shaped spelling`() {
    val app = module(":app")
    val manifests = listOf(manifest(app, preview("Foo", parameterized = true)))

    for (f in listOf("Foo_PARAM_1", "foo_param_1", "PARAM_1", "Crimson")) {
      assertEquals(
        listOf(":app"),
        modulesMatchingPreviewRequest(listOf(app), manifests, exactId = null, filter = f).map {
          it.gradlePath
        },
        "--filter $f must keep the module that owns the rows it could name",
      )
    }
  }

  /**
   * Exact-hit precedence must not extend to substring selectors: `--filter Crimson` legitimately
   * names both a parameterized `Foo`'s `Foo_Crimson` and an ordinary `CrimsonButton`. Only `--id`
   * is single-target.
   */
  @Test
  fun `a direct filter hit does not suppress other modules' row owners`() {
    val app = module(":app")
    val ui = module(":ui")

    val selected =
      modulesMatchingPreviewRequest(
        modules = listOf(app, ui),
        manifests =
          listOf(
            manifest(app, preview("Foo", parameterized = true)),
            manifest(ui, preview("CrimsonButton")),
          ),
        exactId = null,
        filter = "Crimson",
      )

    assertEquals(listOf(":app", ":ui"), selected.map { it.gradlePath })
  }

  /** Same for the loose `--preview` form, which is a substring rule too. */
  @Test
  fun `a direct preview-ref hit does not suppress other modules' row owners`() {
    val app = module(":app")
    val ui = module(":ui")

    val selected =
      modulesMatchingPreviewRequest(
        modules = listOf(app, ui),
        manifests =
          listOf(
            manifest(app, preview("Foo", parameterized = true)),
            manifest(ui, preview("CrimsonButton")),
          ),
        exactId = null,
        filter = null,
        previewRef = "Crimson",
      )

    assertEquals(listOf(":app", ":ui"), selected.map { it.gradlePath })
  }

  /**
   * The row lane is opt-in: a command may keep a module on a "maybe" only if it can cash the keep
   * in after rendering (`serve` via `ServeParameterRows`; `show` / `list` / `render` via
   * [selectRequestedResults]). Extension commands only know declared ids, so they stay strict and
   * fail before rendering.
   */
  @Test
  fun `the row lane is off for commands that cannot expand rows`() {
    val app = module(":app")
    val manifests = listOf(manifest(app, preview("Foo", parameterized = true)))

    assertTrue(
      modulesMatchingPreviewRequest(
          listOf(app),
          manifests,
          exactId = null,
          filter = "Crimson",
          rowAware = false,
        )
        .isEmpty(),
      "a non-row-aware command must not render a module on a maybe",
    )
    assertEquals(
      listOf(":app"),
      modulesMatchingPreviewRequest(
          listOf(app),
          manifests,
          exactId = null,
          filter = "Crimson",
          rowAware = true,
        )
        .map { it.gradlePath },
    )
  }

  /** The same undecidability applies to `--preview`, whose loose form is also a substring rule. */
  @Test
  fun `preview ref keeps a parameterized preview for a row label`() {
    val app = module(":app")

    assertEquals(
      listOf(":app"),
      modulesMatchingPreviewRequest(
          listOf(app),
          listOf(manifest(app, preview("Foo", parameterized = true))),
          exactId = null,
          filter = null,
          previewRef = "Crimson",
        )
        .map { it.gradlePath },
    )
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
   * Keeping the module isn't enough: the scope must select the base preview, or it falls back to
   * `FULL` and renders the whole module.
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

  /**
   * The same narrowing via the command: [Command.rowAwareSelection] routes row requests into this
   * lane for `show` / `render`. Without the flag the module renders in full; without row-aware
   * output filtering the command prints "No previews matched." anyway.
   */
  @Test
  fun `show and render narrow a row id to its base preview`() {
    val app = module(":app")
    val manifests = listOf(manifest(app, preview("Foo", parameterized = true), preview("Other")))
    val expected = listOf("-P${PreviewRenderScope.GRADLE_PROPERTY}=${PreviewRenderScope.ANCHOR}Foo")

    for (command in
      listOf(
        ShowCommand(listOf("--id", "Foo_PARAM_1")),
        RenderCommand(listOf("--id", "Foo_PARAM_1")),
      )) {
      val scope =
        command.previewRenderScope(
          renderModules = listOf(app),
          discoveryManifests = manifests,
          discoverySucceeded = true,
        )
      assertEquals(expected, scope.gradleArgs, "${command::class.simpleName} must narrow to Foo")
      assertEquals(setOf("Foo"), scope.renderedIds)
    }
  }
}
