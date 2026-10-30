package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Xml;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/**
 * App shortcuts, as a launcher shows them on a long press of an icon: the static ones the manifest
 * declares ({@code android.app.shortcuts} metadata on a launcher activity, parsed through the app's
 * resources), and the dynamic ones the app publishes through {@code ShortcutManager} (Robolectric's
 * shadow keeps them). A shortcut the app pins with {@code requestPinShortcut} becomes an icon on
 * the home screen. Opening a shortcut starts its intents as the system does, the first with {@code
 * NEW_TASK | CLEAR_TASK}.
 */
final class Shortcuts {

  /** One shortcut, from the manifest or the app. */
  static final class Shortcut {
    final String id;
    final String source;
    final ComponentName activity;
    final CharSequence shortLabel;
    final CharSequence longLabel;
    final boolean enabled;
    final Drawable icon;
    final List<Intent> intents;

    Shortcut(
        String id,
        String source,
        ComponentName activity,
        CharSequence shortLabel,
        CharSequence longLabel,
        boolean enabled,
        Drawable icon,
        List<Intent> intents) {
      this.id = id;
      this.source = source;
      this.activity = activity;
      this.shortLabel = shortLabel;
      this.longLabel = longLabel;
      this.enabled = enabled;
      this.icon = icon;
      this.intents = intents;
    }

    CharSequence label() {
      return longLabel != null && longLabel.length() > 0 ? longLabel : shortLabel;
    }
  }

  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  private final HomeScreen home;
  private final Set<String> pinnedSeen = new LinkedHashSet<>();

  Shortcuts(Application app, Navigator navigator, Protocol protocol, HomeScreen home) {
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    this.home = home;
  }

  /** The control-channel commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    switch (name) {
      case "shortcuts":
        {
          String action = c.optString("action", "list");
          ComponentName activity = c.has("activity") ? activity(c.getString("activity")) : null;
          if (action.equals("open")) {
            home.showShortcuts(activity != null ? activity : firstLauncher());
            return describe(activity);
          }
          if (!action.equals("list")) {
            throw new IllegalArgumentException("shortcuts takes list or open, not " + action);
          }
          return describe(activity);
        }
      case "shortcut":
        // `id` is the command's own: the shortcut is `shortcutId`.
        return open(find(c.getString("shortcutId")));
      default:
        return null;
    }
  }

  /** One simulator cycle: a shortcut the app pinned since the last turns into a home icon. */
  void cycle() {
    for (ShortcutInfo info : manager().getPinnedShortcuts()) {
      if (!pinnedSeen.add(info.getId())) continue;
      Shortcut shortcut = fromApp(info, "pinned");
      home.addIcon(
          new HomeScreen.Icon(
              "shortcut", shortcut.activity, shortcut.id, shortcut.shortLabel, shortcut.icon));
      protocol.event(
          "shortcut",
          new Protocol.Json()
              .put("state", "pinned")
              .put("shortcutId", shortcut.id)
              .put("label", string(shortcut.shortLabel))
              .object());
    }
  }

  private ShortcutManager manager() {
    return app.getSystemService(ShortcutManager.class);
  }

  /** The shortcuts a long press on [activity]'s icon shows: static first, then dynamic. */
  List<Shortcut> forActivity(ComponentName activity) {
    List<Shortcut> all = new ArrayList<>(manifest(activity));
    ComponentName main = firstLauncher();
    for (ShortcutInfo info : manager().getDynamicShortcuts()) {
      ComponentName target = info.getActivity() != null ? info.getActivity() : main;
      if (activity == null || activity.equals(target)) all.add(fromApp(info, "dynamic"));
    }
    return all;
  }

  /** Every shortcut of the app, pinned ones included. */
  private List<Shortcut> everything() {
    List<Shortcut> all = new ArrayList<>();
    for (ResolveInfo launcher : home.launchables()) {
      all.addAll(
          manifest(
              new ComponentName(launcher.activityInfo.packageName, launcher.activityInfo.name)));
    }
    for (ShortcutInfo info : manager().getDynamicShortcuts()) all.add(fromApp(info, "dynamic"));
    for (ShortcutInfo info : manager().getPinnedShortcuts()) all.add(fromApp(info, "pinned"));
    return all;
  }

  Shortcut find(String id) {
    for (Shortcut shortcut : everything()) if (shortcut.id.equals(id)) return shortcut;
    throw new IllegalArgumentException("no shortcut " + id + " (see shortcuts)");
  }

  /** Starts [shortcut]'s intents as the system does when a launcher opens one. */
  JSONObject open(Shortcut shortcut) {
    if (!shortcut.enabled)
      throw new IllegalStateException("shortcut " + shortcut.id + " is disabled");
    if (shortcut.intents.isEmpty())
      throw new IllegalStateException("shortcut " + shortcut.id + " has no intent");
    home.closeOverlay();
    JSONArray started = new JSONArray();
    for (int i = 0; i < shortcut.intents.size(); i++) {
      Intent intent = new Intent(shortcut.intents.get(i));
      if (i == 0) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      started.put(navigator.startActivity(intent, -1));
    }
    protocol.event(
        "shortcut",
        new Protocol.Json()
            .put("state", "opened")
            .put("shortcutId", shortcut.id)
            .put("source", shortcut.source)
            .object());
    return new Protocol.Json().put("shortcut", describe(shortcut)).put("started", started).object();
  }

  ComponentName firstLauncher() {
    List<ResolveInfo> launchers = home.launchables();
    if (launchers.isEmpty()) return null;
    ActivityInfo info = launchers.get(0).activityInfo;
    return new ComponentName(info.packageName, info.name);
  }

  private ComponentName activity(String name) {
    for (ResolveInfo launcher : home.launchables()) {
      ActivityInfo info = launcher.activityInfo;
      if (info.name.equals(name) || info.name.endsWith("." + name) || info.name.endsWith(name)) {
        return new ComponentName(info.packageName, info.name);
      }
    }
    throw new IllegalArgumentException("no launcher activity " + name);
  }

  private Shortcut fromApp(ShortcutInfo info, String source) {
    List<Intent> intents = new ArrayList<>();
    Intent[] all = info.getIntents();
    if (all != null) for (Intent intent : all) intents.add(intent);
    Drawable icon = null;
    ComponentName activity = info.getActivity() != null ? info.getActivity() : firstLauncher();
    try {
      if (activity != null) icon = app.getPackageManager().getActivityIcon(activity);
    } catch (PackageManager.NameNotFoundException e) {
      icon = null;
    }
    return new Shortcut(
        info.getId(),
        source,
        activity,
        info.getShortLabel(),
        info.getLongLabel(),
        info.isEnabled(),
        icon,
        intents);
  }

  // ---------------------------------------------------------------------------------------------
  // Static shortcuts from the manifest

  private static final String ANDROID = "http://schemas.android.com/apk/res/android";

  private static final int[] SHORTCUT_ATTRS = {
    android.R.attr.enabled,
    android.R.attr.icon,
    android.R.attr.shortcutId,
    android.R.attr.shortcutShortLabel,
    android.R.attr.shortcutLongLabel,
  };

  private static final int[] INTENT_ATTRS = {
    android.R.attr.action,
    android.R.attr.data,
    android.R.attr.mimeType,
    android.R.attr.targetClass,
    android.R.attr.targetPackage,
  };

  /** [activity]'s {@code <shortcuts>} metadata, or none. */
  List<Shortcut> manifest(ComponentName activity) {
    List<Shortcut> shortcuts = new ArrayList<>();
    if (activity == null) return shortcuts;
    Bundle meta;
    try {
      meta =
          app.getPackageManager().getActivityInfo(activity, PackageManager.GET_META_DATA).metaData;
    } catch (PackageManager.NameNotFoundException e) {
      return shortcuts;
    }
    int xml = meta == null ? 0 : meta.getInt("android.app.shortcuts");
    if (xml == 0) return shortcuts;
    Resources res = app.getResources();
    try (XmlResourceParser parser = res.getXml(xml)) {
      String id = null;
      CharSequence shortLabel = null;
      CharSequence longLabel = null;
      boolean enabled = true;
      Drawable icon = null;
      List<Intent> intents = null;
      int type;
      while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
        if (type == XmlPullParser.START_TAG && parser.getName().equals("shortcut")) {
          WidgetHost.Attrs a =
              WidgetHost.Attrs.read(res, Xml.asAttributeSet(parser), SHORTCUT_ATTRS);
          try {
            id = a.string(android.R.attr.shortcutId);
            shortLabel = a.text(android.R.attr.shortcutShortLabel);
            longLabel = a.text(android.R.attr.shortcutLongLabel);
            enabled = a.bool(android.R.attr.enabled, true);
            int iconRes = a.resource(android.R.attr.icon);
            icon = iconRes == 0 ? null : res.getDrawable(iconRes, null);
          } finally {
            a.recycle();
          }
          intents = new ArrayList<>();
        } else if (type == XmlPullParser.START_TAG
            && parser.getName().equals("intent")
            && intents != null) {
          intents.add(intent(res, parser));
        } else if (type == XmlPullParser.START_TAG
            && parser.getName().equals("categories")
            && intents != null
            && !intents.isEmpty()) {
          String category = parser.getAttributeValue(ANDROID, "name");
          if (category != null) intents.get(intents.size() - 1).addCategory(category);
        } else if (type == XmlPullParser.END_TAG
            && parser.getName().equals("shortcut")
            && id != null) {
          shortcuts.add(
              new Shortcut(
                  id, "manifest", activity, shortLabel, longLabel, enabled, icon, intents));
          id = null;
          intents = null;
        }
      }
    } catch (Exception e) {
      protocol.event(
          "error",
          new Protocol.Json()
              .put("kind", "shortcuts")
              .put("activity", activity.getClassName())
              .put("message", String.valueOf(e))
              .object());
    }
    return shortcuts;
  }

  private Intent intent(Resources res, XmlResourceParser parser) {
    WidgetHost.Attrs a = WidgetHost.Attrs.read(res, Xml.asAttributeSet(parser), INTENT_ATTRS);
    try {
      Intent intent = new Intent(a.string(android.R.attr.action));
      String pkg = a.string(android.R.attr.targetPackage);
      String cls = a.string(android.R.attr.targetClass);
      if (cls != null)
        intent.setComponent(new ComponentName(pkg != null ? pkg : app.getPackageName(), cls));
      else if (pkg != null) intent.setPackage(pkg);
      String data = a.string(android.R.attr.data);
      String mime = a.string(android.R.attr.mimeType);
      if (data != null && mime != null) intent.setDataAndType(Uri.parse(data), mime);
      else if (data != null) intent.setData(Uri.parse(data));
      else if (mime != null) intent.setType(mime);
      return intent;
    } finally {
      a.recycle();
    }
  }

  // ---------------------------------------------------------------------------------------------

  private JSONObject describe(ComponentName activity) {
    JSONArray list = new JSONArray();
    for (Shortcut shortcut : activity == null ? everything() : forActivity(activity)) {
      list.put(describe(shortcut));
    }
    return new Protocol.Json().put("shortcuts", list).object();
  }

  JSONObject describe(Shortcut shortcut) {
    JSONArray intents = new JSONArray();
    for (Intent intent : shortcut.intents) intents.put(Intents.toJson(intent));
    return new Protocol.Json()
        .put("shortcutId", shortcut.id)
        .put("source", shortcut.source)
        .put("shortLabel", string(shortcut.shortLabel))
        .put("longLabel", string(shortcut.longLabel))
        .put("enabled", shortcut.enabled)
        .put("activity", shortcut.activity == null ? null : shortcut.activity.getClassName())
        .put("intents", intents)
        .object();
  }

  private static String string(CharSequence value) {
    return value == null ? null : value.toString();
  }
}
