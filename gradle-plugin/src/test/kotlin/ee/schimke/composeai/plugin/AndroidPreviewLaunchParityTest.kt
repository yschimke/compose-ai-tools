package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.RobolectricLaunch
import org.junit.Test

/**
 * The Gradle lane's Robolectric launch inputs, checked against the renderer's `RobolectricLaunch`.
 * The plugin keeps its own copy because depending on the daemon would put `daemon-client`,
 * `daemon-core`, kotlinx-serialization and Okio on every consumer's buildscript classpath; this
 * test-only dependency removes the drift instead (which already cost offline fonts, see #5371).
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
   * The one deliberate difference: the Gradle lane's forked `Test` JVM launcher isn't ours, so no
   * `--enable-native-access`; the CLI and daemon lanes pass it.
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
   * A renderer property a lane never sets silently takes its default; adding one to the renderer
   * without adding it here fails.
   */
  @Test
  fun `every property the renderer reads is set on this lane too`() {
    assertThat(pluginProperties().keys).containsAtLeastElementsIn(rendererProperties().keys)
  }
}
