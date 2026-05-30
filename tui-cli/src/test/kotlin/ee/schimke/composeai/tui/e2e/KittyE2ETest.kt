package ee.schimke.composeai.tui.e2e

import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end visual capture of `compose-preview-tui` running inside a real `kitty` terminal, hosted
 * on a virtual X server (`Xvfb`). The harness drives the TUI via `xdotool` and captures the root
 * window with `import` after every state change so reviewers can eyeball the layout across
 * navigation, filtering, the live-mode toggle and the wide ↔ narrow flip without having to spin up
 * the binary themselves.
 *
 * ## Why a separate process rather than `runMosaic` in-test
 *
 * Mosaic owns stdin/stdout and assumes a real PTY; the only way to exercise the actual rendered
 * output is to attach it to one. Kitty under Xvfb is the cheapest way to do that on a headless
 * runner — it gives us a UTF-8 PTY plus an X window we can screenshot, all under apt-installable
 * binaries.
 *
 * ## Skip semantics
 *
 * Each binary in the harness chain (`Xvfb`, `kitty`, `xdotool`, `import`) is JUnit-`assume`'d up
 * front. On a runner without any one of them, the test logs the skip reason and returns green — it
 * isn't a failure, the harness simply isn't installable in that environment. CI jobs that want the
 * test to actually execute add `apt install kitty xdotool imagemagick xvfb` to their setup step.
 */
class KittyE2ETest {

  @Test
  @DisplayName("captures TUI in initial / down / live-toggle / filter / focus-shift states")
  fun captureStates(@TempDir tempDir: File) {
    assumeBinaries()

    val installDirProp =
      System.getProperty("tui-cli.install-dir")
        ?: error("system property 'tui-cli.install-dir' missing — wire it from Gradle")
    val installDir = File(installDirProp)
    assumeTrue(
      File(installDir, "bin/compose-preview-tui").canExecute(),
      "tui launcher not built; run :tui-cli:installDist",
    )

    val fixture = Fixtures.build(tempDir)
    val screenshots = File("build/e2e-screenshots").absoluteFile.apply { mkdirs() }

    DisplayHarness().use { display ->
      // 1920×1080 viewport is enough to fit a 140-col / 42-row kitty window at fontSize=10 with
      // a small headroom. We deliberately oversize the Xvfb screen rather than the kitty window
      // because clipped-off-screen content (kitty grid wider than the X viewport) shows up as a
      // missing right-pane in screenshots that look fine at a glance.
      display.start(width = 1920, height = 1080)

      // --- Wide layout (140 cols > 120 col breakpoint, fits the Xvfb screen end-to-end) ---
      KittyHarness(
          display = display,
          tuiInstallDir = installDir,
          fixtureRoot = fixture,
          screenshotDir = File(screenshots, "wide"),
        )
        .use { kitty ->
          kitty.start(cols = 140, rows = 42, fontSize = 11)
          kitty.capture("01-initial")

          // Navigate down once and capture — the list cursor should land on `CardPreview`
          // and the centre pane should re-read that PNG, giving a visibly different ASCII
          // image.
          kitty.sendKeys("Down")
          kitty.capture("02-after-down")

          // Toggle live mode. The fixture has no `daemon-launch.json` so the session goes
          // to FAILED with the expected error message — that's a real state worth a capture
          // (it's how the user sees "the daemon isn't running" without a separate doctor
          // command).
          kitty.sendKeys("shift+l")
          kitty.capture("03-live-failed")
          // Toggle off so the session controller doesn't keep retrying as we exercise more
          // states.
          kitty.sendKeys("shift+l")

          // Filter editor: `/`, then "Card", then Enter.
          kitty.sendKeys("slash")
          kitty.type("Card")
          kitty.capture("04-filter-editing")
          kitty.sendKeys("Return")
          kitty.capture("05-filter-applied")

          // Cancel the filter so we land back on ButtonPreview (which has a11y findings) for
          // the data-pane capture. Escape clears the editor; we also need to clear the applied
          // filter so the row count goes back to 3 — easiest path is `/` to reopen and `Enter`
          // on an empty draft, which the App treats as "no filter".
          kitty.sendKeys("slash")
          kitty.sendKeys("Return")

          // Now Tab twice from list → preview → data. The status bar's pane indicator flips
          // and the data pane header gets the inverted-bold focus style.
          kitty.sendKeys("Tab")
          kitty.sendKeys("Tab")
          kitty.capture("06-data-pane-focused")
        }

      // --- Narrow layout (80 cols < 120 col breakpoint) ---
      KittyHarness(
          display = display,
          tuiInstallDir = installDir,
          fixtureRoot = fixture,
          screenshotDir = File(screenshots, "narrow"),
        )
        .use { kitty ->
          kitty.start(cols = 80, rows = 30)
          kitty.capture("01-narrow-list")
          // Tab cycles through the three tabs in narrow mode — capture each.
          kitty.sendKeys("Tab")
          kitty.capture("02-narrow-preview")
          kitty.sendKeys("Tab")
          kitty.capture("03-narrow-data")
        }
    }

    val all =
      screenshots
        .walkTopDown()
        .filter { it.isFile && it.extension == "png" }
        .sortedBy { it.absolutePath }
        .toList()
    check(all.isNotEmpty()) { "harness produced no screenshots" }
    all.forEach {
      check(it.length() > 1024) { "screenshot ${it.name} too small (${it.length()} bytes)" }
    }
    println("e2e screenshots:")
    all.forEach { println("  ${it.absolutePath}") }
  }

  /** Assumption gate for the four binaries the harness shells out to. */
  private fun assumeBinaries() {
    listOf("Xvfb", "kitty", "xdotool", "import").forEach { binary ->
      val present =
        ProcessBuilder("sh", "-c", "command -v $binary >/dev/null 2>&1").start().waitFor() == 0
      assumeTrue(present, "binary '$binary' not on PATH — skipping e2e capture run")
    }
  }
}
