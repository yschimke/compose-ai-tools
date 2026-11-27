package ee.schimke.composeai.simulator;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import java.util.Arrays;

/** Sorted-attribute reader over Android XML resources shared across simulator modules. */
final class XmlAttrs {
  private final TypedArray array;
  private final int[] sorted;

  private XmlAttrs(TypedArray array, int[] sorted) {
    this.array = array;
    this.sorted = sorted;
  }

  static XmlAttrs read(Resources res, AttributeSet set, int[] ids) {
    int[] sorted = ids.clone();
    Arrays.sort(sorted);
    return new XmlAttrs(res.obtainAttributes(set, sorted), sorted);
  }

  private int index(int attr) {
    return Arrays.binarySearch(sorted, attr);
  }

  String string(int attr) {
    int i = index(attr);
    return i >= 0 && array.hasValue(i) ? array.getString(i) : null;
  }

  boolean bool(int attr, boolean fallback) {
    int i = index(attr);
    return i >= 0 && array.hasValue(i) ? array.getBoolean(i, fallback) : fallback;
  }

  void recycle() {
    array.recycle();
  }
}
