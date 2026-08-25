package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeWebKnownDifferenceContextTest {

  private fun issue(number: Int, previewIds: List<String>) =
    ParityIssue(
      repository = "example/catalog",
      number = number,
      title = "Issue $number",
      url = "https://github.com/example/catalog/issues/$number",
      state = "closed",
      previewIds = previewIds,
    )

  @Test
  fun `acceptance lifecycle receives the full issue index while the panel stays filtered`() {
    val preview = ServePreview(id = "preview", label = "Preview")
    val reference =
      DesignReference(
        id = "reference",
        previewId = preview.id,
        raster = DesignReferenceRaster(path = "reference.png", sha256 = "a".repeat(64)),
      )
    val visible = issue(41, listOf(preview.id))
    val lifecycleOnly = issue(99, listOf("old-preview-locator"))

    val html =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "catalog",
        preview = preview,
        reference = reference,
        references = listOf(reference),
        token = "token",
        knownDifferences =
          KnownDifferenceScope(
            system = "catalog",
            component = "Button",
            previewId = preview.id,
            referenceId = reference.id,
            variant = "default",
            referenceSha256 = reference.raster.sha256,
          ),
        parityIssues = listOf(visible),
        acceptanceIssues = listOf(visible, lifecycleOnly),
      )

    assertTrue("\"number\":99" in html, "full issue evidence must reach the lifecycle payload")
    assertTrue("#41 Issue 41" in html, "the matching issue must remain visible in the panel")
    assertFalse("#99 Issue 99" in html, "stale locators must not leak into the filtered panel")
  }
}
