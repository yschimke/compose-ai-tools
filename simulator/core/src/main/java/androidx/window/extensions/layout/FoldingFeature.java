package androidx.window.extensions.layout;

import android.graphics.Rect;

/**
 * A fold or hinge across the window, as in the extensions library: a {@code TYPE_FOLD} has zero
 * width across its axis, a {@code TYPE_HINGE} the width of the hinge it hides.
 */
public class FoldingFeature implements DisplayFeature {

  public static final int TYPE_FOLD = 1;
  public static final int TYPE_HINGE = 2;
  public static final int STATE_FLAT = 1;
  public static final int STATE_HALF_OPENED = 2;

  private final Rect bounds;
  private final int type;
  private final int state;

  public FoldingFeature(Rect bounds, int type, int state) {
    this.bounds = new Rect(bounds);
    this.type = type;
    this.state = state;
  }

  @Override
  public Rect getBounds() {
    return new Rect(bounds);
  }

  public int getType() {
    return type;
  }

  public int getState() {
    return state;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof FoldingFeature)) return false;
    FoldingFeature that = (FoldingFeature) other;
    return type == that.type && state == that.state && bounds.equals(that.bounds);
  }

  @Override
  public int hashCode() {
    return (bounds.hashCode() * 31 + type) * 31 + state;
  }

  @Override
  public String toString() {
    return "FoldingFeature{" + bounds + ", type=" + type + ", state=" + state + "}";
  }
}
