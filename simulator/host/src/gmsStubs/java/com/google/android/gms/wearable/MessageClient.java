// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.wearable;

import android.content.Context;
import android.net.Uri;
import com.google.android.gms.common.api.GoogleApi;
import com.google.android.gms.tasks.Task;

public abstract class MessageClient extends GoogleApi {
  public static final int FILTER_LITERAL = 0;
  public static final int FILTER_PREFIX = 1;

  public MessageClient(Context context, GoogleApi.Settings settings) {}

  public abstract Task<Integer> sendMessage(String nodeId, String path, byte[] data);

  public abstract Task<byte[]> sendRequest(String nodeId, String path, byte[] data);

  public abstract Task<Void> addListener(OnMessageReceivedListener listener);

  public abstract Task<Void> addListener(
      OnMessageReceivedListener listener, Uri uri, int filterType);

  public abstract Task<Boolean> removeListener(OnMessageReceivedListener listener);

  public abstract Task<Void> addRpcService(RpcService service, String path);

  public abstract Task<Void> addRpcService(RpcService service, String path, String nodeId);

  public abstract Task<Boolean> removeRpcService(RpcService service);

  public interface OnMessageReceivedListener {
    void onMessageReceived(MessageEvent event);
  }

  public interface RpcService {
    Task<byte[]> onRequest(String nodeId, String path, byte[] request);
  }
}
