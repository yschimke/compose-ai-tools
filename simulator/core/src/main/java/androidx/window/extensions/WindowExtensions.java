package androidx.window.extensions;

import androidx.window.extensions.layout.WindowLayoutComponent;

/** The extensions' entry point, as in {@code androidx.window.extensions:extensions}. */
public interface WindowExtensions {

  /** The extensions API level: 2 adds the {@code Context} listener with the core consumer. */
  int getVendorApiLevel();

  WindowLayoutComponent getWindowLayoutComponent();
}
