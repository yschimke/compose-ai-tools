package ee.schimke.composeai.simulator.fakegms;

import java.util.LinkedHashMap;

/** A hidden constructor taking a map that is not a {@code java.util.Map} ({@code SimpleArrayMap}). */
public final class HiddenSimpleMapException extends Exception {
  public static final class SimpleMap {
    public final LinkedHashMap<Object, Object> entries = new LinkedHashMap<>();

    SimpleMap() {}

    public Object put(Object key, Object value) {
      return entries.put(key, value);
    }
  }

  public final SimpleMap results;

  private HiddenSimpleMapException(SimpleMap results) {
    this.results = results;
  }

  HiddenSimpleMapException(String unrelated) {
    this.results = null;
  }
}
