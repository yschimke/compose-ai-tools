package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * Locks the CLI mirror of `PreviewManifest.reportsView`. The v1 `accessibilityReport` alias was
 * removed after one transition release, so the view is a thin pass-through over the v2 map today —
 * but the seam stays so future wire-format evolutions can land here without touching every
 * callsite. These tests pin the pass-through plus the empty-manifest behaviour the strategy layer
 * relies on.
 */
class PreviewManifestReportsViewTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `v2 manifest exposes dataExtensionReports via reportsView`() {
    val payload =
      """
      {
        "module": "sample",
        "variant": "debug",
        "previews": [],
        "dataExtensionReports": {"a11y": "accessibility.json"}
      }
      """
        .trimIndent()
    val manifest = json.decodeFromString(PreviewManifest.serializer(), payload)
    assertEquals(mapOf("a11y" to "accessibility.json"), manifest.reportsView)
  }

  @Test
  fun `manifest without dataExtensionReports returns empty reportsView`() {
    val payload =
      """
      {"module": "sample", "variant": "debug", "previews": []}
      """
        .trimIndent()
    val manifest = json.decodeFromString(PreviewManifest.serializer(), payload)
    assertEquals(emptyMap<String, String>(), manifest.reportsView)
  }

  @Test
  fun `unknown legacy fields are tolerated and ignored`() {
    // Regression guard: a manifest written by an older plugin that still emits the v1
    // `accessibilityReport` alias must still parse cleanly under `ignoreUnknownKeys = true`. The
    // value is dropped on the floor — older plugins paired with this CLI will silently miss
    // a11y findings, which is the documented transition contract.
    val payload =
      """
      {
        "module": "sample",
        "variant": "debug",
        "previews": [],
        "accessibilityReport": "accessibility.json"
      }
      """
        .trimIndent()
    val manifest = json.decodeFromString(PreviewManifest.serializer(), payload)
    assertEquals(emptyMap<String, String>(), manifest.reportsView)
  }
}
