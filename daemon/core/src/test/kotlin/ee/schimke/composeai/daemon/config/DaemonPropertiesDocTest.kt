package ee.schimke.composeai.daemon.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `docs/daemon/TUNABLES.md` is generated from [DaemonProperties.renderMarkdown]; this test is the
 * gate that keeps the checked-in copy honest.
 *
 * Regenerate with:
 * ```
 * ./gradlew :daemon:core:test --tests '*DaemonPropertiesDocTest*' -Pcomposeai.docs.regenerate=true
 * ```
 */
class DaemonPropertiesDocTest {

  @Test
  fun generatedTableMatchesCheckedInDoc() {
    val expected = DaemonProperties.renderMarkdown()
    val doc = File(repoRoot(), "docs/daemon/TUNABLES.md")

    if (System.getProperty("composeai.docs.regenerate") == "true") {
      doc.parentFile.mkdirs()
      doc.writeText(expected)
      return
    }

    assertEquals(
      "docs/daemon/TUNABLES.md is generated from DaemonProperties and is out of date. " +
        "Regenerate with: ./gradlew :daemon:core:test --tests '*DaemonPropertiesDocTest*' " +
        "-Pcomposeai.docs.regenerate=true",
      expected,
      if (doc.isFile) doc.readText() else "",
    )
  }

  private fun repoRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("could not locate the repository root from ${File("").absolutePath}")
  }
}
