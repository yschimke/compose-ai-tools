package ee.schimke.composeai.simulator;

import java.util.List;

/**
 * The type tags {@link DataMapJson} writes a {@code DataMap} value with, chosen from the Java value
 * alone, so the choice is testable without Play services.
 */
final class DataMapTypes {

  static final String STRING = "string";
  static final String BOOLEAN = "boolean";
  static final String INT = "int";
  static final String LONG = "long";
  static final String FLOAT = "float";
  static final String DOUBLE = "double";
  static final String BYTES = "bytes";
  static final String STRING_ARRAY = "string[]";
  static final String LONG_ARRAY = "long[]";
  static final String FLOAT_ARRAY = "float[]";
  static final String STRING_LIST = "stringList";
  static final String INT_LIST = "intList";
  static final String DATA_MAP = "dataMap";
  static final String DATA_MAP_LIST = "dataMapList";
  /** A list, written element by element. */
  static final String LIST = "list";
  /** Anything else (an {@code Asset}), written as its string. */
  static final String OTHER = "other";

  /** The tag for [value]; a nested map is {@link #DATA_MAP} by its class name, not by type. */
  static String tagOf(Object value) {
    if (value instanceof String) return STRING;
    if (value instanceof Boolean) return BOOLEAN;
    if (value instanceof Integer) return INT;
    if (value instanceof Long) return LONG;
    if (value instanceof Float) return FLOAT;
    if (value instanceof Double) return DOUBLE;
    if (value instanceof byte[]) return BYTES;
    if (value instanceof String[]) return STRING_ARRAY;
    if (value instanceof long[]) return LONG_ARRAY;
    if (value instanceof float[]) return FLOAT_ARRAY;
    if (value instanceof List) return LIST;
    if (value != null && value.getClass().getName().equals("com.google.android.gms.wearable.DataMap")) {
      return DATA_MAP;
    }
    return OTHER;
  }

  /** Whether a JSON integer is written as an {@code int} rather than a {@code long}. */
  static boolean fitsInt(long value) {
    return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
  }

  private DataMapTypes() {}
}
