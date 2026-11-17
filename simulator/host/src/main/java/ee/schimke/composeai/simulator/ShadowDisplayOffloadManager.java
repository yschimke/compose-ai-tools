package ee.schimke.composeai.simulator;

import android.content.Context;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.util.Pair;
import java.util.Collections;
import java.util.List;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

/**
 * Simulator-safe implementation of {@code
 * com.google.android.clockwork.ambient.DisplayOffloadManager}.
 *
 * <p>Wear OS 7 / API 37 {@code DeclarativeWatchFaceRuntime.apk} bundles compile-time stubs for
 * {@code com.google.android.clockwork.ambient.*} whose constructors and methods throw {@code
 * RuntimeException("Stub!")}. Under Robolectric there is no display offload co-processor, so
 * report offload as unavailable and treat layout/callback operations as no-ops.
 */
@Implements(className = ShadowDisplayOffloadManager.TARGET, isInAndroidSdk = false)
public final class ShadowDisplayOffloadManager {

  static final String TARGET = "com.google.android.clockwork.ambient.DisplayOffloadManager";
  private static final String LAYOUT_ITEM = "com.google.android.clockwork.ambient.LayoutItem";
  private static final String SEND_LAYOUT_CALLBACKS =
      "com.google.android.clockwork.ambient.ISendLayoutCallbacks";
  private static final String OFFLOAD_STATE_CALLBACKS =
      "com.google.android.clockwork.ambient.IOffloadStateCallbacks";
  private static final String PRE_OFFLOAD_STATE_CALLBACKS =
      "com.google.android.clockwork.ambient.IPreOffloadStateCallbacks";

  private static volatile Object instance;

  @Implementation
  protected void __constructor__() {}

  @Implementation
  protected static Object instance() {
    Object existing = instance;
    if (existing != null) return existing;
    try {
      Class<?> cls =
          Class.forName(TARGET, false, ShadowDisplayOffloadManager.class.getClassLoader());
      Object created = ReflectionHelpers.callConstructor(cls);
      instance = created;
      return created;
    } catch (ClassNotFoundException e) {
      throw new LinkageError("Missing " + TARGET, e);
    }
  }

  @Implementation
  protected static boolean isAodOffDisplayOffloadEnabled(Context context) {
    return false;
  }

  @Implementation
  protected void clearActivityLayout() {}

  @Implementation
  protected void clearWatchFaceLayout() {}

  @Implementation
  protected Bundle getAndClearLastDataSnapshot() {
    return new Bundle();
  }

  @Implementation
  protected List<String> getOffloadCapabilityCustomLayoutElements() {
    return Collections.emptyList();
  }

  @Implementation
  protected List<String> getOffloadCapabilityGenericMetrics() {
    return Collections.emptyList();
  }

  @Implementation
  protected List<String> getOffloadCapabilityOemMetrics() {
    return Collections.emptyList();
  }

  @Implementation
  protected List<String> getOffloadCapabilityRenderFeatures() {
    return Collections.emptyList();
  }

  @Implementation
  protected Pair<Icon, RectF> getStatusBar() {
    return null;
  }

  @Implementation
  protected boolean isPeleDisplayOffloadAvailable() {
    return false;
  }

  @Implementation
  protected Object newDisplayControlLock(String name) {
    try {
      Class<?> cls =
          Class.forName(
              ShadowDisplayControlLock.TARGET,
              false,
              ShadowDisplayOffloadManager.class.getClassLoader());
      return ReflectionHelpers.callConstructor(cls);
    } catch (ClassNotFoundException e) {
      throw new LinkageError("Missing " + ShadowDisplayControlLock.TARGET, e);
    }
  }

  @Implementation
  protected void registerOffloadStateCallbacks(
      @ClassName(OFFLOAD_STATE_CALLBACKS) Object callbacks, Handler handler) {}

  @Implementation
  protected void registerPreOffloadStateCallbacks(
      @ClassName(PRE_OFFLOAD_STATE_CALLBACKS) Object callbacks) {}

  @Implementation
  protected void registerStatusBarUpdateListener(Handler handler, Runnable listener) {}

  @Implementation
  protected void registerStatusBarUpdateListener(Runnable listener) {}

  @Implementation
  protected void sendActivityLayout(
      Context context,
      @ClassName(LAYOUT_ITEM) Object layoutItem,
      int displayId,
      @ClassName(SEND_LAYOUT_CALLBACKS) Object callbacks,
      Handler handler) {}

  @Implementation
  protected void sendActivityLayout(
      Context context,
      @ClassName(LAYOUT_ITEM) Object layoutItem,
      int displayId,
      boolean force,
      @ClassName(SEND_LAYOUT_CALLBACKS) Object callbacks,
      Handler handler) {}

  @Implementation
  protected void sendEmptyWatchFaceLayout(
      Context context, int displayId, @ClassName(SEND_LAYOUT_CALLBACKS) Object callbacks) {}

  @Implementation
  protected void sendEmptyWatchFaceLayout(
      Context context, @ClassName(SEND_LAYOUT_CALLBACKS) Object callbacks) {}

  @Implementation
  protected void sendStatusBar(Icon icon, RectF bounds) {}

  @Implementation
  protected void sendWatchFaceLayout(
      Context context,
      @ClassName(LAYOUT_ITEM) Object layoutItem,
      int displayId,
      @ClassName(SEND_LAYOUT_CALLBACKS) Object callbacks,
      Handler handler) {}

  @Implementation
  protected void sendWatchFaceLayout(
      Context context,
      @ClassName(LAYOUT_ITEM) Object layoutItem,
      int displayId,
      boolean force,
      @ClassName(SEND_LAYOUT_CALLBACKS) Object callbacks,
      Handler handler) {}

  @Implementation
  protected void setSysUIAnimateOnTilt(boolean animate) {}

  @Implementation
  protected Object translateLayoutToKoru(
      Context context, @ClassName(LAYOUT_ITEM) Object layoutItem) {
    return null;
  }

  @Implementation
  protected Object translateLayoutToKoru(
      Context context, @ClassName(LAYOUT_ITEM) Object layoutItem, boolean force) {
    return null;
  }

  @Implementation
  protected void unregisterOffloadStateCallbacks(
      @ClassName(OFFLOAD_STATE_CALLBACKS) Object callbacks) {}

  @Implementation
  protected void unregisterPreOffloadStateCallbacks(
      @ClassName(PRE_OFFLOAD_STATE_CALLBACKS) Object callbacks) {}

  @Implementation
  protected void unregisterStatusBarUpdateListener(Runnable listener) {}
}
