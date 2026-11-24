package ee.schimke.composeai.simulator;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import java.nio.file.Path;
import org.robolectric.shadows.ShadowPackageParser;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * {@code InstrumentationRegistry.getInstrumentation().getContext()} during {@code --instrument}:
 * the test APK's own context, as a device gives it, beside the app's target context. Its assets
 * and resources are the test APK's ({@code androidTest/assets}, {@code androidTest/res}), and its
 * package is the test package; everything else (files, services, the content resolver) is the
 * app's, since one process serves both, as it does on a device.
 *
 * <p>Robolectric's instrumentation answers the target context for both, so a test reading a
 * fixture from its own assets found nothing ({@code FileNotFoundException}).
 */
final class TestApkContext extends ContextWrapper {

  private final AssetManager assets;
  private final Resources resources;
  private final String packageName;

  private TestApkContext(Context app, AssetManager assets, String packageName) {
    super(app);
    this.assets = assets;
    Resources base = app.getResources();
    this.resources = new Resources(assets, base.getDisplayMetrics(), base.getConfiguration());
    this.packageName = packageName;
  }

  /** The test APK's context, or null when its assets cannot be opened (then: the app's). */
  static Context create(Context app, String testApk) {
    try {
      AssetManager assets = AssetManager.class.getConstructor().newInstance();
      int cookie =
          ReflectionHelpers.callInstanceMethod(
              assets, "addAssetPath", ClassParameter.from(String.class, testApk));
      if (cookie == 0) throw new IllegalStateException("addAssetPath returned 0");
      Object parsed =
          ReflectionHelpers.callStaticMethod(
              ShadowPackageParser.class,
              "callParsePackage",
              ClassParameter.from(Path.class, Path.of(testApk)));
      String packageName = ReflectionHelpers.getField(parsed, "packageName");
      return new TestApkContext(app, assets, packageName);
    } catch (ReflectiveOperationException | RuntimeException e) {
      System.err.println("instrument: cannot open the test APK's assets (" + e + "); getContext() is the app's");
      return null;
    }
  }

  @Override
  public AssetManager getAssets() {
    return assets;
  }

  @Override
  public Resources getResources() {
    return resources;
  }

  @Override
  public String getPackageName() {
    return packageName;
  }

  @Override
  public String getOpPackageName() {
    return getBaseContext().getOpPackageName();
  }
}
