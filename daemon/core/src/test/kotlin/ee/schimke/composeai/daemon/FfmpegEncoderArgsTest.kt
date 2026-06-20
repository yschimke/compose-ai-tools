package ee.schimke.composeai.daemon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [FfmpegEncoder.buildArgs] — especially the optional TalkBack audio-track muxing (issue #1956
 * Phase 4) — without shelling out to ffmpeg. The no-audio path must stay byte-identical to the
 * pre-#1956 video-only command; the audio path must add a second input, the container's audio
 * codec, explicit stream maps, and `-shortest`.
 */
class FfmpegEncoderArgsTest {

  private val frames = File("/frames")
  private val out = File("/out/clip.mp4")

  @Test
  fun `mp4 without audio is the video-only command`() {
    val args =
      FfmpegEncoder.buildArgs(
        frames,
        fps = 30,
        FfmpegEncoder.RecordingFormatChoice.MP4,
        out,
        audioTrack = null,
      )
    assertEquals(
      listOf(
        "ffmpeg",
        "-y",
        "-framerate",
        "30",
        "-i",
        File(frames, "frame-%05d.png").absolutePath,
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        "-preset",
        "veryfast",
        "-movflags",
        "+faststart",
        out.absolutePath,
      ),
      args,
    )
    // No audio plumbing leaks into the silent path.
    assertFalse(args.any { it == "-c:a" || it == "-map" || it == "-shortest" })
  }

  @Test
  fun `mp4 with audio adds aac input, maps and shortest`() {
    val audio = File("/tmp/talkback.wav")
    val args =
      FfmpegEncoder.buildArgs(
        frames,
        fps = 30,
        FfmpegEncoder.RecordingFormatChoice.MP4,
        out,
        audioTrack = audio,
      )
    // Second input is the audio track.
    val inputs = args.withIndex().filter { it.value == "-i" }.map { it.index }
    assertEquals("expected two -i inputs (frames + audio)", 2, inputs.size)
    assertEquals(audio.absolutePath, args[inputs[1] + 1])
    // AAC for the MP4 container, explicit maps, shortest clamp.
    assertTrue(containsSeq(args, listOf("-c:a", "aac")))
    assertTrue(containsSeq(args, listOf("-map", "0:v:0")))
    assertTrue(containsSeq(args, listOf("-map", "1:a:0")))
    assertTrue(args.contains("-shortest"))
    assertEquals("output stays last", out.absolutePath, args.last())
  }

  @Test
  fun `webm with audio uses libopus`() {
    val audio = File("/tmp/talkback.wav")
    val webmOut = File("/out/clip.webm")
    val args =
      FfmpegEncoder.buildArgs(
        frames,
        fps = 24,
        FfmpegEncoder.RecordingFormatChoice.WEBM,
        webmOut,
        audioTrack = audio,
      )
    assertTrue(containsSeq(args, listOf("-c:v", "libvpx-vp9")))
    assertTrue(containsSeq(args, listOf("-c:a", "libopus")))
    assertTrue(args.contains("-shortest"))
  }

  private fun containsSeq(haystack: List<String>, needle: List<String>): Boolean {
    if (needle.isEmpty()) return true
    for (i in 0..haystack.size - needle.size) {
      if (haystack.subList(i, i + needle.size) == needle) return true
    }
    return false
  }
}
