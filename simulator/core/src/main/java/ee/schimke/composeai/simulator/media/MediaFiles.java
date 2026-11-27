package ee.schimke.composeai.simulator.media;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a media file's tracks and samples, for {@link SimulatorShadowMediaExtractor}: MP3, WAV and
 * (non-fragmented) MP4 / M4A, the containers an app's own files most often are. Pure Java over the
 * file's bytes, with no framework types, so it is tested on the host JVM; the shadow turns each
 * {@link Track#format} into a {@code MediaFormat}.
 *
 * <p>Robolectric's {@code MediaExtractor} knows only tracks registered in advance by a test, so an
 * app (or an instrumentation test) that opens a real file found no tracks at all.
 */
public final class MediaFiles {

  /** One track: its format keys ({@code MediaFormat} names) and its samples in decode order. */
  public static final class Track {
    public final Map<String, Object> format = new LinkedHashMap<>();
    public final List<Sample> samples = new ArrayList<>();

    String mime() {
      return (String) format.get("mime");
    }
  }

  /** A sample: where it is in the file, its presentation time and whether it is a sync sample. */
  public record Sample(long offset, int size, long timeUs, boolean sync) {}

  /** {@code MediaExtractor.SEEK_TO_PREVIOUS_SYNC}, {@code NEXT_SYNC}, {@code CLOSEST_SYNC}. */
  static final int SEEK_PREVIOUS = 0;
  static final int SEEK_NEXT = 1;
  static final int SEEK_CLOSEST = 2;

  /**
   * The time of the sync sample a seek to [timeUs] lands on, as {@code MediaExtractor.seekTo}: at
   * or before it ({@link #SEEK_PREVIOUS}), at or after it ({@link #SEEK_NEXT}), or the nearer of
   * the two ({@link #SEEK_CLOSEST}, the earlier on a tie). A sync sample exactly at [timeUs] is
   * both. With none on one side, the other; with none at all, [timeUs].
   */
  public static long syncTime(List<Sample> samples, long timeUs, int mode) {
    Long before = null;
    Long after = null;
    for (Sample s : samples) {
      if (!s.sync()) continue;
      if (s.timeUs() <= timeUs) before = s.timeUs();
      if (s.timeUs() >= timeUs) {
        after = s.timeUs();
        break;
      }
    }
    if (before == null && after == null) return timeUs;
    if (before == null) return after;
    if (after == null) return before;
    return switch (mode) {
      case SEEK_NEXT -> after;
      case SEEK_CLOSEST -> after - timeUs < timeUs - before ? after : before;
      default -> before;
    };
  }

  /** The tracks of [file], or null when it is not a container this reads. */
  public static List<Track> read(Path file) throws IOException {
    long length = Files.size(file);
    if (length < 12) return null;
    byte[] head = new byte[12];
    try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
      in.readFully(head);
    }
    if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return mp4(file);
    if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
        && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') {
      return wav(file);
    }
    return mp3(file);
  }

  // MP3 ------------------------------------------------------------------------------------------

  private static final int[][] BITRATES_V1 = {
    {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448}, // layer I
    {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384}, // layer II
    {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}, // layer III
  };
  private static final int[][] BITRATES_V2 = {
    {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256}, // layer I
    {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}, // layers II and III
  };
  private static final int[] SAMPLE_RATES_V1 = {44_100, 48_000, 32_000};

  /** A parsed MPEG audio frame header. */
  record Mp3Frame(int sampleRate, int channels, int samples, int length, int bitrate, int version) {}

  /** The MPEG audio frame header at [b][at], or null when there is none. */
  static Mp3Frame mp3Frame(byte[] b, int at) {
    if (at + 4 > b.length) return null;
    int h = ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8)
        | (b[at + 3] & 0xff);
    if ((h & 0xffe00000) != 0xffe00000) return null;
    int version = (h >>> 19) & 3; // 0: 2.5, 2: 2, 3: 1
    int layer = (h >>> 17) & 3; // 1: III, 2: II, 3: I
    int bitrateIndex = (h >>> 12) & 0xf;
    int rateIndex = (h >>> 10) & 3;
    if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) {
      return null;
    }
    int padding = (h >>> 9) & 1;
    int channels = ((h >>> 6) & 3) == 3 ? 1 : 2;
    int sampleRate = SAMPLE_RATES_V1[rateIndex] >> (version == 3 ? 0 : version == 2 ? 1 : 2);
    int kbps =
        version == 3
            ? BITRATES_V1[3 - layer][bitrateIndex]
            : BITRATES_V2[layer == 3 ? 0 : 1][bitrateIndex];
    int bitrate = kbps * 1000;
    int samples;
    int length;
    if (layer == 3) { // layer I
      samples = 384;
      length = (12 * bitrate / sampleRate + padding) * 4;
    } else if (layer == 2 || version == 3) { // layer II, or layer III in MPEG-1
      samples = 1152;
      length = 144 * bitrate / sampleRate + padding;
    } else { // layer III in MPEG-2 / 2.5
      samples = 576;
      length = 72 * bitrate / sampleRate + padding;
    }
    return length < 4 ? null : new Mp3Frame(sampleRate, channels, samples, length, bitrate, version);
  }

  /** Whether the frame at [at] is a Xing / Info / VBRI header rather than audio. */
  private static boolean isInfoFrame(byte[] b, int at, Mp3Frame frame) {
    int side = frame.version == 3 ? (frame.channels == 1 ? 17 : 32) : (frame.channels == 1 ? 9 : 17);
    return tag(b, at + 4 + side, "Xing") || tag(b, at + 4 + side, "Info") || tag(b, at + 36, "VBRI");
  }

  private static List<Track> mp3(Path file) throws IOException {
    byte[] b = Files.readAllBytes(file);
    int at = 0;
    // ID3v2 tags, possibly several.
    while (at + 10 <= b.length && tag(b, at, "ID3")) {
      int size = ((b[at + 6] & 0x7f) << 21) | ((b[at + 7] & 0x7f) << 14) | ((b[at + 8] & 0x7f) << 7)
          | (b[at + 9] & 0x7f);
      at += 10 + size + ((b[at + 5] & 0x10) != 0 ? 10 : 0);
    }
    // Resynchronise within the first few kilobytes, as Android's extractor does.
    int limit = Math.min(b.length, at + 64 * 1024);
    Mp3Frame first = null;
    while (at < limit) {
      first = mp3Frame(b, at);
      Mp3Frame next = first == null ? null : mp3Frame(b, at + first.length);
      if (first != null && (next != null || at + first.length == b.length)) break;
      first = null;
      at++;
    }
    if (first == null) return null;
    Track track = new Track();
    long samples = 0;
    long bytes = 0;
    boolean firstFrame = true;
    while (at < b.length) {
      Mp3Frame frame = mp3Frame(b, at);
      if (frame == null || at + frame.length > b.length) break;
      if (!(firstFrame && isInfoFrame(b, at, frame))) {
        long timeUs = samples * 1_000_000L / first.sampleRate;
        track.samples.add(new Sample(at, frame.length, timeUs, true));
        samples += frame.samples;
        bytes += frame.length;
      }
      firstFrame = false;
      at += frame.length;
    }
    if (track.samples.isEmpty()) return null;
    long durationUs = samples * 1_000_000L / first.sampleRate;
    track.format.put("mime", "audio/mpeg");
    track.format.put("sample-rate", first.sampleRate);
    track.format.put("channel-count", first.channels);
    track.format.put("durationUs", durationUs);
    if (durationUs > 0) track.format.put("bitrate", (int) (bytes * 8_000_000L / durationUs));
    return List.of(track);
  }

  // WAV ------------------------------------------------------------------------------------------

  private static List<Track> wav(Path file) throws IOException {
    byte[] b = Files.readAllBytes(file);
    ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    int at = 12;
    int channels = 0;
    int sampleRate = 0;
    int bits = 0;
    int format = 0;
    while (at + 8 <= b.length) {
      String id = new String(b, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
      long size = buf.getInt(at + 4) & 0xffffffffL;
      int body = at + 8;
      if (id.equals("fmt ") && size >= 16) {
        format = buf.getShort(body) & 0xffff;
        channels = buf.getShort(body + 2) & 0xffff;
        sampleRate = buf.getInt(body + 4);
        bits = buf.getShort(body + 14) & 0xffff;
      } else if (id.equals("data")) {
        if (channels == 0 || sampleRate == 0 || bits == 0) return null;
        long end = Math.min(b.length, body + size);
        int frameBytes = channels * bits / 8;
        Track track = new Track();
        track.format.put("mime", "audio/raw");
        track.format.put("sample-rate", sampleRate);
        track.format.put("channel-count", channels);
        Integer encoding = pcmEncoding(format, bits);
        if (encoding != null) track.format.put("pcm-encoding", encoding);
        track.format.put("durationUs", (end - body) / frameBytes * 1_000_000L / sampleRate);
        int chunk = Math.max(frameBytes, 4096 / frameBytes * frameBytes);
        for (long o = body; o < end; o += chunk) {
          long timeUs = (o - body) / frameBytes * 1_000_000L / sampleRate;
          track.samples.add(new Sample(o, (int) Math.min(chunk, end - o), timeUs, true));
        }
        return List.of(track);
      }
      at = (int) (body + size + (size & 1));
    }
    return null;
  }

  /** {@code AudioFormat.ENCODING_PCM_*} for a WAV format tag and sample size. */
  private static Integer pcmEncoding(int format, int bits) {
    if (format == 3 && bits == 32) return 4; // float
    if (format != 1 && format != 0xfffe) return null;
    return switch (bits) {
      case 8 -> 3;
      case 16 -> 2;
      case 24 -> 21;
      case 32 -> 22;
      default -> null;
    };
  }

  // MP4 ------------------------------------------------------------------------------------------

  private static List<Track> mp4(Path file) throws IOException {
    byte[] b = Files.readAllBytes(file);
    ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
    int[] moov = child(buf, 0, b.length, "moov");
    if (moov == null) return null; // fragmented or truncated: not read here
    List<Track> tracks = new ArrayList<>();
    for (int[] trak : children(buf, moov[0], moov[1], "trak")) {
      Track track = mp4Track(buf, trak);
      if (track != null) tracks.add(track);
    }
    return tracks.isEmpty() ? null : tracks;
  }

  private static Track mp4Track(ByteBuffer buf, int[] trak) {
    int[] mdia = child(buf, trak[0], trak[1], "mdia");
    if (mdia == null) return null;
    int[] mdhd = child(buf, mdia[0], mdia[1], "mdhd");
    int[] hdlr = child(buf, mdia[0], mdia[1], "hdlr");
    int[] minf = child(buf, mdia[0], mdia[1], "minf");
    int[] stbl = minf == null ? null : child(buf, minf[0], minf[1], "stbl");
    if (mdhd == null || hdlr == null || stbl == null) return null;
    int version = buf.get(mdhd[0]) & 0xff;
    long timescale;
    long duration;
    if (version == 1) {
      timescale = buf.getInt(mdhd[0] + 20) & 0xffffffffL;
      duration = buf.getLong(mdhd[0] + 24);
    } else {
      timescale = buf.getInt(mdhd[0] + 12) & 0xffffffffL;
      duration = buf.getInt(mdhd[0] + 16) & 0xffffffffL;
    }
    if (timescale == 0) return null;
    String handler = fourcc(buf, hdlr[0] + 8);
    int[] stsd = child(buf, stbl[0], stbl[1], "stsd");
    if (stsd == null) return null;
    int entry = stsd[0] + 8;
    String type = fourcc(buf, entry + 4);
    int entryEnd = entry + buf.getInt(entry);
    Track track = new Track();
    if (handler.equals("soun")) {
      int channels = buf.getShort(entry + 8 + 16) & 0xffff;
      int sampleRate = (buf.getInt(entry + 8 + 24) >>> 16) & 0xffff;
      String mime =
          switch (type) {
            case "mp4a" -> "audio/mp4a-latm";
            case "Opus" -> "audio/opus";
            case "fLaC" -> "audio/flac";
            case "ac-3" -> "audio/ac3";
            case "ec-3" -> "audio/eac3";
            case "alac" -> "audio/alac";
            default -> null;
          };
      if (mime == null) return null;
      track.format.put("mime", mime);
      track.format.put("sample-rate", sampleRate);
      track.format.put("channel-count", channels);
      int[] esds = child(buf, entry + 8 + 28, entryEnd, "esds");
      if (esds != null) {
        byte[] asc = decoderSpecificInfo(buf, esds[0] + 4, esds[1]);
        if (asc != null) track.format.put("csd-0", asc);
      }
    } else if (handler.equals("vide")) {
      String mime =
          switch (type) {
            case "avc1", "avc3" -> "video/avc";
            case "hvc1", "hev1" -> "video/hevc";
            case "mp4v" -> "video/mp4v-es";
            case "av01" -> "video/av01";
            case "vp09" -> "video/x-vnd.on2.vp9";
            default -> null;
          };
      if (mime == null) return null;
      track.format.put("mime", mime);
      track.format.put("width", buf.getShort(entry + 8 + 24) & 0xffff);
      track.format.put("height", buf.getShort(entry + 8 + 26) & 0xffff);
    } else {
      return null;
    }
    track.format.put("durationUs", duration * 1_000_000L / timescale);
    samples(buf, stbl, timescale, track);
    return track;
  }

  /** The samples of a track from its sample table: sizes, chunk offsets, timing, sync samples. */
  private static void samples(ByteBuffer buf, int[] stbl, long timescale, Track track) {
    int[] stsz = child(buf, stbl[0], stbl[1], "stsz");
    int[] stsc = child(buf, stbl[0], stbl[1], "stsc");
    int[] stts = child(buf, stbl[0], stbl[1], "stts");
    int[] stco = child(buf, stbl[0], stbl[1], "stco");
    int[] co64 = child(buf, stbl[0], stbl[1], "co64");
    int[] stss = child(buf, stbl[0], stbl[1], "stss");
    if (stsz == null || stsc == null || stts == null || (stco == null && co64 == null)) return;
    int fixedSize = buf.getInt(stsz[0] + 4);
    int count = buf.getInt(stsz[0] + 8);
    int[] sizes = new int[count];
    for (int i = 0; i < count; i++) {
      sizes[i] = fixedSize != 0 ? fixedSize : buf.getInt(stsz[0] + 12 + 4 * i);
    }
    int chunks = buf.getInt((co64 != null ? co64 : stco)[0] + 4);
    long[] chunkOffsets = new long[chunks];
    for (int i = 0; i < chunks; i++) {
      chunkOffsets[i] =
          co64 != null
              ? buf.getLong(co64[0] + 8 + 8 * i)
              : buf.getInt(stco[0] + 8 + 4 * i) & 0xffffffffL;
    }
    java.util.Set<Integer> syncSamples = null;
    if (stss != null) {
      syncSamples = new java.util.HashSet<>();
      int n = buf.getInt(stss[0] + 4);
      for (int i = 0; i < n; i++) syncSamples.add(buf.getInt(stss[0] + 8 + 4 * i) - 1);
    }
    // Timing: (count, delta) runs.
    long[] times = new long[count];
    int timingRuns = buf.getInt(stts[0] + 4);
    int sample = 0;
    long t = 0;
    for (int r = 0; r < timingRuns && sample < count; r++) {
      int runCount = buf.getInt(stts[0] + 8 + 8 * r);
      int delta = buf.getInt(stts[0] + 12 + 8 * r);
      for (int i = 0; i < runCount && sample < count; i++) {
        times[sample++] = t;
        t += delta;
      }
    }
    // Chunks: (first chunk, samples per chunk, description) runs.
    int runs = buf.getInt(stsc[0] + 4);
    sample = 0;
    for (int r = 0; r < runs && sample < count; r++) {
      int firstChunk = buf.getInt(stsc[0] + 8 + 12 * r) - 1;
      int perChunk = buf.getInt(stsc[0] + 12 + 12 * r);
      int lastChunk = r + 1 < runs ? buf.getInt(stsc[0] + 8 + 12 * (r + 1)) - 1 : chunks;
      for (int c = firstChunk; c < lastChunk && c < chunks && sample < count; c++) {
        long offset = chunkOffsets[c];
        for (int i = 0; i < perChunk && sample < count; i++) {
          boolean sync = syncSamples == null || syncSamples.contains(sample);
          track.samples.add(
              new Sample(offset, sizes[sample], times[sample] * 1_000_000L / timescale, sync));
          offset += sizes[sample];
          sample++;
        }
      }
    }
  }

  /** The DecoderSpecificInfo (an AAC AudioSpecificConfig) inside an {@code esds} payload. */
  private static byte[] decoderSpecificInfo(ByteBuffer buf, int at, int end) {
    while (at < end) {
      int tag = buf.get(at++) & 0xff;
      int size = 0;
      for (int i = 0; i < 4 && at < end; i++) {
        int b = buf.get(at++) & 0xff;
        size = (size << 7) | (b & 0x7f);
        if ((b & 0x80) == 0) break;
      }
      switch (tag) {
        case 0x03 -> { // ES_Descriptor: id, flags, then optional fields, then children
          int flags = buf.get(at + 2) & 0xff;
          at += 3;
          if ((flags & 0x80) != 0) at += 2;
          if ((flags & 0x40) != 0) at += 1 + (buf.get(at) & 0xff);
          if ((flags & 0x20) != 0) at += 2;
        }
        case 0x04 -> at += 13; // DecoderConfigDescriptor header; its children follow
        case 0x05 -> {
          byte[] out = new byte[size];
          for (int i = 0; i < size; i++) out[i] = buf.get(at + i);
          return out;
        }
        default -> at += size;
      }
    }
    return null;
  }

  /** [start, end) of the payload of the first [type] box in [start, end), or null. */
  private static int[] child(ByteBuffer buf, int start, int end, String type) {
    List<int[]> found = children(buf, start, end, type);
    return found.isEmpty() ? null : found.get(0);
  }

  private static List<int[]> children(ByteBuffer buf, int start, int end, String type) {
    List<int[]> found = new ArrayList<>();
    int at = start;
    while (at + 8 <= end) {
      long size = buf.getInt(at) & 0xffffffffL;
      int header = 8;
      if (size == 1) {
        size = buf.getLong(at + 8);
        header = 16;
      } else if (size == 0) {
        size = end - at;
      }
      if (size < header || at + size > end) break;
      if (fourcc(buf, at + 4).equals(type)) {
        int payload = at + header;
        // Full boxes whose children follow a version/flags word.
        if (type.equals("meta")) payload += 4;
        found.add(new int[] {payload, (int) (at + size)});
      }
      at += (int) size;
    }
    return found;
  }

  private static String fourcc(ByteBuffer buf, int at) {
    byte[] four = new byte[4];
    for (int i = 0; i < 4; i++) four[i] = buf.get(at + i);
    return new String(four, java.nio.charset.StandardCharsets.ISO_8859_1);
  }

  private static boolean tag(byte[] b, int at, String tag) {
    if (at < 0 || at + tag.length() > b.length) return false;
    for (int i = 0; i < tag.length(); i++) if (b[at + i] != tag.charAt(i)) return false;
    return true;
  }

  private MediaFiles() {}
}
