// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.wearable;

import com.google.android.gms.tasks.Task;

public abstract class WearableListenerService extends android.app.Service
    implements MessageClient.RpcService {
  public void onDataChanged(DataEventBuffer events) {}

  public void onMessageReceived(MessageEvent event) {}

  @Override
  public Task<byte[]> onRequest(String nodeId, String path, byte[] request) {
    return null;
  }

  public void onCapabilityChanged(CapabilityInfo info) {}

  public void onPeerConnected(Node peer) {}

  public void onPeerDisconnected(Node peer) {}
}
