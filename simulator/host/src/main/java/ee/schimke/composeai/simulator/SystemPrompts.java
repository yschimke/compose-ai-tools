package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.speech.RecognizerIntent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBiometricManager;
import org.robolectric.shadows.ShadowBiometricPrompt;
import org.robolectric.shadows.ShadowKeyguardManager;
import org.robolectric.shadows.ShadowPackageManager;

/**
 * The system's prompts over the app, which the agent answers: the biometric prompt ({@code
 * BiometricPrompt}, androidx.biometric's on API 28+), the device-credential confirmation ({@code
 * KeyguardManager.createConfirmDeviceCredentialIntent}), and voice input ({@code
 * RecognizerIntent.ACTION_RECOGNIZE_SPEECH}). Robolectric has none of them: an authenticate call
 * waited for good and a voice-input start came to nothing.
 *
 * <p>Each prompt is a {@code prompt} event; under the {@code ask} policy (the default with a
 * control channel) it waits for the {@code biometric} or {@code speech} command's answer, and
 * under {@code cancel} (the default without one) it is cancelled at once, as a person backing out.
 * The {@code biometric} command can also answer every prompt with {@code succeed} as a policy,
 * unenroll the sensor ({@code enrolled}) and give the device a screen lock ({@code secure}).
 *
 * <p>Also here, as the smaller system services: {@code clipboard}, which reads and writes the
 * primary clip, and the {@code speak} events of {@link SimulatorShadowTextToSpeech}.
 */
final class SystemPrompts {

  /** The voice-input activity a device resolves {@code ACTION_RECOGNIZE_SPEECH} to. */
  static final String RECOGNIZER = "com.google.android.googlequicksearchbox";

  /** {@code KeyguardManager.ACTION_CONFIRM_DEVICE_CREDENTIAL}, hidden. */
  static final String CONFIRM_DEVICE_CREDENTIAL = "android.app.action.CONFIRM_DEVICE_CREDENTIAL";

  private static final class Waiting {
    final int id;
    final String kind;
    final JSONObject described;
    /** For a credential confirmation or voice input, the activity waiting for the result. */
    final Activity requester;

    final int requestCode;

    Waiting(int id, String kind, JSONObject described, Activity requester, int requestCode) {
      this.id = id;
      this.kind = kind;
      this.described = described;
      this.requester = requester;
      this.requestCode = requestCode;
    }
  }

  private final Application app;
  private final Protocol protocol;
  private final Navigator navigator;
  private final List<Waiting> waiting = new ArrayList<>();
  private int nextId = 1;
  private String biometricPolicy;
  private String speechPolicy;
  /** The authentication session last reported: one prompt object can be shown again. */
  private Object reported;

  SystemPrompts(Application app, Protocol protocol, Navigator navigator) {
    this.app = app;
    this.protocol = protocol;
    this.navigator = navigator;
    this.biometricPolicy = protocol.enabled() ? "ask" : "cancel";
    this.speechPolicy = protocol.enabled() ? "ask" : "cancel";
    navigator.addSystemUi(this::handle);
    SimulatorShadowTextToSpeech.events = event -> protocol.event("speak", event);
  }

  /** Before {@code Application.onCreate()}: voice input resolves, as with Google's app. */
  static void install(Context context) {
    ShadowPackageManager packages = Shadow.extract(context.getPackageManager());
    ApplicationInfo application = new ApplicationInfo();
    application.packageName = RECOGNIZER;
    application.flags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_INSTALLED;
    application.enabled = true;
    PackageInfo info = new PackageInfo();
    info.packageName = RECOGNIZER;
    info.applicationInfo = application;
    packages.installPackage(info);
    ComponentName component =
        new ComponentName(RECOGNIZER, "com.google.android.voicesearch.intentapi.IntentApiActivity");
    ActivityInfo activity = packages.addActivityIfNotPresent(component);
    activity.exported = true;
    activity.applicationInfo = application;
    packages.addOrUpdateActivity(activity);
    IntentFilter filter = new IntentFilter(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    filter.addCategory(Intent.CATEGORY_DEFAULT);
    packages.addIntentFilterForActivity(component, filter);
  }

  /** One cycle: reports a biometric prompt the app just showed, and answers it by policy. */
  void cycle() {
    if (Build.VERSION.SDK_INT < 28) return;
    BiometricPrompt prompt = ShadowBiometricPrompt.getCurrentPrompt();
    // Each authenticate() is a session of its own, even on a BiometricPrompt shown before.
    Object session =
        org.robolectric.util.ReflectionHelpers.getStaticField(
            ShadowBiometricPrompt.class, "currentAuthenticateSession");
    if (prompt == null || session == null || session == reported) return;
    reported = session;
    Protocol.Json json =
        new Protocol.Json()
            .put("title", text(prompt.getTitle()))
            .put("subtitle", text(prompt.getSubtitle()))
            .put("description", text(prompt.getDescription()))
            .put("negativeButton", text(prompt.getNegativeButtonText()));
    if (Build.VERSION.SDK_INT >= 30) json.put("authenticators", prompt.getAllowedAuthenticators());
    Waiting request = new Waiting(nextId++, "biometric", json.object(), null, -1);
    if (biometricPolicy.equals("ask")) {
      waiting.add(request);
      report(request, "waiting", null);
    } else {
      answerBiometric(request, biometricPolicy);
    }
  }

  private static String text(CharSequence text) {
    return text == null ? null : text.toString();
  }

  private boolean handle(Intent intent, int requestCode, Activity requester) {
    String action = intent.getAction();
    String kind;
    String policy;
    if (CONFIRM_DEVICE_CREDENTIAL.equals(action)) {
      kind = "deviceCredential";
      policy = biometricPolicy;
    } else if (RecognizerIntent.ACTION_RECOGNIZE_SPEECH.equals(action)) {
      kind = "speech";
      policy = speechPolicy;
    } else {
      return false;
    }
    Protocol.Json json = new Protocol.Json().put("intent", Intents.toJson(intent));
    if (kind.equals("speech")) {
      json.put("prompt", text(intent.getCharSequenceExtra(RecognizerIntent.EXTRA_PROMPT)))
          .put("language", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
          .put("maxResults", intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1));
    } else {
      json.put("title", text(intent.getCharSequenceExtra("android.app.extra.TITLE")))
          .put("description", text(intent.getCharSequenceExtra("android.app.extra.DESCRIPTION")));
    }
    Waiting request = new Waiting(nextId++, kind, json.object(), requester, requestCode);
    if (requestCode < 0 || requester == null) {
      report(request, "answered", "RESULT_CANCELED");
    } else if (policy.equals("ask")) {
      waiting.add(request);
      report(request, "waiting", null);
    } else if (kind.equals("deviceCredential") && policy.equals("succeed")) {
      deliver(request, Activity.RESULT_OK, null);
      report(request, "answered", "RESULT_OK");
    } else {
      deliver(request, Activity.RESULT_CANCELED, null);
      report(request, "answered", "RESULT_CANCELED");
    }
    return true;
  }

  private void report(Waiting request, String state, String answer) {
    JSONObject event = copy(request.described);
    try {
      event.put("requestId", request.id).put("kind", request.kind).put("state", state);
      if (answer != null) event.put("answer", answer);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    protocol.event("prompt", event);
  }

  private static JSONObject copy(JSONObject json) {
    try {
      return new JSONObject(json.toString());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private void deliver(Waiting request, int resultCode, Intent data) {
    if (request.requester == null || request.requestCode < 0) return;
    if (!navigator.isRunning(request.requester)) return;
    navigator.deliverResult(request.requester, request.requestCode, resultCode, data);
  }

  /** The prompt [id] waiting (or the oldest with -1), of one of [kinds]. */
  private Waiting take(int id, String... kinds) {
    for (Iterator<Waiting> it = waiting.iterator(); it.hasNext(); ) {
      Waiting request = it.next();
      if (!java.util.Arrays.asList(kinds).contains(request.kind)) continue;
      if (id < 0 || request.id == id) {
        it.remove();
        return request;
      }
    }
    return null;
  }

  private JSONArray pending(String... kinds) {
    JSONArray array = new JSONArray();
    for (Waiting request : waiting) {
      if (!java.util.Arrays.asList(kinds).contains(request.kind)) continue;
      JSONObject json = copy(request.described);
      try {
        array.put(json.put("requestId", request.id).put("kind", request.kind));
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
    }
    return array;
  }

  /** The commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    switch (name) {
      case "biometric":
        return biometric(c);
      case "speech":
        return speech(c);
      case "clipboard":
        return clipboard(c);
      default:
        return null;
    }
  }

  static boolean changesUi(String name) {
    return name.equals("biometric") || name.equals("speech");
  }

  private JSONObject biometric(JSONObject c) throws JSONException {
    if (c.has("enrolled")) {
      ShadowBiometricManager shadow = Shadow.extract(app.getSystemService(BiometricManager.class));
      shadow.setCanAuthenticate(c.getBoolean("enrolled"));
    }
    if (c.has("secure")) {
      ShadowKeyguardManager keyguard = Shadow.extract(app.getSystemService(KeyguardManager.class));
      keyguard.setIsDeviceSecure(c.getBoolean("secure"));
      keyguard.setIsKeyguardSecure(c.getBoolean("secure"));
    }
    if (c.has("policy")) {
      String policy = c.getString("policy");
      if (!java.util.Arrays.asList("ask", "succeed", "cancel").contains(policy)) {
        throw new IllegalArgumentException("biometric policy is ask, succeed or cancel, not " + policy);
      }
      biometricPolicy = policy;
    }
    if (c.has("answer")) {
      Object which = c.opt("requestId");
      Waiting request =
          take(which instanceof Number ? ((Number) which).intValue() : -1, "biometric", "deviceCredential");
      if (request == null) throw new IllegalArgumentException("no biometric prompt is waiting");
      String answer = c.getString("answer");
      if (request.kind.equals("deviceCredential")) {
        boolean ok = answer.equals("succeed");
        deliver(request, ok ? Activity.RESULT_OK : Activity.RESULT_CANCELED, null);
        report(request, "answered", ok ? "RESULT_OK" : "RESULT_CANCELED");
      } else {
        if (answer.equals("fail")) waiting.add(0, request);
        answerBiometric(request, answer);
      }
      return new Protocol.Json().put("answered", request.id).put("answer", answer).object();
    }
    KeyguardManager keyguard = app.getSystemService(KeyguardManager.class);
    Protocol.Json json =
        new Protocol.Json()
            .put("policy", biometricPolicy)
            .put("secure", keyguard.isDeviceSecure())
            .put("waiting", pending("biometric", "deviceCredential"));
    if (Build.VERSION.SDK_INT >= 30) {
      json.put(
          "canAuthenticate",
          app.getSystemService(BiometricManager.class)
              .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG));
    }
    return json.object();
  }

  /**
   * Answers the current biometric prompt: {@code succeed}, {@code fail} (a finger not recognised;
   * the prompt stays up), {@code cancel} (the person backed out), {@code negative} (the negative
   * button), {@code lockout} (too many attempts), or an error code.
   */
  private void answerBiometric(Waiting request, String answer) {
    switch (answer) {
      case "succeed":
        ShadowBiometricPrompt.authenticateCurrentSessionSuccessfully();
        break;
      case "fail":
        ShadowBiometricPrompt.failCurrentSessionOnce();
        break;
      case "cancel":
        ShadowBiometricPrompt.authenticateCurrentSessionWithError(
            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, "Authentication cancelled");
        break;
      case "negative":
        // BiometricConstants.BIOMETRIC_ERROR_NEGATIVE_BUTTON, which androidx reports as such.
        ShadowBiometricPrompt.authenticateCurrentSessionWithError(13, "Cancel");
        break;
      case "lockout":
        ShadowBiometricPrompt.authenticateCurrentSessionWithError(
            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, "Too many attempts. Try again later.");
        break;
      default:
        int code;
        try {
          code = Integer.parseInt(answer);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(
              "answer a biometric prompt with succeed, fail, cancel, negative, lockout or an error"
                  + " code, not "
                  + answer);
        }
        ShadowBiometricPrompt.authenticateCurrentSessionWithError(code, "Error " + code);
    }
    report(request, answer.equals("fail") ? "waiting" : "answered", answer);
  }

  private JSONObject speech(JSONObject c) throws JSONException {
    if (c.has("policy")) {
      String policy = c.getString("policy");
      if (!policy.equals("ask") && !policy.equals("cancel")) {
        throw new IllegalArgumentException("speech policy is ask or cancel, not " + policy);
      }
      speechPolicy = policy;
    }
    if (c.has("text") || c.optBoolean("cancel", false)) {
      Object which = c.opt("requestId");
      Waiting request = take(which instanceof Number ? ((Number) which).intValue() : -1, "speech");
      if (request == null) throw new IllegalArgumentException("no voice input is waiting");
      if (c.optBoolean("cancel", false)) {
        deliver(request, Activity.RESULT_CANCELED, null);
        report(request, "answered", "RESULT_CANCELED");
        return new Protocol.Json().put("answered", request.id).put("answer", "RESULT_CANCELED").object();
      }
      ArrayList<String> results = new ArrayList<>();
      Object text = c.get("text");
      if (text instanceof JSONArray) {
        for (int i = 0; i < ((JSONArray) text).length(); i++) results.add(((JSONArray) text).getString(i));
      } else {
        results.add(String.valueOf(text));
      }
      float[] confidence = new float[results.size()];
      for (int i = 0; i < confidence.length; i++) confidence[i] = i == 0 ? 0.9f : 0.5f;
      Intent data =
          new Intent()
              .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results)
              .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, confidence);
      deliver(request, Activity.RESULT_OK, data);
      report(request, "answered", "RESULT_OK");
      return new Protocol.Json().put("answered", request.id).put("results", new JSONArray(results)).object();
    }
    return new Protocol.Json().put("policy", speechPolicy).put("waiting", pending("speech")).object();
  }

  private JSONObject clipboard(JSONObject c) throws JSONException {
    ClipboardManager clipboard = app.getSystemService(ClipboardManager.class);
    if (c.has("text")) {
      clipboard.setPrimaryClip(ClipData.newPlainText(c.optString("label", "simulator"), c.getString("text")));
    } else if (c.optBoolean("clear", false) && Build.VERSION.SDK_INT >= 28) {
      clipboard.clearPrimaryClip();
    }
    ClipData clip = clipboard.getPrimaryClip();
    JSONArray items = new JSONArray();
    if (clip != null) {
      for (int i = 0; i < clip.getItemCount(); i++) {
        ClipData.Item item = clip.getItemAt(i);
        items.put(
            new Protocol.Json()
                .put("text", item.getText() == null ? null : item.getText().toString())
                .put("uri", item.getUri() == null ? null : item.getUri().toString())
                .object());
      }
    }
    return new Protocol.Json()
        .put("label", clip == null || clip.getDescription().getLabel() == null ? null : clip.getDescription().getLabel().toString())
        .put("items", items)
        .object();
  }
}
