package ee.schimke.composeai.simulator;

import android.content.Intent;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowBaseAdapter;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * The adapter behind a widget's collection ({@code setRemoteAdapter} with a {@code
 * RemoteViewsService}), talking to the app's {@code RemoteViewsFactory} in-process. On a device the
 * adapter binds the service across processes through {@code AppWidgetManager} and loads rows on a
 * worker thread; here the service is bound through {@link Navigator} (so it is created, reported
 * and destroyed like any other) and its factory driven on the main thread: {@code onCreate} at the
 * bind, {@code onDataSetChanged} at the first load and on every {@code
 * notifyAppWidgetViewDataChanged}, then {@code getCount} and {@code getViewAt}.
 *
 * <p>Each row is wrapped in a frame as the device's adapter wraps it, flagged as a collection
 * child, and applied with the widget's interaction handler, so a row's fill-in intent and the
 * list's {@code setPendingIntentTemplate} combine into one click as on a device.
 *
 * <p>Replaces Robolectric's shadow, which builds the service outside the app's component lifecycle,
 * never calls {@code onDataSetChanged}, and returns rows without the frame the template click looks
 * for. Added to the shadow map by {@link SimulatorSandbox}.
 */
@Implements(className = "android.widget.RemoteViewsAdapter", isInAndroidSdk = false)
public class SimulatorShadowRemoteViewsAdapter extends ShadowBaseAdapter {

  private static final String FACTORY = "com.android.internal.widget.IRemoteViewsFactory";

  @RealObject private Object realAdapter;

  /** The service's {@code IRemoteViewsFactory} (its local object), once bound. */
  private Object factory;

  private boolean loaded;
  private String failure;

  /** The device binds on a worker thread; here {@link #factory()} binds on first use instead. */
  @Implementation
  protected void requestBindService() {}

  @Implementation
  protected boolean isDataReady() {
    return factory() != null;
  }

  @Implementation
  protected int getCount() {
    Object f = factory();
    return f == null ? 0 : (int) call(f, "getCount");
  }

  @Implementation
  protected Object getItem(int position) {
    return null;
  }

  @Implementation
  protected long getItemId(int position) {
    Object f = factory();
    return f == null
        ? position
        : (long) call(f, "getItemId", ClassParameter.from(int.class, position));
  }

  @Implementation
  protected boolean hasStableIds() {
    Object f = factory();
    return f != null && (boolean) call(f, "hasStableIds");
  }

  /** One view type: a row's frame is reused and its views reapplied or replaced by layout. */
  @Implementation
  protected int getItemViewType(int position) {
    return 0;
  }

  @Implementation
  protected int getViewTypeCount() {
    return 1;
  }

  @Implementation
  protected boolean isEmpty() {
    return getCount() == 0;
  }

  @Implementation
  protected void setVisibleRangeHint(int lowerBound, int upperBound) {}

  @Implementation
  protected void saveRemoteViewsCache() {}

  @Implementation
  protected View getView(int position, View convertView, ViewGroup parent) {
    Object f = factory();
    FrameLayout frame =
        convertView instanceof FrameLayout
            ? (FrameLayout) convertView
            : new FrameLayout(parent.getContext());
    if (f == null) return frame;
    RemoteViews row = (RemoteViews) call(f, "getViewAt", ClassParameter.from(int.class, position));
    if (row == null) row = (RemoteViews) call(f, "getLoadingView");
    if (row == null) {
      frame.removeAllViews();
      return frame;
    }
    Object handler = ReflectionHelpers.getField(realAdapter, "mRemoteViewsInteractionHandler");
    Class<?> handlerType = handlerType();
    View child = frame.getChildCount() == 1 ? frame.getChildAt(0) : null;
    if (child != null
        && (boolean)
            ReflectionHelpers.callInstanceMethod(
                row, "canRecycleView", ClassParameter.from(View.class, child))) {
      ReflectionHelpers.callInstanceMethod(
          row,
          "reapply",
          ClassParameter.from(android.content.Context.class, parent.getContext()),
          ClassParameter.from(View.class, child),
          ClassParameter.from(handlerType, handler));
    } else {
      frame.removeAllViews();
      child =
          ReflectionHelpers.callInstanceMethod(
              row,
              "apply",
              ClassParameter.from(android.content.Context.class, parent.getContext()),
              ClassParameter.from(ViewGroup.class, frame),
              ClassParameter.from(handlerType, handler));
      frame.addView(child);
    }
    return frame;
  }

  private boolean notifying;

  /**
   * {@code notifyAppWidgetViewDataChanged} reaches here through the host view: the factory reloads,
   * then the list. The adapter's own {@code BaseAdapter.notifyDataSetChanged}, which tells the
   * list, dispatches here too (this shadow is the adapter's {@code ShadowBaseAdapter}); that inner
   * call is passed on.
   */
  @Implementation
  @Override
  protected void notifyDataSetChanged() {
    if (notifying) {
      super.notifyDataSetChanged();
      return;
    }
    notifying = true;
    try {
      Object f = factory();
      if (f != null) call(f, "onDataSetChanged");
      ReflectionHelpers.callInstanceMethod(realAdapter, "superNotifyDataSetChanged");
    } finally {
      notifying = false;
    }
  }

  /** Binds the service on first use and loads its data once. */
  private Object factory() {
    if (factory != null || failure != null) {
      if (factory != null && !loaded) {
        loaded = true;
        call(factory, "onDataSetChanged");
      }
      return factory;
    }
    Intent intent = ReflectionHelpers.getField(realAdapter, "mIntent");
    Navigator navigator = Navigator.current();
    if (intent == null || navigator == null) {
      failure = "no service intent";
      return null;
    }
    try {
      IBinder binder = navigator.bindForHost(intent);
      Object local = binder == null ? null : binder.queryLocalInterface(FACTORY);
      if (local == null) {
        failure = "the service's binder is not a RemoteViewsFactory";
        return null;
      }
      factory = local;
    } catch (RuntimeException e) {
      failure = String.valueOf(e);
      System.err.println("widget collection: cannot bind " + intent + ": " + e);
      return null;
    }
    loaded = true;
    call(factory, "onDataSetChanged");
    return factory;
  }

  private static Object call(Object target, String method, ClassParameter<?>... args) {
    return ReflectionHelpers.callInstanceMethod(target, method, args);
  }

  private static Class<?> handlerType() {
    try {
      return Class.forName(
          "android.widget.RemoteViews$InteractionHandler",
          false,
          SimulatorShadowRemoteViewsAdapter.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }
}
