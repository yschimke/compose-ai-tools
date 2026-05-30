package ee.schimke.composeai.tui.e2e

import java.io.File
import java.net.ServerSocket

/**
 * Starts and stops an `Xvfb` X server on a free display number for the duration of one e2e run.
 * `Xvnc` would give us VNC playback for live debugging, but `Xvfb` is in apt's standard server set
 * across every distro the CI hits — Xvnc requires `tigervnc-standalone-server` which isn't on every
 * runner. Both behave identically for our purposes (a virtual X server with no real display
 * attached); if you want VNC-driven inspection at debug time, swap the binary name in [start].
 *
 * The test framework's `assumeTrue(...)` guards everything — if `Xvfb` isn't on PATH the whole test
 * self-skips, so this class is only constructed when we know the binary is present.
 */
class DisplayHarness : AutoCloseable {
  private var process: Process? = null
  private var lock: File? = null
  var displayNumber: Int = -1
    private set

  /** Returns `":NN"` formatted for `DISPLAY=`. */
  val display: String
    get() = ":$displayNumber"

  fun start(width: Int = 1280, height: Int = 800, depth: Int = 24) {
    val n = pickDisplayNumber()
    val lockFile = File("/tmp/.X${n}-lock")
    val pb =
      ProcessBuilder("Xvfb", ":$n", "-screen", "0", "${width}x${height}x$depth", "-nolisten", "tcp")
        .redirectErrorStream(true)
        .redirectOutput(File("/tmp/xvfb-tui-cli-$n.log"))
    process = pb.start()
    displayNumber = n
    lock = lockFile
    // Xvfb writes its lock file as soon as it's accepting connections. Poll for ~3s; if it
    // never appears the binary failed and `assertReady` throws (the test framework will see
    // the IOException and fail loudly rather than hang at the first `xdotool key`).
    val deadline = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() < deadline) {
      if (lockFile.exists()) return
      Thread.sleep(50)
    }
    error("Xvfb didn't come up on $display within 3s — see /tmp/xvfb-tui-cli-$n.log")
  }

  override fun close() {
    process?.destroyForcibly()
    process = null
  }

  /**
   * Pick a display number that's almost certainly free. We can't ask X — there's no protocol for
   * "list active displays" — so we look for the absence of the convention lock file
   * (`/tmp/.X<n>-lock`) and the matching Unix socket (`/tmp/.X11-unix/X<n>`). Starting at 90 keeps
   * us clear of any real desktop sessions (`:0`, `:1`) the runner might have.
   */
  private fun pickDisplayNumber(): Int {
    for (n in 90..120) {
      val lockFile = File("/tmp/.X$n-lock")
      val socketFile = File("/tmp/.X11-unix/X$n")
      if (!lockFile.exists() && !socketFile.exists()) {
        // Final guard against TOCTOU — try to bind a TCP port unique to this number. We don't
        // actually use the port; it's just a per-test serialisation point so two concurrent
        // tests can't race for the same display.
        return try {
          ServerSocket(20000 + n).use { n }
        } catch (_: Throwable) {
          continue
        }
      }
    }
    error("no free X display between :90 and :120")
  }
}
