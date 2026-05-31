package ee.schimke.composeai.tui

import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File

/**
 * Argument parser for `compose-preview-tui`. Mirrors the subset of flags `:cli`'s [Command] base
 * understands so the same `--module` / `--filter` / `--id` invocation works against both binaries —
 * switching a workflow from `compose-preview list --module :samples:android --filter Foo` to
 * `compose-preview-tui --module :samples:android --filter Foo` should be muscle-memory.
 *
 * Unknown flags are silently ignored (printed back as a warning to stderr) rather than failing the
 * parse — the TUI is interactive and the user can recover from a typo without a restart.
 */
data class TuiArgs(
  val module: String? = null,
  val filter: String? = null,
  val exactId: String? = null,
  /** Extensions to enable on the live session (always enabled regardless of live-mode toggle). */
  val extensions: Set<String> = setOf("a11y"),
  /** Initial live-mode state. Sticky once toggled; flag just sets the starting value. */
  val liveOnStart: Boolean = false,
  /** Project root override. Defaults to walking up from cwd looking for `gradlew`. */
  val projectRoot: File? = null,
  /**
   * A bundle PNG to open directly. When set, the TUI skips Gradle discovery and the browser UI
   * entirely and renders just this preview's image — full-screen, no chrome — seeding the first
   * frame from the PNG and (if it carries provenance) attaching the daemon for live re-renders.
   * Captured from a lone existing `*.png` positional argument.
   */
  val bundlePng: File? = null,
  /**
   * Non-interactive dump mode (`--dump` / `--ascii`). Requires a bundle PNG positional: render a
   * baked preview to stdout as text (half-block / ASCII fallback via Mosaic's one-shot
   * [com.jakewharton.mosaic.renderMosaic]) and exit, instead of taking over the terminal. Dumps the
   * cover preview by default; `--id` pins an exact preview and `--filter` dumps a matching slice.
   * Built for CI / piped stdout where there's no PTY.
   */
  val dump: Boolean = false,
  val verbose: Boolean = false,
  val timeoutSeconds: Long = 300,
  /**
   * Test escape hatch: skip the Gradle Tooling-API discovery pass and synthesise a single
   * `PreviewModule` directly from [module] + [projectRoot]. Required by the kitty-under-Xvfb e2e
   * harness — driving a real Tooling-API discovery from a synthetic fixture would push the test
   * into the 60s+ range and we want every state capture to land in seconds. Not surfaced in
   * `--help`; the consumer-facing flow always goes through discovery.
   */
  val noDiscovery: Boolean = false,
) {
  companion object {
    fun parse(argv: Array<String>): TuiArgs {
      var module: String? = null
      var filter: String? = null
      var exactId: String? = null
      val extensions = mutableSetOf("a11y")
      var live = false
      var verbose = false
      var projectRoot: File? = null
      var timeoutSeconds = 300L
      var noDiscovery = false
      var dump = false
      val positionals = mutableListOf<String>()

      val valuedFlags =
        setOf("--module", "--filter", "--id", "--timeout", "--project-root", "--with-extension")
      var i = 0
      while (i < argv.size) {
        val arg = argv[i]
        val (name, inlineValue) =
          if (arg.startsWith("--") && arg.contains('=')) {
            val (n, v) = arg.split('=', limit = 2)
            n to v
          } else {
            arg to null
          }
        fun nextValue(): String? = inlineValue ?: argv.getOrNull(i + 1)?.also { i += 1 }
        when (name) {
          "--module" -> module = nextValue()
          "--filter" -> filter = nextValue()
          "--id" -> exactId = nextValue()
          "--with-extension" -> {
            val v = nextValue() ?: continue
            extensions += v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
          }
          "--timeout" -> timeoutSeconds = nextValue()?.toLongOrNull() ?: timeoutSeconds
          "--project-root" -> projectRoot = nextValue()?.let(::File)
          "--live" -> live = true
          "--no-discovery" -> noDiscovery = true
          "--dump",
          "--ascii" -> dump = true
          "--verbose",
          "-v" -> verbose = true
          "--help",
          "-h" -> {
            printUsage()
            kotlin.system.exitProcess(0)
          }
          else ->
            if (name.startsWith("-")) {
              System.err.println("warning: ignoring unknown flag '$name'")
            } else {
              positionals += arg
            }
        }
        i += 1
      }

      // A lone `*.png` positional — a local path OR an http(s)/file URL — opens straight into the
      // image-only bundle view. URLs are downloaded to a temp file first.
      val bundlePng = positionals.firstOrNull()?.let(::resolveBundleArg)

      return TuiArgs(
        module = module,
        filter = filter,
        exactId = exactId,
        extensions = extensions,
        liveOnStart = live,
        projectRoot = projectRoot,
        bundlePng = bundlePng,
        dump = dump,
        verbose = verbose,
        timeoutSeconds = timeoutSeconds,
        noDiscovery = noDiscovery,
      )
    }

    fun printUsage() {
      println(
        """
        compose-preview-tui — interactive Mosaic-based Compose Preview browser

        Usage:
          compose-preview-tui [options]          Browse a project's previews
          compose-preview-tui <bundle.png | URL>  Open a bundle full-screen (image only, live if it
                                                 carries provenance). A URL is downloaded first.
          compose-preview-tui --dump <bundle.png | URL>
                                                 Print a baked preview to stdout as text
                                                 (half-block / ASCII) and exit — the cover by
                                                 default, or pick with --id / --filter. No PTY
                                                 needed — for CI / piped output.

        Options:
          --dump, --ascii        Non-interactive: dump a baked preview in a bundle to stdout
                                 (ASCII fallback) and exit. The cover preview by default;
                                 use --id / --filter to choose. Requires a bundle PNG.
          --module <path>        Gradle path (e.g. :samples:android). Default: prompt
                                 / pick first discovered.
          --filter <pattern>     Case-insensitive substring filter on preview id.
          --id <exact>           Pin to a single preview by exact id.
          --with-extension <id>  Enable a data extension on the live session.
                                 Repeatable / comma-separated. Default: a11y.
          --live                 Start in live mode (sticky across navigation).
          --project-root <dir>   Project root (default: walk up from cwd for gradlew).
          --timeout <seconds>    Gradle build timeout (default 300).
          --verbose, -v          Stream Gradle output to stderr.
          --help, -h             Show this help.

        Key bindings (interactive):
          ↑ / ↓ or k / j        Move selection in the preview list.
          → / l                  Focus the right pane (data) in narrow mode.
          ← / h                  Focus the list pane.
          Tab                    Cycle tabs (narrow mode) / focus pane (wide).
          /                      Start typing a filter; Enter applies, Esc cancels.
          L                      Toggle sticky live mode.
          r                      Force re-render of the selected preview.
          q                      Quit.

        Live mode subscribes the underlying render-session daemon to the selected
        module. Edits made outside of this terminal (vim in another pane, VS Code,
        etc.) are detected via a filesystem watcher rooted at the module's project
        directory and forwarded to the daemon as `fileChanged` notifications — the
        daemon re-renders and pushes a notification back, and the TUI reloads the
        PNG + accessibility findings without manual intervention.
        """
          .trimIndent()
      )
    }
  }
}

/**
 * Resolve a bundle positional — a local `*.png` path or an http(s)/file URL — to a readable local
 * file, or null when it isn't an openable bundle (missing path, non-png, failed download). URLs are
 * downloaded to a temp file (delete-on-exit). Self-contained here rather than depending on `:cli` so
 * the opt-in TUI module's graph stays minimal.
 */
private fun resolveBundleArg(arg: String): File? {
  val scheme = arg.substringBefore(':', missingDelimiterValue = "").lowercase()
  val isUrl = scheme == "http" || scheme == "https" || scheme == "file"
  if (!isUrl) {
    return File(arg).takeIf { it.isFile && it.name.endsWith(".png", ignoreCase = true) }
  }
  return try {
    val uri = java.net.URI(arg)
    if (uri.scheme.equals("file", ignoreCase = true)) {
      return File(uri).takeIf { it.isFile && it.name.endsWith(".png", ignoreCase = true) }
    }
    val temp = java.nio.file.Files.createTempFile("compose-preview-tui-bundle-", ".png").toFile()
    temp.deleteOnExit()
    // Ktor client over the OkHttp engine; stream the body to disk on a 2xx. runBlocking is fine at
    // this one-shot startup parse.
    val ok =
      io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp).use { client ->
        kotlinx.coroutines.runBlocking {
          client.prepareGet(uri.toString()).execute { response ->
            if (response.status.isSuccess()) {
              temp.outputStream().use { out -> response.bodyAsChannel().copyTo(out) }
              true
            } else {
              false
            }
          }
        }
      }
    if (ok && temp.length() > 0) temp
    else {
      temp.delete()
      null
    }
  } catch (_: Exception) {
    null
  }
}
