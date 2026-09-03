package ee.schimke.composeai.previewdriver

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which Gradle distribution drives a build root that has no wrapper of its own (issue #5031).
 *
 * `GradleConnector.forProjectDirectory` reads the distribution from *that* directory's wrapper
 * properties and falls back to the Tooling API's own default when there is none — so a nested build
 * borrowing its parent repository's wrapper would be driven by a Gradle the repository never chose.
 * [inheritedWrapperDistribution] supplies the ancestor's instead, and stays out of the way when the
 * directory has a wrapper of its own.
 */
class InheritedWrapperDistributionTest {

  private val tmp: File = Files.createTempDirectory("wrapper-dist").toFile()

  @AfterTest
  fun cleanUp() {
    tmp.deleteRecursively()
  }

  private fun wrapper(dir: File, distributionUrl: String) {
    File(dir, "gradle/wrapper").mkdirs()
    File(dir, "gradle/wrapper/gradle-wrapper.properties")
      .writeText("distributionBase=GRADLE_USER_HOME\ndistributionUrl=$distributionUrl\n")
  }

  private fun dir(path: String): File = File(tmp, path).apply { mkdirs() }

  @Test
  fun `inherits the nearest ancestor's distribution`() {
    val repo = dir("repo")
    // Written the way the wrapper writes it: a properties file escapes the scheme colon.
    wrapper(repo, "https\\://services.gradle.org/distributions/gradle-9.7-bin.zip")
    val nested = dir("repo/components")

    assertEquals(
      "https://services.gradle.org/distributions/gradle-9.7-bin.zip",
      inheritedWrapperDistribution(nested)?.toString(),
    )
  }

  @Test
  fun `leaves a directory with its own wrapper alone`() {
    val repo = dir("own")
    wrapper(repo, "https\\://services.gradle.org/distributions/gradle-9.7-bin.zip")
    assertNull(inheritedWrapperDistribution(repo))
  }

  @Test
  fun `null when no ancestor has a wrapper`() {
    assertNull(inheritedWrapperDistribution(dir("bare/deep")))
  }

  @Test
  fun `resolves a relative distributionUrl against the wrapper directory`() {
    // The wrapper resolves a relative distributionUrl against the directory holding
    // gradle-wrapper.properties, which is how a locally vendored distribution is normally written.
    val repo = dir("relative")
    wrapper(repo, "gradle-9.7-bin.zip")
    val resolved = inheritedWrapperDistribution(dir("relative/nested"))
    assertEquals(
      File(repo, "gradle/wrapper/gradle-9.7-bin.zip").toURI().normalize(),
      resolved?.normalize(),
    )
  }

  @Test
  fun `says so when the inherited wrapper pins a checksum it cannot carry over`() {
    val repo = dir("pinned")
    File(repo, "gradle/wrapper").mkdirs()
    File(repo, "gradle/wrapper/gradle-wrapper.properties")
      .writeText(
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7-bin.zip\n" +
          "distributionSha256Sum=aaaabbbbccccdddd\n"
      )
    val warnings = mutableListOf<String>()
    val resolved = inheritedWrapperDistribution(dir("pinned/nested")) { warnings += it }
    assertEquals(
      "https://services.gradle.org/distributions/gradle-9.7-bin.zip",
      resolved?.toString(),
    )
    assertTrue(
      warnings.single().contains("distributionSha256Sum"),
      "expected the dropped checksum to be named; got $warnings",
    )
  }
}
