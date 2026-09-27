package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Host entries must name a launcher that survives upgrades, and a global entry must never pin one
 * project. A real report: Claude Code failed with ENOENT on
 * `~/.claude/skills/compose-preview/cli/compose-preview-0.9.3/bin/compose-preview` long after 2.x
 * was installed at `~/.local/bin/compose-preview`.
 */
class McpHostRepairTest {

  private val home = File("/home/u")
  private val stable = "/home/u/.local/bin/compose-preview"
  private val versioned =
    "/home/u/.claude/skills/compose-preview/cli/compose-preview-0.9.3/bin/compose-preview"
  private val existing = setOf(stable, "/usr/local/bin/custom-compose-preview")
  private val exists: (String) -> Boolean = { it in existing }

  // -- launcher selection -----------------------------------------------------------------------

  @Test
  fun `a PATH symlink is registered as found, not resolved into the versioned dir`() {
    val tmp = Files.createTempDirectory("launcher").toFile()
    val versionDir = File(tmp, "cli/compose-preview-2.28.1/bin").apply { mkdirs() }
    val target = File(versionDir, "compose-preview").apply { writeText("#!/bin/sh\n") }
    target.setExecutable(true)
    val bin = File(tmp, "bin").apply { mkdirs() }
    val link = File(bin, "compose-preview")
    Files.createSymbolicLink(link.toPath(), target.toPath())

    val choice = StableLauncher.select(bin.absolutePath, File(tmp, "home"), versionDir.parent)
    assertEquals(LauncherChoice(link.absolutePath), choice)
  }

  @Test
  fun `a versioned dir on PATH loses to the installer's stable symlink`() {
    val choice =
      StableLauncher.select(
        pathEnv = "/home/u/cli/compose-preview-2.28.1/bin",
        home = home,
        appHome = "/home/u/cli/compose-preview-2.28.1",
        isExecutable = {
          it.path in setOf(stable, "/home/u/cli/compose-preview-2.28.1/bin/compose-preview")
        },
      )
    assertEquals(LauncherChoice(stable), choice)
  }

  @Test
  fun `only a versioned launcher is used with a warning`() {
    val own = "/opt/cli/compose-preview-2.28.1/bin/compose-preview"
    val choice =
      StableLauncher.select("/usr/bin", home, "/opt/cli/compose-preview-2.28.1") { it.path == own }
    assertEquals(own, choice?.path)
    assertTrue(choice!!.warning!!.contains("break"), choice.warning)
  }

  @Test
  fun `versioned path detection`() {
    assertTrue(StableLauncher.isVersioned(versioned))
    assertTrue(StableLauncher.isVersioned("C:\\x\\compose-preview-2.0.0\\bin\\compose-preview"))
    assertFalse(StableLauncher.isVersioned(stable))
    assertFalse(
      StableLauncher.isVersioned("/home/u/.claude/skills/compose-preview/bin/compose-preview")
    )
  }

  // -- entry health -----------------------------------------------------------------------------

  @Test
  fun `a stable global entry with no project is left alone`() {
    assertNull(McpHostRepair.fix(stable, listOf("mcp", "serve"), stable, userScope = true, exists))
  }

  @Test
  fun `a custom launcher that exists is left alone`() {
    val custom = "/usr/local/bin/custom-compose-preview"
    assertNull(McpHostRepair.fix(custom, listOf("mcp", "serve"), stable, userScope = true, exists))
  }

  @Test
  fun `a global entry drops --project in both spellings`() {
    val (command, args, _) =
      McpHostRepair.fix(stable, listOf("mcp", "serve", "--project=/w/app"), stable, true, exists)!!
    assertEquals(stable to listOf("mcp", "serve"), command to args)
    assertEquals(
      listOf("mcp", "serve", "--verbose"),
      McpHostRepair.stripProject(listOf("mcp", "serve", "--project", "/w/app", "--verbose")),
    )
  }

  @Test
  fun `a project-scoped entry keeps --project and only swaps the launcher`() {
    val (command, args, _) =
      McpHostRepair.fix(
        versioned,
        listOf("mcp", "serve", "--project=/w/app"),
        stable,
        false,
        exists,
      )!!
    assertEquals(stable to listOf("mcp", "serve", "--project=/w/app"), command to args)
    assertNull(
      McpHostRepair.fix(stable, listOf("mcp", "serve", "--project=/w/app"), stable, false, exists)
    )
  }

  @Test
  fun `a launcher that no longer exists is replaced`() {
    val gone = "/home/u/.local/share/compose-preview/bin/compose-preview"
    val (command, _, problems) =
      McpHostRepair.fix(gone, listOf("mcp", "serve"), stable, true, exists)!!
    assertEquals(stable, command)
    assertTrue(problems.single().contains("does not exist"))
  }

  // -- per-format repair ------------------------------------------------------------------------

  private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

  private fun JsonObject.obj(vararg keys: String): JsonObject =
    keys.fold(this) { o, k -> o[k]!!.jsonObject }

  private fun JsonObject.strings(key: String) =
    this[key]!!.jsonArray.map { it.jsonPrimitive.content }

  @Test
  fun `claude json repair fixes the user entry, keeps local project scope and siblings`() {
    val text =
      """
      {
        "numStartups": 12,
        "mcpServers": {
          "compose-preview-mcp": {
            "type": "stdio",
            "command": "$versioned",
            "args": ["mcp", "serve", "--project=/w/one"],
            "env": {"A": "1"}
          },
          "other": {"command": "/usr/bin/other", "args": []}
        },
        "projects": {
          "/w/two": {
            "allowedTools": [],
            "mcpServers": {
              "compose-preview-mcp": {
                "command": "$versioned",
                "args": ["mcp", "serve", "--project=/w/two"]
              }
            }
          }
        }
      }
      """
    val fixed = assertNotNull(McpHostRepair.repairClaudeJson(text, stable, exists))
    val root = parse(fixed.updated)
    val user = root.obj("mcpServers", "compose-preview-mcp")
    assertEquals(stable, user["command"]!!.jsonPrimitive.content)
    assertEquals(listOf("mcp", "serve"), user.strings("args"))
    assertEquals("1", user.obj("env")["A"]!!.jsonPrimitive.content)
    assertEquals("stdio", user["type"]!!.jsonPrimitive.content)
    assertEquals(
      "/usr/bin/other",
      root.obj("mcpServers", "other")["command"]!!.jsonPrimitive.content,
    )
    assertEquals("12", root["numStartups"]!!.jsonPrimitive.content)
    val local = root.obj("projects", "/w/two", "mcpServers", "compose-preview-mcp")
    assertEquals(stable, local["command"]!!.jsonPrimitive.content)
    assertEquals(listOf("mcp", "serve", "--project=/w/two"), local.strings("args"))
    assertEquals(2, fixed.changes.size)

    assertNull(
      McpHostRepair.repairClaudeJson(fixed.updated, stable, exists),
      "second run is a no-op",
    )
  }

  @Test
  fun `claude repair via the CLI uses add-json in the entry's own scope`() {
    val text =
      """{"mcpServers":{"compose-preview-mcp":{"type":"stdio","command":"$versioned","args":["mcp","serve"]}}}"""
    val repair = McpHostRepair.claudeRepairs(text, stable, exists).single()
    val (remove, add) = McpHostRepair.claudeRepairCommands(repair)
    assertEquals(
      listOf("claude", "mcp", "remove", "--scope", "user", "compose-preview-mcp"),
      remove,
    )
    assertEquals(
      listOf("claude", "mcp", "add-json", "--scope", "user", "compose-preview-mcp"),
      add.dropLast(1),
    )
    val json = parse(add.last())
    assertEquals(stable, json["command"]!!.jsonPrimitive.content)
    assertEquals("stdio", json["type"]!!.jsonPrimitive.content)
  }

  @Test
  fun `antigravity repair rewrites command and strips project`() {
    val text =
      """{"mcpServers":{"compose-preview-mcp":{"command":"$versioned","args":["mcp","serve","--project=/w/one"]},"x":{"command":"/x"}},"theme":"dark"}"""
    val fixed = assertNotNull(McpHostRepair.repairAntigravity(text, stable, exists))
    val root = parse(fixed.updated)
    val server = root.obj("mcpServers", "compose-preview-mcp")
    assertEquals(stable, server["command"]!!.jsonPrimitive.content)
    assertEquals(listOf("mcp", "serve"), server.strings("args"))
    assertEquals("dark", root["theme"]!!.jsonPrimitive.content)
    assertNull(McpHostRepair.repairAntigravity(fixed.updated, stable, exists))
  }

  @Test
  fun `codex repair rewrites only command and args, keeping other keys and tables`() {
    val text =
      """
      model = "gpt-5"

      [mcp_servers.compose-preview-mcp]
      command = "$versioned"
      args = ["mcp", "serve", "--project=/w/one"]
      startup_timeout_sec = 30

      [mcp_servers.other]
      command = "/usr/bin/other"
      """
        .trimIndent()
    val fixed = assertNotNull(McpHostRepair.repairCodex(text, stable, exists))
    assertEquals(
      """
      model = "gpt-5"

      [mcp_servers.compose-preview-mcp]
      command = "$stable"
      args = ["mcp", "serve"]
      startup_timeout_sec = 30

      [mcp_servers.other]
      command = "/usr/bin/other"
      """
        .trimIndent(),
      fixed.updated,
    )
    assertNull(McpHostRepair.repairCodex(fixed.updated, stable, exists))
  }

  @Test
  fun `opencode repair fixes the launcher in the command array`() {
    val text =
      """{"${'$'}schema":"x","mcp":{"servers":{"compose-preview-mcp":{"type":"local","command":["$versioned","mcp","serve","--project=/w"],"codemode":false}}}}"""
    val fixed = assertNotNull(McpHostRepair.repairOpenCode(text, stable, userScope = true, exists))
    val server = parse(fixed.updated).obj("mcp", "servers", "compose-preview-mcp")
    assertEquals(listOf(stable, "mcp", "serve"), server.strings("command"))
    assertEquals("local", server["type"]!!.jsonPrimitive.content)
    assertNull(McpHostRepair.repairOpenCode(fixed.updated, stable, userScope = true, exists))
  }

  @Test
  fun `repairAll touches only broken entries and never adds one`() {
    val tmp = Files.createTempDirectory("hosts").toFile()
    val files =
      McpHostRepair.HostFiles(
        claudeJson = File(tmp, ".claude.json"),
        antigravity = File(tmp, "mcp_config.json"),
        codex = File(tmp, "config.toml"),
        openCode = File(tmp, "opencode.json"),
      )
    files.claudeJson.writeText(
      """{"mcpServers":{"compose-preview-mcp":{"command":"$versioned","args":["mcp","serve","--project=/w"]}}}"""
    )
    val healthyAntigravity =
      """{"mcpServers":{"compose-preview-mcp":{"command":"$stable","args":["mcp","serve"]}}}"""
    files.antigravity.writeText(healthyAntigravity)
    val codexWithoutEntry = "model = \"gpt-5\"\n"
    files.codex.writeText(codexWithoutEntry)
    // opencode.json absent: must stay absent.

    val calls = mutableListOf<List<String>>()
    val changes =
      McpHostRepair.repairAll(files, stable, exists) { argv, dir ->
        assertNull(dir)
        calls += argv
        0
      }
    assertEquals(1, changes.size, changes.toString())
    assertEquals(listOf("remove", "add-json"), calls.map { it[2] })
    assertEquals(healthyAntigravity, files.antigravity.readText())
    assertEquals(codexWithoutEntry, files.codex.readText())
    assertFalse(files.openCode!!.exists())

    // Without `claude` on PATH, ~/.claude.json is edited directly.
    val direct = McpHostRepair.repairAll(files, stable, exists, claude = null)
    assertEquals(1, direct.size)
    val user = parse(files.claudeJson.readText()).obj("mcpServers", "compose-preview-mcp")
    assertEquals(stable, user["command"]!!.jsonPrimitive.content)
    assertEquals(listOf("mcp", "serve"), user.strings("args"))
    assertTrue(McpHostRepair.repairAll(files, stable, exists, claude = null).isEmpty())
  }
}
