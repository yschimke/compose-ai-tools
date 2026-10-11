package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.GuidelineCredentials
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuidelinesKeyCommandTest {
  private val dir = Files.createTempDirectory("guidelines-key").toFile()
  private val file = File(dir, "credentials.json")
  private val secret = "sk-or-v1-not-a-real-key-9876"

  @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

  private fun command(
    vararg args: String,
    env: Map<String, String> = emptyMap(),
    key: String? = secret,
  ) = GuidelinesKeyCommand(args.toList(), env = { env[it] }, file = file, readKey = { key })

  private fun stdout(block: () -> Int): Pair<Int, String> {
    val captured = ByteArrayOutputStream()
    val original = System.out
    System.setOut(PrintStream(captured, true))
    try {
      return block() to captured.toString()
    } finally {
      System.setOut(original)
    }
  }

  @Test
  fun `set stores the key and status shows only its tail`() {
    val (setCode, setOut) = stdout { command("set").set() }
    assertEquals(0, setCode)
    assertFalse(secret in setOut)
    assertEquals(secret, GuidelineCredentials.openRouter(env = { null }, file = file)?.key)

    val (code, out) = stdout { command("status").status() }
    assertEquals(0, code)
    assertTrue("…9876" in out)
    assertTrue(file.path in out)
    assertFalse(secret in out)
  }

  @Test
  fun `status says when the environment overrides the file`() {
    stdout { command("set").set() }
    val (_, out) =
      stdout {
        command("status", "--json", env = mapOf(GuidelineCredentials.ENV to "sk-or-env-abcd"))
          .status()
      }
    assertTrue("\"source\":\"${GuidelineCredentials.ENV}\"" in out)
    assertFalse("sk-or-env-abcd" in out)
    assertFalse(secret in out)
  }

  @Test
  fun `forget removes the key and status then exits 1`() {
    stdout { command("set").set() }
    assertEquals(0, stdout { command("forget").forget() }.first)
    assertEquals(1, stdout { command("status").status() }.first)
  }

  @Test
  fun `a key that is not one word is refused and nothing is written`() {
    assertEquals(1, command("set", key = "two words").set())
    assertEquals(1, command("set", key = null).set())
    assertFalse(file.exists())
  }

  @Test
  fun `an untrusted project is refused without a terminal and nothing is remembered`() {
    stdout { command("set").set() }
    val project = File(dir, "app").apply { mkdirs() }
    assertFalse(GuidelinesKeyCommand.confirmTrust(file, project, ask = null))
    assertFalse(GuidelineCredentials.isTrusted(file, project))
  }

  @Test
  fun `answering yes trusts the project once and for all, anything else declines`() {
    stdout { command("set").set() }
    val project = File(dir, "app").apply { mkdirs() }
    var asked = 0
    assertFalse(
      GuidelinesKeyCommand.confirmTrust(file, project) {
        asked++
        ""
      }
    )
    assertFalse(GuidelineCredentials.isTrusted(file, project))
    assertTrue(
      GuidelinesKeyCommand.confirmTrust(file, project) {
        asked++
        "y"
      }
    )
    assertTrue(
      GuidelinesKeyCommand.confirmTrust(file, project) {
        asked++
        "n"
      }
    )
    assertEquals(2, asked)
  }

  @Test
  fun `trust and untrust take a directory`() {
    stdout { command("set").set() }
    val project = File(dir, "app").apply { mkdirs() }
    assertEquals(0, stdout { command("trust", project.path).trust() }.first)
    assertTrue(GuidelineCredentials.isTrusted(file, project))
    val (_, out) = stdout { command("status").status() }
    assertTrue(project.canonicalPath in out)
    assertEquals(0, stdout { command("untrust", project.path).untrust() }.first)
    assertFalse(GuidelineCredentials.isTrusted(file, project))
    assertEquals(1, stdout { command("trust", File(dir, "missing").path).trust() }.first)
  }
}
