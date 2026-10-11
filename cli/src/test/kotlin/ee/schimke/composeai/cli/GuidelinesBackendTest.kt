package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.GuidelineCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuidelinesBackendTest {
  private val envKey = GuidelineCredentials.Resolved("sk-env", GuidelineCredentials.ENV)
  private val storedKey = GuidelineCredentials.Resolved("sk-file", "/home/u/.config/c.json")

  private fun choose(
    requested: String? = null,
    review: Boolean = true,
    env: Map<String, String> = emptyMap(),
    key: GuidelineCredentials.Resolved? = null,
    trusted: Boolean = false,
    terminal: Boolean = false,
  ) = GuidelinesBackend.choose(requested, review, { env[it] }, key, trusted, terminal)

  @Test
  fun `an explicit backend is kept, and each command has its default`() {
    assertEquals("openai", choose("openai", env = mapOf("CLAUDECODE" to "1")).backend)
    assertEquals("openrouter", choose(review = false).backend)
    assertEquals("prompt", choose(key = null).backend)
  }

  @Test
  fun `inside an agent the agent judges, whatever key is around`() {
    val choice = choose(env = mapOf("CLAUDECODE" to "1"), key = envKey)
    assertEquals("prompt", choice.backend)
    assertTrue("CLAUDECODE" in choice.reason)
    assertNull(GuidelinesBackend.agentVariable { if (it == "CLAUDECODE") "0" else null })
  }

  @Test
  fun `a key in the environment is used, a stored one when trusted or askable`() {
    assertEquals("openrouter", choose(key = envKey).backend)
    assertEquals("openrouter", choose(key = storedKey, trusted = true).backend)
    assertEquals("openrouter", choose(key = storedKey, terminal = true).backend)
    val untrusted = choose(key = storedKey)
    assertEquals("prompt", untrusted.backend)
    assertTrue("key trust" in untrusted.reason)
  }
}
