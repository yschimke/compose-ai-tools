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

  internal companion object {
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
