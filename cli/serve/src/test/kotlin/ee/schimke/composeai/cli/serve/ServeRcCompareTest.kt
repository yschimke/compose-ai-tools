package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

/**
 * The serve side of the published Remote Compose player comparison: re-keying a delivery branch's
 * `rc-compare-summary.json` onto catalog ids, planning the lane images to stage, and reading the
 * staged result back.
 */
class ServeRcCompareTest {

  /** A summary in the exact shape `rc-compare.mjs` writes, trimmed to the fields serve reads. */
  private val summaryJson =
    """
    {
      "system": "remote-m3",
      "threshold": 0.1,
      "meanMismatchPct": 1.21,
      "rows": [
        {
          "id": "com.example.CatalogPreviewsKt.AppCardRemote_width_320dp",
          "rendered": true, "mismatchPct": 2.96, "mismatchPx": 9116,
          "width": 640, "height": 480, "note": null, "referenceBlank": false,
          "embeddedRendered": true, "embeddedMismatchPct": 0.007, "embeddedMismatchPx": 24,
          "embeddedNote": null,
          "androidxEmbeddedRendered": true, "androidxEmbeddedMismatchPct": 0.5,
          "androidxEmbeddedMismatchPx": 1700, "androidxEmbeddedNote": null,
          "cmpWasmRendered": false, "cmpWasmMismatchPct": null, "cmpWasmMismatchPx": null,
          "cmpWasmNote": "Unsupported operation at byte 110"
        },
        {
          "id": "com.example.CatalogPreviewsKt.BlankRemote_width_200dp",
          "rendered": true, "mismatchPct": null, "mismatchPx": null,
          "width": 400, "height": 400, "note": null, "referenceBlank": true,
          "embeddedRendered": true, "embeddedMismatchPct": null, "embeddedMismatchPx": null,
          "embeddedNote": null,
          "androidxEmbeddedRendered": false, "androidxEmbeddedMismatchPct": null,
          "androidxEmbeddedMismatchPx": null, "androidxEmbeddedNote": "upstream render failed",
          "cmpWasmRendered": false, "cmpWasmNote": "no render"
        },
        {
          "id": "com.example.CatalogPreviewsKt.UnpublishedRemote_width_100dp",
          "rendered": true, "mismatchPct": 1.0, "mismatchPx": 10,
          "width": 100, "height": 100, "referenceBlank": false
        }
      ]
    }
    """
      .trimIndent()

  private val alias =
    mapOf(
      "appcard__ideal__default__compact" to
        "com.example.CatalogPreviewsKt.AppCardRemote_width_320dp",
      "blank__ideal__default__compact" to "com.example.CatalogPreviewsKt.BlankRemote_width_200dp",
      // No summary row — an Android-only catalog id, which must simply not appear.
      "switch__ideal__default__compact" to "com.example.CatalogPreviewsKt.SwitchRemote",
    )

  private fun plan(): RcComparePlan {
    val summary = assertNotNull(ServeRcCompare.parseSummary(summaryJson.encodeToByteArray()))
    return assertNotNull(ServeRcCompare.plan(summary, alias))
  }

  @Test
  fun `re-keys published rows onto catalog ids and drops unmatched ones`() {
    val manifest = plan().manifest
    assertEquals(
      listOf("appcard__ideal__default__compact", "blank__ideal__default__compact"),
      manifest.rows.map { it.previewId },
    )
    assertEquals(0.1, manifest.threshold)
    assertEquals(640, manifest.rows[0].width)
    assertTrue(manifest.rows[1].referenceBlank)
  }

  @Test
  fun `keeps only the lanes the run actually covered`() {
    // The published run had no cmp-jvm player, so that column does not exist at all — an empty
    // column would read as "the player rendered nothing", which is a different claim.
    assertEquals(
      listOf("baked", "js", "embedded", "androidx-embedded", "cmp-wasm"),
      plan().manifest.lanes.map { it.id },
    )
  }

  @Test
  fun `plans one fetch per rendered lane, keyed by lane and slot`() {
    val plan = plan()
    assertEquals(
      "rc/com.example.CatalogPreviewsKt.AppCardRemote_width_320dp.png",
      plan.assets.entries.first { it.value == "js/0.png" }.key,
    )
    assertEquals(
      "rc-diff/com.example.CatalogPreviewsKt.AppCardRemote_width_320dp.png",
      plan.assets.entries.first { it.value == "js-diff/0.png" }.key,
    )
    // A lane that could not render the document costs no round-trip at all.
    assertFalse(plan.assets.values.any { it.startsWith("cmp-wasm/") })
    // …and its reason travels instead of an image.
    val wasm = plan.manifest.rows[0].lanes.getValue("cmp-wasm")
    assertFalse(wasm.rendered)
    assertEquals("Unsupported operation at byte 110", wasm.note)
    val upstream = plan.manifest.rows[1].lanes.getValue("androidx-embedded")
    assertFalse(upstream.rendered)
    assertEquals("upstream render failed", upstream.note)
  }

  @Test
  fun `an unscorable row keeps its renders but carries no score`() {
    val blank = plan().manifest.rows[1]
    val js = blank.lanes.getValue("js")
    assertTrue(js.rendered)
    assertEquals("js/1.png", js.render)
    // Nothing was diffed against a blank baked capture, so there is no diff and no percentage —
    // a 0.00% here would be a green band for a comparison that never happened.
    assertEquals("", js.diff)
    assertNull(js.mismatchPct)
  }

  @Test
  fun `drops cells whose image never landed, and the manifest when none did`() {
    val plan = plan()
    val partial =
      assertNotNull(ServeRcCompare.retainStaged(plan.manifest, setOf("baked/0.png", "js/0.png")))
    assertEquals("js/0.png", partial.rows[0].lanes.getValue("js").render)
    // The render landed but its diff didn't: the cell stays, and the page falls back to diffing it.
    assertEquals("", partial.rows[0].lanes.getValue("js").diff)
    val gone = partial.rows[1].lanes.getValue("js")
    assertFalse(gone.rendered)
    assertEquals("render was not published", gone.note)
    assertNull(ServeRcCompare.retainStaged(plan.manifest, emptySet()))
  }

  @Test
  fun `serves only names from the fixed lane vocabulary`(@TempDir dir: File) {
    val root = File(dir, ServeRcCompare.DIRECTORY)
    File(root, "js").mkdirs()
    File(root, "js/0.png").writeBytes(byteArrayOf(1, 2, 3))
    File(dir, "secret.txt").writeText("nope")
    val store = ServeRcCompareStore.load(dir)

    assertEquals(listOf<Byte>(1, 2, 3), store.image("js/0.png")!!.toList())
    assertNull(store.image("js/1.png"), "an unstaged slot")
    assertNull(store.image("nope/0.png"), "an unknown lane")
    assertNull(store.image("js/../../secret.txt"), "a traversing name")
    assertNull(store.image("../secret.txt"), "an escaping name")
    assertNull(store.image("js/0.png/x"), "a name with extra segments")
  }

  @Test
  fun `the manifest resolves only once the staging lane has written it`(@TempDir dir: File) {
    val store = ServeRcCompareStore.load(dir)
    // The lane PNGs are fetched in the background, so a host built before they land must keep
    // looking rather than caching "this catalog has no comparison" for its whole lifetime.
    assertNull(store.manifest())
    val manifest = ServeRcCompare.retainStaged(plan().manifest, plan().assets.values.toSet())!!
    val index = File(dir, "${ServeRcCompare.DIRECTORY}/${ServeRcCompare.INDEX_FILE}")
    index.parentFile.mkdirs()
    index.writeText(Json.encodeToString(RcCompareManifest.serializer(), manifest))
    assertEquals(2, store.manifest()?.rows?.size)
  }

  @Test
  fun `a settled catalog with no comparison is not pending`(@TempDir dir: File) {
    // "The lane hasn't finished" and "this catalog publishes none" both show the older in-browser
    // lane, but only the first makes the page's shape provisional — and only that one must stay out
    // of caches, or the pre-manifest page is pinned at the edge after the lanes land.
    val store = ServeRcCompareStore.load(dir)
    assertTrue(store.pending())
    val index = File(dir, "${ServeRcCompare.DIRECTORY}/${ServeRcCompare.INDEX_FILE}")
    index.parentFile.mkdirs()
    index.writeText(Json.encodeToString(RcCompareManifest.serializer(), ServeRcCompare.NONE))
    assertFalse(store.pending())
    assertNull(store.manifest(), "an empty manifest is a settled absence, not a view")
  }

  @Test
  fun `a summary with no rows, or one nothing matches, yields no view`() {
    assertNull(ServeRcCompare.parseSummary("""{"rows":[]}""".encodeToByteArray()))
    assertNull(ServeRcCompare.parseSummary("not json".encodeToByteArray()))
    val summary = assertNotNull(ServeRcCompare.parseSummary(summaryJson.encodeToByteArray()))
    assertNull(ServeRcCompare.plan(summary, mapOf("x" to "y")))
  }
}
