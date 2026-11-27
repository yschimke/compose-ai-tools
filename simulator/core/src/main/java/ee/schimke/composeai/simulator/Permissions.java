package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPackageManager;

/**
 * Runtime permission requests, answered in place of the system's dialog. What the permission
 * controller decides on its own is decided as on a device ({@link PermissionModel}): a permission
 * already granted is not asked about again, one missing from the manifest is denied, one denied
 * twice is denied without asking, and so on. What a device would ask the user is answered by that
 * permission's own answer if one was given, else by the policy: {@code grant} (a user tapping
 * "Allow"), {@code deny}, or {@code ask}, which reports the request as a {@code permissions} event
 * and holds it until the {@code permissions} command answers it. Install-time permissions are
 * granted from the start, as on a device.
 */
final class Permissions {

  /** Hands a request's result to the activity that asked, as the dialog's result. */
  interface Delivery {
    void deliver(Activity requester, int requestCode, String[] names, int[] results);
  }

  private final Application app;
  private final ShadowApplication shadowApp;
  private final Protocol protocol;
  private final Delivery delivery;
  private PermissionModel model;

  /** {@code grant}, {@code deny} or {@code ask}. */
  private String policy = "grant";

  /** Per-permission answers, overriding the policy. */
  private final Map<String, Boolean> answers = new LinkedHashMap<>();

  /** Requests waiting for the agent's answer, under the {@code ask} policy. */
  private final List<Request> pending = new ArrayList<>();

  private int nextRequestId = 1;

  /** Every permission whose state has been set: the manifest's, and any other named. */
  private final Set<String> known = new LinkedHashSet<>();

  private static final class Request {
    final int id;
    final String[] names;
    final int requestCode;
    final Activity requester;
    final List<PermissionModel.Decision> decisions;
    final Map<String, PermissionModel.Answer> preset = new LinkedHashMap<>();

    Request(
        int id,
        String[] names,
        int requestCode,
        Activity requester,
        List<PermissionModel.Decision> decisions) {
      this.id = id;
      this.names = names;
      this.requestCode = requestCode;
      this.requester = requester;
      this.decisions = decisions;
    }

    List<String> asking() {
      List<String> names = new ArrayList<>(PermissionModel.waiting(decisions));
      names.removeAll(preset.keySet());
      return names;
    }
  }

  /**
   * The APK's own {@code targetSdkVersion}. Robolectric's {@code ApplicationInfo} reports 23 for an
   * APK (it reads the SDK levels from a source manifest, which an APK launch has none of), and the
   * platform's permission rules are keyed on the real one.
   */
  static volatile int apkTargetSdk;

  static int targetSdk(Application app) {
    return apkTargetSdk > 0 ? apkTargetSdk : app.getApplicationInfo().targetSdkVersion;
  }

  Permissions(Application app, Protocol protocol, Delivery delivery) {
    this.app = app;
    this.shadowApp = Shadow.extract(app);
    this.protocol = protocol;
    this.delivery = delivery;
  }

  /** Built on first use: the manifest and the install-time grants are in place by then. */
  private PermissionModel model() {
    if (model != null) return model;
    PackageManager pm = app.getPackageManager();
    List<String> requested = requestedPermissions(app);
    model =
        new PermissionModel(
            requested,
            name -> isRuntime(pm, name),
            Build.VERSION.SDK_INT,
            targetSdk(app));
    for (String name : requested) {
      known.add(name);
      model.set(name, app.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED);
    }
    return model;
  }

  private static List<String> requestedPermissions(Application app) {
    try {
      PackageInfo info =
          app.getPackageManager()
              .getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS);
      if (info.requestedPermissions != null) return Arrays.asList(info.requestedPermissions);
    } catch (PackageManager.NameNotFoundException e) {
      // Nothing requested.
    }
    return Collections.emptyList();
  }

  /**
   * Grants what a device grants at install: every requested permission that is not a runtime one.
   * Robolectric starts with all of them denied, so {@code INTERNET} or {@code FOREGROUND_SERVICE}
   * checks fail. Runs before the application's {@code onCreate()}.
   */
  static void grantInstallTime(Application app) {
    PackageManager pm = app.getPackageManager();
    PackageInfo info;
    try {
      info = pm.getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS);
    } catch (PackageManager.NameNotFoundException e) {
      return;
    }
    if (info.requestedPermissions == null) return;
    ShadowApplication shadowApp = Shadow.extract(app);
    for (String name : info.requestedPermissions) {
      if (!isRuntime(pm, name)) shadowApp.grantPermissions(name);
    }
  }

  /** A runtime permission: the platform's, or one the app defines as dangerous. */
  static boolean isRuntime(PackageManager pm, String name) {
    // Health Connect's are all runtime; ADD_VOICEMAIL is the one outside `android.permission.`.
    if (PermissionModel.isPlatform(name)) return PermissionModel.isPlatformRuntime(name);
    try {
      return pm.getPermissionInfo(name, 0).getProtection() == PermissionInfo.PROTECTION_DANGEROUS;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  /**
   * A request from [requester] for [names] (with [requestCode]; -1 when no result is expected):
   * what the controller decides alone is decided, the rest answered by the agent's answers or the
   * policy, or held for the agent under {@code ask}.
   */
  void request(String[] names, int requestCode, Activity requester) {
    PermissionModel model = model();
    Request r =
        new Request(nextRequestId++, names, requestCode, requester, model.triage(names));
    boolean ask = false;
    for (String name : PermissionModel.waiting(r.decisions)) {
      Boolean answer = answers.get(name);
      if (answer != null) {
        r.preset.put(name, answer ? PermissionModel.Answer.GRANT : PermissionModel.Answer.DENY);
      } else if (policy.equals("ask")) {
        ask = true;
      } else {
        r.preset.put(
            name,
            policy.equals("grant") ? PermissionModel.Answer.GRANT : PermissionModel.Answer.DENY);
      }
    }
    sync();
    if (!ask) {
      finish(r, r.preset);
      return;
    }
    pending.add(r);
    protocol.event(
        "permissions",
        new Protocol.Json()
            .put("state", "waiting")
            .put("requestId", r.id)
            .put("requestCode", requestCode)
            .put("ask", new JSONArray(r.asking()))
            .put("results", results(r, false))
            .object());
  }

  private void finish(Request r, Map<String, PermissionModel.Answer> answers) {
    pending.remove(r);
    model().answer(r.decisions, answers);
    sync();
    int[] codes = new int[r.names.length];
    JSONArray granted = new JSONArray();
    JSONArray denied = new JSONArray();
    for (int i = 0; i < r.names.length; i++) {
      boolean g = r.decisions.get(i).granted;
      codes[i] = g ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
      (g ? granted : denied).put(r.names[i]);
    }
    protocol.event(
        "permissions",
        new Protocol.Json()
            .put("state", "answered")
            .put("requestId", r.id)
            .put("requestCode", r.requestCode)
            .put("granted", granted)
            .put("denied", denied)
            .put("results", results(r, true))
            .object());
    if (r.requester == null || r.requestCode < 0 || r.requester.isDestroyed()) return;
    delivery.deliver(r.requester, r.requestCode, r.names, codes);
  }

  /** Each permission of [r] once: its result and how it was reached. */
  private JSONArray results(Request r, boolean answered) {
    JSONArray results = new JSONArray();
    Set<String> seen = new HashSet<>();
    for (PermissionModel.Decision d : r.decisions) {
      if (!seen.add(d.name)) continue;
      if (d.waiting() && !answered) continue;
      results.put(
          new Protocol.Json()
              .put("permission", d.name)
              .put("granted", d.granted)
              .put("by", d.how.json)
              .put("reason", d.reason)
              .put("rationale", model().rationale(d.name))
              .put("dontAskAgain", model().dontAskAgain(d.name))
              .object());
    }
    return results;
  }

  /**
   * The {@code permissions} command. {@code policy} ({@code grant}, {@code deny} or {@code ask})
   * answers later requests; {@code grant} and {@code deny} name permissions to answer that way
   * from now on, and change their state now too (a deny revokes; a grant also lifts "don't ask
   * again", as Settings does); {@code answer} answers a waiting request ({@code requestId}'s, else
   * the oldest): {@code grant}, {@code deny}, {@code dismiss}, {@code approximate}, {@code
   * selected}, or an object of permission → {@code grant} / {@code deny} / {@code dismiss}.
   * Returns the policy, the answers, the waiting requests, and the state of every permission the
   * manifest requests.
   */
  JSONObject command(JSONObject c) throws JSONException {
    model();
    prune();
    if (c.optBoolean("reset", false)) reset();
    if (c.has("policy")) {
      String p = c.getString("policy");
      if (!p.equals("grant") && !p.equals("deny") && !p.equals("ask")) {
        throw new IllegalArgumentException("policy is grant, deny or ask, not " + p);
      }
      policy = p;
    }
    answer(c.optJSONArray("grant"), true);
    answer(c.optJSONArray("deny"), false);
    if (c.has("answer")) answerRequest(c);
    if (!policy.equals("ask")) {
      // Requests held under `ask` are answered by the policy that replaced it.
      for (Request r : new ArrayList<>(pending)) {
        Map<String, PermissionModel.Answer> all = new LinkedHashMap<>(r.preset);
        all.putAll(PermissionModel.expand(r.asking(), policy));
        finish(r, all);
      }
    }
    sync();
    return state();
  }

  private void answerRequest(JSONObject c) throws JSONException {
    Request target = null;
    for (Request r : pending) {
      if (!c.has("requestId") || c.getInt("requestId") == r.id) {
        target = r;
        break;
      }
    }
    if (target == null) {
      throw new IllegalStateException(
          c.has("requestId")
              ? "no permission request " + c.get("requestId") + " is waiting"
              : "no permission request is waiting");
    }
    List<String> asking = target.asking();
    Map<String, PermissionModel.Answer> all = new LinkedHashMap<>(target.preset);
    Object answer = c.get("answer");
    if (answer instanceof JSONObject) {
      JSONObject each = (JSONObject) answer;
      Map<String, PermissionModel.Answer> given = new LinkedHashMap<>();
      for (Iterator<String> keys = each.keys(); keys.hasNext(); ) {
        String key = keys.next();
        given.put(fullName(key), PermissionModel.Answer.parse(each.getString(key)));
      }
      List<String> missing = new ArrayList<>();
      for (String name : asking) {
        if (given.containsKey(name)) all.put(name, given.get(name));
        else missing.add(name);
      }
      if (!missing.isEmpty()) throw new IllegalArgumentException("no answer for " + missing);
    } else {
      all.putAll(PermissionModel.expand(asking, String.valueOf(answer)));
    }
    finish(target, all);
  }

  private static String fullName(String name) {
    return name.contains(".") ? name : "android.permission." + name;
  }

  /**
   * As after a fresh install ({@code pm reset-permissions}, {@code pm clear}): no answers, the
   * granting policy, every runtime permission the manifest requests revoked and never asked
   * about.
   */
  void reset() {
    answers.clear();
    policy = "grant";
    model().reset();
    sync();
  }

  private void answer(JSONArray names, boolean granted) throws JSONException {
    if (names == null) return;
    for (int i = 0; i < names.length(); i++) {
      String name = names.getString(i);
      answers.put(name, granted);
      known.add(name);
      model().set(name, granted);
    }
  }

  /** Applies the model's state to Robolectric: grants and the rationale flag. */
  private void sync() {
    ShadowPackageManager pm = Shadow.extract(app.getPackageManager());
    for (PermissionModel.Decision d : pendingDecisions()) known.add(d.name);
    for (String name : known) {
      if (model.isGranted(name)) {
        shadowApp.grantPermissions(name);
      } else {
        shadowApp.denyPermissions(name);
      }
      pm.setShouldShowRequestPermissionRationale(name, model.rationale(name));
    }
  }

  private List<PermissionModel.Decision> pendingDecisions() {
    List<PermissionModel.Decision> all = new ArrayList<>();
    for (Request r : pending) all.addAll(r.decisions);
    return all;
  }

  /** Drops waiting requests whose activity is gone (finished, force-stopped): nothing to answer. */
  private void prune() {
    pending.removeIf(r -> r.requester != null && r.requester.isDestroyed());
  }

  JSONObject state() {
    PermissionModel model = model();
    prune();
    JSONObject current = new JSONObject();
    JSONArray rationale = new JSONArray();
    JSONArray dontAskAgain = new JSONArray();
    try {
      for (String name : requestedPermissions(app)) {
        boolean granted = app.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED;
        current.put(name, granted ? "granted" : "denied");
        if (model.rationale(name)) rationale.put(name);
        if (model.dontAskAgain(name)) dontAskAgain.put(name);
      }
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    JSONObject overrides = new JSONObject();
    try {
      for (Map.Entry<String, Boolean> entry : answers.entrySet()) {
        overrides.put(entry.getKey(), entry.getValue() ? "grant" : "deny");
      }
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    JSONArray waiting = new JSONArray();
    for (Request r : pending) {
      waiting.put(
          new Protocol.Json()
              .put("requestId", r.id)
              .put("requestCode", r.requestCode)
              .put("ask", new JSONArray(r.asking()))
              .object());
    }
    return new Protocol.Json()
        .put("policy", policy)
        .put("answers", overrides)
        .put("pending", waiting)
        .put("state", current)
        .put("rationale", rationale)
        .put("dontAskAgain", dontAskAgain)
        .object();
  }
}
