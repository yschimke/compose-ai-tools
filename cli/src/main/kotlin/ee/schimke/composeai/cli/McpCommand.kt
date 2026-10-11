package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.previewdriver.DriverOptions
import ee.schimke.composeai.previewdriver.GradlePreviewDriver
import ee.schimke.composeai.previewdriver.RenderRequest
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * `compose-preview mcp <subcommand>`:
 * - `serve` — launch the MCP server on stdio, or the shared UI Builder's authenticated Streamable
 *   HTTP endpoint with `--streamable-http`. Status / errors go to stderr.
 * - `install` — bootstrap and enable every plugin module's daemon descriptor, run
 *   `composePreviewDiscover`, and print or install the agent-host MCP configuration.
 * - `doctor` — report per-module descriptor state (present / missing / disabled / stale) read-only.
 *
 * `serve` is a launcher: it execs the `compose-preview-mcp` binary from compose-preview-server
 * (which needs an HTTP server), as [ServeCommand] does. `install` and `doctor` are offline and stay
 * here, and the config they write still names this CLI's `mcp serve`. JSON-RPC runs over inherited
 * stdio and the exit code is passed back.
 */
internal class McpCommand(
  args: List<String>,
  private val fileSystem: FileSystem = SystemFileSystem,
) {

  private val parsed = parseSubcommand(args)
  private val sub: String = parsed.first
  private val rest: List<String> = parsed.second

  fun run() {
    when (sub) {
      "serve" -> serve(rest)
      "install" -> install(rest)
      "register" -> register(rest)
      "repair" -> repair(rest)
      "doctor" -> doctor(rest)
      "help",
      "--help",
      "-h" -> {
        printUsage()
      }
      else -> {
        System.err.println("Unknown mcp subcommand: $sub")
        printUsage()
        exitProcess(1)
      }
    }
  }

  private fun printUsage() {
    println(
      """
      compose-preview mcp <subcommand>

      Subcommands:
        serve    Launch the MCP server on stdio. Forwards remaining flags to it
                 (--project <path>[:<rootName>] is optional; projects can also be registered
                 later with the register_project MCP tool). The server ships from
                 compose-preview-server and is fetched on first use; --mcp-binary <path>
                 or COMPOSE_PREVIEW_MCP names one you already have.
        install  Per-project bootstrap: daemon descriptors for every module with the
                 plugin applied (enabled) and composePreviewDiscover. Then registers
                 compose-preview-mcp with detected hosts only where the entry is missing
                 or broken; a healthy global entry is never rewritten.
        register One-time host registration only (no Gradle): writes
                 `<launcher> mcp serve` to each detected host where missing or broken.
        repair   Fix existing compose-preview-mcp entries in ~/.claude.json, Antigravity,
                 Codex and OpenCode configs: a missing or versioned launcher path
                 (…/compose-preview-<version>/bin/) becomes the stable one, and a global
                 entry's --project is dropped. Never adds entries. `update` runs it.
        doctor   Report per-module descriptor state (no mutations).

      Common options (install/doctor):
        --project <path>     Project root containing settings.gradle[.kts] (default: cwd)
        --module <gradlePath> Limit to one or more module paths (repeatable)
        --json               Emit a structured envelope on stdout (install, doctor)

      serve: remote UI builder (optional; native MCP profile only):
        --ui-builder-url <url>
                             Add the eight UI-builder tools backed by that preview-server URL.
        --ui-builder-actor <actor>
                             Override the authenticated actor id. By default it is derived from
                             the grant token fingerprint.
        COMPOSE_PREVIEW_UI_BUILDER_TOKEN
                             Required secret for stdio when --ui-builder-url is set. It is read
                             from the environment and never accepted as an argv value. Streamable
                             HTTP instead accepts each client's Authorization bearer.

      serve: shared UI builder over Streamable HTTP:
        --streamable-http    Serve only the eight remote UI-builder tools over MCP Streamable HTTP.
                             Each client supplies its preview-server grant as Authorization: Bearer.
        --http-host <host>   Bind host (default 127.0.0.1; use a reverse proxy for public TLS).
        --http-port <port>   Bind port (default 8788).
        --http-path <path>   MCP endpoint path (default /ui-builder/mcp).
        --http-allowed-host <host>
                             Accepted Host header; repeat for aliases. Required for non-loopback
                             binds and normally set to the shared server's public hostname.
        --http-allowed-origin <host>
                             Accepted browser Origin hostname; repeat to narrow independently.

      install/register: agent host registration (each defaults to "on" when the host is
      detected locally; opt out with --no-<host>). Global entries run the stable launcher
      (e.g. ~/.local/bin/compose-preview) as `mcp serve` with no --project; the server
      finds the project from the client's roots or working directory:
        --claude / --no-claude
                             Run `claude mcp add --scope user` for compose-preview-mcp
                             when absent (repaired in place when broken). Detected when
                             `claude` is on PATH or ~/.claude/ exists.
        --codex / --no-codex
                             Merge a [mcp_servers.compose-preview-mcp] table into Codex's
                             config.toml. Detected when `codex` is on PATH or ~/.codex/
                             exists.
        --codex-config <path>
                             Override the Codex config path (default ~/.codex/config.toml).
        --opencode / --no-opencode
                             Merge a local server into OpenCode v2's mcp.servers config.
                             Detected when `opencode` is on PATH, ~/.config/opencode/ exists,
                             or OPENCODE=1.
        --opencode-config <path>
                             Override the OpenCode config path.
        --scope <user|project>
                             OpenCode config scope (default user). Project writes ./opencode.json.
        --antigravity / --no-antigravity
                             Merge into Antigravity's mcp_config.json: whichever of
                             ~/.gemini/antigravity/ and ~/.gemini/config/ already has one
                             (legacy first). Detected via
                             __CFBundleIdentifier=com.google.antigravity, ANTIGRAVITY_CLI_ALIAS,
                             ~/.gemini/antigravity/ or ~/.gemini/config/. Skipped when the
                             compose-preview Antigravity plugin is installed, since it already
                             provides the server; --antigravity forces the global entry.
        --antigravity-config <path>
                             Override the Antigravity config path.
        --no-plugin-hint     Do not print plugin installation commands for detected harnesses.

      See https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/mcp.md for the full agent flow.
      """
        .trimIndent()
    )
  }

  // -- serve -------------------------------------------------------------------------------------

  private fun serve(args: List<String>) {
    // The MCP server owns stdout for JSON-RPC, so nothing may be printed there; status goes to
    // stderr. Don't infer a project from cwd (hosts may launch from "/"). `inheritIO` so this
    // process never copies or buffers the protocol. Cached copies are refreshed against the newest
    // release daily.
    val found = ServerBinaryDiscovery.choose(args, ReleasedDistribution.MCP)
    val choice =
      found?.let(::refreshedMcp)
        ?: provisionMcp()
        ?: run {
          System.err.println(ServerBinaryDiscovery.installationHint(ReleasedDistribution.MCP))
          exitProcess(1)
        }
    System.err.println(
      ServerBinaryDiscovery.describeLaunch(
        choice,
        downloaded = choice != found,
        label = ReleasedDistribution.MCP.label,
      )
    )
    // Same preflight as `serve`; matters more here since MCP hosts hide stderr, so a JVM mismatch
    // would only look like a server that won't connect.
    ServerJavaPreflight.failure(choice, ReleasedDistribution.MCP)?.let {
      System.err.println(it)
      exitProcess(1)
    }
    val command = mcpLaunchCommand(choice.binary, args)
    val exit =
      try {
        ProcessBuilder(command)
          .inheritIO()
          .also { builder ->
            cliEnvironment(locateHostLauncher()?.path, builder.environment())?.let {
              builder.environment()[CLI_ENV] = it
            }
          }
          .runTiedToLauncher()
      } catch (t: Throwable) {
        System.err.println(
          "could not start ${choice.binary} (from ${choice.source}): " +
            "${t.message ?: t.javaClass.name}"
        )
        System.err.println()
        System.err.println(ServerBinaryDiscovery.installationHint(ReleasedDistribution.MCP))
        exitProcess(1)
      }
    exitProcess(exit)
  }

  /**
   * Fetch the MCP server when the machine has none (first use, like `serve`), and name the result.
   * It ships in the same release as the preview server.
   */
  private fun refreshedMcp(found: ServerBinaryDiscovery.Choice): ServerBinaryDiscovery.Choice =
    ServerBinaryDiscovery.refreshed(
      found,
      requested = ServerDistributionProvision.requestedVersion(),
      offline = ServerDistributionProvision.defaultOffline(),
      stamp =
        File(
          ServerDistributionProvision.defaultCacheRoot(ReleasedDistribution.MCP),
          ServerBinaryDiscovery.REFRESH_STAMP,
        ),
      latest = { ServerDistributionProvision.latestVersion() },
      provision = { version ->
        ServerDistributionProvision.ensure(ReleasedDistribution.MCP, requested = version)?.let {
          ServerBinaryDiscovery.Choice(it.path, ServerBinaryDiscovery.CACHE)
        }
      },
    )

  private fun provisionMcp(): ServerBinaryDiscovery.Choice? =
    ServerDistributionProvision.ensure(ReleasedDistribution.MCP)?.let {
      ServerBinaryDiscovery.Choice(it.path, ServerBinaryDiscovery.CACHE)
    }

  // -- install -----------------------------------------------------------------------------------

  private fun install(args: List<String>) {
    val emitJson = "--json" in args

    val projectDir = resolveProjectDir(args)
    if ((args.flagValue("--scope") ?: "user") !in setOf("user", "project")) {
      System.err.println("compose-preview mcp install: --scope must be user or project")
      exitProcess(2)
    }
    val moduleFilter = args.flagValuesAll("--module").map { it.removePrefix(":") }.toSet()

    val injectArgs = autoInjectInitScriptArgs(args, projectRoot = projectDir)
    val driver =
      GradlePreviewDriver(
        projectDir,
        DriverOptions(
          verbose = "--verbose" in args || "-v" in args,
          extraArguments = injectArgs + gradleWriteLocksArgs(),
        ),
      )
    driver.use {
      val allModules = driver.discoverModules()
      val modules =
        if (moduleFilter.isEmpty()) allModules
        else allModules.filter { it.gradlePath in moduleFilter }

      if (modules.isEmpty()) {
        val msg =
          if (moduleFilter.isEmpty()) "no modules apply the compose-preview plugin"
          else "none of --module ${moduleFilter.joinToString(",")} apply the plugin"
        System.err.println("compose-preview mcp install: $msg")
        printDiscoveryFailures(driver.lastDiscoveryFailures)
        exitProcess(2)
      }

      // Two batched phases (bootstrap descriptors, then re-run discovery), one Gradle invocation
      // each.
      System.err.println(
        "==> bootstrapping daemon descriptors for ${modules.size} module(s): " +
          modules.joinToString(", ") { ":${it.gradlePath}" }
      )
      // DaemonExtension's `enabled` isn't wired to a Gradle property, so run the task and patch the
      // JSON below.
      val daemonOk =
        driver
          .render(
            RenderRequest(
              modules = modules,
              taskFor = { ":${it.gradlePath}:composePreviewDaemonStart" },
            )
          )
          .buildOk
      if (!daemonOk) {
        System.err.println("composePreviewDaemonStart failed; aborting.")
        exitProcess(1)
      }

      // Flip the on-disk `enabled` flag so the agent needn't edit the build script.
      val descriptors = modules.mapNotNull { module ->
        val descriptor = File(module.projectDir, "build/compose-previews/daemon-launch.json")
        if (!descriptor.isFile) {
          System.err.println(
            "warning: descriptor missing for :${module.gradlePath} (${descriptor})"
          )
          null
        } else {
          enableDescriptor(descriptor)
          DescriptorState(module.gradlePath, descriptor, enabled = true)
        }
      }

      System.err.println("==> running composePreviewDiscover so previews.json is up to date")
      val discoverOk =
        driver
          .render(
            RenderRequest(
              modules = modules,
              taskFor = { ":${it.gradlePath}:composePreviewDiscover" },
            )
          )
          .buildOk
      if (!discoverOk) {
        System.err.println("composePreviewDiscover failed; descriptors are still in place.")
        exitProcess(1)
      }

      val registration = registerHosts(args, projectDir)
      if (emitJson) {
        val payload = buildJsonObject {
          put("schema", JsonPrimitive("compose-preview-mcp-install/v1"))
          put("projectRoot", JsonPrimitive(projectDir.absolutePath))
          registration.toJson().forEach { (k, v) -> put(k, v) }
          put(
            "modules",
            kotlinx.serialization.json.JsonArray(
              descriptors.map {
                buildJsonObject {
                  put("gradlePath", JsonPrimitive(":${it.gradlePath}"))
                  put("descriptor", JsonPrimitive(it.descriptor.absolutePath))
                  put("enabled", JsonPrimitive(it.enabled))
                }
              }
            ),
          )
        }
        println(JSON.encodeToString(JsonObject.serializer(), payload))
      } else {
        System.err.println()
        System.err.println("==> ready. Bootstrapped descriptors:")
        descriptors.forEach { d ->
          System.err.println("    :${d.gradlePath}  ${d.descriptor}  (enabled=true)")
        }
        System.err.println()
        registration.print()
      }
    }
  }

  // -- register / repair -------------------------------------------------------------------------

  /** `mcp register`: the one-time host registration, without touching any Gradle project. */
  private fun register(args: List<String>) {
    val registration = registerHosts(args, resolveProjectDir(args))
    if ("--json" in args) {
      val payload = buildJsonObject {
        put("schema", JsonPrimitive("compose-preview-mcp-register/v1"))
        registration.toJson().forEach { (k, v) -> put(k, v) }
      }
      println(JSON.encodeToString(JsonObject.serializer(), payload))
    } else {
      registration.print()
    }
  }

  /** `mcp repair`: fix existing entries in every known host config; never adds one. */
  private fun repair(@Suppress("UNUSED_PARAMETER") args: List<String>) {
    val changes = repairHostConfigs()
    if (changes == null) {
      System.err.println("compose-preview mcp repair: cannot locate a compose-preview launcher")
      exitProcess(1)
    }
    if (changes.isEmpty()) System.err.println("compose-preview-mcp host entries are up to date")
    changes.forEach { System.err.println("==> repaired $it") }
  }

  private class Registration(
    val launcher: String,
    val claudeMcpAdd: String,
    val results: List<HostResult>,
    val pluginHints: List<PluginInstallHint>,
    val antigravityConfig: File,
    val codexConfig: File,
    val openCodeConfig: File?,
    val installed: Map<String, Boolean>,
  ) {
    fun toJson(): Map<String, kotlinx.serialization.json.JsonElement> = buildMap {
      put("launcher", JsonPrimitive(launcher))
      put("claudeMcpAdd", JsonPrimitive(claudeMcpAdd))
      put("antigravityConfig", JsonPrimitive(antigravityConfig.absolutePath))
      put("antigravityInstalled", JsonPrimitive(installed.getValue("antigravity")))
      put("codexConfig", JsonPrimitive(codexConfig.absolutePath))
      put("codexInstalled", JsonPrimitive(installed.getValue("codex")))
      openCodeConfig?.let { put("opencodeConfig", JsonPrimitive(it.absolutePath)) }
      put("opencodeInstalled", JsonPrimitive(installed.getValue("opencode")))
      put("claudeInstalled", JsonPrimitive(installed.getValue("claude")))
      put(
        "pluginHints",
        kotlinx.serialization.json.JsonArray(
          pluginHints.map {
            buildJsonObject {
              put("host", JsonPrimitive(it.host))
              put("command", JsonPrimitive(it.command))
              it.note?.let { note -> put("note", JsonPrimitive(note)) }
            }
          }
        ),
      )
      put(
        "hosts",
        kotlinx.serialization.json.JsonArray(
          results.map {
            buildJsonObject {
              put("name", JsonPrimitive(it.name))
              put("ok", JsonPrimitive(it.ok))
              it.action?.let { a -> put("action", JsonPrimitive(a)) }
              it.path?.let { p -> put("path", JsonPrimitive(p)) }
              it.error?.let { e -> put("error", JsonPrimitive(e)) }
            }
          }
        ),
      )
    }

    fun print() {
      if (results.isEmpty()) {
        System.err.println("No agent host detected. To attach one manually, copy/paste:")
        println(claudeMcpAdd)
      } else {
        System.err.println("Agent hosts:")
        results.forEach { r ->
          val tag = if (r.ok) "ok" else "failed"
          val where = r.path?.let { "  (${it})" } ?: ""
          val action = r.action?.let { " $it" } ?: ""
          System.err.println("    [$tag] ${r.name}$where$action")
          r.changes.forEach { System.err.println("           $it") }
          if (!r.ok && r.error != null) System.err.println("           ${r.error}")
        }
        if (results.none { it.name == "claude" && it.ok }) {
          System.err.println()
          System.err.println("To attach Claude Code manually, run:")
          println(claudeMcpAdd)
        }
      }
      if (pluginHints.isNotEmpty()) {
        System.err.println()
        System.err.println(
          "Detected harness plugins also provide skills and hooks. Install the wiring plugin:"
        )
        System.err.print(AgentMcpConfig.renderPluginHints(pluginHints))
      }
    }
  }

  /**
   * Register `compose-preview-mcp` with each selected host only where the entry is missing or
   * broken, so `mcp install` never rewrites healthy global config. Global entries are `<stable
   * launcher> mcp serve` without `--project`; only project-scoped OpenCode config names one.
   */
  private fun registerHosts(args: List<String>, projectDir: File): Registration {
    val antigravityDetected = isAntigravityEnvironment()
    val claudeDetected = isClaudeEnvironment()
    val codexDetected = isCodexEnvironment()
    val openCodeDetected = isOpenCodeEnvironment()

    // Per-host: default to "on if detected", opt-in via --<host>, opt-out via --no-<host>.
    val installAntigravity =
      "--no-antigravity" !in args && ("--antigravity" in args || antigravityDetected)
    val installClaude = "--no-claude" !in args && ("--claude" in args || claudeDetected)
    val installCodex = "--no-codex" !in args && ("--codex" in args || codexDetected)
    val installOpenCode = "--no-opencode" !in args && ("--opencode" in args || openCodeDetected)
    val openCodeScope = args.flagValue("--scope") ?: "user"
    if (openCodeScope !in setOf("user", "project")) {
      System.err.println("compose-preview mcp: --scope must be user or project")
      exitProcess(2)
    }

    val choice = locateHostLauncher()
    choice?.warning?.let { System.err.println(it) }
    val anyHost = installAntigravity || installCodex || installClaude || installOpenCode
    val launcher =
      choice?.path
        ?: if (anyHost) {
          System.err.println(
            "compose-preview mcp: cannot locate an absolute compose-preview launcher " +
              "for agent host config"
          )
          exitProcess(1)
        } else {
          "compose-preview"
        }
    val claudeMcpAdd = AgentMcpConfig.claudeMcpAddCommand(launcher).joinToString(" ")
    val exists: (String) -> Boolean = { File(it).exists() }

    val antigravity = AntigravityConfig(File(System.getProperty("user.home")))
    val antigravityConfig =
      args.flagValue("--antigravity-config")?.let(::File) ?: antigravity.target()
    // The plugin registers the same server; a global entry would duplicate every tool. Only an
    // explicit --antigravity / --antigravity-config overrides that.
    val antigravityViaPlugin =
      antigravity.pluginInstalled &&
        "--antigravity" !in args &&
        args.flagValue("--antigravity-config") == null
    val claude = ClaudeConfig(File(System.getProperty("user.home")))
    // Same for the Claude Code plugin; only an explicit --claude overrides it.
    val claudeViaPlugin = claude.pluginInstalled && "--claude" !in args
    val codexConfig = args.flagValue("--codex-config")?.let(::File) ?: defaultCodexConfig()
    val openCodeConfig =
      selectOpenCodeConfig(
        explicitConfig = args.flagValue("--opencode-config")?.let(::File),
        installOpenCode = installOpenCode,
      ) {
        defaultOpenCodeConfig(projectDir, openCodeScope)
      }
    val openCodeProject = projectDir.absolutePath.takeIf { openCodeScope == "project" }
    val pluginHints =
      AgentMcpConfig.pluginInstallHints(
        buildSet {
          if (antigravityDetected && !antigravity.pluginInstalled) add("antigravity")
          if (claudeDetected && !claude.pluginInstalled) add("claude")
          if (codexDetected) add("codex")
        },
        enabled = "--no-plugin-hint" !in args,
      )

    val results = mutableListOf<HostResult>()
    if (installAntigravity && antigravityViaPlugin) {
      results += antigravityPluginResult(antigravity)
    } else if (installAntigravity) {
      results +=
        upsertFile(
          "antigravity",
          antigravityConfig,
          has = McpHostRepair::hasAntigravityEntry,
          repair = { McpHostRepair.repairAntigravity(it, launcher, exists) },
          merge = { AgentMcpConfig.mergeAntigravityConfig(it, launcher, null) },
        )
    }
    if (installCodex) {
      results +=
        upsertFile(
          "codex",
          codexConfig,
          has = McpHostRepair::hasCodexEntry,
          repair = { McpHostRepair.repairCodex(it, launcher, exists) },
          merge = { AgentMcpConfig.mergeCodexConfig(it, launcher, null) },
        )
    }
    if (installOpenCode) {
      val config = checkNotNull(openCodeConfig)
      results +=
        upsertFile(
          "opencode",
          config,
          has = McpHostRepair::hasOpenCodeEntry,
          repair = {
            McpHostRepair.repairOpenCode(it, launcher, userScope = openCodeProject == null, exists)
          },
          merge = { existing ->
            AgentMcpConfig.openCodeRewriteRefusal(config.name, existing)?.let { reason ->
              val snippet = AgentMcpConfig.openCodeConfigSnippet(launcher, openCodeProject)
              throw IllegalStateException(
                "$reason; refusing to rewrite ${config.absolutePath}. " +
                  "Merge this snippet manually:\n$snippet"
              )
            }
            AgentMcpConfig.mergeOpenCodeConfig(existing, launcher, openCodeProject)
          },
        )
    }
    if (installClaude && claudeViaPlugin) {
      results += claudePluginResult(claude)
    } else if (installClaude) {
      results +=
        if (locateOnPath("claude") == null) {
          HostResult(
            "claude",
            false,
            null,
            "`claude` not on PATH; copy/paste the printed command instead",
          )
        } else {
          registerClaude(claude, launcher, exists)
        }
    }
    return Registration(
      launcher = launcher,
      claudeMcpAdd = claudeMcpAdd,
      results = results,
      pluginHints = pluginHints,
      antigravityConfig = antigravityConfig,
      codexConfig = codexConfig,
      openCodeConfig = openCodeConfig,
      installed =
        mapOf(
          "antigravity" to installAntigravity,
          "codex" to installCodex,
          "opencode" to installOpenCode,
          "claude" to installClaude,
        ),
    )
  }

  /**
   * The plugin provides the server: leave global config alone, but name any duplicate global entry
   * for the user to remove.
   */
  private fun antigravityPluginResult(antigravity: AntigravityConfig): HostResult =
    HostResult(
      "antigravity",
      true,
      antigravity.pluginDir.absolutePath,
      null,
      "provided by the compose-preview plugin; no global entry written",
      antigravity.found().filter { it.hasEntry }.map { duplicateAntigravityEntry(it.file) },
    )

  /** The Claude Code counterpart of [antigravityPluginResult]. */
  private fun claudePluginResult(claude: ClaudeConfig): HostResult =
    HostResult(
      "claude",
      true,
      claude.installedPlugins.absolutePath,
      null,
      "provided by the compose-preview plugin; no global entry written",
      if (claude.hasUserEntry()) listOf(duplicateClaudeEntry(claude.claudeJson)) else emptyList(),
    )

  private fun upsertFile(
    name: String,
    file: File,
    has: (String?) -> Boolean,
    repair: (String) -> McpRepair?,
    merge: (String?) -> String,
  ): HostResult = runCatching {
    val existing =
      if (file.isFile) {
        try {
          file.readText()
        } catch (e: Exception) {
          throw IllegalStateException("config unreadable: ${file.absolutePath}: ${e.message}")
        }
      } else null
    if (existing == null || !has(existing)) {
      val merged =
        try {
          merge(existing)
        } catch (e: IllegalStateException) {
          throw e
        } catch (e: Exception) {
          throw IllegalStateException("config is not valid: ${file.absolutePath}: ${e.message}")
        }
      file.parentFile?.mkdirs()
      file.writeText(merged)
      HostResult(name, true, file.absolutePath, null, "registered")
    } else {
      val fixed = repair(existing)
      if (fixed == null) {
        HostResult(name, true, file.absolutePath, null, "already registered")
      } else {
        file.writeText(fixed.updated)
        HostResult(name, true, file.absolutePath, null, "repaired", fixed.changes)
      }
    }
  }
    .getOrElse { e -> HostResult(name, false, file.absolutePath, e.message) }

  private fun registerClaude(
    claude: ClaudeConfig,
    launcher: String,
    exists: (String) -> Boolean,
  ): HostResult {
    val claudeJson = claude.claudeJson
    val text = claudeJson.takeIf { it.isFile }?.readText()
    if (!McpHostRepair.hasClaudeUserEntry(text)) {
      val exit = runProcess(AgentMcpConfig.claudeMcpAddCommand(launcher))
      return if (exit == 0) HostResult("claude", true, claudeJson.path, null, "registered")
      else HostResult("claude", false, null, "claude mcp add exited with status $exit")
    }
    val repair =
      McpHostRepair.claudeRepairs(text!!, launcher, exists).firstOrNull { it.scope == "user" }
        ?: return HostResult("claude", true, claudeJson.path, null, "already registered")
    val (remove, add) = McpHostRepair.claudeRepairCommands(repair)
    runProcess(remove)
    val exit = runProcess(add)
    return if (exit == 0) {
      HostResult("claude", true, claudeJson.path, null, "repaired", repair.changes)
    } else {
      HostResult("claude", false, null, "claude mcp add-json exited with status $exit")
    }
  }

  private fun repairHostConfigs(): List<String>? = repairInstalledHostConfigs()

  private data class HostResult(
    val name: String,
    val ok: Boolean,
    val path: String?,
    val error: String?,
    val action: String? = null,
    val changes: List<String> = emptyList(),
  )

  private fun runProcess(argv: List<String>, dir: File? = null): Int =
    try {
      ProcessBuilder(argv).inheritIO().apply { dir?.let(::directory) }.start().waitFor()
    } catch (e: Exception) {
      System.err.println("compose-preview mcp install: ${argv.first()} failed: ${e.message}")
      127
    }

  // -- doctor ------------------------------------------------------------------------------------

  private fun doctor(args: List<String>) {
    val emitJson = "--json" in args
    val projectDir = resolveProjectDir(args)
    val moduleFilter = args.flagValuesAll("--module").map { it.removePrefix(":") }.toSet()

    val injectArgs = autoInjectInitScriptArgs(args, projectRoot = projectDir)
    val driver =
      GradlePreviewDriver(
        projectDir,
        DriverOptions(verbose = false, extraArguments = injectArgs + gradleWriteLocksArgs()),
      )
    driver.use {
      val allModules = driver.discoverModules()
      val modules =
        if (moduleFilter.isEmpty()) allModules
        else allModules.filter { it.gradlePath in moduleFilter }

      if (modules.isEmpty()) {
        System.err.println("compose-preview mcp doctor: no matching modules apply the plugin")
        printDiscoveryFailures(driver.lastDiscoveryFailures)
        exitProcess(2)
      }

      val states = modules.map { module ->
        val descriptor = File(module.projectDir, "build/compose-previews/daemon-launch.json")
        inspectDescriptor(module.gradlePath, descriptor)
      }
      val antigravityFindings =
        inspectAntigravity(AntigravityConfig(File(System.getProperty("user.home"))))

      if (emitJson) {
        val payload = buildJsonObject {
          put("schema", JsonPrimitive("compose-preview-mcp-doctor/v1"))
          put("projectRoot", JsonPrimitive(projectDir.absolutePath))
          put("verdict", JsonPrimitive(aggregateVerdict(states)))
          put(
            "antigravity",
            kotlinx.serialization.json.JsonArray(
              antigravityFindings.map { f ->
                buildJsonObject {
                  put("id", JsonPrimitive(f.id))
                  put("level", JsonPrimitive(f.level))
                  put("message", JsonPrimitive(f.message))
                }
              }
            ),
          )
          put(
            "modules",
            kotlinx.serialization.json.JsonArray(
              states.map {
                buildJsonObject {
                  put("gradlePath", JsonPrimitive(":${it.gradlePath}"))
                  put("descriptor", JsonPrimitive(it.descriptor.absolutePath))
                  put("status", JsonPrimitive(it.status))
                  put("enabled", JsonPrimitive(it.enabled))
                  put("verdict", JsonPrimitive(it.verdict))
                  put(
                    "findings",
                    kotlinx.serialization.json.JsonArray(
                      it.findings.map { f ->
                        buildJsonObject {
                          put("id", JsonPrimitive(f.id))
                          put("level", JsonPrimitive(f.level))
                          put("message", JsonPrimitive(f.message))
                        }
                      }
                    ),
                  )
                }
              }
            ),
          )
        }
        println(JSON.encodeToString(JsonObject.serializer(), payload))
      } else {
        states.forEach { s ->
          val tail = verdictHint(s.verdict)
          println(":${s.gradlePath}  ${s.status}${if (tail.isNotEmpty()) " — $tail" else ""}")
          s.findings
            .filter { it.level != "ok" }
            .forEach { println("    [${it.level}] ${it.id}: ${it.message}") }
          println("    descriptor: ${s.descriptor}")
        }
        if (antigravityFindings.isNotEmpty()) {
          println("antigravity:")
          antigravityFindings.forEach { f ->
            val tag = if (f.level == "info") "" else "[${f.level}] "
            println("    $tag${f.message}")
          }
        }
        val verdict = aggregateVerdict(states)
        if (verdict == "ok") {
          println()
          println(
            "All checks passed. The supervisor handles classpath drift automatically " +
              "(`classpathDirty` respawn) — do not re-run `mcp install` or kill daemons."
          )
        }
      }

      val anyProblem = states.any { it.verdict != "ok" }
      if (anyProblem) exitProcess(1)
    }
  }

  private fun verdictHint(verdict: String): String =
    when (verdict) {
      "ok" -> ""
      "run-mcp-install" -> "run `compose-preview mcp install`"
      else -> verdict
    }

  // -- helpers -----------------------------------------------------------------------------------

  private fun resolveProjectDir(args: List<String>): File {
    val explicit = args.flagValue("--project")?.let(::File)
    val cwd = explicit ?: File(".").canonicalFile
    val resolved = findGradleRoot(cwd) ?: cwd
    return resolved
  }

  private fun findGradleRoot(from: File): File? {
    var dir: File? = from.canonicalFile
    while (dir != null) {
      if (
        File(dir, "settings.gradle.kts").isFile ||
          File(dir, "settings.gradle").isFile ||
          File(dir, "gradlew").isFile
      ) {
        return dir
      }
      dir = dir.parentFile
    }
    return null
  }

  private fun enableDescriptor(file: File) {
    val path = file.path.toPath()
    val text = fileSystem.read(path) { readUtf8() }
    val updated = text.replace(Regex("\"enabled\"\\s*:\\s*false"), "\"enabled\": true")
    if (updated != text) fileSystem.write(path) { writeUtf8(updated) }
  }

  private fun defaultCodexConfig(): File =
    File(System.getProperty("user.home"), ".codex/config.toml")

  private fun defaultOpenCodeConfig(projectDir: File, scope: String): File =
    openCodeConfigFile(
      userHome = File(System.getProperty("user.home")),
      homeEnvironment = System.getenv("HOME"),
      xdgConfigHome = System.getenv("XDG_CONFIG_HOME"),
      projectDir = projectDir,
      scope = scope,
    )
      ?: throw IllegalStateException(
        "compose-preview mcp install: cannot determine an absolute OpenCode config home"
      )

  private fun isAntigravityEnvironment(): Boolean =
    System.getenv("__CFBundleIdentifier") == "com.google.antigravity" ||
      !System.getenv("ANTIGRAVITY_CLI_ALIAS").isNullOrBlank() ||
      AntigravityConfig(File(System.getProperty("user.home"))).present()

  private fun isClaudeEnvironment(): Boolean =
    locateOnPath("claude") != null ||
      ClaudeConfig(File(System.getProperty("user.home"))).configDir.isDirectory

  private fun isCodexEnvironment(): Boolean =
    locateOnPath("codex") != null || File(System.getProperty("user.home"), ".codex").isDirectory

  private fun isOpenCodeEnvironment(): Boolean =
    isOpenCodeDetected(
      executableOnPath = locateOnPath("opencode") != null,
      configDirectoryExists =
        openCodeConfigDirectory(
            File(System.getProperty("user.home")),
            System.getenv("HOME"),
            System.getenv("XDG_CONFIG_HOME"),
          )
          ?.isDirectory == true,
      environmentValue = System.getenv("OPENCODE"),
    )

  /** The stable launcher to register; see [StableLauncher]. */
  private fun locateHostLauncher(): LauncherChoice? =
    StableLauncher.select(
      System.getenv("PATH"),
      File(System.getProperty("user.home")),
      System.getenv("APP_HOME"),
    )

  private fun locateOnPath(command: String): String? = findOnPath(command)

  private data class DescriptorState(
    val gradlePath: String,
    val descriptor: File,
    val enabled: Boolean,
  )

  internal companion object {
    /**
     * Names this CLI's launcher to the MCP server, so it can run `compose-preview init-script
     * --path` itself where `mcp install` never ran.
     */
    const val CLI_ENV = "COMPOSE_PREVIEW_CLI"

    /** The launcher to export as [CLI_ENV], or null to leave [current] as it is. */
    fun cliEnvironment(launcher: String?, current: Map<String, String>): String? =
      launcher?.takeIf {
        it.isNotBlank() && current[CLI_ENV].isNullOrBlank()
      }

    val JSON: Json = Json { prettyPrint = true }

    /** First executable [command] on PATH, as found: symlinks are deliberately not resolved. */
    internal fun findOnPath(command: String): String? =
      System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.asSequence()
        ?.map { File(it, command) }
        ?.firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath

    /**
     * Repair every existing `compose-preview-mcp` entry in the global host configs (Claude Code,
     * Antigravity, Codex, OpenCode), for `mcp repair` and `compose-preview update`. Returns one
     * line per change, or null when no launcher can be found.
     */
    internal fun repairInstalledHostConfigs(): List<String>? {
      val home = File(System.getProperty("user.home"))
      val choice =
        StableLauncher.select(System.getenv("PATH"), home, System.getenv("APP_HOME")) ?: return null
      choice.warning?.let { System.err.println(it) }
      val openCode =
        openCodeConfigFile(
          userHome = home,
          homeEnvironment = System.getenv("HOME"),
          xdgConfigHome = System.getenv("XDG_CONFIG_HOME"),
          projectDir = home,
          scope = "user",
        )
      val claude: ((List<String>, File?) -> Int)? =
        if (findOnPath("claude") == null) null
        else
          { argv, dir ->
            try {
              ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .apply { dir?.let(::directory) }
                .start()
                .waitFor()
            } catch (e: Exception) {
              127
            }
          }
      return McpHostRepair.repairAll(
        McpHostRepair.defaultHostFiles(home, openCode),
        choice.path,
        claude = claude,
      )
    }

    /**
     * The argv for the MCP server: everything except this launcher's own `--mcp-binary`, unparsed,
     * so the server's flags aren't re-validated (and drifted) here. No subcommand word: the binary
     * is the server.
     */
    internal fun mcpLaunchCommand(binary: String, args: List<String>): List<String> = buildList {
      add(binary)
      var index = 0
      while (index < args.size) {
        if (args[index] == ReleasedDistribution.MCP.flag) {
          // Skip the flag and its value.
          index += 2
          continue
        }
        add(args[index])
        index++
      }
    }

    internal fun isOpenCodeDetected(
      executableOnPath: Boolean,
      configDirectoryExists: Boolean,
      environmentValue: String?,
    ): Boolean = executableOnPath || configDirectoryExists || environmentValue == "1"

    internal fun openCodeConfigDirectory(
      userHome: File,
      homeEnvironment: String?,
      xdgConfigHome: String?,
    ): File? {
      val fallbackHome = userHome.takeIf { it.isAbsolute }
      val home =
        homeEnvironment?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isAbsolute }
          ?: fallbackHome
      val configHome =
        xdgConfigHome?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isAbsolute }
          ?: home?.let { File(it, ".config") }
      if (configHome == null) return null
      return File(configHome, "opencode")
    }

    internal fun openCodeConfigFile(
      userHome: File,
      homeEnvironment: String?,
      xdgConfigHome: String?,
      projectDir: File,
      scope: String,
    ): File? {
      val directory =
        if (scope == "project") projectDir
        else openCodeConfigDirectory(userHome, homeEnvironment, xdgConfigHome) ?: return null
      val jsonc = File(directory, "opencode.jsonc")
      return if (jsonc.isFile) jsonc else File(directory, "opencode.json")
    }

    internal fun selectOpenCodeConfig(
      explicitConfig: File?,
      installOpenCode: Boolean,
      defaultConfig: () -> File,
    ): File? = explicitConfig ?: if (installOpenCode) defaultConfig() else null

    private val VALUE_FLAGS =
      setOf(
        "--project",
        "--module",
        "--replicas-per-daemon",
        "--ui-builder-url",
        "--ui-builder-actor",
        "--antigravity-config",
        "--codex-config",
        "--opencode-config",
        "--scope",
      )

    private fun parseSubcommand(args: List<String>): Pair<String, List<String>> {
      var i = 0
      while (i < args.size) {
        val arg = args[i]
        when {
          arg in VALUE_FLAGS -> i += 2
          VALUE_FLAGS.any { arg.startsWith("$it=") } -> i++
          arg.startsWith("--") -> i++
          arg.startsWith("-") -> i++
          else -> {
            val rest = args.toMutableList().apply { removeAt(i) }
            return arg to rest
          }
        }
      }
      return "help" to args
    }
  }
}

// Must equal DAEMON_DESCRIPTOR_SCHEMA_VERSION in DaemonClasspathDescriptor.kt;
// `checkDaemonLaunchSchema` enforces it.
internal const val EXPECTED_DESCRIPTOR_SCHEMA_VERSION: Int = 2

internal data class DoctorState(
  val gradlePath: String,
  val descriptor: File,
  val status: String,
  val enabled: Boolean,
  val verdict: String = "ok",
  val findings: List<DoctorFinding> = emptyList(),
)

internal data class DoctorFinding(val id: String, val level: String, val message: String)

/**
 * Validate the on-disk daemon descriptor and return findings plus a verdict for the agent. Separate
 * from [McpCommand] for unit testing.
 */
internal fun inspectDescriptor(
  gradlePath: String,
  descriptor: File,
  fileSystem: FileSystem = SystemFileSystem,
): DoctorState {
  val findings = mutableListOf<DoctorFinding>()

  if (!descriptor.isFile) {
    findings += DoctorFinding("descriptor.present", "error", "descriptor file is missing")
    return DoctorState(
      gradlePath,
      descriptor,
      status = "missing",
      enabled = false,
      verdict = "run-mcp-install",
      findings = findings,
    )
  }

  val obj =
    try {
      Json.parseToJsonElement(fileSystem.read(descriptor.path.toPath()) { readUtf8() }).jsonObject
    } catch (_: Exception) {
      null
    }
  if (obj == null) {
    findings += DoctorFinding("descriptor.parse", "error", "descriptor is not valid JSON")
    return DoctorState(
      gradlePath,
      descriptor,
      status = "corrupt",
      enabled = false,
      verdict = "run-mcp-install",
      findings = findings,
    )
  }

  val enabled = obj["enabled"]?.jsonPrimitive?.boolean == true
  if (!enabled) {
    findings +=
      DoctorFinding("descriptor.enabled", "error", "enabled=false; install would flip to true")
  }

  val schemaVersion = obj["schemaVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
  if (schemaVersion != null && schemaVersion != EXPECTED_DESCRIPTOR_SCHEMA_VERSION) {
    findings +=
      DoctorFinding(
        "descriptor.schema",
        "error",
        "schemaVersion=$schemaVersion, expected $EXPECTED_DESCRIPTOR_SCHEMA_VERSION " +
          "(plugin upgraded — re-run `mcp install`)",
      )
  }

  val launcherPath = obj["javaLauncher"]?.jsonPrimitive?.contentOrNull
  if (!launcherPath.isNullOrBlank()) {
    val launcher = File(launcherPath)
    if (!launcher.isFile || !launcher.canExecute()) {
      findings +=
        DoctorFinding(
          "descriptor.launcher",
          "error",
          "javaLauncher missing or not executable: $launcherPath",
        )
    }
  }

  val manifestPath = obj["manifestPath"]?.jsonPrimitive?.contentOrNull
  if (!manifestPath.isNullOrBlank() && !File(manifestPath).isFile) {
    findings +=
      DoctorFinding(
        "descriptor.manifest",
        "warning",
        "previews.json missing at $manifestPath; will be regenerated on next render",
      )
  }

  val errorCount = findings.count { it.level == "error" }
  val verdict = if (errorCount > 0) "run-mcp-install" else "ok"
  val status =
    when {
      !enabled -> "disabled"
      errorCount > 0 -> "stale"
      else -> "ok"
    }
  return DoctorState(
    gradlePath,
    descriptor,
    status = status,
    enabled = enabled,
    verdict = verdict,
    findings = findings,
  )
}

internal fun aggregateVerdict(states: List<DoctorState>): String =
  when {
    states.all { it.verdict == "ok" } -> "ok"
    states.any { it.verdict == "run-mcp-install" } -> "run-mcp-install"
    else -> "warning"
  }
