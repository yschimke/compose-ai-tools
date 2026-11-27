// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.wearable;

import android.content.Context;
import com.google.android.gms.common.api.GoogleApi;
import com.google.android.gms.tasks.Task;
import java.util.List;

public abstract class NodeClient extends GoogleApi {
  public NodeClient(Context context, GoogleApi.Settings settings) {}

  public abstract Task<Node> getLocalNode();

  public abstract Task<List<Node>> getConnectedNodes();

  public abstract Task<String> getCompanionPackageForNode(String nodeId);

  public abstract Task<String> getNodeId(String address);
}
