package ee.schimke.composeai.cli

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The launcher an agent host config should name, and why it may be a poor choice. */
internal data class LauncherChoice(val path: String, val warning: String? = null)

/**
 * Picks the `compose-preview` path written into agent host configs. Installs live in versioned
 * directories behind stable symlinks, and old versions are deleted on upgrade, so symlinks are
 * never resolved: the stable path is the point.
 */
internal object StableLauncher {

  private val VERSIONED_DIR = Regex("(^|/)compose-preview-\\d[^/]*/bin/")

  /** True when [path] sits inside a versioned `compose-preview-<version>/bin/` directory. */
  fun isVersioned(path: String): Boolean = VERSIONED_DIR.containsMatchIn(path.replace('\\', '/'))

  fun select(
    pathEnv: String?,
    home: File,
    appHome: String?,
    isExecutable: (File) -> Boolean = { it.isFile && it.canExecute() },
  ): LauncherChoice? {
    val onPath =
      pathEnv
        .orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() && File(it).isAbsolute }
        .map { File(it, "compose-preview") }
        .filter(isExecutable)
        .map { it.absolutePath }
    val stable =
      onPath.filterNot(::isVersioned) +
        listOf(
            File(home, ".local/bin/compose-preview"),
            File(home, ".claude/skills/compose-preview/bin/compose-preview"),
          )
          .filter(isExecutable)
          .map { it.absolutePath }
    stable.firstOrNull()?.let {
      return LauncherChoice(it)
    }
    val fallback =
      onPath.firstOrNull()
        ?: appHome?.let { File(it, "bin/compose-preview") }?.takeIf(isExecutable)?.absolutePath
        ?: return null
    return LauncherChoice(
      fallback,
      "warning: no stable compose-preview launcher found (e.g. ~/.local/bin/compose-preview); " +
        "registering $fallback, which will break when compose-preview is upgraded",
    )
  }
}

/** A rewritten host config and a human-readable line per change. */
internal data class McpRepair(val updated: String, val changes: List<String>)

/** A Claude Code entry to rewrite, as `claude mcp add-json` would take it. */
internal data class ClaudeEntryRepair(
  val scope: String,
  val project: String?,
  val server: JsonObject,
  val changes: List<String>,
)

/**
 * Finds and fixes `compose-preview-mcp` entries in every agent host's config.
 *
 * An entry is broken when its launcher is missing, no longer exists, or points into a versioned
 * install directory; a user-scope (global) entry is also broken when it pins `--project`, because
 * that ties every session in every project to one checkout. Project-scoped entries keep their
 * `--project`. A healthy entry, including a custom launcher that exists, is left untouched.
 */
internal object McpHostRepair {

  private val SERVER = AgentMcpConfig.SERVER_NAME
  private val JSON = Json { prettyPrint = true }

  fun problems(
    command: String?,
    args: List<String>,
    userScope: Boolean,
    exists: (String) -> Boolean,
  ): List<String> = buildList {
    when {
      command.isNullOrBlank() -> add("no launcher command")
      StableLauncher.isVersioned(command) ->
        add("launcher $command is inside a versioned install directory")
      File(command).isAbsolute && !exists(command) -> add("launcher $command does not exist")
    }
    if (userScope) projectArg(args)?.let { add("user-scope entry pins $it") }
  }

  /** The fixed `(command, args)`, or null when nothing needs to change. */
  fun fix(
    command: String?,
    args: List<String>,
    launcher: String,
    userScope: Boolean,
    exists: (String) -> Boolean,
  ): Triple<String, List<String>, List<String>>? {
    val problems = problems(command, args, userScope, exists)
    if (problems.isEmpty()) return null
    val commandBroken =
      command.isNullOrBlank() ||
        StableLauncher.isVersioned(command) ||
        (File(command).isAbsolute && !exists(command))
    val newCommand = if (commandBroken) launcher else command!!
    val newArgs =
      when {
        !userScope -> args
        args.isEmpty() -> listOf("mcp", "serve")
        else -> stripProject(args)
      }
    if (newCommand == command && newArgs == args) return null
    return Triple(newCommand, newArgs, problems)
  }

  fun projectArg(args: List<String>): String? {
    args.forEachIndexed { i, a ->
      if (a.startsWith("--project=")) return a
      if (a == "--project") return listOfNotNull(a, args.getOrNull(i + 1)).joinToString(" ")
    }
    return null
  }

  fun stripProject(args: List<String>): List<String> = buildList {
    var i = 0
    while (i < args.size) {
      val a = args[i]
      when {
        a == "--project" -> i += 2
        a.startsWith("--project=") -> i++
        else -> {
          add(a)
          i++
        }
      }
    }
  }

  private fun describe(where: String, command: String, args: List<String>, problems: List<String>) =
    "$where: ${problems.joinToString("; ")} -> ${(listOf(command) + args).joinToString(" ")}"

  private fun strings(element: JsonElement?): List<String> =
    (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

  /** Fix a `{command, args, ...}` server object, keeping every other key. */
  private fun fixServerObject(
    server: JsonObject,
    where: String,
    launcher: String,
    userScope: Boolean,
    exists: (String) -> Boolean,
  ): Pair<JsonObject, String>? {
    val command = (server["command"] as? JsonPrimitive)?.contentOrNull
    val args = strings(server["args"])
    val (newCommand, newArgs, problems) =
      fix(command, args, launcher, userScope, exists) ?: return null
    val fixed =
      JsonObject(
        server +
          mapOf(
            "command" to JsonPrimitive(newCommand),
            "args" to JsonArray(newArgs.map(::JsonPrimitive)),
          )
      )
    return fixed to describe(where, newCommand, newArgs, problems)
  }

  private fun parse(text: String): JsonObject? = runCatching {
    Json.parseToJsonElement(text) as? JsonObject
  }
    .getOrNull()

  private fun encode(obj: JsonObject) = JSON.encodeToString(JsonObject.serializer(), obj) + "\n"

  /** Antigravity `mcp_config.json`: `mcpServers.compose-preview-mcp`, always global. */
  fun repairAntigravity(text: String, launcher: String, exists: (String) -> Boolean): McpRepair? {
    val root = parse(text) ?: return null
    val servers = root["mcpServers"] as? JsonObject ?: return null
    val server = servers[SERVER] as? JsonObject ?: return null
    val (fixed, change) =
      fixServerObject(server, "antigravity", launcher, userScope = true, exists) ?: return null
    return McpRepair(
      encode(JsonObject(root + ("mcpServers" to JsonObject(servers + (SERVER to fixed))))),
      listOf(change),
    )
  }

  fun hasAntigravityEntry(text: String?): Boolean =
    text != null && ((parse(text)?.get("mcpServers") as? JsonObject)?.get(SERVER) != null)

  /** OpenCode v2 `mcp.servers.compose-preview-mcp.command` is one array: launcher, then args. */
  fun repairOpenCode(
    text: String,
    launcher: String,
    userScope: Boolean,
    exists: (String) -> Boolean,
  ): McpRepair? {
    val root = parse(text) ?: return null
    val mcp = root["mcp"] as? JsonObject ?: return null
    val servers = mcp["servers"] as? JsonObject ?: return null
    val server = servers[SERVER] as? JsonObject ?: return null
    val argv = strings(server["command"])
    val (command, args, problems) =
      fix(argv.firstOrNull(), argv.drop(1), launcher, userScope, exists) ?: return null
    val fixed =
      JsonObject(server + ("command" to JsonArray((listOf(command) + args).map(::JsonPrimitive))))
    val updated =
      JsonObject(
        root + ("mcp" to JsonObject(mcp + ("servers" to JsonObject(servers + (SERVER to fixed)))))
      )
    return McpRepair(encode(updated), listOf(describe("opencode", command, args, problems)))
  }

  fun hasOpenCodeEntry(text: String?): Boolean =
    text != null &&
      (((parse(text)?.get("mcp") as? JsonObject)?.get("servers") as? JsonObject)?.get(SERVER) !=
        null)

  /**
   * Claude Code `~/.claude.json`: the user-scope entry at top-level `mcpServers`, and local-scope
   * entries under `projects.<dir>.mcpServers` (project-scoped, so their `--project` stays).
   */
  fun claudeRepairs(
    text: String,
    launcher: String,
    exists: (String) -> Boolean,
  ): List<ClaudeEntryRepair> {
    val root = parse(text) ?: return emptyList()
    val out = mutableListOf<ClaudeEntryRepair>()
    ((root["mcpServers"] as? JsonObject)?.get(SERVER) as? JsonObject)?.let { server ->
      fixServerObject(server, "claude (user)", launcher, userScope = true, exists)?.let {
        (fixed, change) ->
        out += ClaudeEntryRepair("user", null, fixed, listOf(change))
      }
    }
    (root["projects"] as? JsonObject)?.forEach { (dir, project) ->
      val server =
        ((project as? JsonObject)?.get("mcpServers") as? JsonObject)?.get(SERVER) as? JsonObject
          ?: return@forEach
      fixServerObject(server, "claude (local $dir)", launcher, userScope = false, exists)?.let {
        (fixed, change) ->
        out += ClaudeEntryRepair("local", dir, fixed, listOf(change))
      }
    }
    return out
  }

  fun hasClaudeUserEntry(text: String?): Boolean =
    text != null && ((parse(text)?.get("mcpServers") as? JsonObject)?.get(SERVER) != null)

  /**
   * True when the user-scope entry in [text] has exactly the shape `mcp install` writes through
   * `claude mcp add --scope user compose-preview-mcp -- <launcher> mcp serve`: a stdio server whose
   * launcher is named `compose-preview` and whose arguments are `mcp serve` (an older release also
   * pinned `--project`), with no environment. Anything else — another command, extra arguments or
   * keys, an env block — was written by someone else and is never removed.
   */
  fun isCliWrittenClaudeUserEntry(text: String?): Boolean {
    val server =
      ((text?.let(::parse)?.get("mcpServers") as? JsonObject)?.get(SERVER) as? JsonObject)
        ?: return false
    if (!server.keys.all { it in setOf("type", "command", "args", "env") }) return false
    val type = server["type"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: return false }
    if (type != null && type != "stdio") return false
    val env = server["env"]
    if (env != null && (env as? JsonObject)?.isEmpty() != true) return false
    val command = (server["command"] as? JsonPrimitive)?.contentOrNull ?: return false
    val launcherName = command.replace('\\', '/').substringAfterLast('/')
    if (launcherName !in LAUNCHER_NAMES) return false
    val args = strings(server["args"])
    if (args.size != (server["args"] as? JsonArray)?.size) return false
    return args.take(2) == listOf("mcp", "serve") && stripProject(args.drop(2)).isEmpty()
  }

  private val LAUNCHER_NAMES =
    setOf("compose-preview", "compose-preview.bat", "compose-preview.cmd", "compose-preview.exe")

  /** [text] without the user-scope entry, for when `claude` is not on PATH. */
  fun removeClaudeUserEntry(text: String): String? {
    val root = parse(text) ?: return null
    val servers = root["mcpServers"] as? JsonObject ?: return null
    if (SERVER !in servers) return null
    return encode(JsonObject(root + ("mcpServers" to JsonObject(servers - SERVER))))
  }

  /** Argv that removes the user-scope entry through the Claude CLI. */
  fun claudeUserRemoveCommand(): List<String> = AgentMcpConfig.claudeMcpRemoveCommand()

  /** Apply [claudeRepairs] straight to the JSON, for when `claude` is not on PATH. */
  fun repairClaudeJson(text: String, launcher: String, exists: (String) -> Boolean): McpRepair? {
    val repairs = claudeRepairs(text, launcher, exists)
    if (repairs.isEmpty()) return null
    var root = parse(text) ?: return null
    for (r in repairs) {
      root =
        if (r.project == null) {
          val servers = root["mcpServers"] as JsonObject
          JsonObject(root + ("mcpServers" to JsonObject(servers + (SERVER to r.server))))
        } else {
          val projects = root["projects"] as JsonObject
          val project = projects[r.project] as JsonObject
          val servers = project["mcpServers"] as JsonObject
          val fixedProject =
            JsonObject(project + ("mcpServers" to JsonObject(servers + (SERVER to r.server))))
          JsonObject(root + ("projects" to JsonObject(projects + (r.project to fixedProject))))
        }
    }
    return McpRepair(encode(root), repairs.flatMap { it.changes })
  }

  /** Argv that replaces one Claude entry with [ClaudeEntryRepair.server], in its own scope. */
  fun claudeRepairCommands(repair: ClaudeEntryRepair): List<List<String>> =
    listOf(
      listOf("claude", "mcp", "remove", "--scope", repair.scope, SERVER),
      listOf(
        "claude",
        "mcp",
        "add-json",
        "--scope",
        repair.scope,
        SERVER,
        JsonObject.serializer().let { Json.encodeToString(it, repair.server) },
      ),
    )

  private val TOML_STRING = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"|'([^']*)'")

  private fun tomlValues(value: String): List<String> =
    TOML_STRING.findAll(value)
      .map { m ->
        m.groups[1]?.value?.replace(Regex("\\\\(.)")) { it.groupValues[1] } ?: m.groupValues[2]
      }
      .toList()

  private fun tomlString(value: String) =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  fun hasCodexEntry(text: String?): Boolean =
    text != null && text.lines().any { it.trim() == "[mcp_servers.$SERVER]" }

  /**
   * Codex `config.toml`: rewrite only the `command` and single-line `args` keys of the
   * `[mcp_servers.compose-preview-mcp]` table, leaving every other line (including `env`) alone.
   */
  fun repairCodex(text: String, launcher: String, exists: (String) -> Boolean): McpRepair? {
    val lines = text.lines().toMutableList()
    val header = lines.indexOfFirst { it.trim() == "[mcp_servers.$SERVER]" }
    if (header < 0) return null
    var end = header + 1
    while (end < lines.size && !lines[end].startsWith("[")) end++
    fun keyLine(key: String) =
      (header + 1 until end).firstOrNull {
        lines[it].trimStart().startsWith("$key ") || lines[it].trimStart().startsWith("$key=")
      }
    val commandLine = keyLine("command")
    val argsLine = keyLine("args")?.takeIf { lines[it].trimEnd().endsWith("]") }
    val command = commandLine?.let { tomlValues(lines[it].substringAfter('=')).firstOrNull() }
    val args = argsLine?.let { tomlValues(lines[it].substringAfter('=')) } ?: emptyList()
    val (newCommand, newArgs, problems) =
      fix(command, args, launcher, userScope = true, exists) ?: return null
    val commandText = "command = ${tomlString(newCommand)}"
    val argsText = "args = [${newArgs.joinToString(", ") { tomlString(it) }}]"
    if (argsLine != null) lines[argsLine] = argsText else lines.add(header + 1, argsText)
    if (commandLine != null) lines[commandLine] = commandText
    else lines.add(header + 1, commandText)
    return McpRepair(
      lines.joinToString("\n"),
      listOf(describe("codex", newCommand, newArgs, problems)),
    )
  }

  /** One [repairAll] line as `mcp repair` and `update` print it. */
  fun report(line: String): String =
    if (listOf("removed ", "kept ", "failed", "skipped ").any(line::startsWith)) "==> $line"
    else "==> repaired $line"

  /** What [removeDuplicateClaudeUserEntry] did, and the `.claude.json` text left to repair. */
  data class DuplicateRemoval(val removed: Boolean, val line: String, val remaining: String?)

  /**
   * The `compose-preview` Claude Code plugin registers the same server, so a user-scope entry runs
   * a second server (and a second set of daemons) in every session
   * (yschimke/compose-ai-tools#6363). Remove it when this CLI wrote it; otherwise only say so.
   * Local-scope (per-project) entries are left alone.
   */
  fun removeDuplicateClaudeUserEntry(
    claudeJson: File,
    text: String,
    claude: ((argv: List<String>, dir: File?) -> Int)?,
  ): DuplicateRemoval {
    val where = "claude (user) $SERVER (${claudeJson.path})"
    if (!isCliWrittenClaudeUserEntry(text)) {
      return DuplicateRemoval(
        false,
        "kept $where: it duplicates the compose-preview plugin's server but was not written by " +
          "compose-preview; remove it yourself with " +
          "`claude mcp remove $SERVER --scope user` if it is not needed",
        text,
      )
    }
    val because = "it duplicates the compose-preview plugin's server"
    if (claude != null) {
      val exit = claude(claudeUserRemoveCommand(), null)
      return if (exit == 0) {
        DuplicateRemoval(
          true,
          "removed $where: $because  (claude mcp remove --scope user)",
          claudeJson.takeIf { it.isFile }?.readText(),
        )
      } else {
        DuplicateRemoval(
          false,
          "failed: claude mcp remove $SERVER --scope user exited with $exit; $because",
          text,
        )
      }
    }
    val updated =
      removeClaudeUserEntry(text) ?: return DuplicateRemoval(false, "kept $where: unreadable", text)
    claudeJson.writeText(updated)
    return DuplicateRemoval(true, "removed $where: $because", updated)
  }

  /** The global host config files `compose-preview mcp repair` scans. */
  data class HostFiles(
    val claudeJson: File,
    val antigravity: List<File>,
    val codex: File,
    val openCode: File?,
  )

  fun defaultHostFiles(home: File, openCode: File?, claude: ClaudeConfig = ClaudeConfig(home)) =
    HostFiles(
      claudeJson = claude.claudeJson,
      antigravity = AntigravityConfig(home).candidates,
      codex = File(home, ".codex/config.toml"),
      openCode = openCode,
    )

  /**
   * Repair every existing `compose-preview-mcp` entry in [files]; never adds one. Claude entries go
   * through [claude] (`claude mcp remove` + `add-json`, run in the given directory) when it is
   * non-null, otherwise `~/.claude.json` is edited directly. When [claudePluginEnabled], the
   * user-scope Claude entry is removed instead (see [removeDuplicateClaudeUserEntry]). Returns one
   * line per change.
   */
  fun repairAll(
    files: HostFiles,
    launcher: String,
    exists: (String) -> Boolean = { File(it).exists() },
    claudePluginEnabled: Boolean = false,
    claude: ((argv: List<String>, dir: File?) -> Int)? = null,
  ): List<String> {
    val out = mutableListOf<String>()
    fun rewrite(file: File, repair: (String) -> McpRepair?) {
      val text = file.takeIf { it.isFile }?.readText() ?: return
      val fixed = runCatching { repair(text) }.getOrNull() ?: return
      file.writeText(fixed.updated)
      out += fixed.changes.map { "$it  (${file.path})" }
    }
    var claudeText = files.claudeJson.takeIf { it.isFile }?.readText()
    if (claudeText != null && claudePluginEnabled && hasClaudeUserEntry(claudeText)) {
      val result = removeDuplicateClaudeUserEntry(files.claudeJson, claudeText, claude)
      out += result.line
      claudeText = result.remaining
    }
    if (claudeText != null) {
      if (claude == null) {
        rewrite(files.claudeJson) { repairClaudeJson(it, launcher, exists) }
      } else {
        for (r in claudeRepairs(claudeText, launcher, exists)) {
          val dir = r.project?.let(::File)
          if (dir != null && !dir.isDirectory) {
            out += "skipped claude (local ${r.project}): project directory no longer exists"
            continue
          }
          val cmds = claudeRepairCommands(r)
          claude(cmds[0], dir)
          val exit = claude(cmds[1], dir)
          out +=
            if (exit == 0) r.changes.map { "$it  (claude mcp add-json --scope ${r.scope})" }
            else listOf("failed: claude mcp add-json --scope ${r.scope} exited with $exit")
        }
      }
    }
    files.antigravity.forEach { file -> rewrite(file) { repairAntigravity(it, launcher, exists) } }
    rewrite(files.codex) { repairCodex(it, launcher, exists) }
    files.openCode?.let { file ->
      rewrite(file) { text ->
        if (AgentMcpConfig.openCodeRewriteRefusal(file.name, text) != null) null
        else repairOpenCode(text, launcher, userScope = true, exists)
      }
    }
    return out
  }
}
