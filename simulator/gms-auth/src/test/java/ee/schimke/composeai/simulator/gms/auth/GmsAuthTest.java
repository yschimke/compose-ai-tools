package ee.schimke.composeai.simulator.gms.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import ee.schimke.composeai.simulator.PlayServices;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Queue;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GmsAuthTest {

  private final Queue<Runnable> queue = new ArrayDeque<>();
  private GmsAuth auth;
  private ClassLoader loader;

  @Before
  public void setUp() {
    PlayServices.set(PlayServices.State.INSTALLED);
    auth = GmsAuth.installForTest(null, queue::add);
    loader = getClass().getClassLoader();
  }

  @After
  public void tearDown() {
    GmsAuth.resetCurrent();
    PlayServices.set(PlayServices.State.INSTALLED);
  }

  private void drain() {
    while (!queue.isEmpty()) queue.poll().run();
  }

  private static Object call(Object target, String method, Class<?>[] types, Object... args)
      throws Exception {
    Method m = target.getClass().getMethod(method, types);
    m.setAccessible(true);
    return m.invoke(target, args);
  }

  @Test
  public void identitySignInAndSignOutFlow() throws Exception {
    Object signIn = GmsAuthClients.signInClient(null, loader);

    Task<?> begin = (Task<?>) call(signIn, "beginSignIn", new Class<?>[] {Object.class}, new Object());
    assertFalse(begin.isComplete());
    drain();
    assertTrue(begin.isSuccessful());
    assertNotNull(begin.getResult());

    Object cred = call(signIn, "getSignInCredentialFromIntent", new Class<?>[] {Intent.class}, (Object) null);
    assertEquals("simulator@example.com", call(cred, "getId", new Class<?>[0]));

    Task<?> signOut = (Task<?>) call(signIn, "signOut", new Class<?>[0]);
    drain();
    assertTrue(signOut.isSuccessful());
    assertFalse(auth.isSignedIn());

    Task<?> beginSignedOut =
        (Task<?>) call(signIn, "beginSignIn", new Class<?>[] {Object.class}, new Object());
    drain();
    assertFalse(beginSignedOut.isSuccessful());
    assertEquals(
        GmsAuth.STATUS_SIGN_IN_REQUIRED,
        ((ApiException) beginSignedOut.getException()).getStatusCode());
  }

  @Test
  public void googleSignInAndAuthorizationReflectAuthCommand() throws Exception {
    auth.command(
        new JSONObject()
            .put("action", "signIn")
            .put("email", "alice@example.com")
            .put("displayName", "Alice")
            .put("accessToken", "ya29.alice"));

    GoogleSignInAccount account =
        (GoogleSignInAccount) GmsAuthClients.lastSignedInAccount(null, loader);
    assertNotNull(account);
    assertEquals("alice@example.com", account.getEmail());
    assertEquals("Alice", account.getDisplayName());

    Object authz = GmsAuthClients.authorizationClient(null, loader);
    Task<?> t = (Task<?>) call(authz, "authorize", new Class<?>[] {Object.class}, new Object());
    drain();
    assertTrue(t.isSuccessful());
    assertEquals("ya29.alice", call(t.getResult(), "getAccessToken", new Class<?>[0]));

    auth.command(new JSONObject().put("action", "signOut"));
    assertNull(GmsAuthClients.lastSignedInAccount(null, loader));
  }

  @Test
  public void smsRetrieverAndFidoChecksWork() throws Exception {
    Task<?> smsTask = (Task<?>) GmsAuthClients.startSmsRetriever(null, loader);
    drain();
    assertTrue(smsTask.isSuccessful());
    assertTrue(auth.describe().getBoolean("smsRetrieverActive"));

    Task<?> fidoOn = (Task<?>) GmsAuthClients.fidoPlatformAuthenticatorAvailable(null, loader);
    drain();
    assertEquals(Boolean.TRUE, fidoOn.getResult());

    auth.command(new JSONObject().put("fidoAvailable", false));
    Task<?> fidoOff = (Task<?>) GmsAuthClients.fidoPlatformAuthenticatorAvailable(null, loader);
    drain();
    assertEquals(Boolean.FALSE, fidoOff.getResult());
  }

  @Test
  public void configuredErrorCodeFailsTasks() throws Exception {
    auth.command(new JSONObject().put("errorCode", GmsAuth.STATUS_CANCELED));
    Object signIn = GmsAuthClients.signInClient(null, loader);
    Task<?> t = (Task<?>) call(signIn, "beginSignIn", new Class<?>[] {Object.class}, new Object());
    drain();
    assertFalse(t.isSuccessful());
    assertEquals(GmsAuth.STATUS_CANCELED, ((ApiException) t.getException()).getStatusCode());
  }

  @Test
  public void credentialSavingClientReturnsTypedResultsAndSyncStatus() throws Exception {
    Object saving = GmsAuthClients.credentialSavingClient(null, loader);

    Task<?> savePw =
        (Task<?>) call(saving, "savePassword", new Class<?>[] {Object.class}, new Object());
    drain();
    assertTrue(savePw.isSuccessful());
    assertNotNull(savePw.getResult());
    assertEquals(
        "com.google.android.gms.auth.api.identity.SavePasswordResult",
        savePw.getResult().getClass().getName());

    Task<?> saveLink =
        (Task<?>)
            call(saving, "saveAccountLinkingToken", new Class<?>[] {Object.class}, new Object());
    drain();
    assertTrue(saveLink.isSuccessful());
    assertNotNull(saveLink.getResult());
    assertEquals(
        "com.google.android.gms.auth.api.identity.SaveAccountLinkingTokenResult",
        saveLink.getResult().getClass().getName());

    Object status = call(saving, "getStatusFromIntent", new Class<?>[] {Intent.class}, (Object) null);
    assertEquals(
        "com.google.android.gms.common.api.Status", status.getClass().getName());
  }
}
