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
 * connected, and the destination of a {@code https://madeby.google.com/wear-companion/navigation}
 * deep link (Horologist's install-tile prompt opens {@code dest=tile_management_add}). Nothing of the real app (setup, watch faces, settings) is simulated.
 */
public final class PhoneCompanion extends Activity {

  /** The Pixel Watch app's package. */
  static final String PACKAGE = "com.google.android.apps.wear.companion";

  static final String LABEL = "Pixel Watch";

  static final String VERSION = "2.1.0.576785526";

  /** The app's deep links ({@code dest=tile_management_add}: the watch's tile editor). */
  static final String LINK_HOST = "madeby.google.com";

  static final String LINK_PATH = "/wear-companion/navigation";

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
    // Horologist's install-tile prompt needs at least this one, which deep links to tile editing.
    info.versionName = VERSION;
    info.applicationInfo = application;
    try {
      // The package first, then its activity: Robolectric indexes a component's intent filters
      // only for one added on its own.
      shadow.installPackage(info);
      ComponentName component = new ComponentName(PACKAGE, activity.name);
      shadow.addActivityIfNotPresent(component);
      shadow.addOrUpdateActivity(activity);
      IntentFilter launcher = new IntentFilter(Intent.ACTION_MAIN);
      launcher.addCategory(Intent.CATEGORY_LAUNCHER);
      shadow.addIntentFilterForActivity(component, launcher);
      IntentFilter links = new IntentFilter(Intent.ACTION_VIEW);
      links.addCategory(Intent.CATEGORY_DEFAULT);
      links.addCategory(Intent.CATEGORY_BROWSABLE);
      links.addDataScheme("https");
      links.addDataAuthority(LINK_HOST, null);
      links.addDataPath(LINK_PATH, android.os.PatternMatcher.PATTERN_PREFIX);
      shadow.addIntentFilterForActivity(component, links);
    } catch (RuntimeException e) {
      // Never fatal: without it, startCompanion answers NO_COMPANION_FOUND as before.
      System.err.println("datalayer: no companion app on the phone: " + e);
    }
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
    android.net.Uri link = getIntent() == null ? null : getIntent().getData();
    String dest = link == null ? null : link.getQueryParameter("dest");
    if (dest != null) {
      column.addView(
          spaced(
              PhoneHome.text(
                  this,
                  "tile_management_add".equals(dest) ? "Add a tile to your watch" : dest,
                  18,
                  0xFF0B57D0),
              pad));
    }
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
