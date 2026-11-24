package ee.schimke.composeai.samples.instrumented

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Audio to AAC in an M4A, through Media3's Transformer: the codecs are a device's, or in the
 * simulator the host's ffmpeg (skipped where neither has an AAC encoder).
 */
@RunWith(AndroidJUnit4::class)
class MediaTranscodeTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  @Before
  fun needsAnAacEncoder() {
    val encoders =
      MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.AUDIO_AAC, true) }
      }
    assumeTrue("no AAC encoder", encoders.isNotEmpty())
  }

  @Test
  fun wavBecomesAnAacM4aOfTheSameLength() {
    val wav = File(context.cacheDir, "tone.wav").apply { writeBytes(tone(seconds = 2)) }
    val m4a = transcode(wav)
    assertAacOfAbout(m4a, 2_000_000L)
  }

  @Test
  fun mp3FromTheTestApkBecomesAnAacM4a() {
    val mp3 = File(context.cacheDir, "tone-1s.mp3")
    instrumentation.context.assets.open("tone-1s.mp3").use { input ->
      mp3.outputStream().use { input.copyTo(it) }
    }
    val source = MediaExtractor()
    try {
      source.setDataSource(mp3.path)
      assertEquals("audio/mpeg", source.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
    } finally {
      source.release()
    }
    assertAacOfAbout(transcode(mp3), 1_000_000L)
  }

  private fun transcode(input: File): File {
    val output = File(context.cacheDir, input.nameWithoutExtension + ".m4a").apply { delete() }
    val done = CountDownLatch(1)
    var failure: ExportException? = null
    // A Transformer is created, started and finishes on one looper: the main one here.
    instrumentation.runOnMainSync {
      Transformer.Builder(context)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .addListener(
          object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              done.countDown()
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              failure = exportException
              done.countDown()
            }
          },
        )
        .build()
        .start(MediaItem.fromUri(Uri.fromFile(input)), output.path)
    }
    assertTrue("the export did not finish", done.await(60, TimeUnit.SECONDS))
    failure?.let { throw it }
    return output
  }

  private fun assertAacOfAbout(file: File, durationUs: Long) {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.path)
      assertEquals(1, extractor.trackCount)
      val format = extractor.getTrackFormat(0)
      assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
      val actual = format.getLong(MediaFormat.KEY_DURATION)
      assertTrue("duration $actual", actual in durationUs - 150_000..durationUs + 150_000)
      // The samples are there to read, not only the header.
      extractor.selectTrack(0)
      val buffer = ByteBuffer.allocate(64 * 1024)
      var samples = 0
      while (extractor.readSampleData(buffer, 0) > 0) {
        samples++
        extractor.advance()
      }
      assertTrue("$samples samples", samples > durationUs / 1_000_000 * 40)
    } finally {
      extractor.release()
    }
  }

  /** A 440 Hz mono 16-bit 44.1 kHz WAV of [seconds]. */
  private fun tone(seconds: Int): ByteArray {
    val samples = 44_100 * seconds
    val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
    wav.putInt(16).putShort(1).putShort(1).putInt(44_100).putInt(88_200).putShort(2).putShort(16)
    wav.put("data".toByteArray()).putInt(samples * 2)
    repeat(samples) { wav.putShort((sin(2 * PI * 440 * it / 44_100) * 10_000).toInt().toShort()) }
    return wav.array()
  }
}
