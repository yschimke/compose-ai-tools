package ee.schimke.composeai.cli.serve

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The join between the live catalog and the published feed — the whole point of the page. */
class ServeParityDashboardTest {

  private val sha = "4e73ec2b9f0a1c3d5e7f9a1b3c5d7e9f0a1b3c5d"

  private fun preview(id: String, componentId: String? = null, theme: String? = null) =
    ServePreview(id = id, label = id, componentId = componentId, theme = theme)

  /** A two-component catalog, each with a baked light/dark pair — the usual catalog shape. */
  private val previews =
    listOf(
      preview("button-filled__ideal__default__light", "Button/Filled", "light"),
      preview("button-filled__ideal__default__dark", "Button/Filled", "dark"),
      preview("nav-rail__ideal__default__light", "Navigation/Rail", "light"),
      preview("nav-rail__ideal__default__dark", "Navigation/Rail", "dark"),
    )

  @Test
  fun `coverage folds theme variants onto one component`() {
    val dashboard =
      ServeParityDashboard.build(
        previews = previews,
        hasReference = { it == "button-filled__ideal__default__light" },
        activity = null,
        referenceIdFor = {
          if (it == "button-filled__ideal__default__light") "figma-button-filled" else null
        },
      )

    assertEquals(2, dashboard.coverage.components, "light+dark are one component, not two")
    assertEquals(1, dashboard.coverage.mapped)
    assertEquals(50, dashboard.coverage.percent)
    assertEquals(listOf("Navigation/Rail"), dashboard.coverage.unmapped.map { it.name })
    // The unmapped chip must open the LIGHT default render — the card the grid shows.
    assertEquals("nav-rail__ideal__default__light", dashboard.coverage.unmapped.single().previewId)
    assertEquals(listOf("Button/Filled", "Navigation/Rail"), dashboard.comparisons.map { it.name })
    assertEquals("figma-button-filled", dashboard.comparisons.first().referenceId)
  }

  @Test
  fun `a catalog with no feed still yields a coverage-only dashboard`() {
    val dashboard = ServeParityDashboard.build(previews, hasReference = { true }, activity = null)

    assertEquals(100, dashboard.coverage.percent)
    assertTrue(dashboard.feed.isEmpty())
    assertTrue(!dashboard.hasActivity)
  }

  @Test
  fun `both lanes merge into one reverse-chronological feed`() {
    val dashboard =
      ServeParityDashboard.build(
        previews,
        hasReference = { true },
        activity =
          ParityActivity(
            code =
              CodeLane(
                repo = "yschimke/m3-catalog",
                events =
                  listOf(
                    CodeEvent(
                      sha = sha,
                      subject = "fix: rail indicator height",
                      at = "2026-08-03T10:00:00Z",
                      components = listOf("Navigation/Rail"),
                      previewIds = listOf("nav-rail__ideal__default__light"),
                    )
                  ),
              ),
            figma =
              FigmaLane(
                fileKey = "abc123",
                versions =
                  listOf(
                    FigmaVersionEvent(id = "v1", at = "2026-08-05T10:00:00Z", label = "Rail pass")
                  ),
                comments =
                  listOf(
                    FigmaCommentEvent(
                      id = "c1",
                      at = "2026-08-04T10:00:00Z",
                      message = "4dp too tall",
                      nodeId = "51592:4768",
                      components = listOf("Navigation/Rail"),
                      previewIds = listOf("nav-rail__ideal__default__light"),
                    )
                  ),
              ),
          ),
      )

    assertEquals(
      listOf(
        ServeParityDashboard.Lane.FIGMA_VERSION,
        ServeParityDashboard.Lane.FIGMA_COMMENT,
        ServeParityDashboard.Lane.CODE,
      ),
      dashboard.feed.map { it.lane },
    )
    assertEquals(1, dashboard.openComments)
    assertEquals(
      "https://github.com/yschimke/m3-catalog/commit/$sha",
      dashboard.feed.first { it.lane == ServeParityDashboard.Lane.CODE }.href,
    )
    assertEquals(
      "https://www.figma.com/design/abc123?node-id=51592-4768",
      dashboard.feed.first { it.lane == ServeParityDashboard.Lane.FIGMA_COMMENT }.href,
    )
  }

  @Test
  fun `a feed row never links to a preview this session does not serve`() {
    val dashboard =
      ServeParityDashboard.build(
        previews,
        hasReference = { true },
        activity =
          ParityActivity(
            code =
              CodeLane(
                repo = "yschimke/m3-catalog",
                events =
                  listOf(
                    CodeEvent(
                      sha = sha,
                      subject = "chore: rename a preview",
                      at = "2026-08-03T10:00:00Z",
                      previewIds = listOf("renamed-away__ideal__default__light"),
                    )
                  ),
              )
          ),
      )

    // The published feed outlived the rename; the row still shows, with no dead inbound link.
    assertEquals(1, dashboard.feed.size)
    assertTrue(dashboard.feed.single().previewIds.isEmpty())
  }

  @Test
  fun `one-sided movement is classified and sorted ahead of two-sided`() {
    val dashboard =
      ServeParityDashboard.build(
        previews,
        hasReference = { true },
        activity =
          ParityActivity(
            code =
              CodeLane(
                repo = "yschimke/m3-catalog",
                events =
                  listOf(
                    // Button moved on BOTH sides; Rail moved only in code.
                    CodeEvent(
                      sha = sha,
                      subject = "feat: button",
                      at = "2026-08-05T10:00:00Z",
                      components = listOf("Button/Filled"),
                    ),
                    CodeEvent(
                      sha = sha,
                      subject = "fix: rail",
                      at = "2026-08-01T10:00:00Z",
                      components = listOf("Navigation/Rail"),
                    ),
                  ),
              ),
            figma =
              FigmaLane(
                fileKey = "abc123",
                comments =
                  listOf(
                    FigmaCommentEvent(
                      id = "c1",
                      at = "2026-08-04T10:00:00Z",
                      message = "tweak",
                      components = listOf("Button/Filled"),
                    )
                  ),
              ),
          ),
      )

    val byName = dashboard.components.associateBy { it.name }
    assertEquals(ServeParityDashboard.Correlation.BOTH, byName["Button/Filled"]?.correlation)
    assertEquals(ServeParityDashboard.Correlation.CODE_ONLY, byName["Navigation/Rail"]?.correlation)
    // The actionable (one-sided) row leads, even though the two-sided one is more recent.
    assertEquals("Navigation/Rail", dashboard.components.first().name)
    // It carries a link target resolved against the live catalog.
    assertEquals("nav-rail__ideal__default__light", dashboard.components.first().previewId)
  }

  @Test
  fun `the live catalog's spelling of a component wins over the producer's`() {
    // The producer writes `Switch/On`; this catalog publishes no `componentId`, so its own name for
    // the same component is `Switch on`. Two spellings would split one component across the
    // correlation into two rows, neither of which links anywhere.
    val dashboard =
      ServeParityDashboard.build(
        previews,
        hasReference = { false },
        activity =
          ParityActivity(
            figma =
              FigmaLane(
                fileKey = "abc123",
                comments =
                  listOf(
                    FigmaCommentEvent(
                      id = "c1",
                      at = "2026-08-04T10:00:00Z",
                      message = "track is short",
                      components = listOf("Switch/On"),
                      previewIds = listOf("nav-rail__ideal__default__light"),
                    )
                  ),
              )
          ),
      )

    val component = dashboard.components.single()
    assertEquals("Navigation/Rail", component.name, "the served catalog names the component")
    assertEquals("nav-rail__ideal__default__light", component.previewId)
  }

  @Test
  fun `a design-only comment classifies as design drift`() {
    val dashboard =
      ServeParityDashboard.build(
        previews,
        hasReference = { true },
        activity =
          ParityActivity(
            figma =
              FigmaLane(
                fileKey = "abc123",
                comments =
                  listOf(
                    FigmaCommentEvent(
                      id = "c1",
                      at = "2026-08-04T10:00:00Z",
                      message = "new spec",
                      components = listOf("Button/Filled"),
                    )
                  ),
              )
          ),
      )

    assertEquals(
      ServeParityDashboard.Correlation.DESIGN_ONLY,
      dashboard.components.single().correlation,
    )
  }

  @Test
  fun `a resolved comment does not count as open`() {
    val dashboard =
      ServeParityDashboard.build(
        previews,
        hasReference = { true },
        activity =
          ParityActivity(
            figma =
              FigmaLane(
                fileKey = "abc123",
                comments =
                  listOf(
                    FigmaCommentEvent(
                      id = "c1",
                      at = "2026-08-04T10:00:00Z",
                      message = "done",
                      resolved = true,
                    )
                  ),
              )
          ),
      )

    assertEquals(0, dashboard.openComments)
    assertTrue(dashboard.feed.single().resolved)
  }

  @Test
  fun `a commit naming no component contributes no correlation`() {
    val dashboard =
      ServeParityDashboard.build(
        previews,
        hasReference = { true },
        activity =
          ParityActivity(
            code =
              CodeLane(
                repo = "yschimke/m3-catalog",
                events =
                  listOf(
                    CodeEvent(
                      sha = sha,
                      subject = "chore: bump gradle",
                      at = "2026-08-05T10:00:00Z",
                    )
                  ),
              )
          ),
      )

    assertEquals(1, dashboard.feed.size, "the row still shows — it is real activity")
    assertTrue(dashboard.components.isEmpty(), "but it says nothing about a specific pair")
  }

  @Test
  fun `an empty catalog reports full coverage rather than dividing by zero`() {
    val dashboard =
      ServeParityDashboard.build(emptyList(), hasReference = { false }, activity = null)

    assertEquals(100, dashboard.coverage.percent)
    assertEquals(0, dashboard.coverage.components)
  }

  @Test
  fun `a plain bundle with no catalog ids still keys components apart`() {
    val dashboard =
      ServeParityDashboard.build(
        listOf(preview("com.example.LoginPreview"), preview("com.example.HomePreview")),
        hasReference = { false },
        activity = null,
      )

    assertEquals(2, dashboard.coverage.components)
    assertEquals(listOf("HomePreview", "LoginPreview"), dashboard.coverage.unmapped.map { it.name })
    assertNull(dashboard.generatedAt)
  }
}
