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
 * Fails desktop preview tasks before launch when their JVM classpath contains AndroidX Compose
 * artifacts. Compose Multiplatform desktop uses JetBrains Compose artifacts with the same
 * `androidx.compose.*` packages; AndroidX JVM stubs can resolve first and throw
 * `NotImplementedError: Implemented only in JetBrains fork` from `ImageComposeScene`.
 */
@CacheableTask
abstract class ValidateComposePreviewClasspathTask : DefaultTask() {

  @get:Input abstract val platform: Property<String>

  /**
   * Resolved runtime classpath. Marked [Classpath] (not [Internal] + a derived absolute-path
   * `@Input`) so the cache key is content-hashed and machine-independent — two workstations with
   * the same JARs in different `~/.gradle/caches/...` paths share a cache hit. The validation
   * outcome itself only ever inspects path substrings (`/androidx.compose.ui/`, `jvmstubs` filename
   * markers), which travel with the artifact wherever it lives, so a content-keyed cache is a sound
   * proxy for "we already validated this set of JARs".
   */
  @get:Classpath abstract val classpath: ConfigurableFileCollection

  /**
   * The TOOL half of [classpath] on its own — `composePreviewRenderer`, without the consumer's
   * runtime classpath. Only the skiko check reads it, and only because that check is the one whose
   * answer changes when two independently-resolved classpaths are concatenated: see [reportSkiko].
   * [Classpath] for the same content-keyed reason as [classpath]; the files are also in
   * [classpath], so hashing them twice costs a hash, not a resolve.
   */
  @get:Classpath abstract val toolClasspath: ConfigurableFileCollection

  /**
   * Derived helper used by [validate] to feed the [androidxComposeArtifactsOnDesktopClasspath]
   * substring matcher. Intentionally `@Internal` — the cache key flows through [classpath]'s
   * content hash, not through these absolute path strings (which would otherwise pin the cache to a
   * single workstation).
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
        // The most common way `*-android` Compose artifacts reach the desktop renderer is a
        // `com.android.kotlin.multiplatform.library` (`:shared`-style) module with no
        // `jvm("desktop")` target: the desktop pipeline then falls back to
        // `androidRuntimeClasspath`
        // and surfaces the `*-android` AARs (which reference `android.os.Parcelable`) to the host
        // JVM. The fix is to give the module a JVM-flavoured runtime classpath.
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
   * Say which skiko each classpath resolved, and stop when ONE of them resolved a mismatched pair.
   *
   * skiko is where a Compose Multiplatform bump reaches the renderer's own call sites: 0.150.0
   * changed `Image.encodeToData`'s parameter list, and because skiko resolves to a single version
   * within any one resolved classpath, a consumer on a newer Compose line silently decides which
   * API the tool is running against (compose-ai-tools#4190). The encode itself binds late now, so
   * that much is a diagnostic and not a gate — but the version belongs in the log, because the next
   * skiko change will be diagnosed from it.
   *
   * The failure is an API jar and a platform native runtime jar at different versions **inside one
   * scope**: they are a matched pair, and a skew between them loads a `libskiko` whose exports the
   * API does not declare, so every render dies with `UnsatisfiedLinkError` at draw time.
   *
   * Scope is the whole point, and #4200's first cut of this check did not have it (#4234). The
   * render classpath this task validates is a CONCATENATION of two independently-resolved
   * classpaths — the tool's `composePreviewRenderer` and the consumer's own `runtimeClasspath` (see
   * `ComposePreviewTasks.registerDesktopClasspathGuard`). Each resolves its own coherent skiko
   * pair, and any consumer whose Compose Multiplatform line differs from the tool's therefore puts
   * two DIFFERENT but individually-matched pairs on one classpath. That is not the skew this guard
   * exists for: the JVM loads the first `org.jetbrains.skia` class and the first `libskiko` it
   * finds, both from whichever pair leads the classpath, and the trailing pair is shadowed whole.
   * Counting distinct versions across the concatenation called that a skew and hard-failed every
   * consumer on an older Compose line — including this plugin's own bundle E2E fixture, which pins
   * `org.jetbrains.compose` 1.10.3 (skiko 0.9.37.4) against a tool on 1.11.1 (skiko 0.144.6) and
   * had been rendering correctly for as long as that gap existed.
   *
   * So: check each scope on its own terms, and say — at `info`, not as a failure — when they
   * disagree, because which pair leads the classpath is worth being able to read back out of a log.
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
     * Every distinct skiko version named by a classpath entry, read off the artifact filenames
     * (`skiko-awt-0.150.1.jar`, `skiko-awt-runtime-macos-arm64-0.150.1.jar`). Filenames because the
     * task sees resolved files, not a dependency graph — and the version travels with the artifact.
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
     * One independently-resolved classpath and the skiko versions it named. [label] is what the
     * failure calls it, so it has to read as a place a version came from rather than as jargon.
     */
    internal data class SkikoScope(val label: String, val versions: List<String>)

    /**
     * Split the validated classpath back into the two scopes it was concatenated from — the tool's
     * renderer classpath ([toolPaths]) and everything else, which is the consumer's runtime
     * classpath — and read each one's skiko versions separately. See [reportSkiko] for why the
     * split is what makes the answer correct.
     *
     * A caller that sets no tool classpath (nothing does today; the guard is registered in one
     * place) degrades to a single "render" scope rather than mis-reporting the consumer's jars as
     * the tool's.
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
