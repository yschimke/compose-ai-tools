package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.gms.wearable.Node;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

/**
 * The phone's Wear OS companion app, as a paired phone has it: the Pixel Watch app ({@code
 * com.google.android.apps.wear.companion}), installed with one launcher activity. {@code
 * NodeClient.getCompanionPackageForNode} names it on the phone, as it does for a Pixel Watch, and
 * Horologist's {@code PhoneDataLayerAppHelper} relies on both: {@code startCompanion} launches it
 * ({@code getLaunchIntentForPackage}, so without it every call answered {@code
 * NO_COMPANION_FOUND}, from the phone and from the watch alike), and {@code
 * findWatchToInstallTile} offers the install-tile prompt only for a watch it manages.
 *
 * <p>The activity is the simulator's own, plain views: the watch it is paired with and that it is
 * connected. Nothing of the real app (setup, watch faces, settings) is simulated.
 */
public final class PhoneCompanion extends Activity {

  /** The Pixel Watch app's package. */
  static final String PACKAGE = "com.google.android.apps.wear.companion";

  static final String LABEL = "Pixel Watch";

  public PhoneCompanion() {}

  /** Installs the companion app's package and launcher activity on the phone. */
  static void install(Application app) {
    ShadowPackageManager shadow = Shadow.extract(app.getPackageManager());
    ApplicationInfo application = new ApplicationInfo();
    application.packageName = PACKAGE;
    application.name = null;
    application.nonLocalizedLabel = LABEL;
    application.enabled = true;
    application.flags = ApplicationInfo.FLAG_INSTALLED;
    application.sourceDir = app.getApplicationInfo().sourceDir;
    application.publicSourceDir = app.getApplicationInfo().publicSourceDir;
    application.targetSdkVersion = app.getApplicationInfo().targetSdkVersion;

    ActivityInfo activity = new ActivityInfo();
    activity.name = PhoneCompanion.class.getName();
    activity.packageName = PACKAGE;
    activity.applicationInfo = application;
    activity.nonLocalizedLabel = LABEL;
    activity.exported = true;
    activity.enabled = true;
    activity.theme = android.R.style.Theme_Material_Light_NoActionBar;
    activity.configChanges = 0x7fffffff;
    activity.flags |= ActivityInfo.FLAG_HARDWARE_ACCELERATED;

    PackageInfo info = new PackageInfo();
    info.packageName = PACKAGE;
    info.versionName = "simulated";
    info.applicationInfo = application;
    info.activities = new ActivityInfo[] {activity};
    shadow.installPackage(info);
    shadow.addOrUpdateActivity(activity);
    IntentFilter launcher = new IntentFilter(Intent.ACTION_MAIN);
    launcher.addCategory(Intent.CATEGORY_LAUNCHER);
    shadow.addIntentFilterForActivity(new ComponentName(PACKAGE, activity.name), launcher);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    setTheme(android.R.style.Theme_Material_Light_NoActionBar);
    super.onCreate(savedInstanceState);
    setTitle(LABEL);
    float density = getResources().getDisplayMetrics().density;
    int pad = Math.round(24 * density);
    LinearLayout column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setGravity(Gravity.CENTER_HORIZONTAL);
    column.setPadding(pad, pad * 2, pad, pad);
    column.setBackgroundColor(Color.WHITE);
    column.addView(PhoneHome.text(this, LABEL, 26, 0xFF1F1F1F));
    column.addView(
        PhoneHome.text(this, "Simulated Wear OS companion app", 14, 0xFF5F6368));
    DataLayer node = DataLayer.enabled() ? DataLayer.get() : null;
    java.util.List<Node> watches =
        node != null ? node.connectedNodes() : java.util.Collections.<Node>emptyList();
    if (watches.isEmpty()) {
      column.addView(spaced(PhoneHome.text(this, "No watch connected", 18, 0xFF1F1F1F), pad));
    }
    for (Node watch : watches) {
      column.addView(spaced(PhoneHome.text(this, watch.getDisplayName(), 20, 0xFF1F1F1F), pad));
      column.addView(PhoneHome.text(this, "Connected · " + watch.getId(), 14, 0xFF146C2E));
    }
    setContentView(column);
  }

  private static TextView spaced(TextView view, int top) {
    view.setPadding(0, top, 0, 0);
    return view;
  }
}
