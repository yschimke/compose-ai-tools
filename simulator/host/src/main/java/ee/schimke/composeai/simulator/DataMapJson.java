package ee.schimke.composeai.simulator;

import com.google.android.gms.wearable.DataMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A {@code DataMap} as JSON, both ways, for the agent data layer: the data items and messages an
 * app sends are shown as JSON when their bytes are a {@code DataMap}, and an agent writes one as
 * JSON.
 *
 * <p>A string, a boolean and an integer that fits an {@code int} are bare JSON values; every other
 * value is {@code {"type": <tag>, "value": ...}} with the tags of {@link DataMapTypes}, bytes as
 * base64; a bare JSON object is a nested {@code DataMap}. The app's own {@code DataMap} class does
 * the encoding, so the bytes are exactly what it would read.
 */
final class DataMapJson {

  /** [bytes] as a DataMap's JSON, or null when they are not a DataMap. */
  static JSONObject toJson(byte[] bytes) {
    if (bytes == null || bytes.length == 0) return null;
    try {
      DataMap map = DataMap.fromByteArray(bytes);
      return map == null ? null : toJson(map);
    } catch (RuntimeException | JSONException e) {
      return null;
    }
  }

  static JSONObject toJson(DataMap map) throws JSONException {
    JSONObject json = new JSONObject();
    for (String key : map.keySet()) json.put(key, value(map.get(key)));
    return json;
  }

  private static Object value(Object v) throws JSONException {
    String tag = DataMapTypes.tagOf(v);
    switch (tag) {
      case DataMapTypes.STRING:
      case DataMapTypes.BOOLEAN:
      case DataMapTypes.INT:
        return v;
      case DataMapTypes.DATA_MAP:
        return toJson((DataMap) v);
      default:
        return new JSONObject().put("type", tag).put("value", typedValue(tag, v));
    }
  }

  private static Object typedValue(String tag, Object v) throws JSONException {
    switch (tag) {
      case DataMapTypes.BYTES:
        return Base64.getEncoder().encodeToString((byte[]) v);
      case DataMapTypes.LONG:
      case DataMapTypes.FLOAT:
      case DataMapTypes.DOUBLE:
        return v;
      case DataMapTypes.STRING_ARRAY:
        return new JSONArray((String[]) v);
      case DataMapTypes.LONG_ARRAY:
        {
          JSONArray array = new JSONArray();
          for (long l : (long[]) v) array.put(l);
          return array;
        }
      case DataMapTypes.FLOAT_ARRAY:
        {
          JSONArray array = new JSONArray();
          for (float f : (float[]) v) array.put((double) f);
          return array;
        }
      case DataMapTypes.STRING_LIST:
      case DataMapTypes.INT_LIST:
        return new JSONArray((List<?>) v);
      case DataMapTypes.DATA_MAP_LIST:
        {
          JSONArray array = new JSONArray();
          for (Object item : (List<?>) v) array.put(toJson((DataMap) item));
          return array;
        }
      case DataMapTypes.LIST:
        {
          JSONArray array = new JSONArray();
          for (Object item : (List<?>) v) array.put(value(item));
          return array;
        }
      default:
        return String.valueOf(v);
    }
  }

  /** [json] as a DataMap's bytes. */
  static byte[] toBytes(JSONObject json) throws JSONException {
    return toDataMap(json).toByteArray();
  }

  static DataMap toDataMap(JSONObject json) throws JSONException {
    DataMap map = new DataMap();
    for (Iterator<String> it = json.keys(); it.hasNext(); ) {
      String key = it.next();
      put(map, key, json.get(key));
    }
    return map;
  }

  private static void put(DataMap map, String key, Object v) throws JSONException {
    if (v instanceof String) {
      map.putString(key, (String) v);
    } else if (v instanceof Boolean) {
      map.putBoolean(key, (Boolean) v);
    } else if (v instanceof Integer || v instanceof Long) {
      long l = ((Number) v).longValue();
      if (DataMapTypes.fitsInt(l)) map.putInt(key, (int) l);
      else map.putLong(key, l);
    } else if (v instanceof Number) {
      map.putDouble(key, ((Number) v).doubleValue());
    } else if (v instanceof JSONObject) {
      JSONObject object = (JSONObject) v;
      if (object.has("type") && object.has("value") && object.length() == 2) {
        putTyped(map, key, object.getString("type"), object.get("value"));
      } else {
        map.putDataMap(key, toDataMap(object));
      }
    } else if (v instanceof JSONArray) {
      // An untyped array: strings.
      map.putStringArrayList(key, strings((JSONArray) v));
    } else {
      throw new IllegalArgumentException("cannot put " + key + " = " + v);
    }
  }

  private static void putTyped(DataMap map, String key, String tag, Object v)
      throws JSONException {
    switch (tag) {
      case DataMapTypes.STRING:
        map.putString(key, String.valueOf(v));
        break;
      case DataMapTypes.BOOLEAN:
        map.putBoolean(key, Boolean.parseBoolean(String.valueOf(v)));
        break;
      case DataMapTypes.INT:
        map.putInt(key, ((Number) v).intValue());
        break;
      case DataMapTypes.LONG:
        map.putLong(key, ((Number) v).longValue());
        break;
      case DataMapTypes.FLOAT:
        map.putFloat(key, ((Number) v).floatValue());
        break;
      case DataMapTypes.DOUBLE:
        map.putDouble(key, ((Number) v).doubleValue());
        break;
      case DataMapTypes.BYTES:
        map.putByteArray(key, Base64.getDecoder().decode(String.valueOf(v)));
        break;
      case DataMapTypes.STRING_ARRAY:
        map.putStringArray(key, strings((JSONArray) v).toArray(new String[0]));
        break;
      case DataMapTypes.LONG_ARRAY:
        {
          JSONArray array = (JSONArray) v;
          long[] longs = new long[array.length()];
          for (int i = 0; i < longs.length; i++) longs[i] = array.getLong(i);
          map.putLongArray(key, longs);
          break;
        }
      case DataMapTypes.FLOAT_ARRAY:
        {
          JSONArray array = (JSONArray) v;
          float[] floats = new float[array.length()];
          for (int i = 0; i < floats.length; i++) floats[i] = (float) array.getDouble(i);
          map.putFloatArray(key, floats);
          break;
        }
      case DataMapTypes.STRING_LIST:
        map.putStringArrayList(key, strings((JSONArray) v));
        break;
      case DataMapTypes.INT_LIST:
        {
          JSONArray array = (JSONArray) v;
          ArrayList<Integer> ints = new ArrayList<>();
          for (int i = 0; i < array.length(); i++) ints.add(array.getInt(i));
          map.putIntegerArrayList(key, ints);
          break;
        }
      case DataMapTypes.DATA_MAP:
        map.putDataMap(key, toDataMap((JSONObject) v));
        break;
      case DataMapTypes.LIST:
        {
          String list = DataMapTypes.listTag(javaList((JSONArray) v));
          if (list.equals(DataMapTypes.LIST)) {
            throw new IllegalArgumentException(
                key + ": a DataMap list holds strings, ints or DataMaps, not a mix");
          }
          putTyped(map, key, list, v);
          break;
        }
      case DataMapTypes.DATA_MAP_LIST:
        {
          JSONArray array = (JSONArray) v;
          ArrayList<DataMap> maps = new ArrayList<>();
          for (int i = 0; i < array.length(); i++) maps.add(toDataMap(array.getJSONObject(i)));
          map.putDataMapArrayList(key, maps);
          break;
        }
      default:
        throw new IllegalArgumentException("unknown DataMap type " + tag + " for " + key);
    }
  }

  /** [array]'s elements as Java values, nested objects as maps, for {@link DataMapTypes#listTag}. */
  private static List<Object> javaList(JSONArray array) throws JSONException {
    List<Object> out = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      Object element = array.get(i);
      out.add(element instanceof JSONObject ? new java.util.HashMap<String, Object>() : element);
    }
    return out;
  }

  private static ArrayList<String> strings(JSONArray array) throws JSONException {
    ArrayList<String> out = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) out.add(array.getString(i));
    return out;
  }

  private DataMapJson() {}
}
