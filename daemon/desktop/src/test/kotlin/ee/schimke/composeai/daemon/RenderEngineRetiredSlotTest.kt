package ee.schimke.composeai.daemon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Desktop-backend regression guard for issue #3324 — a **retired** (composed but unplaced)
 * subcomposition slot must not reach the `compose/figma-svg` export.
 *
 * A lazy container does not discard a row the moment it leaves the viewport: the row stays composed
 * with its text attached while Compose stops *placing* it, and an unplaced node reports `(0,0,0,0)`
 * bounds. The export's zero-bounds recovery — meant for a *placed* subcomposed child whose
 * coordinates were detached — then anchored the retired row at its **parent's** origin, so it came
 * back as a ghost painted over the top of the screen. On JetNews' `Screens/Article` the retired
 * article body reappeared inside the `TopAppBar` group over the hero image; on JetLagged's sleep
 * graph card the retired `1Y` tab reappeared at the card's top-left.
 *
 * [ScrolledLazyColumnPreview] reproduces it in miniature: the list scrolls to row 9 before the
 * frame is captured, so rows 1…8 are retired. Sibling of `:daemon:android`'s
 * `FigmaSvgRetiredSlotTest`, which pins the same guarantee on the backend the defect was reported
 * from.
 */
class RenderEngineRetiredSlotTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun retiredLazyRowIsNotExportedOverTheVisibleScreen() {
    val outputDir = tempFolder.newFolder("renders")
    val dataDir = tempFolder.newFolder("data")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      host.submit(
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=ScrolledLazyColumnPreview;" +
              "widthPx=200;heightPx=520;density=1.0;showBackground=true;" +
              "outputBaseName=retired-slot"
        ),
        timeoutMs = 120_000,
      )
    } finally {
      host.shutdown()
    }
    val svg = File(File(dataDir, "retired-slot"), "compose-figma.svg")
    assertTrue("figma SVG must be produced: ${svg.absolutePath}", svg.exists())
    val text = svg.readText()
    // Every exported row must sit at its own place. A retired row carries no placement of its own,
    // so the ghosts all stacked at the same recovered parent origin — several rows sharing one `y`
    // is exactly the defect, and asserting it this way stays true however many rows Compose happens
    // to retire for a given viewport.
    val rows =
      Regex("<text[^>]*\\by=\"([0-9.]+)\"[^>]*>Row ([0-9]+)</text>")
        .findAll(text)
        .map { it.groupValues[2].toInt() to it.groupValues[1].toDouble() }
        .toList()
    assertTrue("the visible rows must still be exported (got $rows)", rows.any { it.first == 9 })
    val stacked = rows.groupBy { it.second }.filterValues { it.size > 1 }
    assertEquals(
      "no two rows may be exported at the same y (retired ghosts):\n$text",
      emptyMap<Double, List<Pair<Int, Double>>>(),
      stacked,
    )
    assertEquals(
      "the exported rows must run down the screen in index order (got $rows)",
      rows.sortedBy { it.first },
      rows.sortedBy { it.second },
    )
  }
}
