package ee.schimke.composeai.render.session.subprocess

import ee.schimke.composeai.daemon.protocol.BootPhase
import ee.schimke.composeai.daemon.protocol.BootProgressParams
import java.util.Locale

/**
 * One human-readable line per daemon `bootProgress` notification (compose-ai-tools#6114), for a
 * caller's log while `initialize` waits on a cold sandbox boot: enough to tell a slow boot from a
 * hung one without reading the daemon's own stderr.
 */
internal object BootProgressLines {

  fun describe(module: String, progress: BootProgressParams): String? {
    val at = String.format(Locale.ROOT, "%.1fs", progress.elapsedMs / 1000.0)
    val slots =
      if (progress.totalSlots > 0) " (${progress.readySlots}/${progress.totalSlots} ready)" else ""
    val what =
      when (progress.phase) {
        BootPhase.STARTING -> "daemon answered; booting its render host"
        BootPhase.SANDBOX_BOOTING -> "booting sandbox ${progress.slot ?: "?"}$slots"
        BootPhase.SANDBOX_READY -> "sandbox ${progress.slot ?: "?"} ready$slots"
        BootPhase.READY -> "daemon ready$slots"
        BootPhase.FAILED -> "daemon failed to boot: ${progress.message ?: "no reason given"}"
        else -> "daemon boot: ${progress.phase}$slots"
      }
    return "$module: $what [+$at]"
  }
}
