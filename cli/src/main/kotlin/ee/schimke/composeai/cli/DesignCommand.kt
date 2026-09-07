package ee.schimke.composeai.cli

/**
 * `compose-preview design` — the launcher for the preview server's `design` command.
 *
 * Fourth of the launchers, beside [ServeCommand], [BrowseCommand] and [UiBuilderCommand]: the
 * server binary renders a UI-builder design, exports its generated source, or reads its document,
 * and writes the result to a file
 * ([yschimke/compose-preview-server#529](https://github.com/yschimke/compose-preview-server/issues/529)).
 *
 * It adds nothing of its own — no defaults, no flag rewriting — for the reason `ui-builder` adds
 * nothing: the work needs the UI-builder renderer, the export executor and the asset store, all of
 * which the layer rule places in that repository, and this side has no useful opinion about any of
 * it. `--help` therefore reaches the server too, which is where the answer lives.
 *
 * **It is the one launcher whose target does not serve anything.** `design` runs against a server
 * that is already up — `--server`, defaulting to a local one — and exits with a code that says
 * whether the export was refused. [ServeCommand] already inherits IO and propagates the child's
 * exit code, so a one-shot command needs nothing more from this side than the command word.
 */
class DesignCommand(private val args: List<String>) {
  fun run() {
    ServeCommand(args, serverCommand = SERVER_COMMAND).run()
  }

  internal companion object {
    const val SERVER_COMMAND: String = "design"
  }
}
