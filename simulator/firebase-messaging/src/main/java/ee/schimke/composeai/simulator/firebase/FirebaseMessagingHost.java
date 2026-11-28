package ee.schimke.composeai.simulator.firebase;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.RemoteException;
import ee.schimke.composeai.simulator.SimulatorHooks;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Local platform emulation for Firebase Cloud Messaging (FCM), which has no official Firebase Local
 * Emulator.
 *
 * <p>Implements the real Android IPC boundaries used by {@code firebase-messaging} and {@code
 * play-services-cloud-messaging}:
 *
 * <ul>
 *   <li>Intercepts GMS C2DM registration intents ({@code com.google.android.c2dm.intent.REGISTER} /
 *       {@code UNREGISTER} sent to {@code com.google.android.gms}) and replies with a deterministic
 *       simulator registration token via {@code google.messenger} (modern {@code Rpc}) or {@code
 *       com.google.android.c2dm.intent.REGISTRATION} broadcast.
 *   <li>Delivers simulated push and data messages ({@code com.google.android.c2dm.intent.RECEIVE})
 *       and token rotations ({@code com.google.firebase.messaging.NEW_TOKEN}) directly to the app's
 *       manifest {@code com.google.firebase.MESSAGING_EVENT} service.
 * </ul>
 */
public final class FirebaseMessagingHost {

  static final String GMS_PACKAGE = "com.google.android.gms";
  static final String ACTION_C2DM_REGISTER = "com.google.android.c2dm.intent.REGISTER";
  static final String ACTION_C2DM_UNREGISTER = "com.google.android.c2dm.intent.UNREGISTER";
  static final String ACTION_IID_TOKEN_REQUEST = "com.google.iid.TOKEN_REQUEST";
  static final String ACTION_C2DM_REGISTRATION = "com.google.android.c2dm.intent.REGISTRATION";
  static final String ACTION_C2DM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";
  static final String ACTION_MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT";
  static final String ACTION_NEW_TOKEN = "com.google.firebase.messaging.NEW_TOKEN";
  static final String BASE_FCM_SERVICE = "com.google.firebase.messaging.FirebaseMessagingService";
  static final String EXTRA_MESSENGER = "google.messenger";
  static final String EXTRA_REGISTRATION_ID = "registration_id";
  static final String EXTRA_UNREGISTERED = "unregistered";

  private static final int MAX_HISTORY = 100;

  private final Application app;
  private final AtomicInteger tokenSeq = new AtomicInteger(1);
  private final AtomicInteger messageSeq = new AtomicInteger(1);
  private final List<JSONObject> sentMessages = new CopyOnWriteArrayList<>();
  private volatile String currentToken;
  private volatile int registrationCount;

  public FirebaseMessagingHost(Application app) {
    this.app = app;
    String pkg = app != null ? app.getPackageName() : "app";
    this.currentToken = "sim-fcm-token:" + pkg + ":" + tokenSeq.get();
  }

  /** Installs GMS C2DM IPC interceptors on {@link SimulatorHooks}. */
  public void install() {
    SimulatorHooks.addBroadcastInterceptor(this::interceptC2dmBroadcast);
    SimulatorHooks.addStartServiceInterceptor(this::interceptC2dmStartService);
  }

  public String currentToken() {
    return currentToken;
  }

  public int registrationCount() {
    return registrationCount;
  }

  public JSONArray sentMessagesJson() {
    JSONArray array = new JSONArray();
    for (JSONObject msg : sentMessages) array.put(msg);
    return array;
  }

  public void clearHistory() {
    sentMessages.clear();
  }

  /**
   * Rotates the simulator FCM token and delivers {@code com.google.firebase.messaging.NEW_TOKEN} to
   * the app's {@code FirebaseMessagingService} (triggering {@code onNewToken}).
   */
  public JSONObject rotateToken(String explicitToken) {
    String pkg = app != null ? app.getPackageName() : "app";
    String next =
        explicitToken != null && !explicitToken.trim().isEmpty()
            ? explicitToken.trim()
            : "sim-fcm-token:" + pkg + ":" + tokenSeq.incrementAndGet();
    currentToken = next;

    ComponentName target = resolveMessagingService();
    boolean delivered = false;
    String deliveryPath = "none";
    if (target != null && app != null) {
      Intent intent = new Intent(ACTION_NEW_TOKEN);
      intent.setComponent(target);
      intent.putExtra("token", next);
      deliveryPath = deliverToMessagingService(intent);
      delivered = true;
    }

    JSONObject out = new JSONObject();
    try {
      out.put("token", next);
      out.put("delivered", delivered);
      out.put("deliveryPath", deliveryPath);
      if (target != null) out.put("service", target.flattenToShortString());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  /**
   * Delivers a simulated FCM push/data message to the app's {@code FirebaseMessagingService} via
   * {@code ServiceStarter.startMessagingService()} (so {@code getStartCommandIntent()} dequeues
   * the payload) with fallback to direct {@code startService()}.
   */
  public JSONObject sendMessage(JSONObject request) {
    String messageId =
        request != null && request.has("messageId")
            ? request.optString("messageId")
            : "sim-fcm-msg-" + messageSeq.getAndIncrement();
    String from =
        request != null && request.has("from") ? request.optString("from") : "simulator";
    String collapseKey = request != null ? request.optString("collapseKey", null) : null;
    int ttl = request != null ? request.optInt("ttl", 86400) : 86400;
    long sentTime = System.currentTimeMillis();

    JSONObject data = request != null ? request.optJSONObject("data") : null;
    JSONObject notification = request != null ? request.optJSONObject("notification") : null;

    ComponentName target = resolveMessagingService();
    boolean delivered = false;
    String deliveryPath = "none";
    if (target != null && app != null) {
      Intent intent = new Intent(ACTION_C2DM_RECEIVE);
      intent.setComponent(target);
      intent.putExtra("google.message_id", messageId);
      intent.putExtra("from", from);
      intent.putExtra("google.sent_time", sentTime);
      intent.putExtra("google.ttl", ttl);
      if (collapseKey != null && !collapseKey.isEmpty()) {
        intent.putExtra("collapse_key", collapseKey);
      }
      if (data != null) {
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
          String key = keys.next();
          Object val = data.opt(key);
          intent.putExtra(key, val == null ? "" : String.valueOf(val));
        }
      }
      if (notification != null) {
        intent.putExtra("gcm.notification.e", "1");
        putOptionalExtra(intent, "gcm.notification.title", notification.optString("title", null));
        putOptionalExtra(intent, "gcm.notification.body", notification.optString("body", null));
        putOptionalExtra(
            intent,
            "gcm.notification.android_channel_id",
            notification.optString("channelId", null));
        putOptionalExtra(intent, "gcm.notification.tag", notification.optString("tag", null));
        putOptionalExtra(
            intent, "gcm.notification.click_action", notification.optString("clickAction", null));
        putOptionalExtra(intent, "gcm.notification.sound", notification.optString("sound", null));
        putOptionalExtra(intent, "gcm.notification.image", notification.optString("imageUrl", null));
      }
      deliveryPath = deliverToMessagingService(intent);
      delivered = true;
    }

    JSONObject record = new JSONObject();
    try {
      record.put("messageId", messageId);
      record.put("from", from);
      record.put("sentTime", sentTime);
      record.put("delivered", delivered);
      record.put("deliveryPath", deliveryPath);
      if (target != null) record.put("service", target.flattenToShortString());
      if (data != null) record.put("data", data);
      if (notification != null) record.put("notification", notification);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    sentMessages.add(record);
    while (sentMessages.size() > MAX_HISTORY) sentMessages.remove(0);
    return record;
  }

  /**
   * Routes {@code intent} through {@code com.google.firebase.messaging.ServiceStarter}'s internal
   * queue so {@code FirebaseMessagingService.getStartCommandIntent()} dequeues it, falling back to
   * {@code app.startService(intent)} when the SDK class is not present.
   */
  String deliverToMessagingService(Intent intent) {
    ClassLoader loader = app.getClassLoader();
    if (loader != null) {
      try {
        Class<?> starterClass =
            Class.forName("com.google.firebase.messaging.ServiceStarter", false, loader);
        java.lang.reflect.Method getInstance = starterClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object starter = getInstance.invoke(null);
        java.lang.reflect.Method start =
            starterClass.getDeclaredMethod(
                "startMessagingService", android.content.Context.class, Intent.class);
        start.setAccessible(true);
        start.invoke(starter, app, intent);
        return "serviceStarter";
      } catch (Throwable ignored) {
        // Fall through to direct startService for non-SDK services.
      }
    }
    app.startService(intent);
    return "startService";
  }

  /**
   * Resolves the app's manifest service for {@code com.google.firebase.MESSAGING_EVENT}, preferring
   * the app's own {@code FirebaseMessagingService} subclass over the library's default service.
   */
  public ComponentName resolveMessagingService() {
    if (app == null) return null;
    PackageManager pm = app.getPackageManager();
    if (pm == null) return null;
    Intent query = new Intent(ACTION_MESSAGING_EVENT);
    query.setPackage(app.getPackageName());
    List<ResolveInfo> resolved = pm.queryIntentServices(query, 0);
    if (resolved == null || resolved.isEmpty()) return null;
    ComponentName fallback = null;
    for (ResolveInfo info : resolved) {
      if (info.serviceInfo == null) continue;
      ComponentName cn = new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
      if (!BASE_FCM_SERVICE.equals(info.serviceInfo.name)) {
        return cn;
      }
      if (fallback == null) fallback = cn;
    }
    return fallback;
  }

  boolean interceptC2dmBroadcast(Intent intent) {
    if (!isC2dmRegistrationIntent(intent)) return false;
    handleRegistration(intent);
    return true;
  }

  ComponentName interceptC2dmStartService(Intent intent) {
    if (!isC2dmRegistrationIntent(intent)) return null;
    handleRegistration(intent);
    return new ComponentName(GMS_PACKAGE, "com.google.android.gms.gcm.GcmRegistrationService");
  }

  static boolean isC2dmRegistrationIntent(Intent intent) {
    if (intent == null) return false;
    String action = intent.getAction();
    if (!ACTION_C2DM_REGISTER.equals(action)
        && !ACTION_C2DM_UNREGISTER.equals(action)
        && !ACTION_IID_TOKEN_REQUEST.equals(action)) {
      return false;
    }
    String pkg =
        intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage();
    return pkg == null || GMS_PACKAGE.equals(pkg);
  }

  private void handleRegistration(Intent request) {
    boolean unregister =
        ACTION_C2DM_UNREGISTER.equals(request.getAction())
            || "1".equals(request.getStringExtra("delete"));
    Intent reply = new Intent(ACTION_C2DM_REGISTRATION);
    if (app != null) reply.setPackage(app.getPackageName());
    String kid = request.getStringExtra("kid");
    if (unregister) {
      String value = app != null ? app.getPackageName() : "app";
      reply.putExtra(EXTRA_UNREGISTERED, formatCorrelatedValue(kid, value));
    } else {
      registrationCount++;
      reply.putExtra(EXTRA_REGISTRATION_ID, formatCorrelatedValue(kid, currentToken));
    }

    Parcelable messengerExtra = request.getParcelableExtra(EXTRA_MESSENGER);
    if (messengerExtra instanceof Messenger) {
      Message msg = Message.obtain();
      msg.obj = reply;
      Bundle data = reply.getExtras();
      if (data != null) msg.setData(new Bundle(data));
      try {
        ((Messenger) messengerExtra).send(msg);
        return;
      } catch (RemoteException ignored) {
        // Fall back to broadcast delivery.
      }
    }
    if (app != null) {
      app.sendBroadcast(reply);
    }
  }

  static String formatCorrelatedValue(String kid, String value) {
    if (kid != null && kid.startsWith("|ID|")) {
      return kid + ":" + value;
    }
    return value;
  }

  private static void putOptionalExtra(Intent intent, String key, String value) {
    if (value != null && !value.isEmpty()) intent.putExtra(key, value);
  }

  public JSONObject statusJson() {
    JSONObject out = new JSONObject();
    try {
      out.put("token", currentToken);
      out.put("registrations", registrationCount);
      out.put("sentCount", sentMessages.size());
      ComponentName service = resolveMessagingService();
      if (service != null) out.put("service", service.flattenToShortString());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  /** Exposed for unit tests inspecting delivered messages. */
  List<JSONObject> sentMessages() {
    return new ArrayList<>(sentMessages);
  }
}
