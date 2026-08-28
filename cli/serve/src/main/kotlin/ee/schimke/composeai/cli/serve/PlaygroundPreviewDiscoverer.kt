package ee.schimke.composeai.cli.serve

import io.github.classgraph.ClassGraph
import okio.Path

/**
 * Finds the `@Preview` id(s) in a just-compiled playground snippet — the production backing for
 * [PlaygroundCompileService.PreviewDiscoverer].
 *
 * A scoped ClassGraph scan of **only** the snippet's `classesDir`: since that directory holds just
 * the snippet's compiled output, every `@Preview`-annotated method in it is the snippet's own (no
 * source-file filtering needed, unlike `:daemon:core`'s incremental `IncrementalDiscovery`, which
 * scans a shared classpath and must filter by source file). The id shape —
 * `<className>.<methodName>` — matches what the gradle plugin emits and what the render pipeline
 * expects, so the token's `previewId` renders directly.
 *
 * Only **direct** `@Preview` annotations are recognised in v1; multi-preview meta-annotations
 * (`@LightDarkPreviews` → many) are a follow-up. Fail-safe: returns an empty list (logging) on any
 * scan failure, which the orchestrator reports as "no @Preview found".
 */
class PlaygroundPreviewDiscoverer(
  private val previewAnnotationFqns: Set<String> = DEFAULT_PREVIEW_ANNOTATION_FQNS
) : PlaygroundCompileService.PreviewDiscoverer {

  override fun discover(classesDir: Path, classpath: List<Path>): List<String> =
    try {
      ClassGraph()
        .enableMethodInfo()
        .enableAnnotationInfo()
        .ignoreMethodVisibility()
        .overrideClasspath(classesDir.toNioPath().toAbsolutePath().toString())
        .ignoreParentClassLoaders()
        .scan()
        .use { scan ->
          val ids = LinkedHashSet<String>()
          for (classInfo in scan.allClasses) {
            for (method in classInfo.methodInfo) {
              val isPreview =
                method.annotationInfo?.any { it.name in previewAnnotationFqns } == true
              if (isPreview) ids.add(previewId(classInfo.name, method.name))
            }
          }
          ids.toList()
        }
    } catch (t: Throwable) {
      System.err.println(
        "playground: preview discovery failed (${t.javaClass.simpleName}: ${t.message}); none found"
      )
      emptyList()
    }

  companion object {
    /**
     * The `@Preview` annotation FQNs a playground snippet can use: Android Compose and Compose
     * Multiplatform (`org.jetbrains.compose.*`). Mirrors `:daemon:core`'s default set.
     */
    val DEFAULT_PREVIEW_ANNOTATION_FQNS =
      setOf(
        "androidx.compose.ui.tooling.preview.Preview",
        "org.jetbrains.compose.ui.tooling.preview.Preview",
      )

    /** The `<className>.<methodName>` id the render pipeline keys a preview by. */
    internal fun previewId(className: String, methodName: String): String = "$className.$methodName"
  }
}
