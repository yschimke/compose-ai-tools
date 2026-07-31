package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.PreviewInfo
import ee.schimke.composeai.cli.PreviewManifest
import kotlinx.serialization.json.Json

/**
 * Shared synthesis of a one-preview `previews.json` for a compiled playground snippet — the
 * manifest a bundle-less daemon renders against. Both the Remote Compose capture
 * ([PlaygroundRcCaptureService]) and the Android first-frame render
 * ([PlaygroundAndroidRenderService]) stand a daemon over the snippet's own classes and need the
 * identical single-entry manifest, so the synthesis lives here rather than in either service.
 */
internal object PlaygroundPreviews {

  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  /**
   * A one-preview `previews.json` the daemon can render: the snippet's discovered id split back
   * into its `className` + `functionName` (the id is `"$className.$functionName"`, per
   * [PlaygroundPreviewDiscoverer]).
   */
  fun singlePreviewManifestJson(snippet: PlaygroundTokenStore.PlaygroundSnippet): String {
    val id = snippet.previewId
    val manifest =
      PreviewManifest(
        module = snippet.moduleName,
        variant = "",
        previews =
          listOf(
            PreviewInfo(
              id = id,
              functionName = id.substringAfterLast('.'),
              className = id.substringBeforeLast('.'),
            )
          ),
      )
    return json.encodeToString(PreviewManifest.serializer(), manifest)
  }
}
