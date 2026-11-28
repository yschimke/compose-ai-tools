package ee.schimke.composeai.simulator.gms.auth;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Dynamic proxy factories for Play services Auth & Identity client interfaces ({@code SignInClient},
 * {@code AuthorizationClient}, {@code CredentialSavingClient}) plus helpers for {@code
 * GoogleSignIn}, {@code SmsRetriever}, and {@code Fido}.
 */
public final class GmsAuthClients {

  static final String SIGN_IN_CLIENT = "com.google.android.gms.auth.api.identity.SignInClient";
  static final String AUTHORIZATION_CLIENT =
      "com.google.android.gms.auth.api.identity.AuthorizationClient";
  static final String CREDENTIAL_SAVING_CLIENT =
      "com.google.android.gms.auth.api.identity.CredentialSavingClient";

  private GmsAuthClients() {}

  public static Object signInClient(Context context, ClassLoader loader) {
    GmsAuth engine = GmsAuth.getOrCreate(context);
    return newProxy(loader, SIGN_IN_CLIENT, new SignInHandler(engine, loader));
  }

  public static Object authorizationClient(Context context, ClassLoader loader) {
    GmsAuth engine = GmsAuth.getOrCreate(context);
    return newProxy(loader, AUTHORIZATION_CLIENT, new AuthorizationHandler(engine, loader));
  }

  public static Object credentialSavingClient(Context context, ClassLoader loader) {
    GmsAuth engine = GmsAuth.getOrCreate(context);
    return newProxy(loader, CREDENTIAL_SAVING_CLIENT, new CredentialSavingHandler(engine, loader));
  }

  public static Object lastSignedInAccount(Context context, ClassLoader loader) {
    return GmsAuth.getOrCreate(context).buildGoogleSignInAccount(loader);
  }

  public static Object signedInAccountFromIntent(Context context, ClassLoader loader) {
    GmsAuth engine = GmsAuth.getOrCreate(context);
    Exception err = authError(engine, loader);
    if (err != null) {
      return GmsAuthTasks.deferredFailure(loader, engine.mainExecutor(), err);
    }
    Object account = engine.buildGoogleSignInAccount(loader);
    if (account == null) {
      return GmsAuthTasks.deferredFailure(
          loader,
          engine.mainExecutor(),
          GmsAuthTasks.apiException(loader, GmsAuth.STATUS_SIGN_IN_REQUIRED, "Sign-in required"));
    }
    return GmsAuthTasks.deferredResult(loader, engine.mainExecutor(), account);
  }

  public static Object startSmsRetriever(Context context, ClassLoader loader) {
    GmsAuth engine = GmsAuth.getOrCreate(context);
    Exception gmsErr = GmsAuthTasks.playServicesError(loader);
    if (gmsErr != null) {
      return GmsAuthTasks.deferredFailure(loader, engine.mainExecutor(), gmsErr);
    }
    engine.markSmsRetrieverActive(true);
    return GmsAuthTasks.deferredResult(loader, engine.mainExecutor(), null);
  }

  public static Object fidoPlatformAuthenticatorAvailable(Context context, ClassLoader loader) {
    GmsAuth engine = GmsAuth.getOrCreate(context);
    Exception gmsErr = GmsAuthTasks.playServicesError(loader);
    if (gmsErr != null) {
      return GmsAuthTasks.deferredFailure(loader, engine.mainExecutor(), gmsErr);
    }
    return GmsAuthTasks.deferredResult(loader, engine.mainExecutor(), engine.isFidoAvailable());
  }

  private static Exception authError(GmsAuth engine, ClassLoader loader) {
    Exception gmsErr = GmsAuthTasks.playServicesError(loader);
    if (gmsErr != null) return gmsErr;
    if (engine.errorCode() != 0) {
      return GmsAuthTasks.apiException(
          loader, engine.errorCode(), "Simulated auth failure: " + engine.errorCode());
    }
    return null;
  }

  private static Object newProxy(
      ClassLoader loader, String interfaceName, AuthProxyHandler handler) {
    try {
      Class<?> iface = Class.forName(interfaceName, false, loader);
      if (!iface.isInterface()) {
        throw new IllegalStateException(interfaceName + " is not an interface");
      }
      return Proxy.newProxyInstance(loader, new Class<?>[] {iface}, handler);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Play services Auth class not found: " + interfaceName, e);
    }
  }

  private abstract static class AuthProxyHandler implements InvocationHandler {
    final GmsAuth engine;
    final ClassLoader loader;
    private final String label;

    AuthProxyHandler(GmsAuth engine, ClassLoader loader, String label) {
      this.engine = engine;
      this.loader = loader;
      this.label = label;
    }

    @Override
    public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String name = method.getName();
      if ("toString".equals(name) && method.getParameterCount() == 0) return "Simulated" + label;
      if ("hashCode".equals(name) && method.getParameterCount() == 0) {
        return System.identityHashCode(proxy);
      }
      if ("equals".equals(name) && method.getParameterCount() == 1) return proxy == args[0];
      if ("getApiKey".equals(name) && method.getParameterCount() == 0) return null;

      Exception err = authError(engine, loader);
      if (err != null && returnsTask(method)) {
        return GmsAuthTasks.deferredFailure(loader, engine.mainExecutor(), err);
      }
      return handle(name, method, args == null ? new Object[0] : args);
    }

    abstract Object handle(String name, Method method, Object[] args) throws Exception;

    Object ok(Object value) {
      return GmsAuthTasks.deferredResult(loader, engine.mainExecutor(), value);
    }

    Object fail(Exception error) {
      return GmsAuthTasks.deferredFailure(loader, engine.mainExecutor(), error);
    }

    private static boolean returnsTask(Method method) {
      return "com.google.android.gms.tasks.Task".equals(method.getReturnType().getName());
    }
  }

  private static final class SignInHandler extends AuthProxyHandler {
    SignInHandler(GmsAuth engine, ClassLoader loader) {
      super(engine, loader, "SignInClient");
    }

    @Override
    Object handle(String name, Method method, Object[] args) throws Exception {
      switch (name) {
        case "beginSignIn":
          if (!engine.isSignedIn()) {
            return fail(
                GmsAuthTasks.apiException(
                    loader, GmsAuth.STATUS_SIGN_IN_REQUIRED, "No signed-in account"));
          }
          return ok(engine.buildBeginSignInResult(loader));
        case "getSignInIntent":
          PendingIntent pi =
              engine.context() != null
                  ? PendingIntent.getActivity(
                      engine.context(),
                      0,
                      new Intent(GmsAuth.ACTION_SIGN_IN_RESULT),
                      PendingIntent.FLAG_IMMUTABLE)
                  : null;
          return ok(pi);
        case "getSignInCredentialFromIntent":
          Exception err = authError(engine, loader);
          if (err != null) throw err;
          if (!engine.isSignedIn()) {
            throw GmsAuthTasks.apiException(
                loader, GmsAuth.STATUS_SIGN_IN_REQUIRED, "No signed-in account");
          }
          return engine.buildSignInCredential(loader);
        case "signOut":
          engine.signOut();
          return ok(null);
        default:
          return ok(null);
      }
    }
  }

  private static final class AuthorizationHandler extends AuthProxyHandler {
    AuthorizationHandler(GmsAuth engine, ClassLoader loader) {
      super(engine, loader, "AuthorizationClient");
    }

    @Override
    Object handle(String name, Method method, Object[] args) throws Exception {
      switch (name) {
        case "authorize":
          if (!engine.isSignedIn()) {
            return fail(
                GmsAuthTasks.apiException(
                    loader, GmsAuth.STATUS_SIGN_IN_REQUIRED, "No signed-in account"));
          }
          return ok(engine.buildAuthorizationResult(loader));
        case "getAuthorizationResultFromIntent":
          Exception err = authError(engine, loader);
          if (err != null) throw err;
          return engine.buildAuthorizationResult(loader);
        default:
          return ok(null);
      }
    }
  }

  private static final class CredentialSavingHandler extends AuthProxyHandler {
    CredentialSavingHandler(GmsAuth engine, ClassLoader loader) {
      super(engine, loader, "CredentialSavingClient");
    }

    @Override
    Object handle(String name, Method method, Object[] args) {
      return ok(null);
    }
  }
}
