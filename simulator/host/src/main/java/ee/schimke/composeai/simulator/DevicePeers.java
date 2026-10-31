package ee.schimke.composeai.simulator;

import android.app.Application;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowPackageParser;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * The other apps installed on the device ({@link DeviceBus#PEERS_PROPERTY}), in this app's {@code
 * PackageManager}: each APK's manifest parsed as the system parses it at install, with its
 * activities, services, receivers and providers and their intent filters, so {@code
 * getPackageInfo}, {@code queryIntentServices} and package visibility find them as on a device.
 * Their code runs in their own simulators; this one only knows their manifests.
 */
final class DevicePeers {

  /** The packages installed, in launch order. */
  private static final List<String> packages = new ArrayList<>();

  /**
   * The host directory that is the device's shared storage ({@code /sdcard}), the same for each of
   * its apps; unset for an app alone on its device.
   */
  static final String SDCARD_PROPERTY = "composeai.simulator.device.sdcard";

  /**
   * Before anything opens a file: the device's apps share its {@code /sdcard}, as they do on a
   * device. Each app's own external directories ({@code Android/data/<package>}) are under it.
   */
  static void shareStorage() {
    String sdcard = System.getProperty(SDCARD_PROPERTY);
    if (sdcard == null || sdcard.isEmpty()) return;
    Path root = Path.of(sdcard);
    try {
      java.nio.file.Files.createDirectories(root);
    } catch (java.io.IOException e) {
      System.err.println("device: cannot create " + root + ": " + e);
      return;
    }
    ShadowEnvironment.setExternalStorageDirectory(root);
    // getExternalStoragePublicDirectory (Download/, Pictures/, ...) reads its own root.
    ShadowEnvironment.setExternalStoragePublicDirectory(root);
  }

  /** Before {@code Application.onCreate()}. */
  static void install(Application app) {
    String peers = System.getProperty(DeviceBus.PEERS_PROPERTY);
    if (peers == null || peers.isEmpty()) return;
    ShadowPackageManager manager = Shadow.extract(app.getPackageManager());
    for (String path : peers.split(File.pathSeparator)) {
      if (path.isEmpty()) continue;
      try {
        // PackageParser.Package is hidden: reached by reflection, as ApkAppLoader parses the app.
        Object parsed =
            ReflectionHelpers.callStaticMethod(
                ShadowPackageParser.class,
                "callParsePackage",
                ClassParameter.from(Path.class, Path.of(path)));
        ReflectionHelpers.callInstanceMethod(
            manager,
            "addPackageInternal",
            ClassParameter.from(parsed.getClass(), parsed));
        packages.add(ReflectionHelpers.getField(parsed, "packageName"));
      } catch (RuntimeException e) {
        System.err.println("device: cannot install " + path + ": " + e);
      }
    }
  }

  static boolean isPeer(String packageName) {
    return packages.contains(packageName);
  }

  static List<String> packages() {
    return new ArrayList<>(packages);
  }

  private DevicePeers() {}
}
