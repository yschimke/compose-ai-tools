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
   * A declared preview whose id genuinely ends in `_1` is matched under its own name — the
   * mis-handling the issue flagged against a naive `substringBeforeLast('_')`.
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

  /**
   * Review follow-up (#3795). A real preview named `Foo_Dark` in one module and a parameterized
   * `Foo` in another: `--id Foo_Dark` must resolve to the module that actually declares it. Keeping
   * both — the second because `Foo_Dark` *could* be a row of `Foo` — makes `serve` abort with "2
   * modules discovered" before its row-level filtering ever runs, turning a previously working
   * exact selection into an error. A direct hit anywhere therefore switches the row lane off, the
   * same way the daemon consults `PreviewRowAddress.split` only on an exact miss.
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
   * Review follow-up (#3795). `--filter` is a case-insensitive **substring** of the final id, so
   * these are ordinary ways to ask for a row — and `ServeCommand.matches(row.id)` would match all
   * of them. Module selection has to agree, or it drops the module before that check runs. A
   * `<base>_<row>` prefix rule answered "no" to the last two: a row's label is not a prefix of
   * anything, and the rule was case-sensitive besides.
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
   * Review follow-up (#3798). The exact-hit precedence must NOT extend to the substring selectors.
   * A parameterized `Foo` yielding `Foo_Crimson` and an ordinary `CrimsonButton` are *both*
   * legitimately named by `--filter Crimson` — matching several previews at once is what a
   * substring rule is for, not a conflict to resolve — so letting the concrete one suppress the row
   * owner would drop a preview that satisfies the documented predicate. Only `--id` is
   * single-target, and only `--id` has a caller (`serve`) that breaks when a second module tags
   * along.
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
   * Review follow-up (#3799). The conservative row lane stays opt-in: a command may only keep a
   * module on a "maybe" if it can cash the keep in after the render, or a speculative keep just
   * renders a module and prints nothing from it. `serve` cashes it in with `ServeParameterRows`,
   * and since #3819 `show` / `list` / `render` cash it in with [selectRequestedResults] — but the
   * extension commands (`a11y` and friends) drive their per-preview data production off the
   * discovery manifest, which knows only declared ids, so they stay strict and a row selector fails
   * there *before* a render rather than after one.
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

  /**
   * Issue #3819. The same narrowing, reached through the command that actually runs it — the flag
   * [Command.rowAwareSelection] is what routes a row request into the lane above, and `show` /
   * `render` now set it because they can cash the keep in ([selectRequestedResults] matches the row
   * ids `PreviewResultBuilder` carries on each capture).
   *
   * Both halves matter and neither is enough alone: without the flag the request selects nothing
   * here and falls back to `FULL`, paying for every preview in the module to answer a single-row
   * question; with the flag but no row-aware output filtering, the render happens and the command
   * prints "No previews matched." anyway — the state the issue was filed about.
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
