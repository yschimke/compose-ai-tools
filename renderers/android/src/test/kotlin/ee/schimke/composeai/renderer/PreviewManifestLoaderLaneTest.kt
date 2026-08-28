package ee.schimke.composeai.renderer

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the manifest split behind [PreviewManifestLoader.Lane].
 *
 * The two render classes exist because Robolectric resolves the Application per test *class*: an
 * Activity needs the manifest-declared one (without it, every Hilt / Koin / `AppComponentFactory`
 * activity fails on launch), while an isolated composable wants the stub. What makes that safe is
 * that the classes' selections are disjoint and together cover everything the single class used to
 * render — a preview must not render twice, and none may fall through the gap.
 */
class PreviewManifestLoaderLaneTest {

  @get:Rule val tmp = TemporaryFolder()

  @After
  fun clearProperties() {
    System.clearProperty("composeai.render.manifest")
    System.clearProperty("composeai.render.outputDir")
  }

  private fun entry(id: String, kind: PreviewKind): RenderPreviewEntry =
    RenderPreviewEntry(
      id = id,
      functionName = id,
      className = "com.example.PreviewsKt",
      params = RenderPreviewParams(kind = kind),
      captures = listOf(RenderPreviewCapture(renderOutput = "renders/$id.png")),
    )

  /** Every kind the Android image renderer actually renders, one entry each. */
  private val entries =
    listOf(
      entry("compose", PreviewKind.COMPOSE),
      entry("tile", PreviewKind.TILE),
      entry("notification", PreviewKind.NOTIFICATION),
      entry("glance", PreviewKind.GLANCE_APPWIDGET),
      entry("activity", PreviewKind.ACTIVITY),
      entry("tour", PreviewKind.APP_TOUR),
    )

  private fun idsFor(lane: PreviewManifestLoader.Lane): List<String> {
    val manifest = File(tmp.root, "previews.json")
    manifest.writeText(
      Json.encodeToString(
        RenderManifest.serializer(),
        RenderManifest(module = ":app", variant = "debug", previews = entries),
      )
    )
    System.setProperty("composeai.render.manifest", manifest.absolutePath)
    System.setProperty("composeai.render.outputDir", tmp.root.absolutePath)
    return PreviewManifestLoader.loadShard(0, 1, lane).map { (it[0] as RenderPreviewEntry).id }
  }

  @Test
  fun `app lane claims exactly the activity and tour previews`() {
    assertEquals(listOf("activity", "tour"), idsFor(PreviewManifestLoader.Lane.APP))
  }

  @Test
  fun `composable lane claims everything else`() {
    assertEquals(
      listOf("compose", "tile", "notification", "glance"),
      idsFor(PreviewManifestLoader.Lane.COMPOSABLE),
    )
  }

  @Test
  fun `the default lane is the composable one so generated shard subclasses are unaffected`() {
    // The plugin's `generateShardTests` emits `PreviewManifestLoader.loadShard(N, M)` with no lane
    // argument. Those subclasses are compiled into consumer builds, so the two-argument overload
    // has to keep meaning what it always did.
    val manifest = File(tmp.root, "previews.json")
    manifest.writeText(
      Json.encodeToString(
        RenderManifest.serializer(),
        RenderManifest(module = ":app", variant = "debug", previews = entries),
      )
    )
    System.setProperty("composeai.render.manifest", manifest.absolutePath)
    System.setProperty("composeai.render.outputDir", tmp.root.absolutePath)
    val ids = PreviewManifestLoader.loadShard(0, 1).map { (it[0] as RenderPreviewEntry).id }
    assertEquals(listOf("compose", "tile", "notification", "glance"), ids)
  }

  @Test
  fun `the two lanes partition the manifest`() {
    // Disjoint (nothing renders twice) and complete (nothing is dropped by the split itself).
    val app = idsFor(PreviewManifestLoader.Lane.APP)
    val composable = idsFor(PreviewManifestLoader.Lane.COMPOSABLE)
    assertEquals(emptyList<String>(), app.filter { it in composable })
    assertEquals(entries.map { it.id }.sorted(), (app + composable).sorted())
  }
}
