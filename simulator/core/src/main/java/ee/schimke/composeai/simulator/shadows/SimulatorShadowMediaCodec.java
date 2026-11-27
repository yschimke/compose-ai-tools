package ee.schimke.composeai.simulator.shadows;

import ee.schimke.composeai.simulator.media.HostCodec;
import ee.schimke.composeai.simulator.media.HostCodecs;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.IBinder;
import android.view.Surface;
import java.nio.ByteBuffer;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaCodec;

/**
 * Robolectric's {@code MediaCodec} shadow, with the simulator's {@link HostCodecs} in it: a codec
 * created by one of their names (or by a type only they serve) runs on a {@link HostCodec};
 * every other codec is Robolectric's. Added to the shadow map by name, by {@link SimulatorSandbox}.
 */
@Implements(className = "android.media.MediaCodec")
public class SimulatorShadowMediaCodec extends ShadowMediaCodec {

  private HostCodec host;

  @Implementation
  @Override
  protected void __constructor__(String name, boolean nameIsType, boolean encoder) {
    super.__constructor__(name, nameIsType, encoder);
    HostCodecs.Kind kind = HostCodecs.kindFor(name, nameIsType, encoder);
    if (kind != null) host = HostCodecs.create(kind);
  }

  @Implementation
  @Override
  protected void native_setCallback(MediaCodec.Callback callback) {
    if (host == null) {
      super.native_setCallback(callback);
    } else if (callback != null) {
      throw new IllegalStateException(
          "The simulator's " + host.kind.name + " runs in synchronous mode only");
    }
  }

  @Implementation(maxSdk = 25)
  @Override
  protected void native_configure(
      String[] keys, Object[] values, Surface surface, MediaCrypto crypto, int flags) {
    if (host == null) super.native_configure(keys, values, surface, crypto, flags);
    else host.configure(format(keys, values));
  }

  @Implementation(minSdk = 26, maxSdk = 26)
  @Override
  protected void native_configure(
      String[] keys,
      Object[] values,
      Surface surface,
      MediaCrypto crypto,
      IBinder descramblerBinder,
      int flags) {
    if (host == null) super.native_configure(keys, values, surface, crypto, descramblerBinder, flags);
    else host.configure(format(keys, values));
  }

  @Implementation(minSdk = 27)
  @Override
  protected void native_configure(
      String[] keys,
      Object[] values,
      Surface surface,
      MediaCrypto crypto,
      @ClassName("android.os.IHwBinder") Object descramblerBinder,
      int flags) {
    if (host == null) super.native_configure(keys, values, surface, crypto, descramblerBinder, flags);
    else host.configure(format(keys, values));
  }

  @Implementation
  @Override
  protected void native_start() {
    if (host == null) super.native_start();
    else host.start();
  }

  @Implementation
  @Override
  protected void native_flush() {
    if (host == null) super.native_flush();
    else host.flush();
  }

  @Implementation
  protected void native_stop() {
    if (host != null) host.stop();
  }

  @Implementation
  protected void native_release() {
    if (host != null) host.stop();
  }

  @Implementation
  @Override
  protected ByteBuffer[] getBuffers(boolean input) {
    return host == null ? super.getBuffers(input) : host.buffers(input);
  }

  @Implementation
  @Override
  protected ByteBuffer getBuffer(boolean input, int index) {
    return host == null ? super.getBuffer(input, index) : host.buffer(input, index);
  }

  @Implementation
  @Override
  protected int native_dequeueInputBuffer(long timeoutUs) {
    return host == null ? super.native_dequeueInputBuffer(timeoutUs) : host.dequeueInputBuffer(timeoutUs);
  }

  @Implementation
  @Override
  protected void native_queueInputBuffer(
      int index, int offset, int size, long presentationTimeUs, int flags) {
    if (host == null) super.native_queueInputBuffer(index, offset, size, presentationTimeUs, flags);
    else host.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
  }

  @Implementation
  @Override
  protected int native_dequeueOutputBuffer(BufferInfo info, long timeoutUs) {
    return host == null
        ? super.native_dequeueOutputBuffer(info, timeoutUs)
        : host.dequeueOutputBuffer(info, timeoutUs);
  }

  @Implementation
  @Override
  protected void releaseOutputBuffer(int index, boolean renderer) {
    if (host == null) super.releaseOutputBuffer(index, renderer);
    else host.releaseOutputBuffer(index);
  }

  @Implementation
  @Override
  protected void releaseOutputBuffer(int index, long renderTimestampNs) {
    if (host == null) super.releaseOutputBuffer(index, renderTimestampNs);
    else host.releaseOutputBuffer(index);
  }

  @Implementation
  @Override
  protected MediaFormat getOutputFormat() {
    return host == null ? super.getOutputFormat() : host.outputFormat();
  }

  @Implementation
  @Override
  protected MediaFormat getInputFormat() {
    return host == null ? super.getInputFormat() : host.inputFormat();
  }

  /** The configure call's keys and values as a format, as Robolectric rebuilds them. */
  private static MediaFormat format(String[] keys, Object[] values) {
    MediaFormat format = new MediaFormat();
    if (keys == null || values == null) return format;
    for (int i = 0; i < keys.length && i < values.length; i++) {
      Object value = values[i];
      if (value == null || value instanceof ByteBuffer) format.setByteBuffer(keys[i], (ByteBuffer) value);
      else if (value instanceof Integer v) format.setInteger(keys[i], v);
      else if (value instanceof Long v) format.setLong(keys[i], v);
      else if (value instanceof Float v) format.setFloat(keys[i], v);
      else if (value instanceof String v) format.setString(keys[i], v);
    }
    return format;
  }
}
