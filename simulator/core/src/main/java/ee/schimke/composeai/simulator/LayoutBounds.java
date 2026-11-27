package ee.schimke.composeai.simulator;

import android.view.View;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.robolectric.shadows.ShadowSystemProperties;
import org.robolectric.util.ReflectionHelpers;

/**
 * Developer options' "Show layout bounds": {@code setprop debug.layout true} and the {@code
 * SYSPROPS_TRANSACTION} poke that follows it ({@code service call activity 1599295570}), which is
 * how Android Studio's Device Settings and {@code adb} turn it on. Every window's {@code
 * AttachInfo} gets {@code mDebugLayout}, so views draw their bounds, margins and corners as on a
 * device; windows made later get it on the next cycle, as a device's read the property when
 * created.
 */
final class LayoutBounds {

  private static boolean on;

  static boolean on() {
    return on;
  }

  /** Sets the property, as {@code setprop} would; [poke] applies it to every window now. */
  static void set(boolean value, boolean poke) {
    on = value;
    ShadowSystemProperties.override("debug.layout", value ? "true" : "false");
    if (poke) apply();
  }

  /** Re-reads the property into every window, as the {@code SYSPROPS_TRANSACTION} poke does. */
  static void apply() {
    for (Object impl : roots()) {
      try {
        Object attach = ReflectionHelpers.getField(impl, "mAttachInfo");
        boolean current = ReflectionHelpers.getField(attach, "mDebugLayout");
        if (current == on) continue;
        ReflectionHelpers.setField(attach, "mDebugLayout", on);
        View view = ReflectionHelpers.callInstanceMethod(impl, "getView");
        if (view != null) invalidate(view);
      } catch (RuntimeException e) {
        // A window being torn down.
      }
    }
  }

  /** Each cycle while on: a window created since gets the bounds too. */
  static void cycle() {
    if (on) apply();
  }

  private static void invalidate(View view) {
    view.invalidate();
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) invalidate(group.getChildAt(i));
    }
  }

  private static List<?> roots() {
    try {
      Object global =
          ReflectionHelpers.callStaticMethod(
              Class.forName(
                  "android.view.WindowManagerGlobal", true, LayoutBounds.class.getClassLoader()),
              "getInstance");
      Object value = ReflectionHelpers.getField(global, "mRoots");
      if (value instanceof Object[]) return Arrays.asList((Object[]) value);
      if (value instanceof List) return new ArrayList<>((List<?>) value);
    } catch (ClassNotFoundException | RuntimeException e) {
      // None.
    }
    return Collections.emptyList();
  }

  private LayoutBounds() {}
}
