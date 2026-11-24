package ee.schimke.composeai.simulator;

import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaFormat;
import java.io.File;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodecList;

/**
 * Audio codecs the simulator serves from the host's {@code ffmpeg}, as {@code MediaCodec}s an app
 * finds through {@code MediaCodecList}: an MP3 decoder and an AAC encoder, what transcoding audio
 * to M4A (Media3 {@code Transformer}) needs. Robolectric has no codecs of its own, so an app's
 * encode failed with "no supported encoder" ({@code ExportException}).
 *
 * <p>{@code ffmpeg} comes from {@value #FFMPEG_PROPERTY} (or {@code COMPOSE_PREVIEW_FFMPEG}), else
 * {@code PATH}; {@code none} turns the codecs off. Without one, nothing is registered and media
 * apps see Robolectric's codec list, as before.
 */
final class HostCodecs {

  static final String FFMPEG_PROPERTY = "composeai.simulator.ffmpeg";

  enum Kind {
    MP3_DECODER("c2.simulator.mp3.decoder", MediaFormat.MIMETYPE_AUDIO_MPEG, false, "mp3"),
    AAC_ENCODER("c2.simulator.aac.encoder", MediaFormat.MIMETYPE_AUDIO_AAC, true, null);

    final String name;
    final String mime;
    final boolean encoder;
    final String ffmpegFormat;

    Kind(String name, String mime, boolean encoder, String ffmpegFormat) {
      this.name = name;
      this.mime = mime;
      this.encoder = encoder;
      this.ffmpegFormat = ffmpegFormat;
    }
  }

  private static volatile String ffmpeg;
  private static volatile boolean installed;

  /** Registers the codecs, once, when the host has {@code ffmpeg}. Before the app's code runs. */
  static synchronized void install() {
    if (installed) return;
    installed = true;
    ffmpeg = locate();
    if (ffmpeg == null) return;
    for (Kind kind : Kind.values()) ShadowMediaCodecList.addCodec(info(kind));
  }

  /** The codec [name] (or, by type, the first for [mime] and direction) names, or null. */
  static Kind kindFor(String nameOrType, boolean nameIsType, boolean encoder) {
    if (ffmpeg == null || nameOrType == null) return null;
    for (Kind kind : Kind.values()) {
      if (nameIsType
          ? kind.mime.equalsIgnoreCase(nameOrType) && kind.encoder == encoder
          : kind.name.equals(nameOrType)) {
        return kind;
      }
    }
    return null;
  }

  static HostCodec create(Kind kind) {
    return new HostCodec(kind, ffmpeg);
  }

  private static MediaCodecInfo info(Kind kind) {
    MediaFormat format = new MediaFormat();
    format.setString(MediaFormat.KEY_MIME, kind.mime);
    // Read by MediaCodecInfo.AudioCapabilities, as a device's codec list declares them.
    format.setString("sample-rate-ranges", "8000,11025,12000,16000,22050,24000,32000,44100,48000");
    format.setString("bitrate-range", "8000-320000");
    format.setString("max-channel-count", kind.encoder ? "6" : "2");
    MediaCodecInfoBuilder.CodecCapabilitiesBuilder capabilities =
        MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setIsEncoder(kind.encoder);
    if (kind == Kind.AAC_ENCODER) {
      CodecProfileLevel lc = new CodecProfileLevel();
      lc.profile = CodecProfileLevel.AACObjectLC;
      lc.level = 0;
      capabilities.setProfileLevels(new CodecProfileLevel[] {lc});
    }
    return MediaCodecInfoBuilder.newBuilder()
        .setName(kind.name)
        .setIsEncoder(kind.encoder)
        .setIsSoftwareOnly(true)
        .setCapabilities(capabilities.build())
        .build();
  }

  /** An AAC-LC AudioSpecificConfig for [sampleRate] and [channels], the encoder's {@code csd-0}. */
  static byte[] aacLcConfig(int sampleRate, int channels) {
    int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
    int index = 4;
    for (int i = 0; i < rates.length; i++) if (rates[i] == sampleRate) index = i;
    int config = (2 << 11) | (index << 7) | ((channels & 0xf) << 3);
    return new byte[] {(byte) (config >>> 8), (byte) config};
  }

  private static String locate() {
    String configured = System.getProperty(FFMPEG_PROPERTY);
    if (configured == null || configured.isEmpty()) configured = System.getenv("COMPOSE_PREVIEW_FFMPEG");
    if (configured != null && !configured.isEmpty()) {
      if (configured.equals("none")) return null;
      return new File(configured).canExecute() ? configured : null;
    }
    String path = System.getenv("PATH");
    if (path != null) {
      for (String dir : path.split(File.pathSeparator)) {
        File candidate = new File(dir, "ffmpeg");
        if (candidate.canExecute()) return candidate.getAbsolutePath();
      }
    }
    for (String dir : new String[] {"/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"}) {
      File candidate = new File(dir, "ffmpeg");
      if (candidate.canExecute()) return candidate.getAbsolutePath();
    }
    return null;
  }

  private HostCodecs() {}
}
