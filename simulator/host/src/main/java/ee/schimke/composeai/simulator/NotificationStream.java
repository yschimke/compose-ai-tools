package ee.schimke.composeai.simulator;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPendingIntent;

/**
 * The notifications the app has posted, as a stream a watch's (or phone's) system UI shows, and the
 * things a person does to one: tap it, press an action, reply inline, dismiss it.
 *
 * <p>The model is the shadow notification manager's active notifications, compared each cycle with
 * the last, so posts, updates and cancels go out as {@code notification} events however the app
 * made them. A notification a device would not show — its channel blocked, or {@code
 * POST_NOTIFICATIONS} denied — is still listed, with {@code shown: false} and the reason, since an
 * agent testing that path wants to see it was dropped. {@code setTimeoutAfter} cancels as the
 * system does.
 *
 * <p>Works with or without the Wear system UI ({@link WearSystemUi}), which draws this stream as
 * cards. Runs on the main thread, inside the sandbox.
 */
final class NotificationStream {

  /** One active notification. */
  static final class Item {
    final String key;
    final String tag;
    final int id;
    final Notification notification;
    final long postTime;

    Item(StatusBarNotification sbn) {
      this.tag = sbn.getTag();
      this.id = sbn.getId();
      this.key = keyOf(tag, id);
      this.notification = sbn.getNotification();
      this.postTime = sbn.getPostTime();
    }
  }

  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  final OngoingActivities ongoing;
  /** The active notifications as of the last cycle, by key, in posting order. */
  private Map<String, Item> active = new LinkedHashMap<>();
  /** Snoozed: cancelled now and posted again at the uptime given. */
  private final Map<String, Snoozed> snoozed = new LinkedHashMap<>();

  private final List<Runnable> listeners = new ArrayList<>();

  private static final class Snoozed {
    final Item item;
    final long until;

    Snoozed(Item item, long until) {
      this.item = item;
      this.until = until;
    }
  }

  NotificationStream(Application app, Navigator navigator, Protocol protocol) {
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    this.ongoing = new OngoingActivities(app, this);
  }

  /** Called on the main thread after each change to the stream, for the system UI to redraw. */
  void onChange(Runnable listener) {
    listeners.add(listener);
  }

  /** The {@code key} a command names a notification by: its id, or {@code tag:id}. */
  static String keyOf(String tag, int id) {
    return tag == null ? Integer.toString(id) : tag + ":" + id;
  }

  // ---------------------------------------------------------------------------------------------
  // Model

  /** One cycle: reports what was posted, updated or removed, and applies timeouts and snoozes. */
  void cycle() {
    long now = SystemClock.uptimeMillis();
    for (Map.Entry<String, Snoozed> e : new ArrayList<>(snoozed.entrySet())) {
      if (e.getValue().until > now) continue;
      snoozed.remove(e.getKey());
      Item item = e.getValue().item;
      manager().notify(item.tag, item.id, item.notification);
    }
    Map<String, Item> next = new LinkedHashMap<>();
    for (StatusBarNotification sbn : activeNotifications()) {
      Item item = new Item(sbn);
      next.put(item.key, item);
    }
    boolean changed = false;
    for (Item item : next.values()) {
      Item before = active.get(item.key);
      if (before == null) {
        protocol.event("notification", describe(item, "posted"));
        changed = true;
      } else if (before.notification != item.notification) {
        protocol.event("notification", describe(item, "updated"));
        changed = true;
      }
    }
    for (Item item : active.values()) {
      if (!next.containsKey(item.key)) {
        protocol.event(
            "notification",
            new Protocol.Json().put("state", "removed").put("key", item.key).object());
        changed = true;
      }
    }
    active = next;
    long wall = System.currentTimeMillis();
    for (Item item : new ArrayList<>(active.values())) {
      long timeout = Build.VERSION.SDK_INT >= 26 ? item.notification.getTimeoutAfter() : 0;
      if (timeout > 0 && item.postTime + timeout <= wall) {
        manager().cancel(item.tag, item.id);
      }
    }
    if (changed) for (Runnable listener : listeners) listener.run();
  }

  private StatusBarNotification[] activeNotifications() {
    StatusBarNotification[] all = manager().getActiveNotifications();
    return all == null ? new StatusBarNotification[0] : all;
  }

  private NotificationManager manager() {
    return app.getSystemService(NotificationManager.class);
  }

  /** The active notifications, oldest first. */
  List<Item> items() {
    return new ArrayList<>(active.values());
  }

  /** Those a device would show, newest first, as the stream orders them. */
  List<Item> shown() {
    List<Item> shown = new ArrayList<>();
    for (Item item : active.values()) if (whyHidden(item) == null) shown.add(0, item);
    return shown;
  }

  Item item(String key) {
    Item item = active.get(key);
    if (item == null) {
      // Not yet seen by a cycle: posted by the command before this one.
      for (StatusBarNotification sbn : activeNotifications()) {
        if (keyOf(sbn.getTag(), sbn.getId()).equals(key)) return new Item(sbn);
      }
      throw new IllegalArgumentException(
          "no active notification " + key + " (active: " + active.keySet() + ")");
    }
    return item;
  }

  /** Why a device would not show [item], or null when it would. */
  String whyHidden(Item item) {
    if (Build.VERSION.SDK_INT >= 33
        && Permissions.targetSdk(app) >= 33
        && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      return "POST_NOTIFICATIONS denied";
    }
    if (!manager().areNotificationsEnabled()) return "notifications disabled for the app";
    NotificationChannel channel = channel(item.notification);
    if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
      return "channel " + channel.getId() + " is blocked";
    }
    return null;
  }

  NotificationChannel channel(Notification notification) {
    if (Build.VERSION.SDK_INT < 26 || notification.getChannelId() == null) return null;
    return manager().getNotificationChannel(notification.getChannelId());
  }

  /** Whether [item] interrupts: a heads-up on a device. */
  boolean headsUp(Item item) {
    NotificationChannel channel = channel(item.notification);
    if (channel != null) return channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
    @SuppressWarnings("deprecation")
    int priority = item.notification.priority;
    return priority >= Notification.PRIORITY_HIGH;
  }

  /** The children of a group summary, oldest first. */
  List<Item> children(Item summary) {
    List<Item> children = new ArrayList<>();
    String group = summary.notification.getGroup();
    if (group == null || !isSummary(summary)) return children;
    for (Item item : active.values()) {
      if (item != summary && group.equals(item.notification.getGroup())) children.add(item);
    }
    return children;
  }

  static boolean isSummary(Item item) {
    return (item.notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
  }

  /** Whether [item] is a group child that a summary for its group collapses. */
  boolean collapsedUnderSummary(Item item) {
    String group = item.notification.getGroup();
    if (group == null || isSummary(item)) return false;
    for (Item other : active.values()) {
      if (other != item && isSummary(other) && group.equals(other.notification.getGroup())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The actions the device offers for [notification]: on a watch its {@code WearableExtender}
   * actions when it has any, which replace the phone's, else (and on every other form factor) its
   * own.
   */
  static List<Notification.Action> actions(Notification notification) {
    List<Notification.Action> actions = new ArrayList<>();
    if (FormFactor.current() == FormFactor.WEAR && wearableActions(notification)) {
      @SuppressWarnings("deprecation")
      List<Notification.Action> wearable =
          new Notification.WearableExtender(notification).getActions();
      actions.addAll(wearable);
    } else if (notification.actions != null) {
      for (Notification.Action action : notification.actions) actions.add(action);
    }
    return actions;
  }

  static boolean wearableActions(Notification notification) {
    @SuppressWarnings("deprecation")
    List<Notification.Action> wearable = new Notification.WearableExtender(notification).getActions();
    return wearable != null && !wearable.isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Commands

  /** The control-channel commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    switch (name) {
      case "notifications":
        return list();
      case "notification":
        return act(c);
      case "ongoing":
        return ongoing.command(c);
      default:
        return null;
    }
  }

  static boolean changesUi(String name) {
    return name.equals("notification") || name.equals("ongoing");
  }

  private JSONObject list() {
    JSONArray array = new JSONArray();
    for (Item item : items()) array.put(describe(item, null));
    return new Protocol.Json()
        .put("notifications", array)
        .put("ongoing", ongoing.list())
        .object();
  }

  private JSONObject act(JSONObject c) throws JSONException {
    String op = c.optString("op", "tap");
    Item item = item(keyArgument(c));
    switch (op) {
      case "tap":
        return tap(item);
      case "action":
        return action(item, c.getInt("index"), null);
      case "reply":
        return action(item, c.optInt("index", -1), c.getString("text"));
      case "dismiss":
        return dismiss(item);
      case "snooze":
        {
          long ms = c.optLong("ms", 60_000);
          snoozed.put(item.key, new Snoozed(item, SystemClock.uptimeMillis() + ms));
          manager().cancel(item.tag, item.id);
          return new Protocol.Json().put("key", item.key).put("snoozedMs", ms).object();
        }
      default:
        throw new IllegalArgumentException(
            "notification op is tap, action, reply, dismiss or snooze, not " + op);
    }
  }

  private static String keyArgument(JSONObject c) throws JSONException {
    Object key = c.get("key");
    return key instanceof Number ? Integer.toString(((Number) key).intValue()) : key.toString();
  }

  /** Taps [item]: sends its content intent, and cancels it when it auto-cancels. */
  JSONObject tap(Item item) {
    PendingIntent content = item.notification.contentIntent;
    if (content == null) {
      throw new IllegalStateException("notification " + item.key + " has no content intent");
    }
    JSONObject sent = send(content, null);
    if ((item.notification.flags & Notification.FLAG_AUTO_CANCEL) != 0) {
      manager().cancel(item.tag, item.id);
    }
    return new Protocol.Json().put("key", item.key).put("sent", sent).object();
  }

  /**
   * Presses action [index] of [item] (as {@link #actions} lists them). With [reply] the action's
   * {@code RemoteInput}s are filled in, as the watch's reply screen does, and an index of -1 picks
   * the first action that takes input.
   */
  JSONObject action(Item item, int index, String reply) {
    List<Notification.Action> actions = actions(item.notification);
    if (index < 0 && reply != null) {
      for (int i = 0; i < actions.size(); i++) {
        RemoteInput[] inputs = actions.get(i).getRemoteInputs();
        if (inputs != null && inputs.length > 0) {
          index = i;
          break;
        }
      }
      if (index < 0) {
        throw new IllegalArgumentException("notification " + item.key + " has no reply action");
      }
    }
    if (index < 0 || index >= actions.size()) {
      throw new IllegalArgumentException(
          "notification " + item.key + " has " + actions.size() + " actions, no " + index);
    }
    Notification.Action action = actions.get(index);
    if (action.actionIntent == null) {
      throw new IllegalStateException("action " + index + " has no intent");
    }
    Intent fillIn = null;
    if (reply != null) {
      RemoteInput[] inputs = action.getRemoteInputs();
      if (inputs == null || inputs.length == 0) {
        throw new IllegalArgumentException("action " + index + " takes no reply");
      }
      fillIn = new Intent();
      Bundle results = new Bundle();
      for (RemoteInput input : inputs) results.putCharSequence(input.getResultKey(), reply);
      RemoteInput.addResultsToIntent(inputs, fillIn, results);
    }
    JSONObject sent = send(action.actionIntent, fillIn);
    return new Protocol.Json()
        .put("key", item.key)
        .put("action", action.title == null ? null : action.title.toString())
        .put("reply", reply)
        .put("sent", sent)
        .object();
  }

  /**
   * Swipes [item] away: sends its delete intent and cancels it, unless it is ongoing, which a
   * person cannot dismiss.
   */
  JSONObject dismiss(Item item) {
    if ((item.notification.flags & Notification.FLAG_ONGOING_EVENT) != 0
        || (item.notification.flags & Notification.FLAG_NO_CLEAR) != 0) {
      return new Protocol.Json().put("key", item.key).put("dismissed", false)
          .put("reason", "ongoing").object();
    }
    JSONObject sent =
        item.notification.deleteIntent == null ? null : send(item.notification.deleteIntent, null);
    manager().cancel(item.tag, item.id);
    return new Protocol.Json().put("key", item.key).put("dismissed", true).put("sent", sent)
        .object();
  }

  /** Dismisses every notification a person could: the stream's "clear all". */
  JSONObject clearAll() {
    JSONArray cleared = new JSONArray();
    for (Item item : items()) {
      if (dismiss(item).optBoolean("dismissed")) cleared.put(item.key);
    }
    return new Protocol.Json().put("cleared", cleared).object();
  }

  /**
   * Sends [pending] as the system does for the app ({@link PendingIntents}), with [fillIn] merged
   * into its intent; an immutable one drops the fill-in, as on a device, and says so.
   */
  JSONObject send(PendingIntent pending, Intent fillIn) {
    JSONObject sent = PendingIntents.send(pending, fillIn, navigator);
    ShadowPendingIntent shadow = Shadow.extract(pending);
    if (fillIn != null && shadow.isImmutable()) {
      try {
        sent.put("fillInDropped", "the PendingIntent is immutable");
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
    }
    return sent;
  }

  // ---------------------------------------------------------------------------------------------
  // Description

  JSONObject describe(Item item, String state) {
    Notification n = item.notification;
    Bundle extras = n.extras;
    Protocol.Json json = new Protocol.Json();
    if (state != null) json.put("state", state);
    json.put("key", item.key);
    json.put("id", item.id);
    json.put("tag", item.tag);
    json.put("channel", n.getChannelId());
    json.put("category", n.category);
    json.put("group", n.getGroup());
    json.put("groupSummary", isSummary(item));
    json.put("ongoing", (n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
    json.put("title", text(extras, Notification.EXTRA_TITLE));
    json.put("text", text(extras, Notification.EXTRA_TEXT));
    json.put("bigText", text(extras, Notification.EXTRA_BIG_TEXT));
    json.put("subText", text(extras, Notification.EXTRA_SUB_TEXT));
    JSONArray messages = messages(n);
    if (messages.length() > 0) json.put("messages", messages);
    json.put("picture", extras != null && extras.get(Notification.EXTRA_PICTURE) != null);
    JSONArray actions = new JSONArray();
    for (Notification.Action action : actions(n)) actions.put(describe(action));
    json.put("actions", actions);
    json.put("wearableActions", wearableActions(n));
    json.put("contentIntent", n.contentIntent != null);
    json.put("deleteIntent", n.deleteIntent != null);
    json.put("headsUp", headsUp(item));
    if (Build.VERSION.SDK_INT >= 26 && n.getTimeoutAfter() > 0) {
      json.put("timeoutAfterMs", n.getTimeoutAfter());
    }
    String hidden = whyHidden(item);
    json.put("shown", hidden == null);
    if (hidden != null) json.put("hiddenBecause", hidden);
    JSONObject ongoingActivity = ongoing.describe(n);
    if (ongoingActivity != null) json.put("ongoingActivity", ongoingActivity);
    return json.object();
  }

  private static JSONObject describe(Notification.Action action) {
    JSONArray inputs = new JSONArray();
    RemoteInput[] remoteInputs = action.getRemoteInputs();
    if (remoteInputs != null) {
      for (RemoteInput input : remoteInputs) {
        JSONArray choices = new JSONArray();
        if (input.getChoices() != null) {
          for (CharSequence choice : input.getChoices()) choices.put(choice.toString());
        }
        inputs.put(
            new Protocol.Json()
                .put("key", input.getResultKey())
                .put("label", input.getLabel() == null ? null : input.getLabel().toString())
                .put("choices", choices)
                .put("freeForm", input.getAllowFreeFormInput())
                .object());
      }
    }
    return new Protocol.Json()
        .put("title", action.title == null ? null : action.title.toString())
        .put("remoteInputs", inputs)
        .object();
  }

  /** {@code MessagingStyle}'s messages, oldest first, as {@code {sender, text}}. */
  static JSONArray messages(Notification n) {
    JSONArray array = new JSONArray();
    if (n.extras == null) return array;
    Parcelable[] messages = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
    if (messages == null) return array;
    for (Parcelable p : messages) {
      if (!(p instanceof Bundle)) continue;
      Bundle m = (Bundle) p;
      CharSequence sender = m.getCharSequence("sender");
      if (sender == null && Build.VERSION.SDK_INT >= 28) {
        android.app.Person person = m.getParcelable("sender_person");
        if (person != null) sender = person.getName();
      }
      CharSequence text = m.getCharSequence("text");
      array.put(
          new Protocol.Json()
              .put("sender", sender == null ? null : sender.toString())
              .put("text", text == null ? null : text.toString())
              .object());
    }
    return array;
  }

  static String text(Bundle extras, String key) {
    if (extras == null) return null;
    CharSequence value = extras.getCharSequence(key);
    return value == null ? null : value.toString();
  }
}
