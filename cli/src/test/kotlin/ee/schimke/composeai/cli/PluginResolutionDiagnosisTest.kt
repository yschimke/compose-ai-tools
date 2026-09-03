package ee.schimke.composeai.cli

import ee.schimke.composeai.previewdriver.ProjectDiscoveryFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the publication-window diagnosis (issue #5034): the compose-preview plugin
 * marker failing to resolve at the version this run injected is named as such, instead of being
 * read as a configuration failure in the consumer's own project.
 *
 * The verbatim failure text below is the one from the issue — three release rounds' worth of
 * imports died with it, and each was diagnosed as a problem with the imported project.
 */
class PluginResolutionDiagnosisTest {

  private val raceFailure =
    """
    A problem occurred configuring root project 'thunderbird-android'.
    > Could not resolve all dependencies for configuration 'classpath'.
       > Could not find ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:1.66.1.
    """
      .trimIndent()

  @Test
  fun `recognises the marker failing to resolve at the injected version`() {
    assertTrue(isUnresolvedPluginMarkerFailure(raceFailure, version = "1.66.1"))
  }

  @Test
  fun `does not claim a failure at a different version is the one we injected`() {
    assertFalse(isUnresolvedPluginMarkerFailure(raceFailure, version = "1.67.0"))
    // …but the marker itself is still recognised, which is what lets the guidance report the
    // version out of the message rather than the one the CLI chose.
    assertTrue(isUnresolvedPluginMarkerFailure(raceFailure))
  }

  @Test
  fun `ignores an unrelated dependency failure`() {
    val unrelated = "Could not find com.squareup.okhttp3:okhttp:4.12.0. Required by: project ':app'"
    assertFalse(isUnresolvedPluginMarkerFailure(unrelated))
    assertNull(pluginResolutionGuidance(unrelated, injectedVersion = "1.66.1"))
  }

  @Test
  fun `ignores a failure that merely mentions the plugin`() {
    val applied =
      "PluginApplicationException: Failed to apply plugin 'ee.schimke.composeai.preview'. " +
        "Could not find method android() for arguments"
    assertFalse(isUnresolvedPluginMarkerFailure(applied))
  }

  @Test
  fun `a generic resolve failure naming the marker is not the race`() {
    // "Could not resolve" / "Cannot resolve external dependency" is also what an unreachable
    // proxy, a TLS failure or a build with no repositories produces — waiting does not fix those.
    val noRepositories =
      "Cannot resolve external dependency " +
        "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:1.66.1 " +
        "because no repositories are defined."
    assertFalse(isUnresolvedPluginMarkerFailure(noRepositories))
    assertNull(pluginResolutionGuidance(noRepositories, injectedVersion = "1.66.1"))
  }

  @Test
  fun `a missing artifact whose cause is transport is not the race`() {
    val proxied =
      "Could not find ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:" +
        "1.66.1. Could not GET 'https://plugins.gradle.org/…'. Received status code 407 Proxy " +
        "Authentication Required"
    assertFalse(isUnresolvedPluginMarkerFailure(proxied))
  }

  @Test
  fun `the plugins DSL form is recognised`() {
    val pluginsDsl =
      "Plugin [id: 'ee.schimke.composeai.preview', version: '1.66.1'] was not found in any of " +
        "the following sources: could not resolve plugin artifact " +
        "'ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:1.66.1'"
    assertTrue(isUnresolvedPluginMarkerFailure(pluginsDsl, version = "1.66.1"))
  }

  @Test
  fun `reads the version out of the coordinate`() {
    assertEquals("1.66.1", unresolvedPluginMarkerVersion(raceFailure))
    assertNull(unresolvedPluginMarkerVersion("Could not find something else:1.0.0"))
  }

  @Test
  fun `guidance names the version, the race and the way out`() {
    val guidance = assertNotNull(pluginResolutionGuidance(raceFailure, injectedVersion = "1.66.1"))
    assertTrue(guidance.contains("1.66.1"), guidance)
    assertTrue(guidance.contains("not with your project"), guidance)
    assertTrue(guidance.contains("publication window"), guidance)
    assertTrue(guidance.contains("compose-preview pin"), guidance)
    assertTrue(
      guidance.contains(
        "https://plugins.gradle.org/m2/ee/schimke/composeai/preview/" +
          "ee.schimke.composeai.preview.gradle.plugin/1.66.1/"
      ),
      guidance,
    )
  }

  @Test
  fun `guidance attributes the version to the pin that chose it`() {
    val guidance =
      assertNotNull(
        pluginResolutionGuidance(
          raceFailure,
          injectedVersion = "1.66.1",
          pinSource = VersionPinSource.GRADLE_PROPERTIES.display,
        )
      )
    assertTrue(guidance.contains("pinned by gradle.properties"), guidance)
  }

  @Test
  fun `guidance says the CLI chose the version when nothing pinned it`() {
    val guidance = assertNotNull(pluginResolutionGuidance(raceFailure, injectedVersion = "1.66.1"))
    assertTrue(guidance.contains("the version this CLI bundles"), guidance)
  }

  @Test
  fun `no attribution when the failing version is not the one this run injected`() {
    // A module that declares the plugin itself keeps its own version — still worth explaining, but
    // not as "pinned by …", which would name a pin that had nothing to do with it.
    val guidance =
      assertNotNull(
        pluginResolutionGuidance(
          raceFailure,
          injectedVersion = "1.68.0",
          pinSource = VersionPinSource.FLAG.display,
        )
      )
    assertTrue(guidance.contains("1.66.1"), guidance)
    assertFalse(guidance.contains("pinned by"), guidance)
  }

  @Test
  fun `reads a version that is not numeric or carries build metadata`() {
    val snapshot =
      "Could not find ee.schimke.composeai.preview:" +
        "ee.schimke.composeai.preview.gradle.plugin:dev-SNAPSHOT."
    assertEquals("dev-SNAPSHOT", unresolvedPluginMarkerVersion(snapshot))
    val metadata =
      "Could not find ee.schimke.composeai.preview:" +
        "ee.schimke.composeai.preview.gradle.plugin:1.2.3+build, required by: project ':app'"
    assertEquals("1.2.3+build", unresolvedPluginMarkerVersion(metadata))
  }

  @Test
  fun `discovery reporting leads with the race instead of the per-project list`() {
    val lines = mutableListOf<String>()
    printDiscoveryFailures(
      listOf(
        ProjectDiscoveryFailure(":app", raceFailure),
        ProjectDiscoveryFailure(":lib", raceFailure),
      ),
      err = { lines += it },
      pluginVersion = "1.66.1",
    )
    assertEquals(1, lines.size, "expected one explanation, got $lines")
    assertTrue(lines.single().contains("publication window"), lines.single())
    assertFalse(lines.single().contains("failed to configure during discovery"), lines.single())
  }

  @Test
  fun `a lone marker failure among unrelated ones does not hide them`() {
    // One project failing on the marker while others fail for their own reasons is not a reason
    // to hide theirs and send the user away to wait for a publication.
    val lines = mutableListOf<String>()
    printDiscoveryFailures(
      listOf(
        ProjectDiscoveryFailure(":app", raceFailure),
        ProjectDiscoveryFailure(":lib", "Cannot resolve external dependency"),
        ProjectDiscoveryFailure(":data", "PluginApplicationException: boom"),
      ),
      err = { lines += it },
      pluginVersion = "1.66.1",
    )
    assertTrue(lines.first().contains("publication window"), lines.first())
    assertTrue(lines.any { it.contains("3 project(s) failed to configure") }, "got $lines")
    assertTrue(lines.any { it.contains(":lib:") }, "got $lines")
    assertTrue(lines.any { it.contains(":data:") }, "got $lines")
  }

  @Test
  fun `discovery reporting is unchanged for ordinary failures`() {
    val lines = mutableListOf<String>()
    printDiscoveryFailures(
      listOf(ProjectDiscoveryFailure(":app", "Cannot resolve external dependency")),
      err = { lines += it },
      pluginVersion = "1.66.1",
    )
    assertTrue(lines.first().contains("1 project(s) failed to configure"), "got $lines")
  }
}
