package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import kotlinx.serialization.json.Json

/**
 * Shared synthesis of a `previews.json` for a compiled playground snippet — the manifest a
 * bundle-less daemon renders against. Both the Remote Compose capture
 * ([PlaygroundRcCaptureService]) and the Android first-frame render
 * ([PlaygroundAndroidRenderService]) stand a daemon over the snippet's own classes and need the
 * identical manifest, so the synthesis lives here rather than in either service.
 */
internal object PlaygroundPreviews {

  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  /**
   * A `previews.json` the daemon can render: each discovered id split back into its `className` +
   * `functionName` (the id is `"$className.$functionName"`, per [PlaygroundPreviewDiscoverer]),
   * ordered as the snippet declared them so entry 0 is the one the still frame drew.
   */
  fun previewManifestJson(snippet: PlaygroundTokenStore.PlaygroundSnippet): String {
    val manifest =
      PreviewManifest(
        module = snippet.moduleName,
        variant = "",
        // EVERY preview the snippet declared, not just the one the still frame drew. The daemon
        // resolves a streamed preview by looking it up here, so an id absent from this list is one
        // the live session can never show — which is what limited a redeemed snippet to a single
        // preview no matter how many it compiled.
        previews =
          snippet.previewIds.map { id ->
            PreviewInfo(
              id = id,
              functionName = id.substringAfterLast('.'),
              className = id.substringBeforeLast('.'),
            )
          },
      )
    return json.encodeToString(PreviewManifest.serializer(), manifest)
  }
}
