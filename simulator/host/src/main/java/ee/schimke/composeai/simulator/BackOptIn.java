package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import java.util.HashMap;
import java.util.Map;
import org.robolectric.util.ReflectionHelpers;
import org.xmlpull.v1.XmlPullParser;

/**
 * The APK's {@code android:enableOnBackInvokedCallback}, which is how an app opts in to the
 * callback API and predictive back. Neither Robolectric's {@code ApplicationInfo} for an APK nor
 * the platform's legacy {@code PackageParser} it is checked against carries the attribute (only
 * the newer parser does), so without this no app is ever opted in and Back always arrives as
 * {@code KEYCODE_BACK}. The manifest is read here, and the flags the framework's {@code
 * WindowOnBackInvokedDispatcher} checks are set: the application's at startup, an activity's
 * (which, from API 34, overrides the application's either way) as the activity is created.
 */
final class BackOptIn {

  private static final String ANDROID = "http://schemas.android.com/apk/res/android";
  private static final String ATTRIBUTE = "enableOnBackInvokedCallback";

  private BackOptIn() {}

  static void apply(Application application) {
    Boolean app;
    Map<String, Boolean> activities = new HashMap<>();
    try (XmlResourceParser manifest =
        application.getAssets().openXmlResourceParser("AndroidManifest.xml")) {
      app = read(manifest, application.getPackageName(), activities);
    } catch (Exception e) {
      System.err.println("Could not read the manifest's " + ATTRIBUTE + ": " + e);
      return;
    }
    if (Boolean.TRUE.equals(app)) {
      ApplicationInfo info = application.getApplicationInfo();
      int flag =
          ReflectionHelpers.getStaticField(
              ApplicationInfo.class, "PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK");
      int ext = ReflectionHelpers.getField(info, "privateFlagsExt");
      ReflectionHelpers.setField(info, "privateFlagsExt", ext | flag);
    }
    if (activities.isEmpty()) return;
    application.registerActivityLifecycleCallbacks(
        new Application.ActivityLifecycleCallbacks() {
          @Override
          public void onActivityPreCreated(Activity activity, Bundle state) {
            Boolean enabled = activities.get(activity.getClass().getName());
            if (enabled == null) return;
            ActivityInfo info = ReflectionHelpers.getField(activity, "mActivityInfo");
            if (info == null) return;
            int flag =
                ReflectionHelpers.getStaticField(
                    ActivityInfo.class,
                    enabled
                        ? "PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK"
                        : "PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK");
            int flags = ReflectionHelpers.getField(info, "privateFlags");
            ReflectionHelpers.setField(info, "privateFlags", flags | flag);
          }

          @Override
          public void onActivityCreated(Activity activity, Bundle state) {}

          @Override
          public void onActivityStarted(Activity activity) {}

          @Override
          public void onActivityResumed(Activity activity) {}

          @Override
          public void onActivityPaused(Activity activity) {}

          @Override
          public void onActivityStopped(Activity activity) {}

          @Override
          public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

          @Override
          public void onActivityDestroyed(Activity activity) {}
        });
  }

  /**
   * The application's attribute (null when absent), filling [activities] with each activity's
   * that sets it, by class name.
   */
  private static Boolean read(
      XmlResourceParser parser, String packageName, Map<String, Boolean> activities)
      throws Exception {
    Boolean app = null;
    for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
      if (event != XmlPullParser.START_TAG) continue;
      String value = parser.getAttributeValue(ANDROID, ATTRIBUTE);
      if (value == null) continue;
      boolean enabled = parser.getAttributeBooleanValue(ANDROID, ATTRIBUTE, false);
      if (parser.getName().equals("application")) {
        app = enabled;
      } else if (parser.getName().equals("activity")) {
        String name = parser.getAttributeValue(ANDROID, "name");
        if (name != null) activities.put(name.startsWith(".") ? packageName + name : name, enabled);
      }
    }
    return app;
  }
}
