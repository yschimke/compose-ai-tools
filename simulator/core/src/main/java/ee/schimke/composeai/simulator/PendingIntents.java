package ee.schimke.composeai.simulator;

import android.app.PendingIntent;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPendingIntent;

/**
 * Sends a {@code PendingIntent} on the app's behalf, as the system does when a person taps
 * something the app handed over (a widget's click, a pin request's callback). Robolectric's own
 * {@code send} only records an activity or service start, and delivers a broadcast to runtime
 * receivers alone; this goes through {@link Navigator}, so an activity lands on the back stack, a
 * broadcast reaches manifest receivers too (Glance's action receiver is one), and a service starts.
 */
final class PendingIntents {

  /**
   * Sends [pending], with [fillIn] filled into its intent unless the pending intent is immutable,
   * and reports what it became.
   */
  static JSONObject send(PendingIntent pending, Intent fillIn, Navigator navigator) {
    ShadowPendingIntent shadow = Shadow.extract(pending);
    if (shadow.isCanceled()) throw new IllegalStateException("the PendingIntent was cancelled");
    Intent[] saved = shadow.getSavedIntents();
    if (saved == null || saved.length == 0) {
      throw new IllegalStateException("the PendingIntent carries no intent");
    }
    Intent[] intents = new Intent[saved.length];
    for (int i = 0; i < saved.length; i++) intents[i] = new Intent(saved[i]);
    Intent last = intents[intents.length - 1];
    if (fillIn != null && !shadow.isImmutable()) last.fillIn(fillIn, 0);
    Protocol.Json json = new Protocol.Json().put("intent", Intents.toJson(last));
    if (shadow.isActivity()) {
      JSONArray started = new JSONArray();
      for (Intent intent : intents) {
        started.put(navigator.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), -1));
      }
      return json.put("kind", "activity").put("result", started).object();
    }
    if (shadow.isBroadcast()) {
      return json.put("kind", "broadcast").put("result", navigator.broadcast(last)).object();
    }
    if (shadow.isService() || shadow.isForegroundService()) {
      return json.put("kind", "service").put("result", navigator.startService(last)).object();
    }
    throw new IllegalStateException("unknown PendingIntent kind for " + last);
  }

  private PendingIntents() {}
}
