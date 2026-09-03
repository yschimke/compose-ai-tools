package ee.schimke.composeai.previewdriver

import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `a pinned checksum is refused rather than silently dropped`() {
    // `useDistribution(URI)` carries a URL and nothing else, so inheriting a pinned distribution
    // that is not already cached would download and run it with the repository's integrity pin
    // gone. Refuse — a warning does not protect the invocation that is happening now.
    val repo = dir("pinned")
    pinnedWrapper(repo)
    val warnings = mutableListOf<String>()
    val resolved =
      inheritedWrapperDistribution(
        dir("pinned/nested"),
        warn = { warnings += it },
        gradleUserHome = dir("empty-gradle-home"),
      )
    assertNull(resolved, "a distribution whose checksum cannot be honoured must not be inherited")
    assertTrue(warnings.single().contains("distributionSha256Sum"), warnings.toString())
  }

  @Test
  fun `a pinned distribution already in the wrapper cache is inherited`() {
    // The wrapper downloaded and verified this copy; reusing it involves no download, so no
    // unverified bytes reach the build.
    val repo = dir("cached")
    pinnedWrapper(repo)
    val home = dir("cached-gradle-home")
    val unpacked = File(home, "wrapper/dists/gradle-9.7-bin/a1b2c3")
    unpacked.mkdirs()
    File(unpacked, "gradle-9.7-bin.zip.ok").writeText("")

    val warnings = mutableListOf<String>()
    val resolved =
      inheritedWrapperDistribution(
        dir("cached/nested"),
        warn = { warnings += it },
        gradleUserHome = home,
      )
    assertEquals(
      "https://services.gradle.org/distributions/gradle-9.7-bin.zip",
      resolved?.toString(),
    )
    assertTrue(warnings.isEmpty(), "nothing to warn about when no download is needed: $warnings")
  }

  @Test
  fun `the warning does not leak credentials from a private distribution URL`() {
    val repo = dir("private")
    File(repo, "gradle/wrapper").mkdirs()
    File(repo, "gradle/wrapper/gradle-wrapper.properties")
      .writeText(
        "distributionUrl=https\\://ci:s3cr3t-token@dist.internal/gradle-9.7-bin.zip?sig=abc\n" +
          "distributionSha256Sum=aaaabbbbccccdddd\n"
      )
    val warnings = mutableListOf<String>()
    inheritedWrapperDistribution(
      dir("private/nested"),
      warn = { warnings += it },
      gradleUserHome = dir("empty-home-2"),
    )
    val warning = warnings.single()
    assertFalse(warning.contains("s3cr3t-token"), warning)
    assertFalse(warning.contains("sig=abc"), warning)
    assertTrue(warning.contains("dist.internal"), warning)
  }

  @Test
  fun `redaction keeps the host and drops userinfo and query`() {
    assertEquals(
      "https://dist.internal/gradle-9.7-bin.zip?…",
      redactedDistribution(URI("https://ci:s3cr3t@dist.internal/gradle-9.7-bin.zip?sig=abc")),
    )
  }

  private fun pinnedWrapper(repo: File) {
    File(repo, "gradle/wrapper").mkdirs()
    File(repo, "gradle/wrapper/gradle-wrapper.properties")
      .writeText(
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7-bin.zip\n" +
          "distributionSha256Sum=aaaabbbbccccdddd\n"
      )
  }
}
