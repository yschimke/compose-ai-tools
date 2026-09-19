package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.RobolectricLaunch
import org.junit.Test

/**
 * The Gradle lane's Robolectric launch inputs, checked against the renderer's own.
 *
 * ### Why this test exists instead of a shared dependency
 *
 * These values are facts about the renderer, and compose-preview-daemon publishes them as
 * `RobolectricLaunch` precisely so that nobody keeps a second copy. `bundle/format` now takes them
 * from there (#5382). This module cannot: the Gradle plugin is a separate composite build with no
 * daemon dependency, and adding one would put `daemon-client`, `daemon-core`, kotlinx-serialization
 * and Okio on **every consumer's buildscript classpath** — a real cost paid by every project
 * applying the plugin, to save a few dozen lines here.
 *
 * So the copy stays and the *drift* is what gets removed. The dependency is test-only, and these
 * assertions fail the build when the two sets stop agreeing.
 *
 * That drift is not hypothetical: three properties the Android renderer reads
 * (`composeai.fonts.offline`, `composeai.svg.embedFonts`, `composeai.svg.background`) were
 * forwarded on this lane and missing from the CLI's copy, so an air-gapped Android render still
 * fetched Google Fonts (#5371). The two copies were kept in sync by a KDoc comment asking the
 * reader to keep them in sync.
 */
class AndroidPreviewLaunchParityTest {

  /** All four forwarded properties set, so the renderer's map is at its full width. */
  private fun rendererProperties(): Map<String, String> {
    val forwarded =
      listOf(
        "composeai.fonts.offline",
        "composeai.svg.embedFonts",
        "composeai.svg.background",
        "composeai.fonts.failOnFallback",
      )
    val saved = forwarded.associateWith { System.getProperty(it) }
    return try {
      forwarded.forEach { System.setProperty(it, "set-for-parity") }
      RobolectricLaunch.systemProperties()
    } finally {
      saved.forEach { (name, value) ->
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
      }
    }
  }

  private fun pluginProperties(): Map<String, String> =
    AndroidPreviewClasspath.buildSystemProperties(
      manifestPath = "/build/previews.json",
      rendersDir = "/build/renders",
      fontsCacheDir = "/cache/fonts",
      fontsOffline = "false",
    )

  @Test
  fun `the add-opens set is the renderer's`() {
    val rendererOpens = RobolectricLaunch.jvmArgs().filter { it.startsWith("--add-opens") }

    assertThat(AndroidPreviewClasspath.buildJvmArgs())
      .containsExactlyElementsIn(rendererOpens)
      .inOrder()
  }

  /**
   * The one deliberate difference, asserted so it stays deliberate: a Gradle render runs in a
   * `Test` task's forked JVM, whose launcher this plugin does not own, while the CLI and daemon
   * lanes build their own command line and pass `--enable-native-access` on it.
   */
  @Test
  fun `the renderer's jvm args differ only by enable-native-access`() {
    assertThat(RobolectricLaunch.jvmArgs() - AndroidPreviewClasspath.buildJvmArgs().toSet())
      .containsExactly("--enable-native-access=ALL-UNNAMED")
  }

  @Test
  fun `the robolectric flags are the renderer's, value for value`() {
    val renderer = rendererProperties()
    val plugin = pluginProperties()

    for (key in
      renderer.keys.filter { it.startsWith("robolectric.") || it.startsWith("roborazzi.") }) {
      assertThat(plugin).containsEntry(key, renderer.getValue(key))
    }
  }

  /**
   * The #5371 guard. A property the renderer reads but a lane never sets is silent: the child JVM
   * takes its default and the render is subtly wrong rather than failing. Adding one to the
   * renderer's set without adding it here fails this test.
   */
  @Test
  fun `every property the renderer reads is set on this lane too`() {
    assertThat(pluginProperties().keys).containsAtLeastElementsIn(rendererProperties().keys)
  }
}
