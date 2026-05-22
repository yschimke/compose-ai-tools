package ee.schimke.composeai.cli.scripting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Trivial coverage for [ScriptCommand.pickScriptPath] — the picker has to be order-robust against
 * `--module :app` interleaving (which would otherwise capture the flag value as the path), but
 * still tolerate scripts named without the `.kts` suffix as a back-stop.
 */
class ScriptCommandPickerTest {

  @Test
  fun `picks the first kts arg`() {
    val args = listOf("--module", ":app", "myteam.composepreview.kts", "--changed-only")
    assertEquals("myteam.composepreview.kts", ScriptCommand.pickScriptPath(args))
  }

  @Test
  fun `picks the kts arg regardless of position`() {
    val args = listOf("foo.composepreview.kts", "--module", ":app")
    assertEquals("foo.composepreview.kts", ScriptCommand.pickScriptPath(args))
  }

  @Test
  fun `falls back to first non-flag arg when no kts suffix present`() {
    val args = listOf("--changed-only", "weird-name-no-suffix")
    assertEquals("weird-name-no-suffix", ScriptCommand.pickScriptPath(args))
  }

  @Test
  fun `returns null when args contain only flags`() {
    val args = listOf("--module", "--json")
    // `--module` starts with `-`, and `--json` does too, so neither qualifies.
    // The first non-flag picker would catch `--module`'s implicit value if present, but here
    // there's no value at all.
    assertNull(ScriptCommand.pickScriptPath(args))
  }
}
