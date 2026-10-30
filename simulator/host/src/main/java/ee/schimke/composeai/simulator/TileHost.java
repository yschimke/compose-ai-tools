package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/**
 * The tiles and Wear Widgets carousel's model: what the app offers, read from its manifest, and
 * which of them the agent has added, in carousel order.
 *
 * <ul>
 *   <li>A tile is a service with the {@code androidx.wear.tiles.action.BIND_TILE_PROVIDER} action;
 *       its {@code androidx.wear.tiles.PREVIEW} meta-data is the picker image.
 *   <li>A Wear Widget is a service whose meta-data points at a {@code *widget_provider*} XML
 *       resource, as {@code androidx.glance.wear} generates; the XML's {@code label}, {@code
 *       description} and {@code previewImage} describe it.
 * </ul>
 *
 * <p>The carousel shows each added item as its picker card: label, kind and preview image. Drawing
 * the tile's own layout needs the protolayout renderer, which apps do not ship, and a widget's
 * Remote Compose document a View-based player; both are the next step (see {@code
 * docs/design/SIMULATOR_WEAR_LAUNCHER.md}), so a card says it is a preview.
 */
final class TileHost {

  static final String TILE_ACTION = "androidx.wear.tiles.action.BIND_TILE_PROVIDER";
  static final String TILE_PREVIEW = "androidx.wear.tiles.PREVIEW";
  /** Launch-time tiles and widgets ({@code --tile}), comma separated components. */
  static final String PROPERTY = "composeai.simulator.tiles";

  /** One tile or widget the app provides. */
  static final class Item {
    final ComponentName component;
    final String kind;
    final String label;
    final String description;
    /** The preview drawable's resource id, or 0. */
    final int preview;

    Item(ComponentName component, String kind, String label, String description, int preview) {
      this.component = component;
      this.kind = kind;
      this.label = label;
      this.description = description;
      this.preview = preview;
    }
  }

  private final Application app;
  private final Map<String, Item> added = new LinkedHashMap<>();
  private int index;

  TileHost(Application app) {
    this.app = app;
  }

  /** Adds the {@code --tile} components; unknown ones are reported, not fatal. */
  void addFromLaunch(Protocol protocol) {
    String value = System.getProperty(PROPERTY);
    if (value == null || value.isEmpty()) return;
    for (String component : value.split(",")) {
      try {
        add(component.trim());
      } catch (RuntimeException e) {
        protocol.event("error", new Protocol.Json().put("kind", "tile").put("message", e.getMessage())
            .object());
      }
    }
  }

  /** Every tile and widget the app's manifest declares. */
  List<Item> available() {
    List<Item> items = new ArrayList<>();
    PackageManager pm = app.getPackageManager();
    List<ResolveInfo> tiles =
        pm.queryIntentServices(
            new Intent(TILE_ACTION).setPackage(app.getPackageName()), PackageManager.GET_META_DATA);
    for (ResolveInfo info : tiles) {
      ServiceInfo service = info.serviceInfo;
      if (service == null) continue;
      Bundle meta = service.metaData;
      items.add(
          new Item(
              new ComponentName(service.packageName, service.name),
              "tile",
              label(service),
              null,
              meta == null ? 0 : meta.getInt(TILE_PREVIEW, 0)));
    }
    PackageInfo pkg;
    try {
      pkg =
          pm.getPackageInfo(
              app.getPackageName(), PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
    } catch (PackageManager.NameNotFoundException e) {
      return items;
    }
    if (pkg.services == null) return items;
    for (ServiceInfo service : pkg.services) {
      if (service.metaData == null) continue;
      for (String key : service.metaData.keySet()) {
        Object value = service.metaData.get(key);
        if (!(value instanceof Integer) || (Integer) value == 0) continue;
        String entry;
        try {
          entry = app.getResources().getResourceEntryName((Integer) value);
        } catch (RuntimeException e) {
          continue;
        }
        if (!entry.contains("widget_provider")) continue;
        items.add(widget(service, (Integer) value));
        break;
      }
    }
    return items;
  }

  private Item widget(ServiceInfo service, int xml) {
    String label = null;
    String description = null;
    int preview = 0;
    try (XmlResourceParser parser = app.getResources().getXml(xml)) {
      int event;
      while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
        if (event != XmlPullParser.START_TAG) continue;
        for (int i = 0; i < parser.getAttributeCount(); i++) {
          String name = parser.getAttributeName(i);
          if (name.equals("label")) label = attribute(parser, i);
          else if (name.equals("description")) description = attribute(parser, i);
          else if (name.equals("previewImage")) preview = parser.getAttributeResourceValue(i, 0);
        }
        break;
      }
    } catch (Exception e) {
      // An unreadable provider XML: the service's own label.
    }
    return new Item(
        new ComponentName(service.packageName, service.name),
        "widget",
        label != null ? label : label(service),
        description,
        preview);
  }

  private String attribute(XmlResourceParser parser, int i) {
    int res = parser.getAttributeResourceValue(i, 0);
    if (res != 0) {
      try {
        return app.getResources().getString(res);
      } catch (RuntimeException e) {
        return null;
      }
    }
    return parser.getAttributeValue(i);
  }

  private String label(ServiceInfo service) {
    CharSequence label = service.loadLabel(app.getPackageManager());
    return label == null ? service.name : label.toString();
  }

  Drawable preview(Item item) {
    if (item.preview == 0) return null;
    try {
      return app.getResources().getDrawable(item.preview, app.getTheme());
    } catch (RuntimeException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The carousel

  List<Item> added() {
    return new ArrayList<>(added.values());
  }

  int index() {
    return added.isEmpty() ? -1 : Math.min(index, added.size() - 1);
  }

  Item current() {
    int i = index();
    return i < 0 ? null : added().get(i);
  }

  /** Adds [component] (a class name, short {@code .Name}, or {@code pkg/.Name}) to the end. */
  Item add(String component) {
    Item item = find(component);
    added.put(item.component.flattenToString(), item);
    return item;
  }

  Item remove(String component) {
    Item item = find(component);
    if (added.remove(item.component.flattenToString()) == null) {
      throw new IllegalArgumentException(component + " is not in the carousel");
    }
    if (index >= added.size()) index = Math.max(0, added.size() - 1);
    return item;
  }

  void show(int i) {
    if (added.isEmpty()) throw new IllegalStateException("no tiles or widgets added");
    if (i < 0 || i >= added.size()) {
      throw new IllegalArgumentException("the carousel has " + added.size() + " items, no " + i);
    }
    index = i;
  }

  int indexOf(String component) {
    Item item = find(component);
    return added().indexOf(added.get(item.component.flattenToString()));
  }

  boolean next() {
    if (index() + 1 >= added.size()) return false;
    index++;
    return true;
  }

  boolean previous() {
    if (index() <= 0) return false;
    index--;
    return true;
  }

  private Item find(String component) {
    String pkg = app.getPackageName();
    ComponentName wanted =
        component.contains("/")
            ? ComponentName.unflattenFromString(component)
            : new ComponentName(pkg, component.startsWith(".") ? pkg + component : component);
    List<Item> available = available();
    for (Item item : available) {
      if (item.component.equals(wanted)
          || item.component.getClassName().endsWith("." + component)) {
        return item;
      }
    }
    List<String> names = new ArrayList<>();
    for (Item item : available) names.add(item.component.getClassName());
    throw new IllegalArgumentException(
        "the app has no tile or widget " + component + " (it has " + names + ")");
  }

  JSONObject describe(Item item) {
    return new Protocol.Json()
        .put("component", item.component.getClassName())
        .put("kind", item.kind)
        .put("label", item.label)
        .put("description", item.description)
        .put("preview", item.preview != 0)
        .put("added", added.containsKey(item.component.flattenToString()))
        .object();
  }

  JSONObject list() {
    JSONArray carousel = new JSONArray();
    for (Item item : added()) carousel.put(describe(item));
    JSONArray all = new JSONArray();
    for (Item item : available()) all.put(describe(item));
    return new Protocol.Json()
        .put("carousel", carousel)
        .put("index", index())
        .put("available", all)
        .object();
  }

  /** {@code tiles} ops that change only the model; {@link WearSystemUi} shows the result. */
  JSONObject command(String op, JSONObject c) throws JSONException {
    switch (op) {
      case "list":
        return list();
      case "add":
        add(c.getString("component"));
        return list();
      case "remove":
        remove(c.getString("component"));
        return list();
      default:
        return null;
    }
  }
}
