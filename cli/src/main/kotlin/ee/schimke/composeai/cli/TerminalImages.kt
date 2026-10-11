package ee.schimke.composeai.cli

import java.io.PrintStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Inline images for `compose-preview show --images`, via kitty's graphics protocol (kitty, Ghostty,
 * WezTerm). Multi-capture previews become a native kitty animation: frame 1 with `a=T`, later
 * frames with `a=f,z=<gap_ms>`, then `a=a,s=3` to loop, with gaps from the `advanceTimeMillis`
 * deltas. iTerm2 / sixel / chafa aren't implemented; `auto` resolves them to [Mode.OFF].
 *
 * Reference: https://sw.kovidgoyal.net/kitty/graphics-protocol/
 */
object TerminalImages {
  enum class Mode {
    KITTY,
    OFF,
  }

  private const val ESC = "\u001b"
  // Kitty graphics control sequence envelope: `ESC _ G <controls> ; <payload> ESC \`.
  internal const val APC = "${ESC}_G"
  internal const val ST = "$ESC\\"

  /**
   * Resolve `--images=<value>` plus the environment into the effective mode (pure; callers pass
   * [System.getenv] and `isTty`). Default `auto` picks KITTY on an interactive TTY in a
   * kitty-capable terminal (`KITTY_WINDOW_ID`, `TERM_PROGRAM` ∈ {WezTerm, ghostty}, or
   * `TERM=xterm-kitty`), else OFF; redirected stdout is always OFF. `off` opts out explicitly.
   */
  fun resolve(modeArg: String?, env: (String) -> String?, isTty: Boolean): Mode {
    val tag = modeArg?.lowercase()
    if (tag == "off") return Mode.OFF
    if (!isTty) return Mode.OFF
    if (tag == "kitty") return Mode.KITTY
    // null (flag absent) and "auto" both mean "sniff the env on a TTY".
    if (env("KITTY_WINDOW_ID") != null) return Mode.KITTY
    val termProgram = env("TERM_PROGRAM")?.lowercase()
    if (termProgram == "wezterm" || termProgram == "ghostty") return Mode.KITTY
    if (env("TERM") == "xterm-kitty") return Mode.KITTY
    return Mode.OFF
  }

  /** One animation frame: the raw PNG bytes plus how long to dwell before the next frame. */
  data class Frame(val pngBytes: ByteArray, val gapMillis: Int)

  /**
   * Inter-frame gaps from a paused-clock series: each frame dwells for the delta to the next
   * `advanceTimeMillis`; the last reuses the previous gap. Null or non-positive deltas use
   * [defaultGapMillis].
   */
  fun framesFromCaptures(
    pngs: List<ByteArray>,
    advanceTimeMillis: List<Long?>,
    defaultGapMillis: Int = 100,
  ): List<Frame> {
    require(pngs.size == advanceTimeMillis.size) { "pngs and times must align" }
    if (pngs.isEmpty()) return emptyList()
    val gaps = IntArray(pngs.size) { defaultGapMillis }
    for (i in 0 until pngs.size - 1) {
      val a = advanceTimeMillis[i]
      val b = advanceTimeMillis[i + 1]
      if (a != null && b != null) {
        val delta = (b - a).toInt()
        if (delta > 0) gaps[i] = delta
      }
    }
    if (pngs.size >= 2) gaps[pngs.size - 1] = gaps[pngs.size - 2]
    return pngs.mapIndexed { i, bytes -> Frame(bytes, gaps[i]) }
  }

  private val nextImageId = AtomicInteger(1)

  /** Emit a single still image using kitty's graphics protocol. */
  fun emitStill(out: PrintStream, pngBytes: ByteArray) {
    val id = nextImageId.getAndIncrement()
    writeKittyChunks(out, controls = "a=T,f=100,i=$id,q=2", payload = base64(pngBytes))
    out.flush()
  }

  /**
   * Emit a multi-frame kitty animation. Transmit frame 1 with `a=T`, add each subsequent frame with
   * `a=f,z=<gap_ms>`, then start looping playback with `a=a,s=3`. Single-frame inputs degrade to
   * [emitStill].
   */
  fun emitAnimation(out: PrintStream, frames: List<Frame>) {
    if (frames.isEmpty()) return
    if (frames.size == 1) {
      emitStill(out, frames[0].pngBytes)
      return
    }
    val id = nextImageId.getAndIncrement()
    val first = frames[0]
    writeKittyChunks(
      out,
      controls = "a=T,f=100,i=$id,z=${first.gapMillis},q=2",
      payload = base64(first.pngBytes),
    )
    for (i in 1 until frames.size) {
      val f = frames[i]
      writeKittyChunks(
        out,
        controls = "a=f,f=100,i=$id,z=${f.gapMillis},q=2",
        payload = base64(f.pngBytes),
      )
    }
    out.print("${APC}a=a,i=$id,s=3,v=0,q=2$ST")
    out.flush()
  }

  /**
   * Kitty APC payloads cap at 4096 base64 chars; longer payloads use `m=1` on every chunk except
   * the last (`m=0`). Control fields ride only on the first chunk.
   */
  private fun writeKittyChunks(out: PrintStream, controls: String, payload: String) {
    val chunkSize = 4096
    if (payload.length <= chunkSize) {
      out.print("$APC$controls;$payload$ST")
      return
    }
    var offset = 0
    var first = true
    while (offset < payload.length) {
      val end = (offset + chunkSize).coerceAtMost(payload.length)
      val isLast = end == payload.length
      val header = if (first) "$controls,m=${if (isLast) 0 else 1}" else "m=${if (isLast) 0 else 1}"
      out.print("$APC$header;${payload.substring(offset, end)}$ST")
      first = false
      offset = end
    }
  }

  private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
