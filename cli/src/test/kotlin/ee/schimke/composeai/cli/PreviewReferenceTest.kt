package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `--preview <ref>` as a selector on the render commands (issue #3744).
 *
 * The bug this pins: `--preview` used to be read by `record` alone. On `render` / `show` / `list`
 * it was neither honoured nor rejected — the CLI has no unknown-flag check, so `compose-preview
 * render --preview HomePreview` **silently rendered the whole module** while the user believed they
 * had narrowed it to one preview. Every test below fails on the pre-#3744 code by selecting (or
 * rendering) more than the reference asked for.
 *
 * The reference forms themselves are pinned in [previewMatchesReference]: exact id,
 * `<className>.<functionName>`, bare function name, or a case-insensitive substring of the id. The
 * first three are what `record --preview` and the Gradle `--preview` option accept; the fourth is
 * the `--filter` rule, which is what people typed at these commands before there was a flag to
 * catch it.
 */
class PreviewReferenceTest {

  // ---------- the reference rule itself ----------

  @Test
  fun `an exact id is a reference`() {
    assertTrue(previewMatchesReference("HomePreview", "HomePreview"))
  }

  @Test
  fun `a fully qualified Class function reference resolves through the row metadata`() {
    // Not reachable by substring: the id carries neither the package nor the class.
    assertTrue(
      previewMatchesReference(
        "com.example.HomeKt.HomePreview",
        id = "HomePreview",
        className = "com.example.HomeKt",
        functionName = "HomePreview",
      )
    )
  }

  @Test
  fun `a bare function name is a reference even when the id is decorated`() {
    assertTrue(
      previewMatchesReference(
        "Home",
        id = "com.example.HomeKt.Home_row0",
        className = "com.example.HomeKt",
        functionName = "Home",
      )
    )
  }

  @Test
  fun `a case-insensitive substring of the id is a reference`() {
    assertTrue(previewMatchesReference("homeprev", "HomePreview"))
  }

  @Test
  fun `an unrelated reference matches nothing`() {
    assertFalse(
      previewMatchesReference(
        "Settings",
        id = "HomePreview",
        className = "com.example.HomeKt",
        functionName = "HomePreview",
      )
    )
  }

  @Test
  fun `the class-function form is not honoured without the row metadata`() {
    // Documented limitation of the id-only call sites: the FQN form needs the manifest row.
    assertFalse(previewMatchesReference("com.example.HomeKt.HomePreview", id = "HomePreview"))
  }

  // ---------- row filtering (`show`, `list`, `render`, report commands) ----------

  @Test
  fun `preview narrows the printed rows`() {
    // Pre-#3744 this returned all three: `--preview` was parsed by nobody and dropped on the floor.
    val cmd = TestableCommand(listOf("--preview", "SettingsPreview"))

    val filtered = cmd.applyFiltersFor(listOf(row("HomePreview"), row("SettingsPreview")))

    assertEquals(listOf("SettingsPreview"), filtered.map { it.id })
  }

  @Test
  fun `preview accepts the fully qualified form on the render commands too`() {
    val cmd = TestableCommand(listOf("--preview", "com.example.SettingsKt.SettingsPreview"))

    val filtered = cmd.applyFiltersFor(listOf(row("HomePreview"), row("SettingsPreview")))

    assertEquals(listOf("SettingsPreview"), filtered.map { it.id })
  }

  @Test
  fun `preview selects every id it is a substring of`() {
    // Deliberate: the rule is a per-preview predicate, so `--preview Home` behaves like
    // `--filter Home` rather than resolving to the one exactly-named preview. `--id` is the
    // exact-match selector.
    val cmd = TestableCommand(listOf("--preview", "Home"))

    val filtered = cmd.applyFiltersFor(listOf(row("Home"), row("HomeDetail"), row("Settings")))

    assertEquals(listOf("Home", "HomeDetail"), filtered.map { it.id })
  }

  @Test
  fun `preview intersects with filter rather than replacing it`() {
    val cmd = TestableCommand(listOf("--preview", "Home", "--filter", "Detail"))

    val filtered = cmd.applyFiltersFor(listOf(row("Home"), row("HomeDetail"), row("Settings")))

    assertEquals(listOf("HomeDetail"), filtered.map { it.id })
  }

  @Test
  fun `no preview flag still selects everything`() {
    val cmd = TestableCommand(emptyList())

    val filtered = cmd.applyFiltersFor(listOf(row("Home"), row("Settings")))

    assertEquals(listOf("Home", "Settings"), filtered.map { it.id })
  }

  @Test
  fun `a blank preview value is treated as absent rather than as a match-everything filter`() {
    val cmd = TestableCommand(listOf("--preview", "   "))

    assertEquals(2, cmd.applyFiltersFor(listOf(row("Home"), row("Settings"))).size)
  }

  // ---------- module selection (which modules are built at all) ----------

  @Test
  fun `preview drops the modules that declare no matching preview`() {
    val app = module(":app")
    val wear = module(":wear")

    val selected =
      modulesMatchingPreviewRequest(
        modules = listOf(app, wear),
        manifests = listOf(manifest(app, "HomePreview"), manifest(wear, "TilePreview")),
        exactId = null,
        filter = null,
        previewRef = "HomePreview",
      )

    assertEquals(listOf(":app"), selected.map { it.gradlePath })
  }

  // ---------- gradle narrowing (the expensive half — issue #3730's property) ----------

  @Test
  fun `preview narrows the gradle render to the referenced preview`() {
    // The whole point of the issue: without this, the run pays for every preview in the module.
    val app = module(":app")

    val scope =
      PreviewRenderScope.forRequest(
        manifests = listOf(manifest(app, "HomePreview", "SettingsPreview")),
        exactId = null,
        filter = null,
        previewRef = "HomePreview",
      )

    assertEquals(
      listOf("-P${PreviewRenderScope.GRADLE_PROPERTY}=${PreviewRenderScope.ANCHOR}HomePreview"),
      scope.gradleArgs,
    )
    assertEquals(setOf("HomePreview"), scope.renderedIds)
  }

  @Test
  fun `a fully qualified preview reference narrows the gradle render too`() {
    val app = module(":app")

    val scope =
      PreviewRenderScope.forRequest(
        manifests = listOf(manifest(app, "HomePreview", "SettingsPreview")),
        exactId = null,
        filter = null,
        previewRef = "com.example.PreviewsKt.SettingsPreview",
      )

    assertEquals(setOf("SettingsPreview"), scope.renderedIds)
  }

  @Test
  fun `a preview reference that selects everything declines to narrow`() {
    // Same rule the other selectors follow: a filter that buys nothing costs the build cache.
    val app = module(":app")

    val scope =
      PreviewRenderScope.forRequest(
        manifests = listOf(manifest(app, "HomePreview", "HomeDetailPreview")),
        exactId = null,
        filter = null,
        previewRef = "Home",
      )

    assertEquals(emptyList(), scope.gradleArgs)
    assertNull(scope.renderedIds)
  }

  // ---------- helpers ----------

  /** Test-only [Command] subclass re-exposing the protected request filter. `run()` is unused. */
  private class TestableCommand(args: List<String>) : Command(args) {
    override fun run() = Unit

    fun applyFiltersFor(results: List<PreviewResult>): List<PreviewResult> = applyFilters(results)
  }

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
              functionName = id,
              className = "com.example.PreviewsKt",
              params = PreviewParams(kind = "COMPOSE"),
            )
          },
      )

  private fun row(id: String): PreviewResult =
    PreviewResult(
      id = id,
      module = ":app",
      functionName = id,
      className = "com.example.${id.removeSuffix("Preview")}Kt",
      captures = listOf(CaptureResult(pngPath = "/tmp/$id.png")),
      pngPath = "/tmp/$id.png",
    )
}
