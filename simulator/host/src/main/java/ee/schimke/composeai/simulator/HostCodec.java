package ee.schimke.composeai.simulator;

import android.media.MediaCodec;
import android.media.MediaFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * One {@code MediaCodec} the simulator serves from the host's {@code ffmpeg} ({@link
 * HostCodecs}), in synchronous mode: input buffers are written to an {@code ffmpeg} process as
 * they are queued, and what it produces comes back as output buffers, as many and as late as the
 * codec makes them. Robolectric's codecs turn each input buffer into exactly one output buffer,
 * which no real encoder does (AAC takes 1024 samples a frame and delays its output), so an app's
 * transcoding (Media3 {@code Transformer}) found no encoder at all.
 *
 * <p>Timestamps follow the audio: output {@code n} is at the first input's time plus the samples
 * produced before it.
 */
final class HostCodec {

  static final int BUFFER_COUNT = 4;

  /** A produced buffer; [eos] marks the end of the stream. */
  private record Packet(byte[] bytes, long timeUs, boolean eos) {}

  final HostCodecs.Kind kind;
  private final String ffmpeg;

  private ByteBuffer[] inputs;
  private ByteBuffer[] outputs;
  private final BlockingDeque<Integer> freeInputs = new LinkedBlockingDeque<>();
  private final BlockingDeque<Integer> freeOutputs = new LinkedBlockingDeque<>();
  private final BlockingDeque<Packet> ready = new LinkedBlockingDeque<>();

  private MediaFormat inputFormat;
  private MediaFormat outputFormat;
  private boolean formatReported;
  private int sampleRate;
  private int channels;
  private int bitrate;

  private Process process;
  private OutputStream stdin;
  private Thread reader;
  private volatile long baseTimeUs = -1;
  private boolean inputEnded;

  HostCodec(HostCodecs.Kind kind, String ffmpeg) {
    this.kind = kind;
    this.ffmpeg = ffmpeg;
  }

  void configure(MediaFormat format) {
    inputFormat = format;
    sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
        ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        : 44_100;
    channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
        ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        : 2;
    bitrate = format.containsKey(MediaFormat.KEY_BIT_RATE)
        ? format.getInteger(MediaFormat.KEY_BIT_RATE)
        : 128_000;
    int frameBytes = 2 * channels;
    int inputSize;
    int outputSize;
    if (kind.encoder) {
      inputSize = 4096 * frameBytes;
      outputSize = 8192;
      outputFormat = MediaFormat.createAudioFormat(kind.mime, sampleRate, channels);
      outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
      outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, 2);
      outputFormat.setByteBuffer(
          "csd-0", ByteBuffer.wrap(HostCodecs.aacLcConfig(sampleRate, channels)));
    } else {
      inputSize =
          format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
              ? Math.max(8192, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
              : 16_384;
      outputSize = 4608 * frameBytes;
      outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, sampleRate, channels);
      outputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, 2); // AudioFormat.ENCODING_PCM_16BIT
    }
    inputs = new ByteBuffer[BUFFER_COUNT];
    outputs = new ByteBuffer[BUFFER_COUNT];
    for (int i = 0; i < BUFFER_COUNT; i++) {
      inputs[i] = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.LITTLE_ENDIAN);
      outputs[i] = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.LITTLE_ENDIAN);
    }
  }

  MediaFormat inputFormat() {
    return inputFormat;
  }

  MediaFormat outputFormat() {
    return outputFormat;
  }

  void start() {
    stopProcess();
    synchronized (this) {
      generation++;
    }
    freeInputs.clear();
    freeOutputs.clear();
    ready.clear();
    for (int i = 0; i < BUFFER_COUNT; i++) {
      inputs[i].clear();
      freeInputs.add(i);
      freeOutputs.add(i);
    }
    baseTimeUs = -1;
    inputEnded = false;
    List<String> command = new ArrayList<>();
    command.add(ffmpeg);
    command.addAll(List.of("-hide_banner", "-loglevel", "error"));
    if (kind.encoder) {
      command.addAll(
          List.of(
              "-f", "s16le", "-ar", "" + sampleRate, "-ac", "" + channels, "-i", "pipe:0",
              "-c:a", "aac", "-b:a", "" + bitrate, "-f", "adts", "pipe:1"));
    } else {
      command.addAll(
          List.of(
              "-probesize", "32", "-analyzeduration", "0", "-f", kind.ffmpegFormat, "-i", "pipe:0",
              "-f", "s16le", "-ar", "" + sampleRate, "-ac", "" + channels, "pipe:1"));
    }
    try {
      ProcessBuilder builder =
          new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
      // A host binary, on the host's loader: the simulator JVM's library path (a Nix store JDK's,
      // the host natives') would load another libc's libraries into it, and ffmpeg dies at start.
      builder.environment().remove("LD_LIBRARY_PATH");
      builder.environment().remove("DYLD_LIBRARY_PATH");
      process = builder.start();
    } catch (IOException e) {
      throw new IllegalStateException("cannot start " + ffmpeg + ": " + e.getMessage(), e);
    }
    stdin = process.getOutputStream();
    InputStream stdout = process.getInputStream();
    Process running = process;
    int generation = this.generation;
    reader =
        new Thread(
            () -> {
              try {
                if (kind.encoder) readAdts(stdout, generation);
                else readPcm(stdout, generation);
              } catch (IOException e) {
                if (running.isAlive()) System.err.println("host codec " + kind.name + ": " + e);
              } finally {
                emit(generation, new Packet(new byte[0], lastTimeUs(), true));
              }
            },
            "host-codec " + kind.name);
    reader.setDaemon(true);
    reader.start();
  }

  private volatile long producedSamples;

  /** Which process's output [ready] holds: a flushed process's late output is dropped. */
  private volatile int generation;

  private synchronized void emit(int from, Packet packet) {
    if (from == generation) ready.add(packet);
  }

  private long lastTimeUs() {
    return timeOf(producedSamples);
  }

  private long timeOf(long samples) {
    long base = Math.max(0, baseTimeUs);
    return base + samples * 1_000_000L / sampleRate;
  }

  /** ADTS frames from ffmpeg's AAC encoder, each passed on without its header. */
  private void readAdts(InputStream in, int generation) throws IOException {
    producedSamples = 0;
    byte[] header = new byte[7];
    while (readFully(in, header, 0, 7)) {
      if ((header[0] & 0xff) != 0xff || (header[1] & 0xf0) != 0xf0) {
        throw new IOException("lost ADTS sync");
      }
      boolean crc = (header[1] & 1) == 0;
      int frameLength =
          ((header[3] & 0x3) << 11) | ((header[4] & 0xff) << 3) | ((header[5] & 0xe0) >>> 5);
      int headerLength = crc ? 9 : 7;
      if (crc && !readFully(in, new byte[2], 0, 2)) break;
      byte[] payload = new byte[frameLength - headerLength];
      if (!readFully(in, payload, 0, payload.length)) break;
      emit(generation, new Packet(payload, timeOf(producedSamples), false));
      producedSamples += 1024;
    }
  }

  /** PCM from ffmpeg's decoder, in output-buffer-sized chunks of whole frames. */
  private void readPcm(InputStream in, int generation) throws IOException {
    producedSamples = 0;
    int frameBytes = 2 * channels;
    int chunk = outputs[0].capacity() / frameBytes * frameBytes;
    byte[] buffer = new byte[chunk];
    int filled = 0;
    while (true) {
      int n = in.read(buffer, filled, chunk - filled);
      if (n < 0) break;
      filled += n;
      // Pass on what has arrived once it is whole frames, without waiting for a full chunk.
      int whole = filled / frameBytes * frameBytes;
      if (whole > 0 && (filled == chunk || in.available() == 0)) {
        byte[] out = java.util.Arrays.copyOf(buffer, whole);
        emit(generation, new Packet(out, timeOf(producedSamples), false));
        producedSamples += whole / frameBytes;
        System.arraycopy(buffer, whole, buffer, 0, filled - whole);
        filled -= whole;
      }
    }
  }

  private static boolean readFully(InputStream in, byte[] b, int off, int len) throws IOException {
    int at = 0;
    while (at < len) {
      int n = in.read(b, off + at, len - at);
      if (n < 0) return false;
      at += n;
    }
    return true;
  }

  int dequeueInputBuffer(long timeoutUs) {
    if (inputEnded) return MediaCodec.INFO_TRY_AGAIN_LATER;
    Integer index = poll(freeInputs, timeoutUs);
    return index == null ? MediaCodec.INFO_TRY_AGAIN_LATER : index;
  }

  void queueInputBuffer(int index, int offset, int size, long timeUs, int flags) {
    if (index < 0 || index >= BUFFER_COUNT || freeInputs.contains(index)) {
      throw new IllegalStateException("Input buffer not owned by client: " + index);
    }
    if (baseTimeUs < 0 && (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) baseTimeUs = timeUs;
    try {
      if (size > 0 && (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
        byte[] bytes = new byte[size];
        ByteBuffer buffer = inputs[index].duplicate();
        buffer.limit(offset + size).position(offset);
        buffer.get(bytes);
        stdin.write(bytes);
      }
      if ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
        inputEnded = true;
        stdin.close();
      }
    } catch (IOException e) {
      throw new IllegalStateException("host codec " + kind.name + " stopped taking input: " + e, e);
    }
    inputs[index].clear();
    freeInputs.add(index);
  }

  int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeoutUs) {
    if (!formatReported) {
      formatReported = true;
      return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
    }
    Packet packet = poll(ready, timeoutUs);
    if (packet == null) return MediaCodec.INFO_TRY_AGAIN_LATER;
    Integer index = freeOutputs.poll();
    if (index == null) {
      ready.addFirst(packet);
      return MediaCodec.INFO_TRY_AGAIN_LATER;
    }
    ByteBuffer out = outputs[index];
    out.clear();
    out.put(packet.bytes);
    out.flip();
    int flags = packet.eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
    if (kind.encoder && !packet.eos) flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
    info.set(0, packet.bytes.length, packet.timeUs, flags);
    return index;
  }

  void releaseOutputBuffer(int index) {
    if (index < 0 || index >= BUFFER_COUNT || freeOutputs.contains(index)) {
      throw new IllegalStateException("Output buffer not owned by client: " + index);
    }
    outputs[index].clear();
    freeOutputs.add(index);
  }

  ByteBuffer[] buffers(boolean input) {
    return input ? inputs : outputs;
  }

  ByteBuffer buffer(boolean input, int index) {
    ByteBuffer[] all = input ? inputs : outputs;
    if (all == null || index < 0 || index >= all.length) return null;
    if (input ? freeInputs.contains(index) : freeOutputs.contains(index)) return null;
    return all[index];
  }

  /** Back to the state after configure: a fresh process, nothing queued either way. */
  void flush() {
    if (process != null) start();
  }

  void stop() {
    stopProcess();
  }

  private void stopProcess() {
    if (process == null) return;
    process.destroy();
    try {
      if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
    process = null;
  }

  private static <T> T poll(BlockingDeque<T> queue, long timeoutUs) {
    try {
      return timeoutUs < 0 ? queue.take() : queue.poll(timeoutUs, TimeUnit.MICROSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }
}
