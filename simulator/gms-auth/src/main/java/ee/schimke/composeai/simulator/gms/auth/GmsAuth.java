package ee.schimke.composeai.simulator.gms.auth;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Stateful simulation engine for Google Play services Auth & Identity ({@code Identity} One Tap /
 * Authorization / Credential Saving, {@code GoogleSignIn}, {@code SmsRetriever}, and {@code Fido}).
 */
public final class GmsAuth {

  public static final String ACTION_SMS_RETRIEVED =
      "com.google.android.gms.auth.api.phone.SMS_RETRIEVED";
  public static final String EXTRA_SMS_MESSAGE =
      "com.google.android.gms.auth.api.phone.EXTRA_SMS_MESSAGE";
  public static final String EXTRA_STATUS = "com.google.android.gms.auth.api.phone.EXTRA_STATUS";
  public static final String ACTION_SIGN_IN_RESULT =
      "ee.schimke.composeai.simulator.gms.auth.SIGN_IN_RESULT";
  public static final String ACTION_CREDENTIAL_SAVE_RESULT =
      "ee.schimke.composeai.simulator.gms.auth.CREDENTIAL_SAVE_RESULT";

  public static final int STATUS_SIGN_IN_REQUIRED = 4;
  public static final int STATUS_CANCELED = 16;

  private static volatile GmsAuth active;

  private final Context context;
  private final Executor main;

  private volatile boolean signedIn = true;
  private volatile String userId = "sim-google-user-1";
  private volatile String email = "simulator@example.com";
  private volatile String displayName = "Simulator User";
  private volatile String givenName = "Simulator";
  private volatile String familyName = "User";
  private volatile String idToken =
      "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJzaW0tZ29vZ2xlLXVzZXItMSIsImVtYWlsIjoic2ltdWxhdG9yQGV4YW1wbGUuY29tIn0.sim-sig";
  private volatile String accessToken = "ya29.simulator-access-token";
  private volatile String serverAuthCode = "4/0simulator-auth-code";
  private volatile int errorCode = 0;
  private volatile boolean fidoAvailable = true;
  private volatile boolean smsRetrieverActive = false;
  private volatile String lastSmsMessage = null;

  GmsAuth(Context context, Executor main) {
    this.context = context;
    this.main = main;
  }

  public static GmsAuth install(Application app) {
    return install(app, null);
  }

  /**
   * Installs {@link GmsAuth} and registers a {@link
   * ee.schimke.composeai.simulator.Navigator.SystemUi} handler so launching the {@code
   * PendingIntent} from {@code beginSignIn}, {@code getSignInIntent}, or {@code
   * CredentialSavingClient} delivers an activity result back to the caller.
   */
  public static GmsAuth install(
      Application app, ee.schimke.composeai.simulator.Navigator navigator) {
    Handler handler = new Handler(Looper.getMainLooper());
    GmsAuth instance = new GmsAuth(app, handler::post);
    if (navigator != null) {
      navigator.addSystemUi(
          (intent, requestCode, requester) -> {
            if (intent == null) return false;
            String action = intent.getAction();
            if (!ACTION_SIGN_IN_RESULT.equals(action)
                && !ACTION_CREDENTIAL_SAVE_RESULT.equals(action)) {
              return false;
            }
            if (requester != null) {
              boolean canceled = instance.errorCode == STATUS_CANCELED;
              int resultCode =
                  canceled
                      ? android.app.Activity.RESULT_CANCELED
                      : android.app.Activity.RESULT_OK;
              navigator.deliverResult(requester, requestCode, resultCode, new Intent(action));
            }
            return true;
          });
    }
    active = instance;
    return instance;
  }

  static GmsAuth installForTest(Context context, Executor main) {
    GmsAuth instance = new GmsAuth(context, main);
    active = instance;
    return instance;
  }

  public static GmsAuth current() {
    return active;
  }

  public static void resetCurrent() {
    active = null;
  }

  static GmsAuth getOrCreate(Context context) {
    GmsAuth cur = active;
    if (cur != null) return cur;
    Executor exec;
    try {
      Handler h = new Handler(Looper.getMainLooper());
      exec = h::post;
    } catch (RuntimeException e) {
      exec = Runnable::run;
    }
    GmsAuth created = new GmsAuth(context, exec);
    active = created;
    return created;
  }

  Executor mainExecutor() {
    return main;
  }

  Context context() {
    return context;
  }

  public boolean isSignedIn() {
    return signedIn;
  }

  public int errorCode() {
    return errorCode;
  }

  public boolean isFidoAvailable() {
    return fidoAvailable;
  }

  public void signOut() {
    this.signedIn = false;
  }

  public void markSmsRetrieverActive(boolean active) {
    this.smsRetrieverActive = active;
  }

  /** Broadcasts {@code SMS_RETRIEVED_ACTION} with the given OTP/SMS message. */
  public void injectSms(String message, ClassLoader loader) {
    this.lastSmsMessage = message;
    if (context == null) return;
    main.execute(
        () -> {
          Intent intent = new Intent(ACTION_SMS_RETRIEVED);
          intent.putExtra(EXTRA_SMS_MESSAGE, message);
          try {
            ClassLoader cl = loader != null ? loader : context.getClassLoader();
            Object st = GmsAuthTasks.status(cl, 0, "OK");
            if (st instanceof Parcelable) {
              intent.putExtra(EXTRA_STATUS, (Parcelable) st);
            }
          } catch (ReflectiveOperationException | RuntimeException ignored) {
          }
          context.sendBroadcast(intent);
        });
  }

  // ---- Reflection builders for GMS Auth / Identity types ----

  /** Constructs a {@code com.google.android.gms.auth.api.signin.GoogleSignInAccount}. */
  Object buildGoogleSignInAccount(ClassLoader loader) {
    if (!signedIn) return null;
    try {
      Class<?> cls =
          Class.forName(
              "com.google.android.gms.auth.api.signin.GoogleSignInAccount", false, loader);
      // Preferred: GoogleSignInAccount.zaa / fromJsonString(String)
      try {
        Method fromJson = cls.getDeclaredMethod("zab", String.class);
        fromJson.setAccessible(true);
        return fromJson.invoke(null, accountJson().toString());
      } catch (NoSuchMethodException ignored) {
      }
      try {
        Method fromJson = cls.getMethod("fromJsonString", String.class);
        return fromJson.invoke(null, accountJson().toString());
      } catch (NoSuchMethodException ignored) {
      }
      // Fallback: test mirror constructor(id, email, displayName, idToken)
      for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
        if (ctor.getParameterCount() == 4) {
          ctor.setAccessible(true);
          return ctor.newInstance(userId, email, displayName, idToken);
        }
      }
    } catch (ReflectiveOperationException | JSONException ignored) {
    }
    return null;
  }

  /** Constructs a {@code com.google.android.gms.auth.api.identity.SignInCredential}. */
  Object buildSignInCredential(ClassLoader loader) {
    try {
      Class<?> cls =
          Class.forName(
              "com.google.android.gms.auth.api.identity.SignInCredential", false, loader);
      for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
        Class<?>[] p = ctor.getParameterTypes();
        if (p.length == 9) {
          ctor.setAccessible(true);
          return ctor.newInstance(
              email, displayName, givenName, familyName, (Uri) null, null, idToken, null, null);
        }
        if (p.length == 4) {
          ctor.setAccessible(true);
          return ctor.newInstance(email, displayName, idToken, null);
        }
      }
    } catch (ReflectiveOperationException ignored) {
    }
    return null;
  }

  /** Constructs a {@code com.google.android.gms.auth.api.identity.BeginSignInResult}. */
  Object buildBeginSignInResult(ClassLoader loader) {
    try {
      Class<?> cls =
          Class.forName(
              "com.google.android.gms.auth.api.identity.BeginSignInResult", false, loader);
      PendingIntent pi =
          context != null
              ? PendingIntent.getActivity(
                  context, 0, new Intent(ACTION_SIGN_IN_RESULT), PendingIntent.FLAG_IMMUTABLE)
              : null;
      Constructor<?> ctor = cls.getDeclaredConstructor(PendingIntent.class);
      ctor.setAccessible(true);
      return ctor.newInstance(pi);
    } catch (ReflectiveOperationException ignored) {
      return null;
    }
  }

  /** Constructs a {@code com.google.android.gms.auth.api.identity.AuthorizationResult}. */
  Object buildAuthorizationResult(ClassLoader loader) {
    try {
      Class<?> cls =
          Class.forName(
              "com.google.android.gms.auth.api.identity.AuthorizationResult", false, loader);
      Object account = buildGoogleSignInAccount(loader);
      for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
        Class<?>[] p = ctor.getParameterTypes();
        if (p.length == 6 && p[0] == String.class && p[1] == String.class) {
          ctor.setAccessible(true);
          return ctor.newInstance(
              serverAuthCode,
              accessToken,
              idToken,
              Collections.singletonList("openid"),
              account,
              null);
        }
        if (p.length == 3 && p[0] == String.class && p[1] == String.class) {
          ctor.setAccessible(true);
          return ctor.newInstance(serverAuthCode, accessToken, idToken);
        }
      }
    } catch (ReflectiveOperationException ignored) {
    }
    return null;
  }

  /** Constructs a {@code com.google.android.gms.auth.api.identity.SavePasswordResult}. */
  Object buildSavePasswordResult(ClassLoader loader) {
    return buildPendingIntentResult(
        loader,
        "com.google.android.gms.auth.api.identity.SavePasswordResult",
        ACTION_CREDENTIAL_SAVE_RESULT);
  }

  /** Constructs a {@code com.google.android.gms.auth.api.identity.SaveAccountLinkingTokenResult}. */
  Object buildSaveAccountLinkingTokenResult(ClassLoader loader) {
    return buildPendingIntentResult(
        loader,
        "com.google.android.gms.auth.api.identity.SaveAccountLinkingTokenResult",
        ACTION_CREDENTIAL_SAVE_RESULT);
  }

  private Object buildPendingIntentResult(ClassLoader loader, String className, String action) {
    try {
      Class<?> cls = Class.forName(className, false, loader);
      PendingIntent pi =
          context != null
              ? PendingIntent.getActivity(
                  context, 0, new Intent(action), PendingIntent.FLAG_IMMUTABLE)
              : null;
      Constructor<?> ctor = cls.getDeclaredConstructor(PendingIntent.class);
      ctor.setAccessible(true);
      return ctor.newInstance(pi);
    } catch (ReflectiveOperationException ignored) {
      return null;
    }
  }

  private JSONObject accountJson() throws JSONException {
    JSONObject json = new JSONObject();
    json.put("id", userId);
    json.put("tokenId", idToken);
    json.put("email", email);
    json.put("displayName", displayName);
    json.put("givenName", givenName);
    json.put("familyName", familyName);
    json.put("expirationTime", System.currentTimeMillis() / 1000L + 3600L);
    json.put("obfuscatedIdentifier", userId);
    json.put("grantedScopes", new JSONArray(List.of("openid", "email", "profile")));
    return json;
  }

  // ---- Control-channel command ("auth") ----

  public JSONObject command(JSONObject c) throws JSONException {
    String action = c.optString("action", "");
    switch (action) {
      case "signIn":
        signedIn = true;
        errorCode = 0;
        if (c.has("id")) userId = c.getString("id");
        if (c.has("email")) email = c.getString("email");
        if (c.has("displayName")) displayName = c.getString("displayName");
        if (c.has("givenName")) givenName = c.getString("givenName");
        if (c.has("familyName")) familyName = c.getString("familyName");
        if (c.has("idToken")) idToken = c.getString("idToken");
        if (c.has("accessToken")) accessToken = c.getString("accessToken");
        if (c.has("serverAuthCode")) serverAuthCode = c.getString("serverAuthCode");
        return describe();
      case "signOut":
        signedIn = false;
        return describe();
      case "sms":
        injectSms(c.getString("message"), null);
        return describe();
      case "clear":
        signedIn = true;
        userId = "sim-google-user-1";
        email = "simulator@example.com";
        displayName = "Simulator User";
        givenName = "Simulator";
        familyName = "User";
        errorCode = 0;
        fidoAvailable = true;
        smsRetrieverActive = false;
        lastSmsMessage = null;
        return describe();
      default:
        break;
    }
    if (c.has("errorCode")) {
      errorCode = c.getInt("errorCode");
    }
    if (c.has("fidoAvailable")) {
      fidoAvailable = c.getBoolean("fidoAvailable");
    }
    return describe();
  }

  public JSONObject describe() throws JSONException {
    JSONObject out = new JSONObject();
    out.put("signedIn", signedIn);
    if (signedIn) {
      out.put("id", userId);
      out.put("email", email);
      out.put("displayName", displayName);
    }
    out.put("errorCode", errorCode);
    out.put("fidoAvailable", fidoAvailable);
    out.put("smsRetrieverActive", smsRetrieverActive);
    if (lastSmsMessage != null) out.put("lastSmsMessage", lastSmsMessage);
    return out;
  }
}
