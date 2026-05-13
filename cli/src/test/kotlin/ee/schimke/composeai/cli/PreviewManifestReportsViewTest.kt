package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * Covers the CLI mirror of `PreviewManifest`'s v1↔v2 compatibility surface:
 * - v2 manifests carry `dataExtensionReports`; old `accessibilityReport` is left null by the plugin
 *   but optionally mirrored — either way [reportsView] returns the map.
 * - v1 manifests (older plugin, only `accessibilityReport` populated) synthesise an `"a11y"` entry
 *   through [reportsView] so the strategy layer doesn't need to know about the wire version.
 *
 * These are pure JSON round-trip tests; the on-disk plugin behaviour is covered by
 * `AccessibilityFunctionalTest` in `:gradle-plugin:functionalTest`.
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
  fun `v1 manifest with only accessibilityReport still produces an a11y entry`() {
    // Mimics a manifest produced by an older plugin version that only knew about the legacy
    // field. The CLI must still surface findings on the new code path.
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
    assertEquals(mapOf("a11y" to "accessibility.json"), manifest.reportsView)
  }

  @Test
  fun `manifest with neither field returns empty reportsView`() {
    val payload =
      """
      {"module": "sample", "variant": "debug", "previews": []}
      """
        .trimIndent()
    val manifest = json.decodeFromString(PreviewManifest.serializer(), payload)
    assertEquals(emptyMap<String, String>(), manifest.reportsView)
  }

  @Test
  fun `v2 map takes precedence when both fields are present`() {
    // A plugin that mirrors `accessibilityReport` for back-compat will set both fields; the
    // map is the source of truth, so a hypothetical drift wins for v2 consumers.
    val payload =
      """
      {
        "module": "sample",
        "variant": "debug",
        "previews": [],
        "dataExtensionReports": {"a11y": "v2-path.json"},
        "accessibilityReport": "v1-path.json"
      }
      """
        .trimIndent()
    val manifest = json.decodeFromString(PreviewManifest.serializer(), payload)
    assertEquals(mapOf("a11y" to "v2-path.json"), manifest.reportsView)
  }
}
