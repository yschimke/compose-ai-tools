package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewParams
import io.github.classgraph.ClassGraph
import kotlinx.serialization.json.Json

/**
 * Shared synthesis of a `previews.json` for a compiled playground snippet — the manifest a
 * bundle-less daemon renders against. Both the Remote Compose capture
 * ([PlaygroundRcCaptureService]) and the Android first-frame render
 * ([PlaygroundAndroidRenderService]) stand a daemon over the snippet's own classes and need the
 * identical manifest, so the synthesis lives here rather than in either service.
 */
// Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
// and the `:server` call sites are in a different module now. Not a widened API by intent.
public object PlaygroundPreviews {

  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  /**
   * A `previews.json` the daemon can render: each discovered id split back into its `className` +
   * `functionName` (the id is `"$className.$functionName"`, per [PlaygroundPreviewDiscoverer]),
   * ordered as the snippet declared them so entry 0 is the one the still frame drew.
   */
  public fun previewManifestJson(snippet: PlaygroundTokenStore.PlaygroundSnippet): String {
    val dimensions = previewDimensions(snippet)
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
              params = dimensions[id] ?: PreviewParams(),
            )
          },
      )
    return json.encodeToString(PreviewManifest.serializer(), manifest)
  }

  // Read bytecode, without loading or executing the snippet. The token retains preview ids but
  // not their annotations; reconstructing an id-only manifest silently changes fixed-size previews
  // into the daemon's default phone frame. This shared manifest also serves redeemed live sessions.
  private fun previewDimensions(
    snippet: PlaygroundTokenStore.PlaygroundSnippet
  ): Map<String, PreviewParams> {
    if (!snippet.classesDir.toFile().isDirectory) return emptyMap()
    return ClassGraph()
      .enableMethodInfo()
      .enableAnnotationInfo()
      .ignoreMethodVisibility()
      .overrideClasspath(snippet.classesDir.toString())
      .ignoreParentClassLoaders()
      .scan()
      .use { scan ->
        buildMap {
          for (classInfo in scan.allClasses) {
            for (method in classInfo.methodInfo) {
              val id = "${classInfo.name}.${method.name}"
              if (id !in snippet.previewIds) continue
              val annotation =
                method.annotationInfo.firstOrNull { it.name in PREVIEW_ANNOTATIONS } ?: continue
              fun dimension(name: String): Int? =
                (annotation.parameterValues.getValue(name) as? Number)?.toInt()?.takeIf { it > 0 }
              put(
                id,
                PreviewParams(widthDp = dimension("widthDp"), heightDp = dimension("heightDp")),
              )
            }
          }
        }
      }
  }

  private val PREVIEW_ANNOTATIONS =
    setOf(
      "androidx.compose.ui.tooling.preview.Preview",
      "androidx.compose.desktop.ui.tooling.preview.Preview",
      "org.jetbrains.compose.ui.tooling.preview.Preview",
    )
}
