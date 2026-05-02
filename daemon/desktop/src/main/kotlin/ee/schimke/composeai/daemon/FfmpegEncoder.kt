package ee.schimke.composeai.daemon

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional `ffmpeg`-backed encoder for [RecordingFormat.MP4] and [RecordingFormat.WEBM] — see
 * RECORDING.md § "encoded formats".
 *
 * **Detection.** [available] runs `ffmpeg -version` once at JVM start (lazily, on first call) and
 * caches the result for the daemon's lifetime. A subsequent uninstall during the daemon's life
 * isn't observed; that's fine — daemons are short-lived per workspace and the path is set at spawn
 * time. When detection fails, [DesktopHost.supportedRecordingFormats] omits `mp4` / `webm` from the
 * advertised set so the MCP layer's `validateRecordingFormat` rejects requests with a clean
 * diagnostic instead of a `record_preview` runtime error.
 *
 * **Encoder shape.** Both formats use the standard `ffmpeg -framerate <fps> -i frame-%05d.png ...`
 * pipeline against the per-frame PNGs [DesktopRecordingSession] writes:
 *
 * - **MP4**: H.264 via libx264 + yuv420p pixel format (the universal-compatibility default — plays
 *   in QuickTime, every browser, mobile players). `-pix_fmt yuv420p` is load-bearing for Android /
 *   iOS players that reject yuv444 and friends.
 * - **WEBM**: VP9 via libvpx-vp9. Smaller files for the same quality at the cost of slower encode;
 *   reasonable trade-off for short recordings (typical agent clips are <10 s).
 *
 * **Why not pure-Java mp4.** JCodec / Humble exist but pull in megabytes of native bindings or
 * pure-Java H.264 implementations that are 5–10× slower than libx264. Since this is an opt-in
 * surface (APNG is the always-available default), shelling out to `ffmpeg` is the right pragmatic
 * answer — most developer machines and CI agents already have it installed.
 *
 * **Threading / process lifetime.** `ffmpeg` is run as a `ProcessBuilder` subprocess with a bounded
 * wait. A 60 s budget covers typical clips (a few hundred frames) with margin; longer recordings
 * should still complete within minutes. The subprocess is `destroyForcibly()`'d on timeout to
 * prevent zombies; tests rely on this to keep the JVM clean across encode failures.
 */
object FfmpegEncoder {

  /**
   * Encoder timeout in milliseconds. 60 s is generous enough for ~5 s of 30 fps content (libx264
   * encodes ~100 fps on a modern laptop) and covers libvpx-vp9's slower path. Longer recordings
   * should still complete; if not, surfacing the timeout to the caller as an exception is
   * preferable to leaving a half-written file on disk.
   */
  const val ENCODE_TIMEOUT_MS: Long = 60_000L

  @Volatile private var detected: Boolean? = null

  /**
   * Cached probe of `ffmpeg -version`. Returns `true` if `ffmpeg` is on `PATH` and exits 0; cache
   * is computed once per JVM. Tests can flip the cache via [resetDetectionForTesting] when they
   * need to exercise the "ffmpeg unavailable" path on a machine where it's installed.
   */
  fun available(): Boolean {
    val cached = detected
    if (cached != null) return cached
    val result =
      try {
        val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() } // drain so the subprocess exits cleanly
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly()
          false
        } else {
          process.exitValue() == 0
        }
      } catch (_: Throwable) {
        false
      }
    detected = result
    return result
  }

  /** Test hook — clears the detection cache so the next [available] call re-probes. */
  internal fun resetDetectionForTesting() {
    detected = null
  }

  /**
   * Encode the per-frame PNGs in [framesDir] (named `frame-NNNNN.png`, contiguous starting at 0) to
   * [out] using [format] at [fps] frames per second. Throws on any non-zero exit, missing binary
   * (when [available] reports false at call time), or timeout — callers map these to a tool-level
   * error so the agent sees the real failure instead of an empty file on disk.
   */
  fun encodeFromPngFrames(framesDir: File, fps: Int, format: RecordingFormatChoice, out: File) {
    require(fps in 1..120) { "FfmpegEncoder: fps=$fps out of range [1, 120]" }
    require(framesDir.isDirectory) {
      "FfmpegEncoder: framesDir does not exist or is not a directory: ${framesDir.absolutePath}"
    }
    if (!available()) {
      error("FfmpegEncoder: ffmpeg not found on PATH; install ffmpeg or use RecordingFormat.APNG")
    }

    out.parentFile?.mkdirs()
    if (out.exists()) out.delete()

    val args = mutableListOf("ffmpeg", "-y", "-framerate", fps.toString())
    args.add("-i")
    args.add(File(framesDir, "frame-%05d.png").absolutePath)
    when (format) {
      RecordingFormatChoice.MP4 -> {
        // H.264 + yuv420p for universal player compatibility (mobile + QuickTime require yuv420p
        // even though libx264's default profile would otherwise pick yuv444). `-movflags
        // +faststart` shifts the moov atom to the start of the file so streaming players can
        // begin playback before downloading the trailer; cheap on small clips, useful when the
        // file is served via HTTP from the daemon's history dir.
        args.addAll(
          listOf(
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-preset",
            "veryfast",
            "-movflags",
            "+faststart",
          )
        )
      }
      RecordingFormatChoice.WEBM -> {
        // VP9 with the row-multithread + tile-columns combo most modern guides recommend for
        // sub-720p content. `-deadline good -cpu-used 4` is the speed/quality middle ground —
        // matching libx264's `veryfast`. Default WebM container.
        args.addAll(
          listOf(
            "-c:v",
            "libvpx-vp9",
            "-pix_fmt",
            "yuv420p",
            "-deadline",
            "good",
            "-cpu-used",
            "4",
            "-row-mt",
            "1",
          )
        )
      }
    }
    args.add(out.absolutePath)

    val pb = ProcessBuilder(args).redirectErrorStream(true)
    val proc = pb.start()
    // Drain stdout/stderr in a background thread so the subprocess doesn't block on a full pipe
    // buffer mid-encode. ffmpeg writes a fair amount of progress info to stderr (which we've
    // merged into stdout via `redirectErrorStream`); not draining causes the encoder to stall on
    // a small clip after a few hundred KB of output.
    val log = StringBuilder()
    val drainThread =
      Thread {
          try {
            proc.inputStream.bufferedReader().useLines { lines ->
              lines.forEach { line ->
                synchronized(log) {
                  log.appendLine(line)
                  if (log.length > MAX_LOG_BYTES) {
                    log.delete(0, log.length - MAX_LOG_BYTES)
                  }
                }
              }
            }
          } catch (_: Throwable) {
            // Process exited; drain done.
          }
        }
        .apply {
          isDaemon = true
          start()
        }

    val finished = proc.waitFor(ENCODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    if (!finished) {
      proc.destroyForcibly()
      runCatching { drainThread.join(2_000) }
      error(
        "FfmpegEncoder: ffmpeg ${format.name.lowercase()} encode timed out after ${ENCODE_TIMEOUT_MS}ms"
      )
    }
    runCatching { drainThread.join(2_000) }
    val exit = proc.exitValue()
    if (exit != 0) {
      val tail = synchronized(log) { log.toString().takeLast(2_000) }
      error("FfmpegEncoder: ffmpeg ${format.name.lowercase()} exited with $exit; tail: $tail")
    }
    if (!out.isFile || out.length() == 0L) {
      error(
        "FfmpegEncoder: ffmpeg ${format.name.lowercase()} produced no output at ${out.absolutePath}"
      )
    }
  }

  /**
   * The subset of [ee.schimke.composeai.daemon.protocol.RecordingFormat] this encoder handles. Kept
   * distinct so the encoder body never has to consider the APNG path (handled by [ApngEncoder]
   * inline in [DesktopRecordingSession.encode]).
   */
  enum class RecordingFormatChoice {
    MP4,
    WEBM,
  }

  private const val MAX_LOG_BYTES: Int = 16 * 1024
}
