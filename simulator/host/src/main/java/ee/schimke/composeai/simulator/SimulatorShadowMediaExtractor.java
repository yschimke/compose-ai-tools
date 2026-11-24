package ee.schimke.composeai.simulator;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaExtractor;

/**
 * Robolectric's {@code MediaExtractor} shadow, reading real files: a path or {@code file:} URI
 * that {@link MediaFiles} reads (MP3, WAV, MP4 / M4A) has its own tracks, formats and samples, as
 * on a device. Anything else falls back to Robolectric's tracks registered in advance by a test.
 * Added to the shadow map by name, by {@link SimulatorSandbox}.
 */
@Implements(className = "android.media.MediaExtractor")
public class SimulatorShadowMediaExtractor extends ShadowMediaExtractor {

  /** The file read, or null when Robolectric's registered tracks serve this extractor. */
  private File file;

  private List<MediaFiles.Track> tracks;
  private final TreeSet<Integer> selected = new TreeSet<>();

  /** The selected tracks' samples in file order: {track, sample} pairs. */
  private final List<int[]> order = new ArrayList<>();

  private int cursor;

  @Implementation
  @Override
  protected void setDataSource(String path) {
    if (!open(path)) super.setDataSource(path);
  }

  @Implementation
  @Override
  protected void setDataSource(String path, Map<String, String> headers) {
    if (!open(path)) super.setDataSource(path, headers);
  }

  @Implementation
  @Override
  protected void setDataSource(Context context, Uri uri, Map<String, String> headers) {
    String path =
        uri != null && ("file".equals(uri.getScheme()) || uri.getScheme() == null)
            ? uri.getPath()
            : null;
    if (path == null || !open(path)) super.setDataSource(context, uri, headers);
  }

  private boolean open(String path) {
    if (path == null || path.contains("://")) return false;
    File candidate = new File(path);
    if (!candidate.isFile()) return false;
    try {
      List<MediaFiles.Track> read = MediaFiles.read(candidate.toPath());
      if (read == null) return false;
      file = candidate;
      tracks = read;
      return true;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  @Implementation
  @Override
  protected int getTrackCount() {
    return file == null ? super.getTrackCount() : tracks.size();
  }

  @Implementation
  @Override
  protected MediaFormat getTrackFormat(int index) {
    if (file == null) return super.getTrackFormat(index);
    if (index < 0 || index >= tracks.size()) {
      throw new IllegalArgumentException("No track " + index + " of " + tracks.size());
    }
    MediaFormat format = new MediaFormat();
    for (Map.Entry<String, Object> entry : tracks.get(index).format.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Integer i) format.setInteger(entry.getKey(), i);
      else if (value instanceof Long l) format.setLong(entry.getKey(), l);
      else if (value instanceof String s) format.setString(entry.getKey(), s);
      else if (value instanceof byte[] b) format.setByteBuffer(entry.getKey(), ByteBuffer.wrap(b));
    }
    return format;
  }

  @Implementation
  @Override
  protected void selectTrack(int index) {
    if (file == null) {
      super.selectTrack(index);
      return;
    }
    if (index < 0 || index >= tracks.size()) throw new IllegalArgumentException("No track " + index);
    selected.add(index);
    reorder(currentTimeUs());
  }

  @Implementation
  @Override
  protected void unselectTrack(int index) {
    if (file == null) {
      super.unselectTrack(index);
      return;
    }
    selected.remove(index);
    reorder(currentTimeUs());
  }

  @Implementation
  @Override
  protected boolean advance() {
    if (file == null) return super.advance();
    if (cursor < order.size()) cursor++;
    return cursor < order.size();
  }

  @Implementation
  @Override
  protected int readSampleData(ByteBuffer byteBuf, int offset) {
    if (file == null) return super.readSampleData(byteBuf, offset);
    MediaFiles.Sample sample = current();
    if (sample == null) return -1;
    if (byteBuf.capacity() - offset < sample.size()) {
      throw new IllegalArgumentException(
          "readSampleData: buffer of " + byteBuf.capacity() + " too small for " + sample.size());
    }
    byte[] bytes = new byte[sample.size()];
    try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
      in.seek(sample.offset());
      in.readFully(bytes);
    } catch (IOException e) {
      return -1;
    }
    // As on a device since API 21: position and limit bracket the sample just read.
    byteBuf.clear();
    byteBuf.position(offset);
    byteBuf.put(bytes);
    byteBuf.limit(offset + bytes.length);
    byteBuf.position(offset);
    return bytes.length;
  }

  @Implementation
  @Override
  protected int getSampleTrackIndex() {
    if (file == null) return super.getSampleTrackIndex();
    return cursor < order.size() ? order.get(cursor)[0] : -1;
  }

  @Implementation
  protected long getSampleTime() {
    if (file == null) return 0;
    MediaFiles.Sample sample = current();
    return sample == null ? -1 : sample.timeUs();
  }

  @Implementation
  protected long getSampleSize() {
    if (file == null) return 0;
    MediaFiles.Sample sample = current();
    return sample == null ? -1 : sample.size();
  }

  @Implementation
  protected int getSampleFlags() {
    if (file == null) return 0;
    MediaFiles.Sample sample = current();
    if (sample == null) return -1;
    return sample.sync() ? MediaExtractor.SAMPLE_FLAG_SYNC : 0;
  }

  @Implementation
  protected void seekTo(long timeUs, int mode) {
    if (file == null) return;
    reorder(syncTime(timeUs, mode));
  }

  /** The sync sample time a seek to [timeUs] lands on, in the first selected track. */
  private long syncTime(long timeUs, int mode) {
    if (selected.isEmpty()) return timeUs;
    return MediaFiles.syncTime(tracks.get(selected.first()).samples, timeUs, mode);
  }

  @Implementation
  protected void release() {
    file = null;
    tracks = null;
    selected.clear();
    order.clear();
  }

  private MediaFiles.Sample current() {
    if (cursor >= order.size()) return null;
    int[] at = order.get(cursor);
    return tracks.get(at[0]).samples.get(at[1]);
  }

  private long currentTimeUs() {
    MediaFiles.Sample sample = current();
    return sample == null ? 0 : sample.timeUs();
  }

  /** Rebuilds the selected tracks' file order and puts the cursor at the first sample from [fromUs]. */
  private void reorder(long fromUs) {
    order.clear();
    for (int track : selected) {
      List<MediaFiles.Sample> samples = tracks.get(track).samples;
      for (int i = 0; i < samples.size(); i++) order.add(new int[] {track, i});
    }
    order.sort(
        Comparator.comparingLong(
            (int[] at) -> tracks.get(at[0]).samples.get(at[1]).offset()));
    cursor = 0;
    while (cursor < order.size() && current().timeUs() < fromUs) cursor++;
  }
}
