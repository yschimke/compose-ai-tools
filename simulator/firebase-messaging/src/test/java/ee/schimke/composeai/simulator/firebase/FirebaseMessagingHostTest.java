package ee.schimke.composeai.simulator.firebase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class FirebaseMessagingHostTest {

  @Test
  public void rotatesTokenAndRecordsSentMessages() throws Exception {
    FirebaseMessagingHost host = new FirebaseMessagingHost(null);
    assertTrue(host.currentToken().startsWith("sim-fcm-token:"));

    JSONObject rotated = host.rotateToken("custom-token-42");
    assertEquals("custom-token-42", rotated.getString("token"));
    assertEquals("custom-token-42", host.currentToken());

    JSONObject request =
        new JSONObject()
            .put("messageId", "msg-1")
            .put("from", "topics/news")
            .put("data", new JSONObject().put("articleId", "99"))
            .put(
                "notification",
                new JSONObject().put("title", "Breaking").put("body", "Hello simulator"));
    JSONObject sent = host.sendMessage(request);
    assertEquals("msg-1", sent.getString("messageId"));
    assertEquals("topics/news", sent.getString("from"));
    assertEquals(1, host.sentMessagesJson().length());

    host.clearHistory();
    assertEquals(0, host.sentMessagesJson().length());
  }

  @Test
  public void detectsC2dmRegistrationIntents() {
    assertFalse(FirebaseMessagingHost.isC2dmRegistrationIntent(null));
    // Note: android.content.Intent is a stub in pure JVM tests; null guard verified here,
    // and full Intent matching is exercised in Robolectric / runtime sessions.
    assertTrue(FirebaseMessagingHost.ACTION_C2DM_REGISTER.endsWith(".REGISTER"));
    assertTrue(FirebaseMessagingHost.ACTION_C2DM_UNREGISTER.endsWith(".UNREGISTER"));
    assertEquals("com.google.firebase.MESSAGING_EVENT", FirebaseMessagingHost.ACTION_MESSAGING_EVENT);
  }
}
