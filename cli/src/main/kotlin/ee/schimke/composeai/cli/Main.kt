package ee.schimke.composeai.cli

import kotlin.system.exitProcess

/**
 * Flat command dispatch table — the single source of truth for which verbs exist and how each is
 * constructed. [CliRouter] decides *what* to run (including group routing and back-compat aliases);
 * this map turns that decision into a command invocation. `CliRouterTest` pins that the keys here
 * match [CliRouter.KNOWN_FLAT], so the router and the dispatcher can't drift.
 */
internal val COMMANDS: Map<String, (List<String>) -> Unit> =
  mapOf(
    "show" to { a -> ShowCommand(a).run() },
    "show-resources" to { a -> ShowResourcesCommand(a).run() },
    "list" to { a -> ListCommand(a).run() },
    "render" to { a -> RenderCommand(a).run() },
    "render-matrix" to { a -> RenderMatrixCommand(a).run() },
    "record" to { a -> RecordPreviewCommand(a).run() },
    "a11y" to { a -> A11yCommand(a).run() },
    "diff-semantics" to { a -> SemanticsDiffCommand(a).run() },
    "history" to { a -> HistoryCommand(a).run() },
    "extensions" to { a -> ExtensionsCommand(a).run() },
    "profile" to { a -> ProfileCommand(a).run() },
    "doctor" to { a -> DoctorCommand(a).run() },
    "devices" to { a -> DevicesCommand(a).run() },
    "serve" to { a -> ServeCommand(a).run() },
    "share-preview" to { a -> SharePreviewCommand(a).run() },
    "bundle" to { a -> BundleCommand(a).run() },
    "mcp" to { a -> McpCommand(a).run() },
    "update" to { a -> UpdateCommand(a).run() },
    "init-script" to { a -> InitScriptCommand(a).run() },
    "version" to { _ -> println("compose-preview $BUNDLE_VERSION") },
    "help" to { a -> printUsage(full = "--all" in a) },
  )

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

  when (val route = CliRouter.route(args)) {
    is CliRouter.Route.Run -> COMMANDS.getValue(route.command).invoke(route.args)
    is CliRouter.Route.GroupUsage -> {
      printGroupUsage(route.group)
      exitProcess(if (route.isError) 1 else 0)
    }
    is CliRouter.Route.TopUsage -> printUsage(full = route.full)
    CliRouter.Route.NoCommand -> {
      System.err.println("No command specified.")
      printUsage()
      exitProcess(1)
    }
    is CliRouter.Route.Unknown -> {
      System.err.println("Unknown command: ${route.command}")
      printUsage()
      exitProcess(1)
    }
  }
}

/**
 * Print a single group's command listing (`compose-preview <group>` with no/unknown subcommand).
 */
private fun printGroupUsage(group: String) {
  val subs = CliRouter.subcommandsOf(group)
  println(
    """
    compose-preview $group — ${subs.joinToString(", ")}

    Usage: compose-preview $group <command> [options]
           (each is also callable directly, e.g. `compose-preview ${subs.firstOrNull() ?: ""}`)

    Run `compose-preview help --all` for the full command + flag reference.
    """
      .trimIndent()
  )
}

/**
 * Tiered help. The default (`full = false`) prints a short core view — the headline render-to-PNG
 * commands plus the handful most users reach for — and points at `help --all` for the rest. The
 * full reference (`help --all`, `--help --all`) is the complete command + flag catalogue. The
 * 160-line reference had become a manual; keeping it behind `--all` stops it from being the first
 * thing a new user hits.
 */
private fun printUsage(full: Boolean = false) {
  if (full) {
    printFullUsage()
    return
  }
  println(
    """
    compose-preview — Compose Preview CLI

    Usage: compose-preview [options] <command> [options]

    Core commands:
      render           Render every @Preview to PNG; --output copies one match to disk
      show             Discover + render previews; print id, path, sha256, changed flag
      list             List discovered previews
      show-resources   Render Android XML resource previews (vector / adaptive-icon / …)
      doctor           Verify Java 17 + Compose/AGP environment before editing Gradle files
      mcp              MCP server lifecycle for agents: serve | install | doctor
      version          Print the installed bundle version and exit
      help             Show this message (`help --all` for every command + flag)

    Command groups (each command is also callable directly by its name):
      inspect   a11y · diff-semantics · devices · extensions · history · profile
      capture   render-matrix · record · bundle
      share     serve · share-preview
      setup     update · init-script
    Run `compose-preview <group>` to list a group, or `help --all` for every command + flag.

    Common options: --module <name>, --filter <pattern>, --id <exact>, --json,
      --output <path>, --verbose/-v. Full list under `help --all`.
    """
      .trimIndent()
  )
}

private fun printFullUsage() {
  println(
    """
    compose-preview — Compose Preview CLI

    Usage: compose-preview [options] <command> [options]

    Commands are grouped (inspect · capture · share · setup); run `compose-preview <group>` to
    list one. Every command below is also reachable as `compose-preview <group> <command>`.

    Commands:
      show             Discover and render previews; print id, path, sha256, changed flag
      show-resources   Render Android XML resource previews (vector, animated-vector,
                       adaptive-icon); same id/path/sha/changed shape as `show`. Sibling
                       command, separate from `show` because the workflows are disjoint —
                       see also `compose-preview-show-resources/v1` JSON envelope.
      list             List discovered previews
      render           Render previews; with --output copies a single match to disk.
                       --bundle additionally packs each module's previews into a
                       portable PNG+ZIP bundle (off by default).
      render-matrix    Render one preview across a cross-product of display axes
                       (--device/--locale/--ui-mode/--font-scale); per-cell hashes +
                       optional --contact-sheet grid PNG.
      record           Record a scripted session against an already-compiled @Preview into a
                       GIF/APNG/MP4/WebM. One command, no daemon/MCP knowledge:
                         compose-preview record --preview <ref> --script <file.json> --out <file>
                       --preview takes an id, a `Class.function` reference, or a unique substring;
                       --script is a JSON array of RecordingScriptEvent (tap/drag/pinch/keys, the
                       same vocabulary MCP record_preview uses). The encoder is picked from the
                       --out extension unless --format is given. gif/apng are always available
                       (pure-JVM); mp4/webm need ffmpeg on PATH. Scripts may include Maestro-style
                       assert.visible / assert.notVisible events (with a target); a failed assertion
                       still writes the recording but exits non-zero (code 2).
      a11y             Render previews with the a11y data extension on and
                       print ATF findings (thin wrapper over `--with-extension a11y`)
      diff-semantics   Diff two compose/semantics trees (base vs head) and report what
                       changed semantically — a cheap, pixel-free regression signal:
                       `compose-preview diff-semantics <base> <head> [--json] [--fail-on-change]`
      history          Inspect archived render history: `history list|read|diff` over the
                       local `.compose-preview-history/` archive or a `--ref` reporting branch
      extensions       Introspect registered data extensions (`extensions list`)
      profile          Run a saved JSON profile: `compose-preview profile <path.json>`. A
                       profile bundles `extensions`, `filter`, `failOn`, and a chosen `report`
                       extension into a single file teams can re-run. Forwards through
                       `ReportCommand`; later flags override profile fields.
      doctor           Verify Java 17 + Compose/AGP environment before editing Gradle files
      devices          List known @Preview(device=...) ids and resolved geometry
      serve            Start a local HTTP server that renders one module's previews on demand and
                       serves them as PNGs with overrides, so you can open or share a network-local
                       link to a specific preview. Read-only; loopback by default, --lan to expose.
                       The shareable link carries an unguessable token (see `serve --help`).
      share-preview    Share rendered previews (a markdown report + images, or a directory of
                       PNGs) somewhere openable. Picks the mechanism by what's available: a gist
                       when the GitHub CLI is installed + authenticated, otherwise a push to a
                       per-branch capture branch through the project remote (works in hosted
                       sessions with no `gh`/token). `--mechanism` forces gist|branch.
      bundle           Pack selected previews + minimal classpath into a portable PNG+ZIP polyglot
      mcp              MCP server lifecycle: serve | install | doctor (see `mcp help`)
      update           Re-run the bootstrap installer to pull the latest release
      init-script      Materialise the bundled auto-inject init script and print the path
                       (--path, default) or its rendered body (--print). Useful for driving
                       Gradle directly with the same `--init-script` body the CLI uses
                       internally.
      version          Print the installed bundle version and exit
      help             Show this help message

    For agents (MCP):
      `compose-preview mcp install|serve` drives the same renders as a push-based, token-frugal
      loop and adds structured data products — accessibility, semantics, layout trees,
      recomposition, live preview state, history. Those data products are also reachable one-shot
      from the commands above (a11y, diff-semantics, history, extensions, record), so MCP is the
      richer surface for an agent loop, not the only way to get the data.

    Options:
      --module <name>      Target module (default: auto-detect all)
      --filter <pattern>   Case-insensitive substring match on preview id
      --id <exact>         Exact match on preview id
      --json               Emit JSON (show, list, a11y, devices)
      --brief              JSON only: drop functionName/className/sourceFile/params
      --changed-only       JSON only (show, a11y): drop previews with no changed capture
      --output <path>      Copy matched preview PNG to this path (render)
      --preview <ref>      record: preview to record — an id, a `Class.function` reference, or a
                           unique substring of an id
      --script <path>      record: JSON array of RecordingScriptEvent driving the session
      --out <path>         record: write the encoded recording here (extension auto-selects the
                           format unless --format is set)
      --format <fmt>       record: gif | apng | mp4 | webm. gif/apng always available (pure-JVM);
                           mp4/webm need ffmpeg on PATH
      --fps <n>            record: frames per second of the virtual playback clock (default 30)
      --scale <f>          record: capture scale multiplier (default 1.0)
      --overrides <k=v,…>  record: per-render overrides, e.g. touchOverlay=true (also device,
                           localeTag, fontScale, density, widthPx, heightPx, inspectionMode,
                           clockEpochMillis=<epoch-ms> to pin a fake wall clock)
      --bundle             render: after rendering, pack each module's previews into a portable
                           PNG+ZIP bundle at <module>/build/compose-previews/bundle.png. Opt-in —
                           adds a classpath closure walk + jar minimization on top of the render.
      --embed-deps         render (with --bundle): embed reachable third-party jars in the bundle
                           instead of referencing Maven coordinates. Bigger file, renders offline.
      --images[=<mode>]    show: emit rendered PNGs inline using the terminal's image protocol.
                           Default `auto` — on by default in an interactive TTY when a
                           kitty-graphics-capable terminal is detected (`KITTY_WINDOW_ID`,
                           `TERM_PROGRAM` ∈ {WezTerm, ghostty}, or `TERM=xterm-kitty`); silent
                           on every other terminal. Modes: `auto`, `kitty` (force; still
                           TTY-gated), `off` (explicit silence). Multi-capture previews
                           (paused-clock animation frames) emit as a native kitty animation;
                           inter-frame gaps come from `advanceTimeMillis` deltas so playback
                           matches the simulated clock. Always off when stdout is piped or
                           `--json` is set so escape sequences don't pollute captured output.
      --progress           Print per-task milestone/heartbeat lines to stderr
      --verbose, -v        Show full Gradle build output (implies --progress)
      --timeout <seconds>  Gradle build timeout (default: 300)
      --fail-on <level>    a11y: exit non-zero on 'errors' or 'warnings' (default: mirror Gradle)
      --with-extension <id>
                           Enable a data extension for this run (repeatable; comma-separated
                           values accepted). Forwards as `-PcomposePreview.activeExtensions
                           =<comma-list>`. The `a11y` command is a thin wrapper that always
                           sets `--with-extension a11y`. Note: a11y data products are
                           daemon-produced — `compose-preview a11y` spins up a short-lived
                           daemon, fetches ATF findings, writes accessibility.json, then shuts
                           it down. It exits non-zero when ATF data is unavailable.
      --force=<reason>     Sanctioned escape hatch when a render looks stale: passes
                           --rerun-tasks to Gradle so every input task re-executes. Does NOT
                           run :clean and does NOT touch build/classes/. Each use is logged
                           with a pointer to issue #924 — please report the freshness gap.
      --missing-renders <fail|warn|ignore>
                           Forwarded as `-PcomposePreview.missingRenders=<value>`. Controls
                           how `composePreviewRenderAll` reacts when a preview is listed in
                           the manifest but produced no PNG: `fail` (Gradle plugin default —
                           throws), `warn` (logs + keeps going), `ignore` (silent). Useful
                           for multi-module CI where a handful of stubborn previews would
                           otherwise gate the whole run.
      --variant <name>     Forwarded as `-PcomposePreview.variant=<name>` to every Gradle
                           invocation (model queries and task runs). Pins which AGP variant
                           the plugin attaches its `composePreview*` tasks to in each
                           module — used to disambiguate flavored apps (e.g.
                           `--variant demoDebug`). Without this flag the plugin defaults to
                           `debug` with a build-type suffix fallback, so flavorless modules
                           pick `debug` and flavored modules pick the first `*Debug` variant
                           AGP enumerates (issue #1546).
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
