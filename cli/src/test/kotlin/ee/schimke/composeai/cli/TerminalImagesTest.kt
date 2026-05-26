package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the kitty graphics protocol formatter behind `compose-preview show --images`. The APC
 * envelope (`ESC _ G ... ESC \`), control-field shape, and frame-extension/animate sequence are
 * load-bearing — kitty silently ignores malformed sequences, so a broken emitter shows up only as
 * "no image appeared" in the user's terminal. Tests assert the literal bytes a kitty would see.
 */
class TerminalImagesTest {
  private val ESC = "\u001b"
  private val APC = "${ESC}_G"
  private val ST = "$ESC\\"

  private fun captureStdout(block: (PrintStream) -> Unit): String {
    val baos = ByteArrayOutputStream()
    val ps = PrintStream(baos, true, Charsets.UTF_8)
    block(ps)
    return baos.toString(Charsets.UTF_8)
  }

  // --- Mode resolution --------------------------------------------------------

  @Test
  fun `absent flag resolves KITTY on an interactive kitty terminal`() {
    // Default is "auto" — `compose-preview show` in a kitty TTY shows images without the user
    // typing `--images`. Pipelines stay safe because non-TTY stdout still resolves OFF (see the
    // dedicated TTY-gate test below).
    val mode =
      TerminalImages.resolve(
        modeArg = null,
        env = { if (it == "KITTY_WINDOW_ID") "abc" else null },
        isTty = true,
      )
    assertEquals(TerminalImages.Mode.KITTY, mode)
  }

  @Test
  fun `absent flag on a non-kitty terminal stays OFF`() {
    // Default-auto must not blast escape sequences at terminals that don't speak the protocol.
    val mode =
      TerminalImages.resolve(
        modeArg = null,
        env = { if (it == "TERM_PROGRAM") "Apple_Terminal" else null },
        isTty = true,
      )
    assertEquals(TerminalImages.Mode.OFF, mode)
  }

  @Test
  fun `absent flag with piped stdout stays OFF even on a kitty terminal`() {
    val mode =
      TerminalImages.resolve(
        modeArg = null,
        env = { if (it == "KITTY_WINDOW_ID") "abc" else null },
        isTty = false,
      )
    assertEquals(TerminalImages.Mode.OFF, mode)
  }

  @Test
  fun `auto sniffs KITTY_WINDOW_ID`() {
    val mode =
      TerminalImages.resolve(
        modeArg = "auto",
        env = { if (it == "KITTY_WINDOW_ID") "12345" else null },
        isTty = true,
      )
    assertEquals(TerminalImages.Mode.KITTY, mode)
  }

  @Test
  fun `auto sniffs TERM_PROGRAM for wezterm and ghostty`() {
    val wez =
      TerminalImages.resolve("auto", env = { if (it == "TERM_PROGRAM") "WezTerm" else null }, true)
    assertEquals(TerminalImages.Mode.KITTY, wez)
    val ghostty =
      TerminalImages.resolve("auto", env = { if (it == "TERM_PROGRAM") "ghostty" else null }, true)
    assertEquals(TerminalImages.Mode.KITTY, ghostty)
  }

  @Test
  fun `auto falls back to OFF when no kitty-capable env signal is present`() {
    val mode =
      TerminalImages.resolve(
        "auto",
        env = { if (it == "TERM_PROGRAM") "Apple_Terminal" else null },
        isTty = true,
      )
    assertEquals(TerminalImages.Mode.OFF, mode)
  }

  @Test
  fun `non-TTY forces OFF even with explicit --images=kitty`() {
    // A redirected stdout (`> out.txt`) can't render images; emitting APCs would just garbage
    // up the captured file. The whole point of the TTY gate is to keep `compose-preview show`
    // safe to redirect.
    val mode = TerminalImages.resolve("kitty", env = { null }, isTty = false)
    assertEquals(TerminalImages.Mode.OFF, mode)
  }

  @Test
  fun `explicit off short-circuits even on a kitty terminal`() {
    val mode =
      TerminalImages.resolve(
        "off",
        env = { if (it == "KITTY_WINDOW_ID") "abc" else null },
        isTty = true,
      )
    assertEquals(TerminalImages.Mode.OFF, mode)
  }

  @Test
  fun `unknown mode value resolves OFF`() {
    val mode = TerminalImages.resolve("magic", env = { null }, isTty = true)
    assertEquals(TerminalImages.Mode.OFF, mode)
  }

  // --- Still image emission ---------------------------------------------------

  @Test
  fun `emitStill wraps PNG in a single APC with a=T, f=100, q=2`() {
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01)
    val out = captureStdout { TerminalImages.emitStill(it, png) }
    assertTrue(out.startsWith(APC), "expected APC introducer, got: ${out.take(8).map { it.code }}")
    assertTrue(out.endsWith(ST))
    assertContains(out, "a=T")
    assertContains(out, "f=100") // f=100 == PNG transmission format
    assertContains(out, "q=2") // suppress kitty's response chatter
    val payload = out.substringAfter(';').substringBefore(ST)
    assertEquals(Base64.getEncoder().encodeToString(png), payload)
  }

  // --- Animation emission -----------------------------------------------------

  @Test
  fun `emitAnimation with one frame degrades to a single still`() {
    val out = captureStdout {
      TerminalImages.emitAnimation(it, listOf(TerminalImages.Frame(byteArrayOf(1, 2, 3), 50)))
    }
    assertContains(out, "a=T")
    assertFalse(out.contains("a=f"), "single-frame path must not extend with a=f")
    assertFalse(out.contains("a=a"), "single-frame path must not start the animation engine")
  }

  @Test
  fun `emitAnimation sends a=T, then a=f per extra frame, then a=a to start playback`() {
    val frames =
      listOf(
        TerminalImages.Frame(byteArrayOf(1), 16),
        TerminalImages.Frame(byteArrayOf(2), 33),
        TerminalImages.Frame(byteArrayOf(3), 50),
      )
    val out = captureStdout { TerminalImages.emitAnimation(it, frames) }

    // Split into APC commands by the ST terminator (drop trailing empty after final ST).
    val commands = out.split(ST).filter { it.isNotEmpty() }
    assertEquals(4, commands.size, "expected 3 frame APCs + 1 animate APC, got: ${commands.size}")

    // Frame 1: base image transmit.
    assertContains(commands[0], "a=T")
    assertContains(commands[0], "z=16")
    // Frame 2 + 3: add-frame extensions.
    assertContains(commands[1], "a=f")
    assertContains(commands[1], "z=33")
    assertContains(commands[2], "a=f")
    assertContains(commands[2], "z=50")
    // Final command: start playback. s=3 = loop forever.
    assertContains(commands[3], "a=a")
    assertContains(commands[3], "s=3")
  }

  @Test
  fun `all frames in one animation share the same image id`() {
    // The graphics protocol routes `a=f` and `a=a` to a frame's base image by `i=` — if the IDs
    // diverge across frames, kitty drops the extra frames silently and the user sees a still.
    val frames = (1..3).map { TerminalImages.Frame(byteArrayOf(it.toByte()), 16) }
    val out = captureStdout { TerminalImages.emitAnimation(it, frames) }
    val ids = Regex("i=(\\d+)").findAll(out).map { it.groupValues[1] }.toList()
    assertEquals(4, ids.size, "expected an i= on each of 4 APCs")
    assertEquals(1, ids.toSet().size, "all APCs in one animation must share i=, got $ids")
  }

  // --- Frame timing from advanceTimeMillis ------------------------------------

  @Test
  fun `framesFromCaptures derives gaps from advanceTimeMillis deltas`() {
    val pngs = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
    val times = listOf<Long?>(0L, 32L, 96L)
    val frames = TerminalImages.framesFromCaptures(pngs, times, defaultGapMillis = 100)
    assertEquals(3, frames.size)
    assertEquals(32, frames[0].gapMillis) // 32 - 0
    assertEquals(64, frames[1].gapMillis) // 96 - 32
    // Last frame inherits the previous gap so the loop returns to frame 0 at a sensible cadence
    // — not the default, which would jolt animations whose real cadence is much faster/slower.
    assertEquals(64, frames[2].gapMillis)
  }

  @Test
  fun `framesFromCaptures falls back to default when advanceTimeMillis is missing or backwards`() {
    val pngs = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
    val times = listOf<Long?>(null, 50L, 40L) // null + non-monotonic
    val frames = TerminalImages.framesFromCaptures(pngs, times, defaultGapMillis = 100)
    assertEquals(100, frames[0].gapMillis) // null endpoint → default
    assertEquals(100, frames[1].gapMillis) // 40 - 50 = -10 → default
    assertEquals(100, frames[2].gapMillis) // last-frame inherit-then-default
  }

  // --- Chunking for large payloads --------------------------------------------

  @Test
  fun `payload larger than 4096 base64 chars splits across m=1 chunks ending with m=0`() {
    // 4 KiB base64 = 3072 raw bytes. Use 5000 raw → ~6668 base64 → 2 chunks.
    val big = ByteArray(5000) { (it % 251).toByte() }
    val out = captureStdout { TerminalImages.emitStill(it, big) }
    val commands = out.split(ST).filter { it.isNotEmpty() }
    assertEquals(2, commands.size, "expected exactly 2 APC chunks for 5000-byte payload")
    // First chunk: carries the full control set plus m=1 (more follow).
    assertContains(commands[0], "a=T")
    assertContains(commands[0], "f=100")
    assertContains(commands[0], "m=1")
    // Last chunk: m=0 terminator, no a=T (control fields ride only on the first chunk).
    assertContains(commands[1], "m=0")
    assertFalse(
      commands[1].contains("a=T"),
      "control fields must not repeat on continuation chunks",
    )
  }
}
