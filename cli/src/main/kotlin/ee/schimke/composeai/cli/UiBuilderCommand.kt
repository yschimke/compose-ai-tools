package ee.schimke.composeai.cli

/**
 * `compose-preview ui-builder` — the launcher for the preview server's `ui` command.
 *
 * Third of the launchers, beside [ServeCommand] and [BrowseCommand]: the server binary builds this
 * project's previews and opens the Compose UI builder against them
 * ([yschimke/compose-preview-server#301](https://github.com/yschimke/compose-preview-server/issues/301)).
 *
 * It adds nothing of its own — no defaults, no flag rewriting — because unlike `browse` there is no
 * project-mode choice to make on this side. Everything `ui` implies (discovery, the packaged
 * builder distribution, the module's component record, the page to open) is decided by the server,
 * which is also the half that knows where those things are. `--help` therefore reaches the server
 * too, which is where the answer lives.
 *
 * The Gradle work still travels the other way: the server spawns `compose-preview build-host
 * --stdio` when it needs a build, so this process is a launcher and not a host.
 */
class UiBuilderCommand(private val args: List<String>) {
  fun run() {
    ServeCommand(args, serverCommand = SERVER_COMMAND).run()
  }

  internal companion object {
    const val SERVER_COMMAND: String = "ui"
  }
}
