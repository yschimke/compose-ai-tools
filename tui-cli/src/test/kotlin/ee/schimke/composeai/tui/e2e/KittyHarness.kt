package ee.schimke.composeai.tui.e2e

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Launches `kitty` against a [DisplayHarness] running the `compose-preview-tui` binary, then drives
 * it with `xdotool` and captures the X root window with ImageMagick's `import`.
 *
 * One harness instance == one kitty window for the lifetime of one test. The driving model is "send
 * key, sleep, capture" — explicit waits rather than busy-polling on the screen, because Mosaic
 * doesn't tell us when a recompose finishes and we'd otherwise race the redraw. The [settleMillis]
 * default of 400ms is enough on every workstation we've tried; if a slower runner makes captures
 * look mid-redraw, bump it.
 *
 * Screenshots are written under [screenshotDir] as PNG. The caller picks the name per state (e.g.
 * `"01-initial"`, `"02-after-down"`), and the harness adds the `.png` suffix.
 */
class KittyHarness(
  private val display: DisplayHarness,
  private val tuiInstallDir: File,
  private val fixtureRoot: File,
  private val screenshotDir: File,
  private val settleMillis: Long = 400,
) : AutoCloseable {
  private var kitty: Process? = null

  fun start(
    cols: Int = 200,
    rows: Int = 50,
    fontSize: Int = 12,
    extraTuiArgs: List<String> = emptyList(),
  ) {
    screenshotDir.mkdirs()
    val launcher =
      File(tuiInstallDir, "bin/compose-preview-tui").apply {
        check(canExecute()) { "tui launcher not executable: $absolutePath" }
      }
    val args = buildList {
      add("kitty")
      // Reduce per-frame work and avoid GPU paths that aren't available under Xvfb.
      add("--override")
      add("font_size=$fontSize")
      add("--override")
      add("window_padding_width=0")
      add("--override")
      add("confirm_os_window_close=0")
      add("--override")
      add("scrollback_lines=200")
      // Kitty's `initial_window_*` keys let us pick the cell-grid size up front so the TUI's
      // first composition lands on the geometry we wanted to test (wide vs narrow). Both keys
      // accept `<n>c` for cells.
      add("--override")
      add("initial_window_width=${cols}c")
      add("--override")
      add("initial_window_height=${rows}c")
      add("--override")
      add("remember_window_size=no")
      add("--override")
      add("enabled_layouts=tall")
      add("--override")
      add("enable_audio_bell=no")
      // Title makes xdotool window selection deterministic across multiple kitty runs.
      add("--title")
      add("compose-preview-tui-e2e")
      add("--name")
      add("compose-preview-tui-e2e")
      add("-e")
      add(launcher.absolutePath)
      add("--no-discovery")
      add("--project-root")
      add(fixtureRoot.absolutePath)
      add("--module")
      add(":sample")
      addAll(extraTuiArgs)
    }
    val pb =
      ProcessBuilder(args)
        .redirectErrorStream(true)
        .redirectOutput(File(screenshotDir, "kitty.log"))
    pb.environment()["DISPLAY"] = display.display
    // Force a UTF-8 locale so the box-drawing / arrow glyphs in the status bar render — the
    // CI runners we test under default to `LANG=C` which substitutes `?` for non-ASCII.
    pb.environment()["LANG"] = "C.UTF-8"
    pb.environment()["LC_ALL"] = "C.UTF-8"
    kitty = pb.start()
    // Kitty's window-mapping delay under Xvfb is unstable below ~1.5s on slow CI runners.
    // Sleep deliberately rather than polling — `xdotool search` doesn't synchronise with the
    // composition's first frame, so even a successful search can return before the TUI has
    // actually drawn anything.
    Thread.sleep(2000)
  }

  /**
   * Send one or more keystrokes via `xdotool key`. Names follow xdotool's syntax — `j`, `Down`,
   * `Return`, `slash`, `shift+L`, etc. Multi-key invocations are issued as separate xdotool calls
   * so a press-flood doesn't compress into one combined event by the X server.
   */
  fun sendKeys(vararg keys: String) {
    for (key in keys) {
      val pb =
        ProcessBuilder(
          "xdotool",
          "search",
          "--name",
          "compose-preview-tui-e2e",
          "key",
          "--window",
          "%@",
          key,
        )
      pb.environment()["DISPLAY"] = display.display
      pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
      val p = pb.start()
      if (!p.waitFor(5, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        error("xdotool stuck sending '$key'")
      }
      // Tiny pause between individual key events so the input layer flushes — without it
      // bursts of `j j j` arrive as one observed redraw and the per-press capture loop sees
      // identical screens.
      Thread.sleep(80)
    }
  }

  /** Type a literal string (no key-name interpretation). */
  fun type(text: String) {
    val pb =
      ProcessBuilder(
        "xdotool",
        "search",
        "--name",
        "compose-preview-tui-e2e",
        "type",
        "--window",
        "%@",
        text,
      )
    pb.environment()["DISPLAY"] = display.display
    pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
    val p = pb.start()
    if (!p.waitFor(5, TimeUnit.SECONDS)) {
      p.destroyForcibly()
      error("xdotool stuck typing '$text'")
    }
  }

  /**
   * Wait the configured [settleMillis] for the composition to redraw, then capture the root window
   * into `<screenshotDir>/<name>.png`. Returns the file for downstream assertions / artifact
   * upload.
   */
  fun capture(name: String): File {
    Thread.sleep(settleMillis)
    val out = File(screenshotDir, "$name.png")
    val pb = ProcessBuilder("import", "-window", "root", out.absolutePath)
    pb.environment()["DISPLAY"] = display.display
    pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
    val p = pb.start()
    if (!p.waitFor(10, TimeUnit.SECONDS)) {
      p.destroyForcibly()
      error("import stuck capturing $name")
    }
    check(out.isFile && out.length() > 0) { "screenshot $name was empty" }
    return out
  }

  override fun close() {
    val k = kitty ?: return
    if (k.isAlive) {
      // Send `q` so the TUI exits gracefully and the daemon (if any) tears down — avoids
      // leaving subprocess kotlin-daemon shadows alive between tests.
      runCatching { sendKeys("q") }
      if (!k.waitFor(1500, TimeUnit.MILLISECONDS)) {
        k.destroyForcibly()
      }
    }
    kitty = null
  }
}
