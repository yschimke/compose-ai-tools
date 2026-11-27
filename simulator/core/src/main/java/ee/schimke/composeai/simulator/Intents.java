package ee.schimke.composeai.simulator;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * JSON ↔ {@link Intent}.
 *
 * <p>An intent spec: {@code action}, {@code data} (a URI), {@code type}, {@code package}, {@code
 * component} ({@code "pkg/cls"}, {@code "pkg/.Cls"}, {@code ".Cls"} or a class name in the app's
 * package), {@code categories}, {@code flags} (an int, or names such as {@code "NEW_TASK"} and
 * {@code "CLEAR_TOP"}), and {@code extras}. An extra's JSON type picks its Java type (string,
 * boolean, int when it fits else long, double, string array); {@code {"type":"long","value":1}}
 * names one explicitly, with types {@code string int long float double boolean uri component
 * null string[] int[]}. {@code flags} may mix names and ints.
 */
final class Intents {

  static Intent fromJson(JSONObject spec, Context context) throws JSONException {
    Intent intent = new Intent();
    if (spec.has("action")) intent.setAction(spec.getString("action"));
    Uri data = spec.has("data") ? Uri.parse(spec.getString("data")) : null;
    String type = spec.has("type") ? spec.getString("type") : null;
    if (data != null && type != null) intent.setDataAndType(data, type);
    else if (data != null) intent.setData(data);
    else if (type != null) intent.setType(type);
    if (spec.has("package")) intent.setPackage(spec.getString("package"));
    if (spec.has("component")) {
      intent.setComponent(component(spec.getString("component"), context.getPackageName()));
    }
    JSONArray categories = spec.optJSONArray("categories");
    if (categories != null) {
      for (int i = 0; i < categories.length(); i++) intent.addCategory(categories.getString(i));
    }
    if (spec.has("flags")) intent.setFlags(flags(spec.get("flags")));
    JSONObject extras = spec.optJSONObject("extras");
    if (extras != null) putExtras(intent, extras);
    return intent;
  }

  static ComponentName component(String value, String appPackage) {
    int slash = value.indexOf('/');
    if (slash >= 0) {
      String pkg = value.substring(0, slash);
      String cls = value.substring(slash + 1);
      return new ComponentName(pkg, cls.startsWith(".") ? pkg + cls : cls);
    }
    return new ComponentName(appPackage, value.startsWith(".") ? appPackage + value : value);
  }

  private static int flags(Object value) throws JSONException {
    if (value instanceof Number) return ((Number) value).intValue();
    JSONArray names = value instanceof JSONArray ? (JSONArray) value : new JSONArray().put(value);
    int flags = 0;
    for (int i = 0; i < names.length(); i++) {
      if (names.get(i) instanceof Number) {
        flags |= ((Number) names.get(i)).intValue();
        continue;
      }
      String name = names.getString(i);
      String field = name.startsWith("FLAG_") ? name : "FLAG_ACTIVITY_" + name;
      try {
        flags |= Intent.class.getField(field).getInt(null);
      } catch (ReflectiveOperationException e) {
        try {
          flags |= Intent.class.getField("FLAG_" + name).getInt(null);
        } catch (ReflectiveOperationException unknown) {
          throw new JSONException("unknown intent flag: " + name);
        }
      }
    }
    return flags;
  }

  private static void putExtras(Intent intent, JSONObject extras) throws JSONException {
    for (Iterator<String> keys = extras.keys(); keys.hasNext(); ) {
      String key = keys.next();
      Object value = extras.get(key);
      if (value instanceof JSONObject && ((JSONObject) value).has("type")) {
        putTyped(intent, key, (JSONObject) value);
      } else if (value instanceof String) {
        intent.putExtra(key, (String) value);
      } else if (value instanceof Boolean) {
        intent.putExtra(key, (Boolean) value);
      } else if (value instanceof Integer) {
        intent.putExtra(key, (Integer) value);
      } else if (value instanceof Long) {
        intent.putExtra(key, (Long) value);
      } else if (value instanceof Number) {
        intent.putExtra(key, ((Number) value).doubleValue());
      } else if (value instanceof JSONArray) {
        JSONArray array = (JSONArray) value;
        String[] strings = new String[array.length()];
        for (int i = 0; i < strings.length; i++) strings[i] = array.getString(i);
        intent.putExtra(key, strings);
      } else {
        throw new JSONException("unsupported extra " + key + ": " + value);
      }
    }
  }

  private static void putTyped(Intent intent, String key, JSONObject typed) throws JSONException {
    String type = typed.getString("type");
    switch (type) {
      case "string" -> intent.putExtra(key, typed.getString("value"));
      case "int" -> intent.putExtra(key, typed.getInt("value"));
      case "long" -> intent.putExtra(key, typed.getLong("value"));
      case "float" -> intent.putExtra(key, (float) typed.getDouble("value"));
      case "double" -> intent.putExtra(key, typed.getDouble("value"));
      case "boolean" -> intent.putExtra(key, typed.getBoolean("value"));
      case "uri" -> intent.putExtra(key, Uri.parse(typed.getString("value")));
      case "null" -> intent.putExtra(key, (String) null);
      case "component" ->
          intent.putExtra(
              key, ComponentName.unflattenFromString(typed.getString("value")));
      case "string[]" -> {
        JSONArray array = typed.getJSONArray("value");
        ArrayList<String> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) list.add(array.getString(i));
        intent.putExtra(key, list.toArray(new String[0]));
      }
      case "int[]" -> {
        JSONArray array = typed.getJSONArray("value");
        int[] ints = new int[array.length()];
        for (int i = 0; i < ints.length; i++) ints[i] = array.getInt(i);
        intent.putExtra(key, ints);
      }
      default -> throw new JSONException("unknown extra type " + type + " for " + key);
    }
  }

  /** An {@link IntentFilter} that matches exactly [intent], for a one-shot receiver delivery. */
  static IntentFilter filterMatching(Intent intent) {
    IntentFilter filter = new IntentFilter();
    if (intent.getAction() != null) filter.addAction(intent.getAction());
    Set<String> categories = intent.getCategories();
    if (categories != null) for (String category : categories) filter.addCategory(category);
    Uri data = intent.getData();
    if (data != null && data.getScheme() != null) {
      filter.addDataScheme(data.getScheme());
      if (data.getHost() != null) {
        filter.addDataAuthority(
            data.getHost(), data.getPort() < 0 ? null : String.valueOf(data.getPort()));
      }
      if (data.getPath() != null) {
        filter.addDataPath(data.getPath(), android.os.PatternMatcher.PATTERN_LITERAL);
      }
    }
    if (intent.getType() != null) {
      try {
        filter.addDataType(intent.getType());
      } catch (IntentFilter.MalformedMimeTypeException e) {
        throw new IllegalArgumentException("bad mime type " + intent.getType(), e);
      }
    }
    return filter;
  }

  static JSONObject toJson(Intent intent) {
    Protocol.Json json = new Protocol.Json();
    if (intent.getAction() != null) json.put("action", intent.getAction());
    if (intent.getData() != null) json.put("data", intent.getData().toString());
    if (intent.getType() != null) json.put("type", intent.getType());
    if (intent.getComponent() != null) {
      json.put("component", intent.getComponent().flattenToShortString());
    }
    if (intent.getPackage() != null) json.put("package", intent.getPackage());
    if (intent.getCategories() != null) {
      json.put("categories", new JSONArray(intent.getCategories()));
    }
    if (intent.getFlags() != 0) json.put("flags", intent.getFlags());
    Bundle extras = intent.getExtras();
    if (extras != null && !extras.isEmpty()) {
      Protocol.Json values = new Protocol.Json();
      for (String key : extras.keySet()) values.put(key, String.valueOf(extras.get(key)));
      json.put("extras", values.object());
    }
    return json.object();
  }

  private Intents() {}
}
