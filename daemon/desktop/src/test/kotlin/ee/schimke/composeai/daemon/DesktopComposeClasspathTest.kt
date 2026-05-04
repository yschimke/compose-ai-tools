package ee.schimke.composeai.daemon

import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopComposeClasspathTest {

  @Test
  fun desktopClasspathDoesNotContainAndroidxComposeUiArtifacts() {
    val offenders =
      System.getProperty("java.class.path")
        .split(java.io.File.pathSeparatorChar)
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

    assertTrue(
      "Desktop daemon test classpath contains AndroidX Compose UI artifacts. " +
        "Use org.jetbrains.compose UI artifacts for Compose Multiplatform desktop.\n" +
        offenders.joinToString(separator = "\n") { " - $it" },
      offenders.isEmpty(),
    )
  }
}
