package ee.schimke.composeai.previewdriver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The environment the Tooling API hands a build. The CLI's own credentials (the design-guidelines
 * OpenRouter key) must not reach the Gradle daemon, where every build script and plugin of the
 * project being built could read them.
 */
class BuildEnvironmentWithoutTest {

  @Test
  fun `the OpenRouter key is withheld and everything else is kept`() {
    val parent =
      mapOf(
        "PATH" to "/usr/bin",
        "JAVA_HOME" to "/jdk",
        "COMPOSE_PREVIEW_OPENROUTER_KEY" to "not-a-real-key",
        "GRADLE_OPTS" to "-Xmx1g",
      )

    val env = buildEnvironmentWithout(parent)

    assertEquals(
      mapOf("PATH" to "/usr/bin", "JAVA_HOME" to "/jdk", "GRADLE_OPTS" to "-Xmx1g"),
      env,
    )
  }

  @Test
  fun `an environment holding nothing withheld keeps the Tooling API default`() {
    assertNull(buildEnvironmentWithout(mapOf("PATH" to "/usr/bin")))
  }

  @Test
  fun `the withheld set names the guidelines key`() {
    assertTrue("COMPOSE_PREVIEW_OPENROUTER_KEY" in WITHHELD_BUILD_ENVIRONMENT)
  }
}
