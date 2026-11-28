package ee.schimke.composeai.simulator.play.billing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ee.schimke.composeai.simulator.PlayServices;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PlayBillingServiceTest {

  private PlayBillingService billing;

  @Before
  public void setUp() {
    PlayServices.set(PlayServices.State.INSTALLED);
    billing = PlayBillingService.installForTest(Runnable::run);
  }

  @After
  public void tearDown() {
    PlayBillingService.resetCurrent();
    PlayServices.set(PlayServices.State.INSTALLED);
  }

  @Test
  public void billingSupportedReflectsAvailabilityAndPlayServices() throws Exception {
    assertEquals(PlayBillingService.RESULT_OK, billing.checkBillingSupported());

    billing.command(new JSONObject().put("available", false));
    assertEquals(PlayBillingService.RESULT_BILLING_UNAVAILABLE, billing.checkBillingSupported());

    billing.command(new JSONObject().put("available", true));
    assertEquals(PlayBillingService.RESULT_OK, billing.checkBillingSupported());

    PlayServices.set(PlayServices.State.NONE);
    assertEquals(PlayBillingService.RESULT_BILLING_UNAVAILABLE, billing.checkBillingSupported());
  }

  @Test
  public void productCatalogAndSyntheticFallbackReturnValidProductDetailsJson() throws Exception {
    billing.command(
        new JSONObject()
            .put("action", "addProduct")
            .put("id", "pro_monthly")
            .put("type", "subs")
            .put("title", "Pro Monthly")
            .put("price", "$4.99")
            .put("priceMicros", 4_990_000L)
            .put("currency", "USD")
            .put("billingPeriod", "P1M"));

    List<String> details =
        billing.queryDetailsJson("subs", List.of("pro_monthly", "pro_yearly"), true);
    assertEquals(2, details.size());

    JSONObject monthly = new JSONObject(details.get(0));
    assertEquals("pro_monthly", monthly.getString("productId"));
    assertEquals("subs", monthly.getString("type"));
    assertEquals(
        "$4.99",
        monthly
            .getJSONArray("subscriptionOfferDetails")
            .getJSONObject(0)
            .getJSONArray("pricingPhases")
            .getJSONObject(0)
            .getString("formattedPrice"));

    JSONObject yearly = new JSONObject(details.get(1));
    assertEquals("pro_yearly", yearly.getString("productId"));
  }

  @Test
  public void purchaseAcknowledgeConsumeAndRevokeLifecycle() throws Exception {
    assertEquals(
        PlayBillingService.RESULT_OK, billing.executeBuy("com.example.app", "coins_100", "inapp"));
    List<PlayBillingService.PurchaseRecord> owned = billing.activePurchases("inapp");
    assertEquals(1, owned.size());
    PlayBillingService.PurchaseRecord rec = owned.get(0);
    assertEquals("coins_100", rec.productId);
    assertFalse(rec.acknowledged);

    // Buying an already-owned item returns ITEM_ALREADY_OWNED.
    assertEquals(
        PlayBillingService.RESULT_ITEM_ALREADY_OWNED,
        billing.executeBuy("com.example.app", "coins_100", "inapp"));

    // Acknowledge, then consume.
    assertEquals(PlayBillingService.RESULT_OK, billing.acknowledgeToken(rec.purchaseToken));
    assertTrue(rec.acknowledged);
    assertEquals(PlayBillingService.RESULT_OK, billing.consumeToken(rec.purchaseToken));
    assertTrue(billing.activePurchases("inapp").isEmpty());
  }

  @Test
  public void outcomeControlsPurchaseFlowResult() throws Exception {
    billing.command(new JSONObject().put("outcome", "user_canceled"));
    assertEquals(
        PlayBillingService.RESULT_USER_CANCELED,
        billing.executeBuy("com.example.app", "pro_monthly", "subs"));
    assertTrue(billing.activePurchases("subs").isEmpty());

    billing.command(new JSONObject().put("outcome", "pending"));
    assertEquals(
        PlayBillingService.RESULT_OK,
        billing.executeBuy("com.example.app", "pro_monthly", "subs"));
    PlayBillingService.PurchaseRecord pendingRec = billing.activePurchases("subs").get(0);
    assertEquals(4, pendingRec.toPurchaseJson().getInt("purchaseState"));
  }

  @Test
  public void grantAndRevokeCommandsManageEntitlements() throws Exception {
    JSONObject granted =
        billing.command(
            new JSONObject()
                .put("action", "grant")
                .put("productId", "pro_monthly")
                .put("type", "subs"));
    assertEquals(1, granted.getJSONArray("purchases").length());
    assertTrue(granted.getJSONArray("purchases").getJSONObject(0).getBoolean("acknowledged"));

    JSONObject revoked =
        billing.command(new JSONObject().put("action", "revoke").put("productId", "pro_monthly"));
    assertEquals(0, revoked.getJSONArray("purchases").length());
  }

  @Test
  public void binderTransactionCodesMatchAidlContract() {
    assertEquals(11, PlayBillingService.TX_GET_PURCHASES_EXTRA_PARAMS);
    assertEquals(901, PlayBillingService.TX_GET_SKU_DETAILS_EXTRA_PARAMS);
    assertEquals(902, PlayBillingService.TX_ACKNOWLEDGE_PURCHASE);
  }
}
