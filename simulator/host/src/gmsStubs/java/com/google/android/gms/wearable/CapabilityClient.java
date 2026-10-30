// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.wearable;

import android.content.Context;
import android.net.Uri;
import com.google.android.gms.common.api.GoogleApi;
import com.google.android.gms.tasks.Task;
import java.util.Map;

public abstract class CapabilityClient extends GoogleApi {
  public static final int FILTER_ALL = 0;
  public static final int FILTER_REACHABLE = 1;
  public static final int FILTER_LITERAL = 0;
  public static final int FILTER_PREFIX = 1;

  public CapabilityClient(Context context, GoogleApi.Settings settings) {}

  public abstract Task<CapabilityInfo> getCapability(String name, int nodeFilter);

  public abstract Task<Map<String, CapabilityInfo>> getAllCapabilities(int nodeFilter);

  public abstract Task<Void> addLocalCapability(String name);

  public abstract Task<Void> removeLocalCapability(String name);

  public abstract Task<Void> addListener(OnCapabilityChangedListener listener, String name);

  public abstract Task<Void> addListener(
      OnCapabilityChangedListener listener, Uri uri, int filterType);

  public abstract Task<Boolean> removeListener(
      OnCapabilityChangedListener listener, String name);

  public abstract Task<Boolean> removeListener(OnCapabilityChangedListener listener);

  public interface OnCapabilityChangedListener {
    void onCapabilityChanged(CapabilityInfo info);
  }
}
