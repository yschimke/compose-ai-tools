package ee.schimke.composeai.cli

import kotlin.system.exitProcess

/**
 * `compose-preview update [VERSION] [--dry-run]`
 *
 * Re-runs `scripts/install.sh` from `main`, which is idempotent and resolves the latest release (or
 * the version pinned via the positional arg) and refreshes both the skill bundle and the CLI
 * launcher. Exists so users don't have to remember the bootstrap URL — paired with `doctor`'s
 * update check.
 *
 * `--dry-run` prints the curl-pipe-bash command without executing it; useful for users who'd rather
 * inspect the installer before running it, or for environments where curl-pipe-bash is
 * policy-blocked.
 */
class UpdateCommand(private val args: List<String>) {
  fun run() {
    val dryRun = "--dry-run" in args
    // Positional version arg, if present (anything not starting with `--`).
    val targetVersion = args.firstOrNull { !it.startsWith("--") }
    val pipeline = buildPipeline(targetVersion)

    if (dryRun) {
      println(pipeline)
      return
    }

    val target = targetVersion ?: "latest"
    System.err.println("==> updating compose-preview (currently $BUNDLE_VERSION) to $target")
    System.err.println("==> running: $pipeline")

    val proc = ProcessBuilder("bash", "-c", pipeline).inheritIO().start()
    val exit = proc.waitFor()
    if (exit != 0) {
      System.err.println("error: install script exited with code $exit")
      exitProcess(exit)
    }
  }

  companion object {
    /**
     * Builds the curl-pipe-bash invocation that re-bootstraps the skill bundle. Pure function so
     * tests can exercise the version-arg quoting without spawning a subprocess.
     */
    internal fun buildPipeline(targetVersion: String?): String {
      val installUrl = "https://raw.githubusercontent.com/$REPO/main/scripts/install.sh"
      return buildString {
        append("curl -fsSL ")
        append(installUrl)
        append(" | bash")
        if (targetVersion != null) {
          append(" -s -- ")
          append(shellQuote(targetVersion))
        }
      }
    }

    /**
     * Conservative shell-quoting for the version arg. `install.sh` only accepts a semver-like
     * positional, so this is belt-and-braces — anything outside `[A-Za-z0-9._-]` gets wrapped in
     * single quotes (with embedded single quotes escaped). Refusing exotic input would also be
     * fine; quoting keeps `compose-preview update <whatever>` from ever expanding into the parent
     * shell pipeline.
     */
    internal fun shellQuote(s: String): String {
      if (s.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) return s
      return "'" + s.replace("'", "'\\''") + "'"
    }
  }
}
