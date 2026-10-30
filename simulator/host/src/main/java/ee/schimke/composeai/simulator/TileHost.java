package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/**
 * The tiles and Wear Widgets carousel's model: what the app offers, read from its manifest, which
 * of them the agent (or the person, on the carousel's add page) has added, and the pages that makes.
 *
 * <ul>
 *   <li>A tile is a service with the {@code androidx.wear.tiles.action.BIND_TILE_PROVIDER} action;
 *       its {@code androidx.wear.tiles.PREVIEW} meta-data is the picker image. Each added tile is a
 *       whole page, drawn by {@link TileClient} with the protolayout renderer.
 *   <li>A Wear Widget is a service with {@code androidx.glance.wear.action.BIND_WIDGET_PROVIDER}
 *       (Glance Wear's {@code GlanceWearWidgetService}, which also answers the tile action; it
 *       counts as a widget), or one whose meta-data points at a {@code *widget_provider*} XML. The
 *       XML's {@code label}, {@code description} and {@code previewImage} describe it. Added widgets
 *       share one page, a scrolling list.
 * </ul>
 *
 * Pages, in order: the widgets (when any are added), each tile, then the add page listing what is
 * available and not yet added.
 */
final class TileHost {

  static final String TILE_ACTION = "androidx.wear.tiles.action.BIND_TILE_PROVIDER";
  static final String WIDGET_ACTION = "androidx.glance.wear.action.BIND_WIDGET_PROVIDER";
  static final String TILE_PREVIEW = "androidx.wear.tiles.PREVIEW";
  /** Launch-time tiles and widgets ({@code --tile}), comma separated components. */
  static final String PROPERTY = "composeai.simulator.tiles";

  static final String WIDGETS_PAGE = "widgets";
  static final String ADD_PAGE = "add";

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

    boolean tile() {
      return kind.equals("tile");
    }

    String key() {
      return component.flattenToString();
    }
  }

  private final Application app;
  private final Navigator navigator;
  private final SystemData systemData;
  private final TileClient.Listener listener;
  private final WidgetClient.Listener widgetListener;
  private final Map<String, WidgetClient> widgetClients = new LinkedHashMap<>();
  private final Map<String, Item> added = new LinkedHashMap<>();
  private final Map<String, TileClient> clients = new LinkedHashMap<>();
  private int nextTileId = 1;
  private int index;

  TileHost(
      Application app,
      Navigator navigator,
      SystemData systemData,
      TileClient.Listener listener,
      WidgetClient.Listener widgetListener) {
    this.app = app;
    this.navigator = navigator;
    this.systemData = systemData;
    this.listener = listener;
    this.widgetListener = widgetListener;
  }

  /** Adds the {@code --tile} components; unknown ones are reported, not fatal. */
  void addFromLaunch(Protocol protocol) {
    String value = System.getProperty(PROPERTY);
    if (value == null || value.isEmpty()) return;
    for (String component : value.split(",")) {
      try {
        add(component.trim());
      } catch (RuntimeException e) {
        protocol.event(
            "error",
            new Protocol.Json().put("kind", "tile").put("message", e.getMessage()).object());
      }
    }
    index = 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Discovery

  /** Every tile and widget the app's manifest declares, widgets first. */
  List<Item> available() {
    PackageManager pm = app.getPackageManager();
    Map<String, Item> items = new LinkedHashMap<>();
    Set<String> widgets = new LinkedHashSet<>();
    for (ResolveInfo info : services(WIDGET_ACTION)) {
      widgets.add(info.serviceInfo.name);
      Item item = widget(info.serviceInfo);
      items.put(item.key(), item);
    }
    try {
      android.content.pm.PackageInfo pkg =
          pm.getPackageInfo(
              app.getPackageName(), PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
      if (pkg.services != null) {
        for (ServiceInfo service : pkg.services) {
          if (widgets.contains(service.name) || widgetXml(service) == 0) continue;
          widgets.add(service.name);
          Item item = widget(service);
          items.put(item.key(), item);
        }
      }
    } catch (PackageManager.NameNotFoundException e) {
      // No package info: the action queries are all there is.
    }
    for (ResolveInfo info : services(TILE_ACTION)) {
      ServiceInfo service = info.serviceInfo;
      if (widgets.contains(service.name)) continue;
      Bundle meta = service.metaData;
      Item item =
          new Item(
              new ComponentName(service.packageName, service.name),
              "tile",
              label(service),
              null,
              meta == null ? 0 : meta.getInt(TILE_PREVIEW, 0));
      items.put(item.key(), item);
    }
    return new ArrayList<>(items.values());
  }

  private List<ResolveInfo> services(String action) {
    List<ResolveInfo> found =
        app.getPackageManager()
            .queryIntentServices(
                new Intent(action).setPackage(app.getPackageName()),
                PackageManager.GET_META_DATA);
    List<ResolveInfo> services = new ArrayList<>();
    if (found != null) for (ResolveInfo info : found) if (info.serviceInfo != null) services.add(info);
    return services;
  }

  /** The service's {@code *widget_provider*} XML resource, or 0. */
  private int widgetXml(ServiceInfo service) {
    if (service.metaData == null) return 0;
    for (String key : service.metaData.keySet()) {
      Object value = service.metaData.get(key);
      if (!(value instanceof Integer) || (Integer) value == 0) continue;
      try {
        if (app.getResources().getResourceEntryName((Integer) value).contains("widget_provider")) {
          return (Integer) value;
        }
      } catch (RuntimeException e) {
        // Not a resource.
      }
    }
    return 0;
  }

  private Item widget(ServiceInfo service) {
    String label = null;
    String description = null;
    int preview = 0;
    int xml = widgetXml(service);
    if (xml != 0) {
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

  List<Item> widgets() {
    List<Item> widgets = new ArrayList<>();
    for (Item item : added.values()) if (!item.tile()) widgets.add(item);
    return widgets;
  }

  List<Item> notAdded() {
    List<Item> items = new ArrayList<>();
    for (Item item : available()) if (!added.containsKey(item.key())) items.add(item);
    return items;
  }

  /** The pages: {@link #WIDGETS_PAGE}, each tile's component key, then {@link #ADD_PAGE}. */
  List<String> pages() {
    List<String> pages = new ArrayList<>();
    if (!widgets().isEmpty()) pages.add(WIDGETS_PAGE);
    for (Item item : added.values()) if (item.tile()) pages.add(item.key());
    pages.add(ADD_PAGE);
    return pages;
  }

  int index() {
    return Math.max(0, Math.min(index, pages().size() - 1));
  }

  String page() {
    return pages().get(index());
  }

  Item item(String key) {
    return added.get(key);
  }

  TileClient client(Item item) {
    return clients.get(item.key());
  }

  WidgetClient widgetClient(Item item) {
    return widgetClients.get(item.key());
  }

  /**
   * Adds [component] (a class name, short {@code .Name}, or {@code pkg/.Name}) and moves to its
   * page; a tile is bound and told it was added, as the watch's tile manager does.
   */
  Item add(String component) {
    Item item = find(component);
    if (!added.containsKey(item.key())) {
      added.put(item.key(), item);
      if (item.tile()) {
        TileClient client =
            new TileClient(app, navigator, systemData, item.component, nextTileId++, listener);
        clients.put(item.key(), client);
        try {
          client.event("TileAddEvent", "onTileAddEvent");
        } catch (ReflectiveOperationException | RuntimeException e) {
          // The tile still renders; the event is best effort, as on a watch.
        }
      } else {
        widgetClients.put(
            item.key(),
            new WidgetClient(
                app, navigator, systemData, item.component, nextTileId++, widgetListener));
      }
    }
    index = pages().indexOf(item.tile() ? item.key() : WIDGETS_PAGE);
    return item;
  }

  Item remove(String component) {
    Item item = find(component);
    if (added.remove(item.key()) == null) {
      throw new IllegalArgumentException(component + " is not in the carousel");
    }
    TileClient client = clients.remove(item.key());
    if (client != null) client.release();
    WidgetClient widget = widgetClients.remove(item.key());
    if (widget != null) widget.release();
    index = Math.min(index, pages().size() - 1);
    return item;
  }

  /** Moves to page [i], or the page holding [component]. */
  void show(int i) {
    List<String> pages = pages();
    if (i < 0 || i >= pages.size()) {
      throw new IllegalArgumentException("the carousel has " + pages.size() + " pages, no " + i);
    }
    index = i;
  }

  void show(String component) {
    Item item = find(component);
    if (!added.containsKey(item.key())) {
      throw new IllegalArgumentException(component + " is not in the carousel: tiles op:add first");
    }
    index = pages().indexOf(item.tile() ? item.key() : WIDGETS_PAGE);
  }

  boolean next() {
    if (index() + 1 >= pages().size()) return false;
    index = index() + 1;
    return true;
  }

  boolean previous() {
    if (index() <= 0) return false;
    index = index() - 1;
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
          || item.component.getClassName().endsWith("." + component.replaceFirst("^\\.", ""))) {
        return item;
      }
    }
    List<String> names = new ArrayList<>();
    for (Item item : available) names.add(item.component.getClassName());
    throw new IllegalArgumentException(
        "the app has no tile or widget " + component + " (it has " + names + ")");
  }

  JSONObject describe(Item item) {
    Protocol.Json json =
        new Protocol.Json()
            .put("component", item.component.getClassName())
            .put("kind", item.kind)
            .put("label", item.label)
            .put("description", item.description)
            .put("preview", item.preview != 0)
            .put("added", added.containsKey(item.key()));
    TileClient client = clients.get(item.key());
    if (client != null) {
      json.put("tileId", client.tileId)
          .put("rendered", client.view() != null)
          .put("requests", client.requests())
          .put("error", client.error());
    }
    WidgetClient widget = widgetClients.get(item.key());
    if (widget != null) {
      json.put("instanceId", widget.instanceId)
          .put("rendered", widget.view() != null)
          .put("error", widget.error());
    }
    return json.object();
  }

  JSONObject list() {
    JSONArray pages = new JSONArray();
    for (String page : pages()) {
      Item item = added.get(page);
      pages.put(item == null ? page : item.component.getClassName());
    }
    JSONArray all = new JSONArray();
    for (Item item : available()) all.put(describe(item));
    return new Protocol.Json()
        .put("pages", pages)
        .put("index", index())
        .put("page", pages.opt(index()))
        .put("available", all)
        .put("systemData", systemData.describe())
        .object();
  }
}
