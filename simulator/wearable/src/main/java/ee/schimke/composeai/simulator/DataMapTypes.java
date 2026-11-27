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
  /**
   * A list of anything else, written element by element. On input, {@code list} is accepted too:
   * the element types pick {@link #STRING_LIST}, {@link #INT_LIST} or {@link #DATA_MAP_LIST}.
   */
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
    if (value instanceof List) return listTag((List<?>) value);
    if (value != null && value.getClass().getName().equals("com.google.android.gms.wearable.DataMap")) {
      return DATA_MAP;
    }
    return OTHER;
  }

  /**
   * The tag of a {@code DataMap}'s {@code ArrayList}, from its elements: the three list types a
   * {@code DataMap} holds ({@code putStringArrayList}, {@code putIntegerArrayList}, {@code
   * putDataMapArrayList}), so a list read from one can be written back. An empty list is a string
   * list (its type is not in the bytes either); a mixed one is {@link #LIST}.
   */
  static String listTag(List<?> values) {
    String tag = null;
    for (Object element : values) {
      String one;
      if (element instanceof String) one = STRING_LIST;
      else if (element instanceof Integer) one = INT_LIST;
      else if (DATA_MAP.equals(tagOf(element)) || element instanceof java.util.Map) {
        one = DATA_MAP_LIST;
      } else return LIST;
      if (tag != null && !tag.equals(one)) return LIST;
      tag = one;
    }
    return tag == null ? STRING_LIST : tag;
  }

  /** The tags {@link DataMapJson} accepts on input, every one {@link #tagOf} can produce but other. */
  static final java.util.Set<String> INPUT_TAGS =
      new java.util.LinkedHashSet<>(
          java.util.Arrays.asList(
              STRING, BOOLEAN, INT, LONG, FLOAT, DOUBLE, BYTES, STRING_ARRAY, LONG_ARRAY,
              FLOAT_ARRAY, STRING_LIST, INT_LIST, DATA_MAP, DATA_MAP_LIST, LIST));

  /** Whether a JSON integer is written as an {@code int} rather than a {@code long}. */
  static boolean fitsInt(long value) {
    return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
  }

  private DataMapTypes() {}
}
