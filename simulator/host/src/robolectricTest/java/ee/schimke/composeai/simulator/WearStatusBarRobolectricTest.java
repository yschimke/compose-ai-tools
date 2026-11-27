package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.WindowInsets;
import com.google.wear.Sdk;
import com.google.wear.settings.WearSettings;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WearStatusBarRobolectricTest {

  @After
  public void clearProperties() {
    System.clearProperty(FormFactor.PROPERTY);
    System.clearProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY);
    System.clearProperty(Sdk.WEAR_API_VERSION_PROPERTY);
  }

  private Application configureApp(int targetSdk, Boolean manifestOptIn) {
    Application app = RuntimeEnvironment.getApplication();
    app.getApplicationInfo().targetSdkVersion = targetSdk;
    if (manifestOptIn == null) {
      app.getApplicationInfo().metaData = null;
    } else {
      Bundle meta = new Bundle();
      meta.putBoolean(WearSettings.METADATA_ENABLE_GLOBAL_STATUS_BAR, manifestOptIn);
      app.getApplicationInfo().metaData = meta;
    }
    return app;
  }

  private static void setRobolectricStatusBarVisible(boolean visible) throws Exception {
    Class<?> systemUi =
        Class.forName(
            "org.robolectric.shadows.SystemUi",
            true,
            WearStatusBarRobolectricTest.class.getClassLoader());
    Object ui = ReflectionHelpers.callStaticMethod(systemUi, "systemUiForDefaultDisplay");
    Object bar = ReflectionHelpers.callInstanceMethod(ui, "getStatusBar");
    ReflectionHelpers.callInstanceMethod(
        bar, "setVisible", ClassParameter.from(boolean.class, visible));
  }

  @Test
  public void wearSettingsRequiresAllPlatformAndAppConditions() {
    System.setProperty(FormFactor.PROPERTY, "wear");
    System.setProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY, "true");
    System.setProperty(Sdk.WEAR_API_VERSION_PROPERTY, "WEAR_CINNAMON_BUN_2");

    Application app = configureApp(35, true);
    assertTrue(WearSettings.isStatusBarEnabled(app));

    // targetSdk < 35 disables it.
    configureApp(34, true);
    assertFalse(WearSettings.isStatusBarEnabled(app));

    // Missing or false manifest opt-in disables it.
    configureApp(35, null);
    assertFalse(WearSettings.isStatusBarEnabled(app));
    configureApp(35, false);
    assertFalse(WearSettings.isStatusBarEnabled(app));

    // Wear OS 7.1 stable (WEAR_CINNAMON_BUN_1) disables it.
    configureApp(35, true);
    System.setProperty(Sdk.WEAR_API_VERSION_PROPERTY, "WEAR_CINNAMON_BUN_1");
    assertFalse(WearSettings.isStatusBarEnabled(app));

    // Platform flag off disables it.
    System.setProperty(Sdk.WEAR_API_VERSION_PROPERTY, "WEAR_CINNAMON_BUN_2");
    System.clearProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY);
    assertFalse(WearSettings.isStatusBarEnabled(app));

    // Non-Wear form factor disables it.
    System.setProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY, "true");
    System.setProperty(FormFactor.PROPERTY, "phone");
    assertFalse(WearSettings.isStatusBarEnabled(app));
  }

  @Test
  public void wearStatusBarTracksRobolectricInsetsVisibility() throws Exception {
    System.setProperty(FormFactor.PROPERTY, "wear");
    System.setProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY, "true");
    System.setProperty(Sdk.WEAR_API_VERSION_PROPERTY, "WEAR_CINNAMON_BUN_2");
    Application app = configureApp(35, true);

    WearStatusBar statusBar = WearStatusBar.create(app, null);
    assertNotNull(statusBar);
    assertTrue(statusBar.isEnabled());

    statusBar.start();
    JSONObject initial = statusBar.command("statusBar", new JSONObject());
    JSONObject bar = initial.getJSONObject("statusBar");
    assertTrue(bar.getBoolean("enabled"));
    assertTrue(bar.getBoolean("platformEnabled"));
    assertTrue(bar.getBoolean("appOptIn"));
    assertEquals(35, bar.getInt("targetSdk"));
    assertEquals("WEAR_CINNAMON_BUN_2", bar.getString("wearApiVersion"));
    assertTrue(bar.getBoolean("visible"));
    assertTrue(bar.getInt("heightPx") > 0);

    // An activity launched with the Wear global status bar active gets top status bar insets
    // and can hide/show the bar via WindowInsetsController, as Wear Compose 1.8's
    // StatusBarOrchestratorImpl does.
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    WindowInsets rootInsets = activity.getWindow().getDecorView().getRootWindowInsets();
    assertNotNull(rootInsets);
    assertTrue(rootInsets.getInsets(WindowInsets.Type.statusBars()).top > 0);
    assertTrue(rootInsets.isVisible(WindowInsets.Type.statusBars()));

    activity.getWindow().getInsetsController().hide(WindowInsets.Type.statusBars());
    statusBar.cycle();
    assertFalse(
        statusBar
            .command("statusBar", new JSONObject())
            .getJSONObject("statusBar")
            .getBoolean("visible"));

    activity.getWindow().getInsetsController().show(WindowInsets.Type.statusBars());
    statusBar.cycle();
    assertTrue(
        statusBar
            .command("statusBar", new JSONObject())
            .getJSONObject("statusBar")
            .getBoolean("visible"));

    // Direct SystemUi visibility updates also propagate.
    setRobolectricStatusBarVisible(false);
    statusBar.cycle();
    assertFalse(
        statusBar
            .command("statusBar", new JSONObject())
            .getJSONObject("statusBar")
            .getBoolean("visible"));
  }

  @Test
  public void wearStatusBarStaysInactiveWhenAppDoesNotOptIn() throws Exception {
    System.setProperty(FormFactor.PROPERTY, "wear");
    System.setProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY, "true");
    System.setProperty(Sdk.WEAR_API_VERSION_PROPERTY, "WEAR_CINNAMON_BUN_2");
    Application app = configureApp(35, false);

    WearStatusBar statusBar = WearStatusBar.create(app, null);
    assertNotNull(statusBar);
    assertFalse(statusBar.isEnabled());

    statusBar.start();
    JSONObject bar = statusBar.command("statusBar", new JSONObject()).getJSONObject("statusBar");
    assertFalse(bar.getBoolean("enabled"));
    assertTrue(bar.getBoolean("platformEnabled"));
    assertFalse(bar.getBoolean("appOptIn"));
    assertFalse(bar.getBoolean("visible"));
    assertEquals(0, bar.getInt("heightPx"));
  }

  @Test
  public void wearStatusBarIsNotCreatedOnPhone() {
    System.setProperty(FormFactor.PROPERTY, "phone");
    assertNull(WearStatusBar.create(RuntimeEnvironment.getApplication(), null));
  }

  @Test
  public void indicatorFollowsSingleIconPriority() {
    WearStatusBar.Signals s = new WearStatusBar.Signals();
    assertNull(WearStatusBar.pickIndicator(s));

    s.offline = true;
    assertEquals(WearStatusBar.Indicator.OFFLINE, WearStatusBar.pickIndicator(s));

    s.battery = 15;
    assertEquals(WearStatusBar.Indicator.LOW_BATTERY, WearStatusBar.pickIndicator(s));

    s.charging = true;
    assertEquals(WearStatusBar.Indicator.CHARGING, WearStatusBar.pickIndicator(s));

    s.batterySaver = true;
    assertEquals(WearStatusBar.Indicator.BATTERY_SAVER, WearStatusBar.pickIndicator(s));

    s.theater = true;
    assertEquals(WearStatusBar.Indicator.THEATER, WearStatusBar.pickIndicator(s));

    s.bedtime = true;
    assertEquals(WearStatusBar.Indicator.BEDTIME, WearStatusBar.pickIndicator(s));

    s.dnd = true;
    assertEquals(WearStatusBar.Indicator.DND, WearStatusBar.pickIndicator(s));

    s.airplane = true;
    assertEquals(WearStatusBar.Indicator.AIRPLANE, WearStatusBar.pickIndicator(s));

    s.location = true;
    assertEquals(WearStatusBar.Indicator.LOCATION, WearStatusBar.pickIndicator(s));

    s.microphone = true;
    assertEquals(WearStatusBar.Indicator.MICROPHONE, WearStatusBar.pickIndicator(s));
  }

  @Test
  public void statusBarCommandControlsModesAndPrivacyIndicators() throws Exception {
    System.setProperty(FormFactor.PROPERTY, "wear");
    System.setProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY, "true");
    System.setProperty(Sdk.WEAR_API_VERSION_PROPERTY, "WEAR_CINNAMON_BUN_2");
    Application app = configureApp(35, true);

    WearStatusBar statusBar = WearStatusBar.create(app, null);
    assertNotNull(statusBar);
    statusBar.start();

    JSONObject dnd =
        statusBar
            .command("statusBar", new JSONObject().put("dnd", true))
            .getJSONObject("statusBar");
    assertTrue(dnd.getBoolean("dnd"));
    assertEquals("dnd", dnd.getString("indicator"));

    JSONObject mic =
        statusBar
            .command("statusBar", new JSONObject().put("microphone", true))
            .getJSONObject("statusBar");
    assertTrue(mic.getBoolean("microphone"));
    assertEquals("microphone", mic.getString("indicator"));

    JSONObject cleared =
        statusBar
            .command(
                "statusBar",
                new JSONObject().put("microphone", false).put("dnd", false).put("bedtime", true))
            .getJSONObject("statusBar");
    assertFalse(cleared.getBoolean("microphone"));
    assertFalse(cleared.getBoolean("dnd"));
    assertTrue(cleared.getBoolean("bedtime"));
    assertEquals("bedtime", cleared.getString("indicator"));
  }
}
