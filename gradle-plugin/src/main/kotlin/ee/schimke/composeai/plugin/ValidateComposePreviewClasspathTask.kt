package ee.schimke.composeai.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Fails desktop preview tasks before launch when the classpath has AndroidX Compose artifacts: they
 * share `androidx.compose.*` packages with JetBrains Compose and can throw `NotImplementedError:
 * Implemented only in JetBrains fork` from `ImageComposeScene`.
 */
@CacheableTask
abstract class ValidateComposePreviewClasspathTask : DefaultTask() {

  @get:Input abstract val platform: Property<String>

  /**
   * [Classpath] so the cache key is content-hashed and machine-independent; validation only
   * inspects path substrings that travel with the artifact.
   */
  @get:Classpath abstract val classpath: ConfigurableFileCollection

  /**
   * The tool classpath (`composePreviewRenderer`) alone, for the skiko check (see [reportSkiko]).
   * Its files are also in [classpath].
   */
  @get:Classpath abstract val toolClasspath: ConfigurableFileCollection

  /**
   * `@Internal`: absolute paths would pin the cache to one machine; [classpath]'s content hash is
   * the key.
   */
  @get:Internal
  val classpathPaths: List<String>
    get() = classpath.files.map { it.absolutePath }

  /** [toolClasspath] as absolute paths. `@Internal` for the same reason as [classpathPaths]. */
  @get:Internal
  val toolClasspathPaths: List<String>
    get() = toolClasspath.files.map { it.absolutePath }

  init {
    group = "compose preview"
    description = "Validate the compose-preview runtime classpath for platform-specific artifacts"
  }

  @TaskAction
  fun validate() {
    if (platform.get() != "desktop") return

    val tool = toolClasspathPaths.toSet()
    reportSkiko(skikoScopes(toolPaths = tool, allPaths = classpathPaths))

    val offenders = androidxComposeArtifactsOnDesktopClasspath(classpathPaths)
    if (offenders.isEmpty()) return

    throw GradleException(
      buildString {
        appendLine(
          "Compose Preview desktop classpath contains AndroidX Compose UI artifacts. " +
            "Use org.jetbrains.compose UI artifacts for Compose Multiplatform desktop classpaths."
        )
        offenders.take(8).forEach { appendLine(" - $it") }
        if (offenders.size > 8) appendLine(" - (+${offenders.size - 8} more)")
        // Usually a KMP-Android module without `jvm("desktop")`, falling back to
        // `androidRuntimeClasspath`; the fix is a JVM-flavoured runtime classpath.
        appendLine()
        appendLine(
          "If this is a com.android.kotlin.multiplatform.library (:shared) module, add a " +
            "`jvm(\"desktop\")` target to its `kotlin { }` block so androidMain previews render " +
            "through the Compose Multiplatform Desktop pipeline. See " +
            "compose-preview/references/cmp-shared.md."
        )
      }
    )
  }

  /**
   * Logs each classpath scope's skiko version, failing only when one scope has a mismatched pair.
   *
   * skiko is where Compose bumps reach the renderer's call sites (e.g. `Image.encodeToData`,
   * #4190), so its version belongs in the log. The failure case is an API jar and native runtime
   * jar at different versions within one scope, which loads a `libskiko` missing exports →
   * `UnsatisfiedLinkError`.
   *
   * Per scope, because the validated classpath concatenates the tool's and the consumer's
   * independently-resolved classpaths, each with its own coherent pair; the leading pair shadows
   * the other. Counting versions across both hard-failed every consumer on a different Compose line
   * (#4234). Cross-scope disagreement is logged at `info`.
   */
  private fun reportSkiko(scopes: List<SkikoScope>) {
    val skewed = scopes.filter { it.versions.size > 1 }
    if (skewed.isNotEmpty()) {
      throw GradleException(
        buildString {
          skewed.forEach { scope ->
            appendLine(
              "The ${scope.label} classpath resolved more than one skiko version " +
                "(${scope.versions.joinToString()}). The skiko API jar and its platform native " +
                "runtime must match — a skew loads a libskiko whose exports the API does not " +
                "declare, and every render fails at draw time."
            )
          }
          append("Align them through a single Compose Multiplatform version.")
        }
      )
    }
    val resolved = scopes.filter { it.versions.isNotEmpty() }
    when (resolved.map { it.versions.single() }.distinct().size) {
      0 -> Unit
      1 ->
        logger.info(
          "Compose Preview desktop classpath: skiko ${resolved.first().versions.single()}"
        )
      else ->
        logger.info(
          "Compose Preview desktop classpath carries two coherent skiko pairs — " +
            resolved.joinToString { "${it.label} ${it.versions.single()}" } +
            ". The one earlier on the classpath is the one that loads; the other is shadowed."
        )
    }
  }

  internal companion object {
    /**
     * Distinct skiko versions from artifact filenames (`skiko-awt-0.150.1.jar`, …); the task sees
     * files, not a graph.
     */
    fun skikoVersionsOnClasspath(paths: Iterable<String>): List<String> =
      paths
        .mapNotNull { path ->
          val filename = path.replace('\\', '/').substringAfterLast('/')
          SKIKO_ARTIFACT.matchEntire(filename)?.groupValues?.get(1)
        }
        .distinct()
        .sorted()

    /** `skiko`, `skiko-awt`, `skiko-awt-runtime-<platform>` — anything but the version suffix. */
    private val SKIKO_ARTIFACT = Regex("""^skiko(?:-[a-z0-9]+)*-(\d[\w.\-]*)\.jar$""")

    /**
     * One independently-resolved classpath and its skiko versions; [label] names where they came
     * from.
     */
    internal data class SkikoScope(val label: String, val versions: List<String>)

    /**
     * Splits the validated classpath into the tool scope ([toolPaths]) and the consumer scope
     * (everything else); see [reportSkiko]. Without a tool classpath it degrades to one "render"
     * scope.
     */
    fun skikoScopes(toolPaths: Set<String>, allPaths: Iterable<String>): List<SkikoScope> {
      if (toolPaths.isEmpty()) {
        return listOf(SkikoScope("render", skikoVersionsOnClasspath(allPaths)))
      }
      val consumerPaths = allPaths.filterNot { it in toolPaths }
      return listOf(
        SkikoScope("compose-preview renderer", skikoVersionsOnClasspath(toolPaths)),
        SkikoScope("consumer runtime", skikoVersionsOnClasspath(consumerPaths)),
      )
    }

    fun androidxComposeArtifactsOnDesktopClasspath(paths: Iterable<String>): List<String> =
      paths
        .filter { path ->
          val normalized = path.replace('\\', '/')
          val filename = normalized.substringAfterLast('/')
          normalized.contains("/androidx.compose.ui/") ||
            normalized.contains("/androidx/compose/ui/") ||
            filename.startsWith("androidx.compose.ui.") ||
            (normalized.contains("/androidx.compose.") && filename.contains("jvmstubs"))
        }
        .distinct()
        .sorted()
  }
}
