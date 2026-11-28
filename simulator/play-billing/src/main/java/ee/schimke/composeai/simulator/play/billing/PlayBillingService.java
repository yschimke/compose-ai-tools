package ee.schimke.composeai.simulator.play.billing;

import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import ee.schimke.composeai.simulator.PlayServices;
import ee.schimke.composeai.simulator.SimulatorHooks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPackageManager;

/**
 * Google Play Billing emulation over {@code com.android.vending}'s real {@code
 * IInAppBillingService} AIDL binder.
 *
 * <p>Installed by {@link #install(Application)} so apps using unmodified {@code
 * com.android.billingclient:billing} (v5–v8) bind, query product/SKU details, launch simulated
 * purchase flows, consume/acknowledge purchases, and restore entitlements.
 */
public final class PlayBillingService {

  public static final String VENDING_PACKAGE = "com.android.vending";
  public static final String ACTION_BIND = "com.android.vending.billing.InAppBillingService.BIND";
  public static final String SERVICE_CLASS =
      "com.android.vending.billing.InAppBillingService";
  public static final String DESCRIPTOR = "com.android.vending.billing.IInAppBillingService";
  public static final String ACTION_PURCHASES_UPDATED =
      "com.android.vending.billing.PURCHASES_UPDATED";
  public static final String ACTION_PURCHASE_FLOW =
      "ee.schimke.composeai.simulator.play.billing.PURCHASE_FLOW";

  // BillingResponseCode constants.
  public static final int RESULT_OK = 0;
  public static final int RESULT_USER_CANCELED = 1;
  public static final int RESULT_SERVICE_UNAVAILABLE = 2;
  public static final int RESULT_BILLING_UNAVAILABLE = 3;
  public static final int RESULT_ITEM_UNAVAILABLE = 4;
  public static final int RESULT_DEVELOPER_ERROR = 5;
  public static final int RESULT_ERROR = 6;
  public static final int RESULT_ITEM_ALREADY_OWNED = 7;
  public static final int RESULT_ITEM_NOT_OWNED = 8;

  // PurchaseState constants.
  public static final int PURCHASE_STATE_PURCHASED = 0;
  public static final int PURCHASE_STATE_PENDING = 2;

  // Standard IInAppBillingService AIDL wire transaction codes (FIRST_CALL_TRANSACTION + id).
  static final int TX_IS_BILLING_SUPPORTED = 1;
  static final int TX_GET_SKU_DETAILS = 2;
  static final int TX_GET_BUY_INTENT = 3;
  static final int TX_GET_PURCHASES = 4;
  static final int TX_CONSUME_PURCHASE = 5;
  static final int TX_GET_BUY_INTENT_EXTRA_PARAMS = 8;
  static final int TX_IS_BILLING_SUPPORTED_EXTRA_PARAMS = 10;
  static final int TX_GET_PURCHASES_EXTRA_PARAMS = 11;
  static final int TX_CONSUME_PURCHASE_EXTRA_PARAMS = 12;
  static final int TX_GET_SKU_DETAILS_EXTRA_PARAMS = 901;
  static final int TX_ACKNOWLEDGE_PURCHASE = 902;

  private static volatile PlayBillingService active;

  private final Application app;
  private final Executor main;
  private final Binder binder;

  private volatile boolean billingAvailable = true;
  private volatile int nextPurchaseOutcome = RESULT_OK;
  private volatile boolean nextPurchasePending = false;
  private volatile String countryCode = "US";

  private final Map<String, Product> products = Collections.synchronizedMap(new LinkedHashMap<>());
  private final Map<String, PurchaseRecord> purchases =
      Collections.synchronizedMap(new LinkedHashMap<>());

  public static final class Product {
    public final String id;
    public final String type; // "inapp" or "subs"
    public final String title;
    public final String description;
    public final String formattedPrice;
    public final long priceAmountMicros;
    public final String currencyCode;
    public final String billingPeriod; // e.g. "P1M", "P1Y" for subs

    public Product(
        String id,
        String type,
        String title,
        String description,
        String formattedPrice,
        long priceAmountMicros,
        String currencyCode,
        String billingPeriod) {
      this.id = id;
      this.type = "subs".equalsIgnoreCase(type) ? "subs" : "inapp";
      this.title = title;
      this.description = description;
      this.formattedPrice = formattedPrice;
      this.priceAmountMicros = priceAmountMicros;
      this.currencyCode = currencyCode;
      this.billingPeriod = billingPeriod;
    }

    /** Serializes as modern Billing v5+ ProductDetails JSON. */
    public JSONObject toProductDetailsJson() throws JSONException {
      JSONObject json = new JSONObject();
      json.put("productId", id);
      json.put("type", type);
      json.put("title", title);
      json.put("name", title);
      json.put("description", description);
      json.put("skuDetailsToken", "sim-token-" + id);
      if ("subs".equals(type)) {
        JSONObject pricingPhase = new JSONObject();
        pricingPhase.put("formattedPrice", formattedPrice);
        pricingPhase.put("priceAmountMicros", priceAmountMicros);
        pricingPhase.put("priceCurrencyCode", currencyCode);
        pricingPhase.put("billingPeriod", billingPeriod != null ? billingPeriod : "P1M");
        pricingPhase.put("billingCycleCount", 0);
        pricingPhase.put("recurrenceMode", 1);
        JSONObject offer = new JSONObject();
        offer.put("basePlanId", id + "-base");
        offer.put("offerIdToken", "sim-offer-" + id);
        offer.put("pricingPhases", new JSONArray().put(pricingPhase));
        offer.put("offerTags", new JSONArray());
        json.put("subscriptionOfferDetails", new JSONArray().put(offer));
      } else {
        JSONObject oneTime = new JSONObject();
        oneTime.put("formattedPrice", formattedPrice);
        oneTime.put("priceAmountMicros", priceAmountMicros);
        oneTime.put("priceCurrencyCode", currencyCode);
        oneTime.put("offerIdToken", "sim-offer-" + id);
        json.put("oneTimePurchaseOfferDetails", oneTime);
      }
      return json;
    }

    /** Serializes as legacy SkuDetails JSON (Billing v3–v4). */
    public JSONObject toSkuDetailsJson() throws JSONException {
      JSONObject json = new JSONObject();
      json.put("productId", id);
      json.put("type", type);
      json.put("title", title);
      json.put("description", description);
      json.put("price", formattedPrice);
      json.put("price_amount_micros", priceAmountMicros);
      json.put("price_currency_code", currencyCode);
      json.put("skuDetailsToken", "sim-token-" + id);
      if ("subs".equals(type) && billingPeriod != null) {
        json.put("subscriptionPeriod", billingPeriod);
      }
      return json;
    }
  }

  public static final class PurchaseRecord {
    public final String orderId;
    public final String packageName;
    public final String productId;
    public final String type;
    public final String purchaseToken;
    public final long purchaseTime;
    public int purchaseState; // 0 = purchased, 2 = pending
    public boolean acknowledged;
    public boolean autoRenewing;

    public PurchaseRecord(
        String orderId,
        String packageName,
        String productId,
        String type,
        String purchaseToken,
        long purchaseTime,
        int purchaseState,
        boolean acknowledged,
        boolean autoRenewing) {
      this.orderId = orderId;
      this.packageName = packageName;
      this.productId = productId;
      this.type = type;
      this.purchaseToken = purchaseToken;
      this.purchaseTime = purchaseTime;
      this.purchaseState = purchaseState;
      this.acknowledged = acknowledged;
      this.autoRenewing = autoRenewing;
    }

    public JSONObject toPurchaseJson() throws JSONException {
      JSONObject json = new JSONObject();
      json.put("orderId", orderId);
      json.put("packageName", packageName);
      json.put("productId", productId);
      json.put("productIds", new JSONArray().put(productId));
      json.put("purchaseTime", purchaseTime);
      json.put("purchaseState", purchaseState);
      json.put("purchaseToken", purchaseToken);
      json.put("quantity", 1);
      json.put("acknowledged", acknowledged);
      if ("subs".equals(type)) {
        json.put("autoRenewing", autoRenewing);
      }
      return json;
    }
  }

  PlayBillingService(Application app, Executor main) {
    this.app = app;
    this.main = main;
    this.binder = app != null ? new BillingBinder() : null;
  }

  /** Installs the {@code IInAppBillingService} binder into {@code com.android.vending}. */
  public static PlayBillingService install(Application app) {
    return install(app, null);
  }

  /**
   * Installs the {@code IInAppBillingService} binder and registers a {@link
   * ee.schimke.composeai.simulator.Navigator.SystemUi} handler for {@code BUY_INTENT} activity
   * launches so {@code ProxyBillingActivity} receives {@code Activity.RESULT_OK} with purchase
   * data.
   */
  public static PlayBillingService install(
      Application app, ee.schimke.composeai.simulator.Navigator navigator) {
    Handler handler = new Handler(Looper.getMainLooper());
    PlayBillingService service = new PlayBillingService(app, handler::post);
    service.registerWithRobolectric();
    if (navigator != null) {
      navigator.addSystemUi(
          (intent, requestCode, requester) -> {
            if (intent == null || !ACTION_PURCHASE_FLOW.equals(intent.getAction())) return false;
            if (requester != null) {
              Intent result = new Intent();
              result.putExtra("RESPONSE_CODE", intent.getIntExtra("RESPONSE_CODE", RESULT_OK));
              String data = intent.getStringExtra("INAPP_PURCHASE_DATA");
              String sig = intent.getStringExtra("INAPP_DATA_SIGNATURE");
              if (data != null) result.putExtra("INAPP_PURCHASE_DATA", data);
              if (sig != null) result.putExtra("INAPP_DATA_SIGNATURE", sig);
              navigator.deliverResult(requester, requestCode, android.app.Activity.RESULT_OK, result);
            }
            return true;
          });
    }
    active = service;
    return service;
  }

  /** Installs a plain-JVM instance for unit tests (without Robolectric). */
  static PlayBillingService installForTest(Executor main) {
    PlayBillingService service = new PlayBillingService(null, main);
    active = service;
    return service;
  }

  public static PlayBillingService current() {
    return active;
  }

  public static void resetCurrent() {
    active = null;
  }

  public IBinder binder() {
    return binder;
  }

  private void registerWithRobolectric() {
    if (app == null || binder == null) return;
    ComponentName component = new ComponentName(VENDING_PACKAGE, SERVICE_CLASS);
    Intent bindIntent = new Intent(ACTION_BIND).setPackage(VENDING_PACKAGE);
    ShadowApplication shadowApp = Shadows.shadowOf(app);
    shadowApp.setComponentNameAndServiceForBindServiceForIntent(bindIntent, component, binder);
    shadowApp.setComponentNameAndServiceForBindService(component, binder);

    ShadowPackageManager pm = Shadows.shadowOf(app.getPackageManager());
    ServiceInfo info = new ServiceInfo();
    info.packageName = VENDING_PACKAGE;
    info.name = SERVICE_CLASS;
    info.exported = true;
    ResolveInfo resolveInfo = new ResolveInfo();
    resolveInfo.serviceInfo = info;
    pm.addResolveInfoForIntent(bindIntent, resolveInfo);
  }

  // ---- Core billing operations (used by both AIDL binder and unit tests) ----

  int checkBillingSupported() {
    if (PlayServices.status(0) != PlayServices.SUCCESS || !billingAvailable) {
      return RESULT_BILLING_UNAVAILABLE;
    }
    return RESULT_OK;
  }

  public void addProduct(Product product) {
    if (product != null) products.put(product.id, product);
  }

  public PurchaseRecord grantPurchase(
      String packageName, String productId, String type, boolean acknowledged, boolean pending) {
    String pkg = packageName != null ? packageName : (app != null ? app.getPackageName() : "app");
    String normalizedType = "subs".equalsIgnoreCase(type) ? "subs" : "inapp";
    String token = "sim-purchase-" + UUID.randomUUID().toString().substring(0, 8);
    String orderId = "GPA.SIM-" + Math.abs(token.hashCode());
    PurchaseRecord record =
        new PurchaseRecord(
            orderId,
            pkg,
            productId,
            normalizedType,
            token,
            SimulatorHooks.currentTimeMillis(),
            pending ? PURCHASE_STATE_PENDING : PURCHASE_STATE_PURCHASED,
            acknowledged,
            "subs".equals(normalizedType));
    purchases.put(token, record);
    broadcastPurchaseUpdate(RESULT_OK, record);
    return record;
  }

  public boolean revokeProduct(String productId) {
    boolean removed = false;
    synchronized (purchases) {
      var it = purchases.entrySet().iterator();
      while (it.hasNext()) {
        if (productId.equals(it.next().getValue().productId)) {
          it.remove();
          removed = true;
        }
      }
    }
    return removed;
  }

  int consumeToken(String token) {
    if (checkBillingSupported() != RESULT_OK) return RESULT_BILLING_UNAVAILABLE;
    if (token == null || !purchases.containsKey(token)) return RESULT_ITEM_NOT_OWNED;
    purchases.remove(token);
    return RESULT_OK;
  }

  int acknowledgeToken(String token) {
    if (checkBillingSupported() != RESULT_OK) return RESULT_BILLING_UNAVAILABLE;
    PurchaseRecord rec = purchases.get(token);
    if (rec == null) return RESULT_ITEM_NOT_OWNED;
    rec.acknowledged = true;
    return RESULT_OK;
  }

  List<String> queryDetailsJson(String type, List<String> ids, boolean productDetailsFormat) {
    List<String> out = new ArrayList<>();
    if (ids == null) return out;
    for (String id : ids) {
      Product p = products.get(id);
      if (p == null) {
        // Synthesize a reasonable default product so apps without pre-seeded catalogs still render.
        p =
            new Product(
                id,
                type,
                id,
                "Simulated product " + id,
                "subs".equalsIgnoreCase(type) ? "$4.99" : "$1.99",
                "subs".equalsIgnoreCase(type) ? 4_990_000L : 1_990_000L,
                "USD",
                "subs".equalsIgnoreCase(type) ? "P1M" : null);
      }
      if (type != null && !type.isEmpty() && !type.equalsIgnoreCase(p.type)) continue;
      try {
        out.add(
            (productDetailsFormat ? p.toProductDetailsJson() : p.toSkuDetailsJson()).toString());
      } catch (JSONException ignored) {
      }
    }
    return out;
  }

  List<PurchaseRecord> activePurchases(String type) {
    List<PurchaseRecord> out = new ArrayList<>();
    synchronized (purchases) {
      for (PurchaseRecord rec : purchases.values()) {
        if (type == null || type.isEmpty() || type.equalsIgnoreCase(rec.type)) {
          out.add(rec);
        }
      }
    }
    return out;
  }

  int executeBuy(String packageName, String sku, String type) {
    if (checkBillingSupported() != RESULT_OK) return RESULT_BILLING_UNAVAILABLE;
    if (nextPurchaseOutcome != RESULT_OK) return nextPurchaseOutcome;
    synchronized (purchases) {
      for (PurchaseRecord rec : purchases.values()) {
        if (sku.equals(rec.productId) && rec.purchaseState == PURCHASE_STATE_PURCHASED) {
          return RESULT_ITEM_ALREADY_OWNED;
        }
      }
    }
    grantPurchase(packageName, sku, type, false, nextPurchasePending);
    return RESULT_OK;
  }

  private void broadcastPurchaseUpdate(int responseCode, PurchaseRecord record) {
    if (app == null) return;
    main.execute(
        () -> {
          try {
            Intent intent = new Intent(ACTION_PURCHASES_UPDATED);
            intent.putExtra("RESPONSE_CODE", responseCode);
            if (record != null) {
              intent.putExtra("INAPP_PURCHASE_DATA", record.toPurchaseJson().toString());
              intent.putExtra("INAPP_DATA_SIGNATURE", "sim-signature-" + record.orderId);
            }
            app.sendBroadcast(intent);
          } catch (JSONException | RuntimeException ignored) {
          }
        });
  }

  // ---- Control-channel command ("billing") ----

  public JSONObject command(JSONObject c) throws JSONException {
    String action = c.optString("action", "");
    switch (action) {
      case "clear":
        products.clear();
        purchases.clear();
        billingAvailable = true;
        nextPurchaseOutcome = RESULT_OK;
        nextPurchasePending = false;
        return describe();
      case "addProduct":
        {
          String id = c.getString("id");
          String type = c.optString("type", "inapp");
          String title = c.optString("title", id);
          String desc = c.optString("description", title);
          String price = c.optString("price", "subs".equalsIgnoreCase(type) ? "$4.99" : "$1.99");
          long micros =
              c.optLong("priceMicros", "subs".equalsIgnoreCase(type) ? 4_990_000L : 1_990_000L);
          String currency = c.optString("currency", "USD");
          String period = c.optString("billingPeriod", "subs".equalsIgnoreCase(type) ? "P1M" : null);
          addProduct(new Product(id, type, title, desc, price, micros, currency, period));
          return describe();
        }
      case "grant":
        {
          String productId = c.getString("productId");
          String type = c.optString("type", "inapp");
          boolean ack = c.optBoolean("acknowledged", true);
          boolean pending = c.optBoolean("pending", false);
          grantPurchase(null, productId, type, ack, pending);
          return describe();
        }
      case "revoke":
        {
          revokeProduct(c.getString("productId"));
          return describe();
        }
      default:
        break;
    }
    if (c.has("available")) {
      billingAvailable = c.getBoolean("available");
    }
    if (c.has("countryCode")) {
      countryCode = c.getString("countryCode").toUpperCase(Locale.ROOT);
    }
    if (c.has("outcome")) {
      String outcome = c.getString("outcome").trim().toLowerCase(Locale.ROOT);
      nextPurchasePending = "pending".equals(outcome);
      nextPurchaseOutcome = parseOutcome(outcome);
    }
    return describe();
  }

  public JSONObject describe() throws JSONException {
    JSONObject out = new JSONObject();
    out.put("available", billingAvailable);
    out.put("responseCode", checkBillingSupported());
    out.put("purchaseOutcome", nextPurchasePending ? "pending" : outcomeName(nextPurchaseOutcome));
    out.put("countryCode", countryCode);
    JSONArray prodArr = new JSONArray();
    synchronized (products) {
      for (Product p : products.values()) {
        prodArr.put(p.toProductDetailsJson());
      }
    }
    out.put("products", prodArr);
    JSONArray purchArr = new JSONArray();
    synchronized (purchases) {
      for (PurchaseRecord rec : purchases.values()) {
        purchArr.put(rec.toPurchaseJson());
      }
    }
    out.put("purchases", purchArr);
    return out;
  }

  static int parseOutcome(String name) {
    switch (name) {
      case "ok":
      case "approved":
      case "pending":
        return RESULT_OK;
      case "canceled":
      case "cancelled":
      case "user_canceled":
        return RESULT_USER_CANCELED;
      case "unavailable":
      case "billing_unavailable":
        return RESULT_BILLING_UNAVAILABLE;
      case "item_unavailable":
        return RESULT_ITEM_UNAVAILABLE;
      case "already_owned":
      case "item_already_owned":
        return RESULT_ITEM_ALREADY_OWNED;
      case "error":
      case "declined":
        return RESULT_ERROR;
      default:
        throw new IllegalArgumentException("Unknown billing outcome: " + name);
    }
  }

  static String outcomeName(int code) {
    switch (code) {
      case RESULT_OK:
        return "ok";
      case RESULT_USER_CANCELED:
        return "user_canceled";
      case RESULT_BILLING_UNAVAILABLE:
        return "billing_unavailable";
      case RESULT_ITEM_UNAVAILABLE:
        return "item_unavailable";
      case RESULT_ITEM_ALREADY_OWNED:
        return "item_already_owned";
      default:
        return "error";
    }
  }

  // ---- IInAppBillingService AIDL Binder ----

  private final class BillingBinder extends Binder {
    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(DESCRIPTOR);
        return true;
      }
      data.enforceInterface(DESCRIPTOR);
      switch (code) {
        case TX_IS_BILLING_SUPPORTED:
          {
            data.readInt(); // apiVersion
            data.readString(); // packageName
            data.readString(); // type
            reply.writeNoException();
            reply.writeInt(checkBillingSupported());
            return true;
          }
        case TX_IS_BILLING_SUPPORTED_EXTRA_PARAMS:
          {
            data.readInt();
            data.readString();
            data.readString();
            readNullableBundle(data);
            reply.writeNoException();
            reply.writeInt(checkBillingSupported());
            return true;
          }
        case TX_GET_SKU_DETAILS:
          {
            int apiVersion = data.readInt();
            data.readString(); // packageName
            String type = data.readString();
            Bundle skusBundle = readNullableBundle(data);
            writeBundleReply(
                reply, buildDetailsBundle(type, skusBundle, apiVersion >= 17));
            return true;
          }
        case TX_GET_SKU_DETAILS_EXTRA_PARAMS:
          {
            int apiVersion = data.readInt();
            data.readString();
            String type = data.readString();
            Bundle skusBundle = readNullableBundle(data);
            Bundle extraParams = readNullableBundle(data);
            String responseFormat =
                extraParams != null ? extraParams.getString("SKU_DETAILS_RESPONSE_FORMAT") : null;
            boolean productDetails =
                apiVersion >= 17 || "PRODUCT_DETAILS".equalsIgnoreCase(responseFormat);
            writeBundleReply(reply, buildDetailsBundle(type, skusBundle, productDetails));
            return true;
          }
        case TX_GET_BUY_INTENT:
          {
            data.readInt();
            String pkg = data.readString();
            String sku = data.readString();
            String type = data.readString();
            data.readString(); // developerPayload
            writeBundleReply(reply, buildBuyIntentBundle(pkg, sku, type));
            return true;
          }
        case TX_GET_BUY_INTENT_EXTRA_PARAMS:
          {
            data.readInt();
            String pkg = data.readString();
            String sku = data.readString();
            String type = data.readString();
            data.readString();
            readNullableBundle(data);
            writeBundleReply(reply, buildBuyIntentBundle(pkg, sku, type));
            return true;
          }
        case TX_GET_PURCHASES:
          {
            data.readInt();
            data.readString();
            String type = data.readString();
            data.readString(); // continuationToken
            writeBundleReply(reply, buildPurchasesBundle(type));
            return true;
          }
        case TX_GET_PURCHASES_EXTRA_PARAMS:
          {
            data.readInt();
            data.readString();
            String type = data.readString();
            data.readString();
            readNullableBundle(data);
            writeBundleReply(reply, buildPurchasesBundle(type));
            return true;
          }
        case TX_CONSUME_PURCHASE:
          {
            data.readInt();
            data.readString();
            String token = data.readString();
            reply.writeNoException();
            reply.writeInt(consumeToken(token));
            return true;
          }
        case TX_CONSUME_PURCHASE_EXTRA_PARAMS:
          {
            data.readInt();
            data.readString();
            String token = data.readString();
            readNullableBundle(data);
            Bundle b = new Bundle();
            b.putInt("RESPONSE_CODE", consumeToken(token));
            b.putString("DEBUG_MESSAGE", "");
            writeBundleReply(reply, b);
            return true;
          }
        case TX_ACKNOWLEDGE_PURCHASE:
          {
            data.readInt();
            data.readString();
            String token = data.readString();
            readNullableBundle(data);
            Bundle b = new Bundle();
            b.putInt("RESPONSE_CODE", acknowledgeToken(token));
            b.putString("DEBUG_MESSAGE", "");
            writeBundleReply(reply, b);
            return true;
          }
        default:
          return super.onTransact(code, data, reply, flags);
      }
    }

    private Bundle buildDetailsBundle(String type, Bundle skusBundle, boolean productDetails) {
      Bundle out = new Bundle();
      int status = checkBillingSupported();
      out.putInt("RESPONSE_CODE", status);
      if (status != RESULT_OK) return out;
      ArrayList<String> ids =
          skusBundle != null ? skusBundle.getStringArrayList("ITEM_ID_LIST") : null;
      ArrayList<String> list = new ArrayList<>(queryDetailsJson(type, ids, productDetails));
      out.putStringArrayList("DETAILS_LIST", list);
      return out;
    }

    private Bundle buildBuyIntentBundle(String pkg, String sku, String type) {
      Bundle out = new Bundle();
      int rc = executeBuy(pkg, sku, type);
      out.putInt("RESPONSE_CODE", rc);
      if (rc == RESULT_OK && app != null) {
        Intent flowIntent = new Intent(ACTION_PURCHASE_FLOW).putExtra("RESPONSE_CODE", rc);
        PurchaseRecord latest = findLatestPurchase(sku);
        if (latest != null) {
          try {
            flowIntent.putExtra("INAPP_PURCHASE_DATA", latest.toPurchaseJson().toString());
            flowIntent.putExtra("INAPP_DATA_SIGNATURE", "sim-signature-" + latest.orderId);
          } catch (JSONException ignored) {
          }
        }
        PendingIntent pi =
            PendingIntent.getActivity(
                app,
                sku.hashCode(),
                flowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        out.putParcelable("BUY_INTENT", pi);
      }
      return out;
    }

    private PurchaseRecord findLatestPurchase(String sku) {
      PurchaseRecord found = null;
      synchronized (purchases) {
        for (PurchaseRecord rec : purchases.values()) {
          if (sku.equals(rec.productId)) found = rec;
        }
      }
      return found;
    }

    private Bundle buildPurchasesBundle(String type) {
      Bundle out = new Bundle();
      int status = checkBillingSupported();
      out.putInt("RESPONSE_CODE", status);
      if (status != RESULT_OK) return out;
      ArrayList<String> items = new ArrayList<>();
      ArrayList<String> dataList = new ArrayList<>();
      ArrayList<String> sigList = new ArrayList<>();
      for (PurchaseRecord rec : activePurchases(type)) {
        try {
          items.add(rec.productId);
          dataList.add(rec.toPurchaseJson().toString());
          sigList.add("sim-signature-" + rec.orderId);
        } catch (JSONException ignored) {
        }
      }
      out.putStringArrayList("INAPP_PURCHASE_ITEM_LIST", items);
      out.putStringArrayList("INAPP_PURCHASE_DATA_LIST", dataList);
      out.putStringArrayList("INAPP_DATA_SIGNATURE_LIST", sigList);
      return out;
    }

    private Bundle readNullableBundle(Parcel data) {
      if (data.readInt() != 0) {
        return Bundle.CREATOR.createFromParcel(data);
      }
      return null;
    }

    private void writeBundleReply(Parcel reply, Bundle bundle) {
      reply.writeNoException();
      if (bundle != null) {
        reply.writeInt(1);
        bundle.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
      } else {
        reply.writeInt(0);
      }
    }
  }
}
