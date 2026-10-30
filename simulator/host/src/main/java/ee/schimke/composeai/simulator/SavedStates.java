package ee.schimke.composeai.simulator;

import android.os.Bundle;
import android.os.Parcel;
import android.util.Base64;
import android.util.SparseArray;
import java.lang.reflect.Array;
import java.util.Collection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * An activity's saved instance state as it crosses a process boundary: the {@code Bundle} is
 * written to a {@code Parcel}, as {@code activityStopped} sends it to the system server, and read
 * back with the app's class loader, as a re-created activity receives it. What does not survive
 * that trip (a value that is neither {@code Parcelable} nor {@code Serializable}) fails here as it
 * would on a device. The bytes are what {@code savedState} hands out and {@code startActivity}'s
 * {@code savedState} takes back.
 */
final class SavedStates {
  private SavedStates() {}

  static byte[] marshall(Bundle state) {
    Parcel parcel = Parcel.obtain();
    try {
      parcel.writeBundle(state);
      return parcel.marshall();
    } finally {
      parcel.recycle();
    }
  }

  static Bundle unmarshall(byte[] bytes, ClassLoader loader) {
    Parcel parcel = Parcel.obtain();
    try {
      parcel.unmarshall(bytes, 0, bytes.length);
      parcel.setDataPosition(0);
      Bundle state = parcel.readBundle(loader);
      if (state != null) state.setClassLoader(loader);
      return state;
    } finally {
      parcel.recycle();
    }
  }

  /** [state] through a parcel and back, as a re-created activity receives it. */
  static Bundle roundTrip(Bundle state, ClassLoader loader) {
    return unmarshall(marshall(state), loader);
  }

  static String encode(byte[] bytes) {
    return Base64.encodeToString(bytes, Base64.NO_WRAP);
  }

  static Bundle decode(String base64, ClassLoader loader) {
    return unmarshall(Base64.decode(base64, Base64.DEFAULT), loader);
  }

  /**
   * [state] as JSON to read: numbers, strings and booleans as themselves, bundles nested, arrays
   * and lists as arrays, anything else as its class and {@code toString}. Compose's {@code
   * rememberSaveable} values sit under {@code androidx.lifecycle.BundlableSavedStateRegistry.key}.
   */
  static JSONObject toJson(Bundle state) throws JSONException {
    JSONObject json = new JSONObject();
    for (String key : state.keySet()) json.put(key, value(state.get(key)));
    return json;
  }

  private static Object value(Object value) throws JSONException {
    if (value == null) return JSONObject.NULL;
    if (value instanceof Boolean || value instanceof Number || value instanceof String) {
      return value;
    }
    if (value instanceof CharSequence || value instanceof Character) return value.toString();
    if (value instanceof Bundle) return toJson((Bundle) value);
    if (value instanceof Collection) {
      JSONArray array = new JSONArray();
      for (Object item : (Collection<?>) value) array.put(value(item));
      return array;
    }
    if (value.getClass().isArray()) {
      JSONArray array = new JSONArray();
      for (int i = 0; i < Array.getLength(value); i++) array.put(value(Array.get(value, i)));
      return array;
    }
    if (value instanceof SparseArray) {
      SparseArray<?> sparse = (SparseArray<?>) value;
      JSONObject json = new JSONObject();
      for (int i = 0; i < sparse.size(); i++) {
        json.put(String.valueOf(sparse.keyAt(i)), value(sparse.valueAt(i)));
      }
      return json;
    }
    String text = String.valueOf(value);
    if (text.length() > 200) text = text.substring(0, 200) + "…";
    return new JSONObject().put("type", value.getClass().getName()).put("value", text);
  }
}
