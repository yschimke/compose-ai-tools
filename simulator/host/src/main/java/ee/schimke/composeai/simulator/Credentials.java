package ee.schimke.composeai.simulator;

import android.credentials.CreateCredentialException;
import android.credentials.CreateCredentialRequest;
import android.credentials.CreateCredentialResponse;
import android.credentials.Credential;
import android.credentials.CredentialOption;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.GetCredentialResponse;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The platform's Credential Manager (API 34+), which androidx.credentials calls on those levels,
 * answered in place of the system's account sheet. Robolectric's {@code CredentialManager} has no
 * service behind it: {@code getCredential} and {@code createCredential} never called back, and a
 * sign-in screen waited for good. {@link SimulatorShadowCredentialManager} routes both here.
 *
 * <p>Under the {@code auto} policy (the default) a saved password is returned to a request that
 * asks for passwords, as one-tap sign-in returns the only account, and anything else is {@code
 * TYPE_NO_CREDENTIAL}; a password the app saves ({@code CreatePasswordRequest}) is kept for the
 * next request, and any other credential it creates (a passkey) is cancelled, there being no
 * provider to hold it. Under {@code cancel} every request is {@code TYPE_USER_CANCELED}; under
 * {@code ask} each is a {@code credentials} event that waits for the {@code credentials} command's
 * answer: a {@code password} ({@code {"id","password"}}), any {@code credential} ({@code
 * {"type","data"}}, the data a provider would return, e.g. a passkey's {@code
 * androidx.credentials.BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON}), {@code none} or {@code cancel}.
 */
final class Credentials {

  static final String PASSWORD = "android.credentials.TYPE_PASSWORD_CREDENTIAL";
  static final String KEY_ID = "androidx.credentials.BUNDLE_KEY_ID";
  static final String KEY_PASSWORD = "androidx.credentials.BUNDLE_KEY_PASSWORD";

  private static String policy = "auto";

  /** Saved passwords: id → password. */
  private static final Map<String, String> passwords = new LinkedHashMap<>();

  private static final List<Request> waiting = new ArrayList<>();
  private static int nextId = 1;
  static volatile Protocol protocol;

  private static final class Request {
    final int id;
    final GetCredentialRequest get;
    final CreateCredentialRequest create;
    final Executor executor;
    final Object callback;

    Request(GetCredentialRequest get, CreateCredentialRequest create, Executor executor, Object callback) {
      this.id = nextId++;
      this.get = get;
      this.create = create;
      this.executor = executor;
      this.callback = callback;
    }
  }

  static synchronized void get(
      GetCredentialRequest request,
      Executor executor,
      OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback) {
    Request pending = new Request(request, null, executor, callback);
    switch (policy) {
      case "cancel":
        failGet(pending, GetCredentialException.TYPE_USER_CANCELED, "cancelled");
        break;
      case "ask":
        waiting.add(pending);
        event(pending, "waiting", null);
        break;
      default:
        if (asksFor(request, PASSWORD) && !passwords.isEmpty()) {
          Map.Entry<String, String> first = passwords.entrySet().iterator().next();
          answerPassword(pending, first.getKey(), first.getValue());
        } else {
          failGet(pending, GetCredentialException.TYPE_NO_CREDENTIAL, "no credentials saved");
        }
    }
  }

  static synchronized void create(
      CreateCredentialRequest request,
      Executor executor,
      OutcomeReceiver<CreateCredentialResponse, CreateCredentialException> callback) {
    Request pending = new Request(null, request, executor, callback);
    switch (policy) {
      case "ask":
        waiting.add(pending);
        event(pending, "waiting", null);
        break;
      case "auto":
        if (PASSWORD.equals(request.getType())) {
          savePassword(pending);
          break;
        }
        // Fall through: nothing holds a passkey here.
      default:
        failCreate(pending, CreateCredentialException.TYPE_USER_CANCELED, "cancelled");
    }
  }

  private static boolean asksFor(GetCredentialRequest request, String type) {
    for (CredentialOption option : request.getCredentialOptions()) {
      if (type.equals(option.getType())) return true;
    }
    return false;
  }

  private static void savePassword(Request pending) {
    Bundle data = pending.create.getCredentialData();
    String id = data.getString(KEY_ID);
    passwords.remove(id);
    passwords.put(id, data.getString(KEY_PASSWORD));
    respondCreate(pending, new CreateCredentialResponse(new Bundle()));
    event(pending, "saved", id);
  }

  private static void answerPassword(Request pending, String id, String password) {
    Bundle data = new Bundle();
    data.putString(KEY_ID, id);
    data.putString(KEY_PASSWORD, password);
    respondGet(pending, new GetCredentialResponse(new Credential(PASSWORD, data)));
    event(pending, "answered", "password " + id);
  }

  @SuppressWarnings("unchecked")
  private static void respondGet(Request pending, GetCredentialResponse response) {
    OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback =
        (OutcomeReceiver<GetCredentialResponse, GetCredentialException>) pending.callback;
    pending.executor.execute(() -> callback.onResult(response));
  }

  @SuppressWarnings("unchecked")
  private static void failGet(Request pending, String type, String message) {
    OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback =
        (OutcomeReceiver<GetCredentialResponse, GetCredentialException>) pending.callback;
    GetCredentialException error = new GetCredentialException(type, message);
    pending.executor.execute(() -> callback.onError(error));
    event(pending, "answered", type);
  }

  @SuppressWarnings("unchecked")
  private static void respondCreate(Request pending, CreateCredentialResponse response) {
    OutcomeReceiver<CreateCredentialResponse, CreateCredentialException> callback =
        (OutcomeReceiver<CreateCredentialResponse, CreateCredentialException>) pending.callback;
    pending.executor.execute(() -> callback.onResult(response));
  }

  @SuppressWarnings("unchecked")
  private static void failCreate(Request pending, String type, String message) {
    OutcomeReceiver<CreateCredentialResponse, CreateCredentialException> callback =
        (OutcomeReceiver<CreateCredentialResponse, CreateCredentialException>) pending.callback;
    CreateCredentialException error = new CreateCredentialException(type, message);
    pending.executor.execute(() -> callback.onError(error));
    event(pending, "answered", type);
  }

  private static void event(Request pending, String state, String answer) {
    Protocol sink = protocol;
    if (sink == null) return;
    sink.event("credentials", describe(pending).put("state", state).put("answer", answer).object());
  }

  private static Protocol.Json describe(Request pending) {
    Protocol.Json json = new Protocol.Json().put("requestId", pending.id);
    if (pending.get != null) {
      JSONArray types = new JSONArray();
      for (CredentialOption option : pending.get.getCredentialOptions()) types.put(option.getType());
      json.put("op", "get").put("types", types);
    } else {
      json.put("op", "create").put("type", pending.create.getType());
      String id = pending.create.getCredentialData().getString(KEY_ID);
      if (id != null) json.put("credentialId", id);
    }
    return json;
  }

  /** The {@code credentials} command. */
  static synchronized JSONObject command(JSONObject c) throws JSONException {
    if (c.has("policy")) {
      String value = c.getString("policy");
      if (!java.util.Arrays.asList("auto", "ask", "cancel").contains(value)) {
        throw new IllegalArgumentException("credentials policy is auto, ask or cancel, not " + value);
      }
      policy = value;
    }
    if (c.has("save")) {
      JSONObject save = c.getJSONObject("save");
      passwords.put(save.getString("id"), save.getString("password"));
    }
    if (c.optBoolean("forget", false)) passwords.clear();
    if (c.has("answer")) return answer(c);
    JSONArray pending = new JSONArray();
    for (Request request : waiting) pending.put(describe(request).object());
    return new Protocol.Json()
        .put("policy", policy)
        .put("passwords", new JSONArray(passwords.keySet()))
        .put("waiting", pending)
        .object();
  }

  private static JSONObject answer(JSONObject c) throws JSONException {
    Object which = c.opt("requestId");
    int id = which instanceof Number ? ((Number) which).intValue() : -1;
    Request pending = null;
    for (Iterator<Request> it = waiting.iterator(); it.hasNext(); ) {
      Request request = it.next();
      if (id < 0 || request.id == id) {
        it.remove();
        pending = request;
        break;
      }
    }
    if (pending == null) throw new IllegalArgumentException("no credential request is waiting");
    Object answer = c.get("answer");
    if ("cancel".equals(answer)) {
      if (pending.get != null) failGet(pending, GetCredentialException.TYPE_USER_CANCELED, "cancelled");
      else failCreate(pending, CreateCredentialException.TYPE_USER_CANCELED, "cancelled");
    } else if ("none".equals(answer)) {
      if (pending.get != null) failGet(pending, GetCredentialException.TYPE_NO_CREDENTIAL, "none");
      else failCreate(pending, CreateCredentialException.TYPE_NO_CREATE_OPTIONS, "none");
    } else if ("save".equals(answer) && pending.create != null) {
      if (PASSWORD.equals(pending.create.getType())) savePassword(pending);
      else respondCreate(pending, new CreateCredentialResponse(bundle(c.optJSONObject("data"))));
    } else if (answer instanceof JSONObject && pending.get != null) {
      JSONObject object = (JSONObject) answer;
      if (object.has("password")) {
        answerPassword(pending, object.getString("id"), object.getString("password"));
      } else {
        respondGet(
            pending,
            new GetCredentialResponse(
                new Credential(object.getString("type"), bundle(object.optJSONObject("data")))));
        event(pending, "answered", object.getString("type"));
      }
    } else {
      throw new IllegalArgumentException(
          "answer a "
              + (pending.get != null ? "get" : "create")
              + " with "
              + (pending.get != null
                  ? "{\"id\",\"password\"}, {\"type\",\"data\"}, none or cancel"
                  : "save (and data), none or cancel"));
    }
    return describe(pending).put("answered", true).object();
  }

  private static Bundle bundle(JSONObject data) throws JSONException {
    Bundle bundle = new Bundle();
    if (data == null) return bundle;
    for (Iterator<String> keys = data.keys(); keys.hasNext(); ) {
      String key = keys.next();
      bundle.putString(key, data.getString(key));
    }
    return bundle;
  }

  private Credentials() {}
}
