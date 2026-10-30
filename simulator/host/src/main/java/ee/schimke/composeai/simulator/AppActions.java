package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Bundle;
import android.util.Xml;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/**
 * App Actions: the {@code <capability>} elements of the app's {@code shortcuts.xml} (the {@code
 * android.app.shortcuts} metadata on its activities), invoked as Google Assistant invokes them.
 * Each capability names a built-in intent ({@code actions.intent.OPEN_APP_FEATURE}, {@code
 * actions.intent.GET_THING}, …) and one or more {@code <intent>}s that fulfil it: a {@code
 * <url-template>} filled from the intent's parameters ({@link UrlTemplates}), or extras keyed by
 * each {@code <parameter>}'s {@code android:key}. Shortcuts bound to a capability ({@code
 * <capability-binding>}, the inline inventory) are listed with their parameter values.
 *
 * <p>The {@code assistant} command lists the capabilities, and with {@code invoke} (a capability
 * name) and {@code params} (built-in intent parameter → value) starts the activity the first
 * fulfilling intent names, as {@code startActivity} would; an intent whose required parameters are
 * missing is skipped, as Assistant skips it.
 */
final class AppActions {

  private static final String ANDROID = "http://schemas.android.com/apk/res/android";

  private static final int[] ATTRS = {
    android.R.attr.name,
    android.R.attr.key,
    android.R.attr.value,
    android.R.attr.action,
    android.R.attr.targetPackage,
    android.R.attr.targetClass,
    android.R.attr.data,
    android.R.attr.mimeType,
    android.R.attr.required,
  };

  static final class Parameter {
    String name;
    String key;
    boolean required;
  }

  static final class Fulfilment {
    String action;
    String targetPackage;
    String targetClass;
    String data;
    String urlTemplate;
    final List<Parameter> parameters = new ArrayList<>();
  }

  static final class Capability {
    String name;
    ComponentName declaredOn;
    final List<Fulfilment> intents = new ArrayList<>();
    /** Shortcut id → its bound parameter values. */
    final Map<String, Map<String, String>> shortcuts = new LinkedHashMap<>();
  }

  private final Application app;
  private final Navigator navigator;

  AppActions(Application app, Navigator navigator) {
    this.app = app;
    this.navigator = navigator;
  }

  /** Every capability the app's activities declare, by name, in declaration order. */
  Map<String, Capability> capabilities() {
    Map<String, Capability> all = new LinkedHashMap<>();
    PackageInfo info;
    try {
      info =
          app.getPackageManager()
              .getPackageInfo(
                  app.getPackageName(), PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
    } catch (PackageManager.NameNotFoundException e) {
      return all;
    }
    if (info.activities == null) return all;
    for (ActivityInfo activity : info.activities) {
      ComponentName component = new ComponentName(activity.packageName, activity.name);
      Bundle meta;
      try {
        meta = app.getPackageManager().getActivityInfo(component, PackageManager.GET_META_DATA).metaData;
      } catch (PackageManager.NameNotFoundException e) {
        continue;
      }
      int xml = meta == null ? 0 : meta.getInt("android.app.shortcuts");
      if (xml != 0) parse(xml, component, all);
    }
    return all;
  }

  private void parse(int xml, ComponentName component, Map<String, Capability> into) {
    Resources res = app.getResources();
    try (XmlResourceParser parser = res.getXml(xml)) {
      Capability capability = null;
      Fulfilment intent = null;
      String shortcut = null;
      Map<String, String> bound = null;
      int type;
      while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
        if (type == XmlPullParser.END_TAG) {
          switch (parser.getName()) {
            case "capability":
              capability = null;
              break;
            case "intent":
              intent = null;
              break;
            case "shortcut":
              shortcut = null;
              break;
            case "capability-binding":
              bound = null;
              break;
            default:
              break;
          }
          continue;
        }
        if (type != XmlPullParser.START_TAG) continue;
        WidgetHost.Attrs a = WidgetHost.Attrs.read(res, Xml.asAttributeSet(parser), ATTRS);
        try {
          switch (parser.getName()) {
            case "capability":
              capability = into.computeIfAbsent(a.string(android.R.attr.name), n -> new Capability());
              capability.name = a.string(android.R.attr.name);
              capability.declaredOn = component;
              break;
            case "intent":
              if (capability != null) {
                intent = new Fulfilment();
                intent.action = a.string(android.R.attr.action);
                intent.targetPackage = a.string(android.R.attr.targetPackage);
                intent.targetClass = a.string(android.R.attr.targetClass);
                intent.data = a.string(android.R.attr.data);
                capability.intents.add(intent);
              }
              break;
            case "url-template":
              if (intent != null) intent.urlTemplate = a.string(android.R.attr.value);
              break;
            case "parameter":
              if (intent != null) {
                Parameter parameter = new Parameter();
                parameter.name = a.string(android.R.attr.name);
                parameter.key = a.string(android.R.attr.key);
                parameter.required = a.bool(android.R.attr.required, false);
                intent.parameters.add(parameter);
              }
              break;
            case "shortcut":
              shortcut = parser.getAttributeValue(ANDROID, "shortcutId");
              break;
            case "capability-binding":
              if (shortcut != null) {
                String key = a.string(android.R.attr.key);
                Capability target = into.computeIfAbsent(key, n -> new Capability());
                if (target.name == null) target.name = key;
                bound = new LinkedHashMap<>();
                target.shortcuts.put(shortcut, bound);
              }
              break;
            case "parameter-binding":
              if (bound != null) bound.put(a.string(android.R.attr.key), a.string(android.R.attr.value));
              break;
            default:
              break;
          }
        } finally {
          a.recycle();
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("cannot read the shortcuts of " + component.getClassName(), e);
    }
  }

  /** The {@code assistant} command. */
  JSONObject command(JSONObject c) throws JSONException {
    Map<String, Capability> capabilities = capabilities();
    if (!c.has("invoke")) {
      JSONArray list = new JSONArray();
      for (Capability capability : capabilities.values()) list.put(describe(capability));
      return new Protocol.Json().put("capabilities", list).object();
    }
    String name = c.getString("invoke");
    Capability capability = capabilities.get(name);
    if (capability == null) {
      throw new IllegalArgumentException(
          "the app declares no capability " + name + "; it has " + capabilities.keySet());
    }
    Map<String, String> params = new LinkedHashMap<>();
    JSONObject given = c.optJSONObject("params");
    if (given != null) {
      for (Iterator<String> keys = given.keys(); keys.hasNext(); ) {
        String key = keys.next();
        params.put(key, given.getString(key));
      }
    }
    for (Fulfilment fulfilment : capability.intents) {
      Intent intent = intent(fulfilment, params);
      if (intent == null) continue;
      JSONObject started = navigator.startActivity(intent, -1);
      return new Protocol.Json()
          .put("capability", name)
          .put("intent", Intents.toJson(intent))
          .put("result", started)
          .object();
    }
    throw new IllegalArgumentException(
        "no intent of " + name + " has its required parameters among " + params.keySet());
  }

  /** The intent [fulfilment] builds from [params], or null when a required one is missing. */
  private Intent intent(Fulfilment fulfilment, Map<String, String> params) {
    Map<String, String> byKey = new LinkedHashMap<>();
    for (Parameter parameter : fulfilment.parameters) {
      String value = params.get(parameter.name);
      if (value == null && parameter.key != null) value = params.get(parameter.key);
      if (value == null) {
        if (parameter.required) return null;
        continue;
      }
      byKey.put(parameter.key != null ? parameter.key : parameter.name, value);
    }
    Intent intent = new Intent(fulfilment.action != null ? fulfilment.action : Intent.ACTION_VIEW);
    String pkg = fulfilment.targetPackage != null ? fulfilment.targetPackage : app.getPackageName();
    if (fulfilment.targetClass != null) intent.setComponent(new ComponentName(pkg, fulfilment.targetClass));
    else intent.setPackage(pkg);
    if (fulfilment.urlTemplate != null) {
      intent.setData(Uri.parse(UrlTemplates.expand(fulfilment.urlTemplate, byKey)));
    } else {
      if (fulfilment.data != null) intent.setData(Uri.parse(fulfilment.data));
      for (Map.Entry<String, String> e : byKey.entrySet()) intent.putExtra(e.getKey(), e.getValue());
    }
    // As Assistant starts it: in the app's task, from outside the app.
    return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  }

  private static JSONObject describe(Capability capability) throws JSONException {
    JSONArray intents = new JSONArray();
    for (Fulfilment fulfilment : capability.intents) {
      JSONArray parameters = new JSONArray();
      for (Parameter parameter : fulfilment.parameters) {
        parameters.put(
            new Protocol.Json()
                .put("name", parameter.name)
                .put("key", parameter.key)
                .put("required", parameter.required)
                .object());
      }
      intents.put(
          new Protocol.Json()
              .put("action", fulfilment.action)
              .put("targetClass", fulfilment.targetClass)
              .put("urlTemplate", fulfilment.urlTemplate)
              .put("data", fulfilment.data)
              .put("parameters", parameters)
              .object());
    }
    JSONObject shortcuts = new JSONObject();
    for (Map.Entry<String, Map<String, String>> e : capability.shortcuts.entrySet()) {
      shortcuts.put(e.getKey(), new JSONObject(e.getValue()));
    }
    return new Protocol.Json()
        .put("name", capability.name)
        .put(
            "activity",
            capability.declaredOn == null ? null : capability.declaredOn.getClassName())
        .put("intents", intents)
        .put("shortcuts", shortcuts)
        .object();
  }
}
