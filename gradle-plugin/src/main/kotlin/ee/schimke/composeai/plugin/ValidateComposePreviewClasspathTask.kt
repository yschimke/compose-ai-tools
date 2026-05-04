package ee.schimke.composeai.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
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

  @get:Internal abstract val classpath: ConfigurableFileCollection

  @get:Input
  val classpathPaths: List<String>
    get() = classpath.files.map { it.absolutePath }

  init {
    group = "compose preview"
    description = "Validate the compose-preview runtime classpath for platform-specific artifacts"
    dependsOn(classpath)
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
