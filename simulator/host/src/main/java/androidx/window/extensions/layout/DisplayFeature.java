package androidx.window.extensions.layout;

import android.graphics.Rect;

/** A physical feature of the display, in window coordinates. */
public interface DisplayFeature {
  Rect getBounds();
}
