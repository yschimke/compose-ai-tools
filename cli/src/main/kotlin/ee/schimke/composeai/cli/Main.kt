package ee.schimke.composeai.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    printUsage()
    exitProcess(0)
  }

  // `--version` / `-V` short-circuit ahead of command parsing — works alongside any other flags
  // so `compose-preview --version --json` still answers the version question. Mirror Unix
  // convention (`-V` not `-v`, since `-v` is `--verbose` everywhere else in this CLI).
  if (args.any { it == "--version" || it == "-V" }) {
    println("compose-preview $BUNDLE_VERSION")
    exitProcess(0)
  }

  // Find the command — first non-flag argument that isn't a flag's value.
  // Flags that take values: --module, --filter, --id, --output, --timeout, --plugin-version
  val valuedFlags =
    setOf(
      "--module",
      "--filter",
      "--id",
      "--output",
      "--timeout",
      "--plugin-version",
      "--fail-on",
      "--desc",
      "--branch",
      "--remote",
      "--pr-number",
      "--message",
      "--with-extension",
      "--with",
    )
  var commandIndex = -1
  var i = 0
  while (i < args.size) {
    val arg = args[i]
    if (arg in valuedFlags) {
      i += 2 // skip flag and its value
      continue
    }
    if (arg.startsWith("-")) {
      i++
      continue
    }
    commandIndex = i
    break
  }

  if (commandIndex < 0) {
    if ("--help" in args || "-h" in args) {
      printUsage()
      exitProcess(0)
    }
    System.err.println("No command specified.")
    printUsage()
    exitProcess(1)
  }

  val command = args[commandIndex]
  val allArgs = args.toMutableList().apply { removeAt(commandIndex) }

  when (command) {
    "show" -> ShowCommand(allArgs).run()
    "show-resources" -> ShowResourcesCommand(allArgs).run()
    "list" -> ListCommand(allArgs).run()
    "render" -> RenderCommand(allArgs).run()
    "a11y" -> A11yCommand(allArgs).run()
    "extensions" -> ExtensionsCommand(allArgs).run()
    "profile" -> ProfileCommand(allArgs).run()
    "doctor" -> DoctorCommand(allArgs).run()
    "devices" -> DevicesCommand(allArgs).run()
    "share-gist" -> ShareGistCommand(allArgs).run()
    "publish-images" -> PublishImagesCommand(allArgs).run()
    "bundle" -> BundleCommand(allArgs).run()
    "mcp" -> McpCommand(allArgs).run()
    "update" -> UpdateCommand(allArgs).run()
    "init-script" -> InitScriptCommand(allArgs).run()
    "script" -> runScriptCommand(allArgs)
    "version" -> println("compose-preview $BUNDLE_VERSION")
    "help" -> printUsage()
    else -> {
      System.err.println("Unknown command: $command")
      printUsage()
      exitProcess(1)
    }
  }
}

/**
 * Reflectively instantiate `:cli-scripting`'s `ScriptCommand`. Lives behind reflection because
 * `:cli-scripting` depends on `:cli` (for `PreviewResult` / `Command`) — a direct compile-time
 * reference here would cycle the project graph. The runtime classpath always carries the sidecar
 * (the MVP slice of issue #1084), so the "missing" branch is purely defensive: it'll become the
 * live entry point once the lazy-fetch + classloader split lands and the bundle may legitimately be
 * absent.
 */
private fun runScriptCommand(args: List<String>) {
  val cls =
    try {
      Class.forName("ee.schimke.composeai.cli.scripting.ScriptCommand")
    } catch (_: ClassNotFoundException) {
      System.err.println(
        "compose-preview script: scripting bundle not on the classpath. " +
          "When the lazy-fetch path lands this will become " +
          "`compose-preview update --with-scripting`; today the host should always be present, " +
          "so this likely indicates a broken installation."
      )
      exitProcess(1)
    }
  val instance = cls.getDeclaredConstructor(List::class.java).newInstance(args)
  cls.getMethod("run").invoke(instance)
}

private fun printUsage() {
  println(
    """
    compose-preview — Compose Preview CLI

    Usage: compose-preview [options] <command> [options]

    Commands:
      show             Discover and render previews; print id, path, sha256, changed flag
      show-resources   Render Android XML resource previews (vector, animated-vector,
                       adaptive-icon); same id/path/sha/changed shape as `show`. Sibling
                       command, separate from `show` because the workflows are disjoint —
                       see also `compose-preview-show-resources/v1` JSON envelope.
      list             List discovered previews
      render           Render previews; with --output copies a single match to disk
      a11y             Render previews with the a11y data extension on and
                       print ATF findings (thin wrapper over `--with-extension a11y`)
      extensions       Introspect registered data extensions (`extensions list`)
      profile          Run a saved JSON profile: `compose-preview profile <path.json>`. A
                       profile bundles `extensions`, `filter`, `failOn`, and a chosen `report`
                       extension into a single file teams can re-run. Forwards through
                       `ReportCommand`; later flags override profile fields.
      doctor           Verify Java 17 + Compose/AGP environment before editing Gradle files
      devices          List known @Preview(device=...) ids and resolved geometry
      share-gist       Create a gist from a markdown file plus image attachments
      publish-images   Push a directory of rendered PNGs to a shared branch (default compose-preview/pr)
      bundle           Pack selected previews + minimal classpath into a portable PNG+ZIP polyglot
      mcp              MCP server lifecycle: serve | install | doctor (see `mcp help`)
      update           Re-run the bootstrap installer to pull the latest release
      init-script      Materialise the bundled auto-inject init script and print the path
                       (--path, default) or its rendered body (--print). Useful for driving
                       Gradle directly with the same `--init-script` body the CLI uses
                       internally.
      script           Run a `*.composepreview.kts` Kotlin script — handle-returning DSL:
                       `previews()` / `show(id)` access rendered previews; `fail(msg)`
                       accumulates non-zero exit. See issue #1084 for the DSL surface and the
                       in-progress lazy-fetch / live-session story.
      version          Print the installed bundle version and exit
      help             Show this help message

    MCP-first workflows:
      Structured data products, extension command routing, live preview state, and history
      inspection are exposed through MCP (`compose-preview mcp install|serve`) rather than the
      main cold-shell CLI.

    Options:
      --module <name>      Target module (default: auto-detect all)
      --filter <pattern>   Case-insensitive substring match on preview id
      --id <exact>         Exact match on preview id
      --json               Emit JSON (show, list, a11y, devices)
      --brief              JSON only: drop functionName/className/sourceFile/params
      --changed-only       JSON only (show, a11y): drop previews with no changed capture
      --output <path>      Copy matched preview PNG to this path (render)
      --progress           Print per-task milestone/heartbeat lines to stderr
      --verbose, -v        Show full Gradle build output (implies --progress)
      --timeout <seconds>  Gradle build timeout (default: 300)
      --fail-on <level>    a11y: exit non-zero on 'errors' or 'warnings' (default: mirror Gradle)
      --with-extension <id>
                           Enable a data extension for this run (repeatable; comma-separated
                           values accepted). Forwards as `-PcomposePreview.activeExtensions
                           =<comma-list>`. The `a11y` command is a thin wrapper that always
                           sets `--with-extension a11y`. Note: a11y data products are now
                           daemon-only; `compose-preview a11y` will require a temporary
                           daemon spin-up (TODO follow-up; today the command runs but produces
                           no findings).
      --force=<reason>     Sanctioned escape hatch when a render looks stale: passes
                           --rerun-tasks to Gradle so every input task re-executes. Does NOT
                           run :clean and does NOT touch build/classes/. Each use is logged
                           with a pointer to issue #924 — please report the freshness gap.
      --daemon             doctor: also spawn each module's preview daemon and confirm the
                           `initialize` round-trip succeeds. Adds ~600ms (Desktop) or 3-10s
                           (Android/Robolectric) per module — opt-in because plain `doctor`
                           stays cheap.
      --no-auto-inject     Skip auto-injecting the `ee.schimke.composeai.preview` plugin
                           via a Gradle init script. By default the CLI ships a bundled
                           init script and passes it via `--init-script` on every Gradle
                           invocation, so projects that already apply
                           `com.android.application`, `com.android.library`, or
                           `org.jetbrains.compose` pick up the preview plugin without an
                           edit to `build.gradle.kts`. Set this when the plugin is
                           already wired manually and you don't want the bundled
                           classpath dependency added. `COMPOSE_PREVIEW_NO_AUTO_INJECT=1`
                           is an equivalent environment-variable escape hatch.
      --no-plugin-warning  Suppress the one-line stderr warning emitted when the plugin is
                           supplied entirely via auto-inject (i.e. no build.gradle(.kts)
                           applies `id("ee.schimke.composeai.preview")`).
                           `COMPOSE_PREVIEW_NO_PLUGIN_WARNING=1` is an equivalent
                           environment-variable escape hatch.

    OSC 9;4 terminal progress (native taskbar/tab progress bar) is on by
    default in a TTY and auto-disables when stdout is piped or redirected.

    JSON output is wrapped in {schema, previews, counts?} (schema:
    compose-preview-show/v1). Each preview includes a `captures[]` array
    with per-capture pngPath/sha256/changed/advanceTimeMillis/scroll.
    For back-compat the top-level pngPath/sha256/changed mirror the first
    capture. State persisted per module under
    <module>/build/compose-previews/.cli-state.json (wiped on `clean`).

    Exit codes: 0 success, 1 build/CLI error, 2 render failure or a11y
    threshold tripped, 3 no previews found / matched.
    """
      .trimIndent()
  )
}
