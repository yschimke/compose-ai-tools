package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the command router: groups partition the non-core/meta commands cleanly, the flat dispatch
 * table and the router agree on the command set (no drift), and routing resolves both the grouped
 * form and the flat back-compat aliases.
 */
class CliRouterTest {
  @Test
  fun `dispatch table matches the router's known commands`() {
    assertEquals(
      CliRouter.KNOWN_FLAT,
      COMMANDS.keys,
      "COMMANDS and CliRouter.KNOWN_FLAT disagree — add/remove the command in both",
    )
  }

  @Test
  fun `groups are disjoint and don't collide with core, meta, or group names`() {
    val grouped = CliRouter.GROUPS.values.flatten()
    assertEquals(grouped.size, grouped.toSet().size, "a command appears in two groups")

    val core = CliRouter.CORE.toSet()
    val meta = CliRouter.META.toSet()
    assertTrue((grouped.toSet() intersect core).isEmpty(), "a grouped command is also core")
    assertTrue((grouped.toSet() intersect meta).isEmpty(), "a grouped command is also meta")
    // A group name must not also be a flat command, or `compose-preview <name>` is ambiguous.
    assertTrue(
      (CliRouter.GROUP_NAMES intersect CliRouter.KNOWN_FLAT).isEmpty(),
      "a group name collides with a command name",
    )
  }

  @Test
  fun `grouped form dispatches to the subcommand with the group token stripped`() {
    assertEquals(
      CliRouter.Route.Run("a11y", listOf("--json")),
      CliRouter.route(arrayOf("inspect", "a11y", "--json")),
    )
    // A flag may precede the subcommand.
    assertEquals(
      CliRouter.Route.Run("record", listOf("--module", ":app", "--out", "r.gif")),
      CliRouter.route(arrayOf("capture", "--module", ":app", "record", "--out", "r.gif")),
    )
  }

  @Test
  fun `flat names remain valid back-compat aliases`() {
    assertEquals(CliRouter.Route.Run("a11y", emptyList()), CliRouter.route(arrayOf("a11y")))
    assertEquals(
      CliRouter.Route.Run("render", listOf("--output", "out.png")),
      CliRouter.route(arrayOf("render", "--output", "out.png")),
    )
  }

  @Test
  fun `group named alone or with an unknown subcommand prints its listing`() {
    assertEquals(
      CliRouter.Route.GroupUsage("inspect", isError = false),
      CliRouter.route(arrayOf("inspect")),
    )
    assertEquals(
      CliRouter.Route.GroupUsage("inspect", isError = true),
      CliRouter.route(arrayOf("inspect", "bogus")),
    )
  }

  @Test
  fun `unknown command and no command are distinguished`() {
    assertEquals(CliRouter.Route.Unknown("frobnicate"), CliRouter.route(arrayOf("frobnicate")))
    assertEquals(CliRouter.Route.NoCommand, CliRouter.route(arrayOf("--json")))
    assertEquals(CliRouter.Route.TopUsage(full = false), CliRouter.route(arrayOf("--help")))
    assertEquals(CliRouter.Route.TopUsage(full = true), CliRouter.route(arrayOf("--help", "--all")))
  }

  @Test
  fun `every non-core, non-meta command lives in exactly one group`() {
    val ungrouped = (COMMANDS.keys - CliRouter.CORE.toSet() - CliRouter.META.toSet())
    val grouped = CliRouter.GROUPS.values.flatten().toSet()
    assertEquals(grouped, ungrouped, "commands missing from a group (or grouped but undispatched)")
  }
}
