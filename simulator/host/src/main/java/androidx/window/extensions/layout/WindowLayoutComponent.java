package androidx.window.extensions.layout;

import android.app.Activity;
import android.content.Context;
import androidx.window.extensions.core.util.function.Consumer;

/** Reports the display features overlapping a window, as in the extensions library. */
public interface WindowLayoutComponent {

  /** Vendor API level 1. */
  void addWindowLayoutInfoListener(
      Activity activity, java.util.function.Consumer<WindowLayoutInfo> consumer);

  /** Vendor API level 1. */
  void removeWindowLayoutInfoListener(java.util.function.Consumer<WindowLayoutInfo> consumer);

  /** Vendor API level 2: any {@code @UiContext}, with the extensions' own consumer type. */
  void addWindowLayoutInfoListener(Context context, Consumer<WindowLayoutInfo> consumer);

  /** Vendor API level 2. */
  void removeWindowLayoutInfoListener(Consumer<WindowLayoutInfo> consumer);
}
