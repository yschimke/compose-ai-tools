package androidx.window.extensions.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The display features overlapping one window. */
public class WindowLayoutInfo {

  private final List<DisplayFeature> displayFeatures;

  public WindowLayoutInfo(List<DisplayFeature> displayFeatures) {
    this.displayFeatures = Collections.unmodifiableList(new ArrayList<>(displayFeatures));
  }

  public List<DisplayFeature> getDisplayFeatures() {
    return displayFeatures;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof WindowLayoutInfo
        && displayFeatures.equals(((WindowLayoutInfo) other).displayFeatures);
  }

  @Override
  public int hashCode() {
    return displayFeatures.hashCode();
  }

  @Override
  public String toString() {
    return "WindowLayoutInfo{" + displayFeatures + "}";
  }
}
