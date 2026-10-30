// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.wearable;

import android.content.Context;
import android.net.Uri;
import com.google.android.gms.common.api.GoogleApi;
import com.google.android.gms.tasks.Task;
import java.io.InputStream;
import java.io.OutputStream;

public abstract class ChannelClient extends GoogleApi {
  public static final String ACTION_CHANNEL_EVENT = "com.google.android.gms.wearable.CHANNEL_EVENT";

  public ChannelClient(Context context, GoogleApi.Settings settings) {}

  public interface Channel extends android.os.Parcelable {
    String getNodeId();

    String getPath();
  }

  public abstract static class ChannelCallback {
    public static final int CLOSE_REASON_NORMAL = 0;
    public static final int CLOSE_REASON_DISCONNECTED = 1;
    public static final int CLOSE_REASON_REMOTE_CLOSE = 2;
    public static final int CLOSE_REASON_LOCAL_CLOSE = 3;

    public void onChannelOpened(Channel channel) {}

    public void onChannelClosed(Channel channel, int closeReason, int appSpecificErrorCode) {}

    public void onInputClosed(Channel channel, int closeReason, int appSpecificErrorCode) {}

    public void onOutputClosed(Channel channel, int closeReason, int appSpecificErrorCode) {}
  }

  public abstract Task<Channel> openChannel(String nodeId, String path);

  public abstract Task<Void> registerChannelCallback(ChannelCallback callback);

  public abstract Task<Void> registerChannelCallback(Channel channel, ChannelCallback callback);

  public abstract Task<Boolean> unregisterChannelCallback(ChannelCallback callback);

  public abstract Task<Boolean> unregisterChannelCallback(
      Channel channel, ChannelCallback callback);

  public abstract Task<Void> close(Channel channel);

  public abstract Task<Void> close(Channel channel, int errorCode);

  public abstract Task<InputStream> getInputStream(Channel channel);

  public abstract Task<OutputStream> getOutputStream(Channel channel);

  public abstract Task<Void> receiveFile(Channel channel, Uri uri, boolean append);

  public abstract Task<Void> sendFile(Channel channel, Uri uri);

  public abstract Task<Void> sendFile(Channel channel, Uri uri, long startOffset, long length);
}
