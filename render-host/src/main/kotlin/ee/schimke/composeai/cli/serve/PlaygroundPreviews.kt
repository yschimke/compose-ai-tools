package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.Capture
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewParams
import io.github.classgraph.ClassGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    val metadata = previewMetadata(snippet)
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
              params = metadata[id]?.params ?: PreviewParams(),
              captures = listOf(metadata[id]?.capture ?: Capture()),
            )
          },
      )
    return json.encodeToString(PreviewManifest.serializer(), manifest)
  }

  // Read bytecode, without loading or executing the snippet. The token retains preview ids but
  // not their annotations; reconstructing an id-only manifest silently changes fixed-size previews
  // into the daemon's default phone frame. This shared manifest also serves redeemed live sessions.
  private data class Metadata(val params: PreviewParams, val capture: Capture)

  private fun previewMetadata(
    snippet: PlaygroundTokenStore.PlaygroundSnippet
  ): Map<String, Metadata> {
    val classes = snippet.classesDir.toFile()
    if (!classes.exists() || !classes.canRead()) {
      System.err.println(
        "playground: preview metadata unavailable: compiled output is missing or unreadable: $classes; using default preview parameters for ${snippet.previewIds}"
      )
      return emptyMap()
    }
    return ClassGraph()
      .enableClassInfo()
      .enableMethodInfo()
      .enableAnnotationInfo()
      .ignoreMethodVisibility()
      .enableClasspathEntries(snippet.classesDir.toString())
      .scan()
      .use { scan ->
        buildMap {
          for (classInfo in scan.allClasses) {
            for (method in classInfo.methodInfo) {
              val id = "${classInfo.name}.${method.name}"
              if (id !in snippet.previewIds) continue
              val annotation =
                method.allAnnotationInfo.firstOrNull { it.name in PREVIEW_ANNOTATIONS } ?: continue
              fun dimension(name: String): Int? =
                (annotation.parameterValues.getValue(name) as? Number)?.toInt()?.takeIf { it > 0 }
              val settled =
                method.allAnnotationInfo.firstOrNull {
                  it.name == "ee.schimke.composeai.preview.SettledPreview"
                }
              val settle = settled?.let {
                // Same defaults and bounds as preview-discovery. Read bytecode only: annotations
                // normally live in catalog jars, outside the snippet output being scanned.
                val after = (it.parameterValues.getValue("afterMs") as? Number)?.toInt() ?: 0
                val max = (it.parameterValues.getValue("maxMs") as? Number)?.toInt() ?: 1000
                buildJsonObject {
                  put("afterMs", after.coerceIn(0, 5000))
                  put("maxMs", max.coerceIn(16, 5000))
                }
              }
              // A playground image is a design still. Generated entries usually declare only
              // @Preview dimensions, so two wall-clock frames otherwise capture dialog entrances.
              // Explicit motion/timeline annotations keep their existing capture path; an explicit
              // @SettledPreview coordinate above always wins over this bounded auto policy.
              val hasTimeline =
                method.allAnnotationInfo.any {
                  it.name in TIMELINE_ANNOTATIONS ||
                    (it.name == "ee.schimke.composeai.preview.FocusedPreview" &&
                      it.parameterValues.getValue("gif") == true)
                }
              val defaultSettle =
                if (snippet.mode == PlaygroundMode.CMP && !hasTimeline)
                  buildJsonObject {
                    put("afterMs", 0)
                    put("maxMs", 1000)
                  }
                else null
              put(
                id,
                Metadata(
                  PreviewParams(widthDp = dimension("widthDp"), heightDp = dimension("heightDp")),
                  Capture(settle = settle ?: defaultSettle),
                ),
              )
            }
          }
        }
          .also { found ->
            val missing = snippet.previewIds.filterNot { it in found }
            if (missing.isNotEmpty()) {
              System.err.println(
                "playground: preview metadata unavailable: no matching @Preview method in $classes for $missing; using default preview parameters"
              )
            }
          }
      }
  }

  private val TIMELINE_ANNOTATIONS =
    setOf(
      "ee.schimke.composeai.preview.AnimatedPreview",
      "ee.schimke.composeai.preview.InteractionPreview",
      "ee.schimke.composeai.preview.ScrollingPreview",
      "com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions",
    )

  private val PREVIEW_ANNOTATIONS =
    setOf(
      "androidx.compose.ui.tooling.preview.Preview",
      "androidx.compose.desktop.ui.tooling.preview.Preview",
      "org.jetbrains.compose.ui.tooling.preview.Preview",
    )
}
