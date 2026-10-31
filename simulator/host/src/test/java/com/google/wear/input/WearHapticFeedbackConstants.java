package com.google.wear.input;

/** Test-only copy of the optional Wear SDK signature class. */
public final class WearHapticFeedbackConstants {
  private WearHapticFeedbackConstants() {}

  public static int getScrollItemFocus() {
    throw new UnsupportedOperationException(
        "Wear SDK calls require the Compose Preview Wear simulator");
  }
}
