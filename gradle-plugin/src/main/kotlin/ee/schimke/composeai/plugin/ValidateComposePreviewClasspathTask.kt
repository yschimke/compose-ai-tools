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
   * Derived helper used by [validate] to feed the [androidxComposeArtifactsOnDesktopClasspath]
   * substring matcher. Intentionally `@Internal` — the cache key flows through [classpath]'s
   * content hash, not through these absolute path strings (which would otherwise pin the cache to a
   * single workstation).
   */
  @get:Internal
  val classpathPaths: List<String>
    get() = classpath.files.map { it.absolutePath }

  init {
    group = "compose preview"
    description = "Validate the compose-preview runtime classpath for platform-specific artifacts"
  }

  @TaskAction
  fun validate() {
    if (platform.get() != "desktop") return

    reportSkiko(skikoVersionsOnClasspath(classpathPaths))

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
   * Say which skiko this classpath resolved, and stop when it resolved more than one.
   *
   * skiko is where a Compose Multiplatform bump reaches the renderer's own call sites: 0.150.0
   * changed `Image.encodeToData`'s parameter list, and because skiko resolves to a SINGLE version
   * across the render classpath, a consumer on a newer Compose line silently decides which API the
   * tool is running against (compose-ai-tools#4190). The encode itself binds late now, so this is a
   * diagnostic and not a gate — but the version belongs in the log, because the next skiko change
   * will be diagnosed from it.
   *
   * Two DIFFERENT versions is the one case that fails. The API jar and the platform's native
   * runtime jar are a matched pair; a skew between them loads a `libskiko` whose exports the API
   * does not have, and every render dies with `UnsatisfiedLinkError` at draw time instead.
   */
  private fun reportSkiko(versions: List<String>) {
    when (versions.size) {
      0 -> Unit
      1 -> logger.info("Compose Preview desktop classpath: skiko ${versions.single()}")
      else ->
        throw GradleException(
          "Compose Preview desktop classpath resolved more than one skiko version " +
            "(${versions.joinToString()}). The skiko API jar and its platform native runtime must " +
            "match — a skew loads a libskiko whose exports the API does not declare, and every " +
            "render fails at draw time. Align them through a single Compose Multiplatform version."
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
