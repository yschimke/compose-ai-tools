package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/** Fixtures made with ffmpeg; expected values are ffprobe's. */
public class MediaFilesTest {

  @Test
  public void mp3HasItsFramesAndDuration() throws Exception {
    List<MediaFiles.Track> tracks = MediaFiles.read(fixture("tone-1s.mp3"));
    assertEquals(1, tracks.size());
    MediaFiles.Track track = tracks.get(0);
    assertEquals("audio/mpeg", track.mime());
    assertEquals(44_100, track.format.get("sample-rate"));
    assertEquals(1, track.format.get("channel-count"));
    // ffprobe: 1.044898 s, 40 frames of 1152 samples (the Xing frame is not audio).
    assertEquals(40, track.samples.size());
    assertEquals(1_044_897L, track.format.get("durationUs"));
    assertEquals(0L, track.samples.get(0).timeUs());
    assertEquals(26_122L, track.samples.get(1).timeUs());
  }

  @Test
  public void m4aHasItsAacTrackSamplesAndConfig() throws Exception {
    List<MediaFiles.Track> tracks = MediaFiles.read(fixture("tone-1s.m4a"));
    assertEquals(1, tracks.size());
    MediaFiles.Track track = tracks.get(0);
    assertEquals("audio/mp4a-latm", track.mime());
    assertEquals(44_100, track.format.get("sample-rate"));
    assertEquals(1, track.format.get("channel-count"));
    // AAC-LC, 44.1 kHz, mono: an AudioSpecificConfig starting 0x12 0x08 (ffmpeg appends the
    // backward-compatible SBR sync extension, as Android's extractor also reports).
    byte[] csd = (byte[]) track.format.get("csd-0");
    assertArrayEquals(new byte[] {0x12, 0x08}, java.util.Arrays.copyOf(csd, 2));
    assertEquals(45, track.samples.size());
    long durationUs = (Long) track.format.get("durationUs");
    assertTrue("duration " + durationUs, durationUs >= 1_000_000L && durationUs <= 1_050_000L);
    // Samples follow each other in the file, 1024 samples apart.
    MediaFiles.Sample first = track.samples.get(0);
    MediaFiles.Sample second = track.samples.get(1);
    assertEquals(first.offset() + first.size(), second.offset());
    assertEquals(23_219L, second.timeUs());
  }

  @Test
  public void wavIsRawPcmInChunks() throws Exception {
    List<MediaFiles.Track> tracks = MediaFiles.read(fixture("tone-0.5s.wav"));
    MediaFiles.Track track = tracks.get(0);
    assertEquals("audio/raw", track.mime());
    assertEquals(48_000, track.format.get("sample-rate"));
    assertEquals(2, track.format.get("channel-count"));
    assertEquals(2, track.format.get("pcm-encoding"));
    assertEquals(500_000L, track.format.get("durationUs"));
    int bytes = track.samples.stream().mapToInt(MediaFiles.Sample::size).sum();
    assertEquals(48_000 / 2 * 4, bytes);
  }

  @Test
  public void mp3BehindAnId3TagSkipsTheTagAndTheInfoFrame() throws IOException {
    // ID3v2 with a 20-byte body, then an Info frame and three audio frames of MPEG-1 layer III,
    // 64 kbps, 44.1 kHz, mono: 208 bytes each.
    byte[] frame = mpeg1LayerIiiMono64k();
    byte[] info = frame.clone();
    System.arraycopy("Info".getBytes(), 0, info, 4 + 17, 4);
    byte[] id3 = new byte[10 + 20];
    id3[0] = 'I';
    id3[1] = 'D';
    id3[2] = '3';
    id3[3] = 4;
    id3[9] = 20;
    File file = File.createTempFile("tagged", ".mp3");
    file.deleteOnExit();
    Files.write(file.toPath(), concat(id3, info, frame, frame, frame));

    MediaFiles.Track track = MediaFiles.read(file.toPath()).get(0);
    assertEquals(3, track.samples.size());
    assertEquals(30L + 208, track.samples.get(0).offset());
    assertEquals(3 * 1152 * 1_000_000L / 44_100, track.format.get("durationUs"));
  }

  @Test
  public void otherFilesAreNotRead() throws IOException {
    File file = File.createTempFile("not-media", ".bin");
    file.deleteOnExit();
    Files.write(file.toPath(), "just some text, no media here".getBytes());
    assertNull(MediaFiles.read(file.toPath()));
  }

  /** A silent MPEG-1 layer III frame header: 64 kbps, 44.1 kHz, mono, no padding. */
  private static byte[] mpeg1LayerIiiMono64k() {
    byte[] frame = new byte[144 * 64_000 / 44_100];
    frame[0] = (byte) 0xff;
    frame[1] = (byte) 0xfb; // MPEG-1, layer III, no CRC
    frame[2] = (byte) 0x50; // 64 kbps, 44.1 kHz, no padding
    frame[3] = (byte) 0xc0; // mono
    return frame;
  }

  private static byte[] concat(byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) length += part.length;
    byte[] out = new byte[length];
    int at = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, out, at, part.length);
      at += part.length;
    }
    return out;
  }

  private static Path fixture(String name) throws URISyntaxException {
    return Path.of(MediaFilesTest.class.getResource("/media/" + name).toURI());
  }
}
