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
  fun `every routed command has a flag allowlist`() {
    assertEquals(CliRouter.KNOWN_FLAT, CliFlagValidation.BY_COMMAND.keys)
  }

  @Test
  fun `unknown flag validation is command specific and supports attached values`() {
    assertEquals(
      listOf("--fitler", "--fail-on"),
      CliFlagValidation.unknownFlags(
        "render",
        listOf("--module", ":app", "--fitler=Button", "--fail-on", "warnings"),
      ),
    )
    assertEquals(
      emptyList(),
      CliFlagValidation.unknownFlags(
        "a11y",
        listOf("--module=:app", "--filter", "Button", "--fail-on=warnings"),
      ),
    )
  }

  @Test
  fun `a recognised value may look like a flag without becoming an unknown option`() {
    assertEquals(
      emptyList(),
      CliFlagValidation.unknownFlags("render", listOf("--filter", "--literal-preview-name")),
    )
  }

  @Test
  fun `the note for an unknown flag is accurate about where it lands`() {
    // A launcher forwards its argv; "(ignored)" would be false, because the server accepts the
    // flag and it takes effect there. A non-launcher really does ignore it.
    assertTrue(
      CliFlagValidation.unknownFlagNote("ui-builder", "--no-project")
        .contains("forwarded to the compose-preview-server binary")
    )
    assertTrue(
      CliFlagValidation.unknownFlagNote("design", "--bogus")
        .contains("forwarded to the compose-preview-server binary")
    )
    assertTrue(CliFlagValidation.unknownFlagNote("render", "--fitler").contains("(ignored)"))
  }

  @Test
  fun `flags the ui-builder help advertises are on its allowlist`() {
    // `--no-project` is the server's packaged-catalogs mode, documented in the `ui-builder
    // --help` the server answers with — a warning on it read as a failure to launch the mode.
    assertEquals(
      emptyList(),
      CliFlagValidation.unknownFlags("ui-builder", listOf("--no-project", "--no-open")),
    )
    assertTrue(
      CliFlagValidation.FORWARDED_TO_SERVER ==
        setOf("serve", "browse", "ui-builder", "design", "a2ui")
    )
  }

  @Test
  fun `ui-builder accepts every flag serve accepts`() {
    // The server's `ui` command takes every `serve` flag, so a launcher that listed a hand-picked
    // subset warned about flags the documented command line actually takes. The two entries share
    // one set now; this pins that they cannot drift apart again.
    val serve = CliFlagValidation.BY_COMMAND.getValue("serve")
    val uiBuilder = CliFlagValidation.BY_COMMAND.getValue("ui-builder")
    assertEquals(
      emptySet(),
      serve - uiBuilder,
      "a serve flag missing from ui-builder would be reported as unrecognised by the launcher",
    )
  }

  @Test
  fun `the local MCP flags the server documents are known to the launcher`() {
    // The exact flags from the friction report: documented by the 3.40.0 server, and previously
    // reported as unrecognised (and mis-described as ignored) by the CLI.
    assertEquals(
      emptyList(),
      CliFlagValidation.unknownFlags(
        "ui-builder",
        listOf(
          "--no-project",
          "--catalog-mcp",
          "--agent-grants",
          "--agent-grant-capabilities",
          "ui-builder-read,ui-builder-write,ui-builder-export",
        ),
      ),
    )
  }

  @Test
  fun `the a2ui command is a share launcher that knows its documented flags`() {
    assertTrue("a2ui" in CliRouter.subcommandsOf("share"))
    assertEquals(
      emptyList(),
      CliFlagValidation.unknownFlags(
        "a2ui",
        listOf(
          "render",
          "--server",
          "https://preview.coo.ee",
          "--catalog",
          "a2ui-catalog",
          "--document",
          "doc.jsonl",
          "-o",
          "out.png",
          "--timeout",
          "60",
        ),
      ),
    )
  }

  @Test
  fun `the design command knows its documented flags`() {
    // `design --help` documents the local replay lane; the launcher's allowlist omitted it, so
    // `design render --document doc.json --local` warned about the flags it was built around.
    assertEquals(
      emptyList(),
      CliFlagValidation.unknownFlags(
        "design",
        listOf(
          "--local",
          "--document",
          "doc.json",
          "--catalog",
          "wear-m3.bundle",
          "--assets",
          "assets",
          "--components",
          "m3-catalog=components.json",
        ),
      ),
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
    assertEquals(
      CliRouter.Route.Run("browse", listOf("--module", ":app")),
      CliRouter.route(arrayOf("browse", "--module", ":app")),
    )
    assertEquals(CliRouter.Route.Run("a11y", emptyList()), CliRouter.route(arrayOf("a11y")))
    assertEquals(
      CliRouter.Route.Run("render", listOf("--output", "out.png")),
      CliRouter.route(arrayOf("render", "--output", "out.png")),
    )
  }

  @Test
  fun `unknown valued flag before a command still routes to that command for validation`() {
    assertEquals(
      CliRouter.Route.Run("render", listOf("--fitler", "Button")),
      CliRouter.route(arrayOf("--fitler", "Button", "render")),
    )
    assertEquals(
      CliRouter.Route.Run("a11y", listOf("--fitler", "Button")),
      CliRouter.route(arrayOf("inspect", "--fitler", "Button", "a11y")),
    )
    // A value belonging to a known flag must not be recovered as the command.
    assertEquals(
      CliRouter.Route.Run("list", listOf("--fitler", "Button", "--filter", "render")),
      CliRouter.route(arrayOf("--fitler", "Button", "--filter", "render", "list")),
    )
  }

  @Test
  fun `ordinary unknown first positional stays an unknown command`() {
    assertEquals(
      CliRouter.Route.Unknown("frobnicate"),
      CliRouter.route(arrayOf("frobnicate", "render")),
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
