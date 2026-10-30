package ee.schimke.composeai.simulator;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.provider.MediaStore;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

/**
 * The system apps a device answers common intents with, installed in {@code PackageManager} with
 * an activity and intent filters each, but no code: the documents and photo pickers, the camera,
 * the share sheet, a browser, a dialer and an email app. An app that checks {@code
 * resolveActivity} or {@code queryIntentActivities} before it opens a file picker or a link sees
 * them; when it starts one, {@link SystemPickers} or {@link Handoffs} answers in the activity's
 * place, since none of them has a class to run.
 */
final class SystemApps {

  static final String DOCUMENTS = "com.android.documentsui";
  static final String MEDIA = "com.android.providers.media.module";
  static final String CAMERA = "com.android.camera2";
  static final String RESOLVER = "com.android.intentresolver";
  static final String BROWSER = "com.android.browser";
  static final String DIALER = "com.android.dialer";
  static final String EMAIL = "com.android.email";

  /** {@code MediaStore.ACTION_PICK_IMAGES}, by name: it is API 33. */
  static final String ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES";

  private static final String[] ANY_TYPE = {"*/*"};
  private static final String[] NONE = {};

  static void install(Context context) {
    ShadowPackageManager packages = Shadow.extract(context.getPackageManager());
    String[] documentActions = {
      Intent.ACTION_OPEN_DOCUMENT,
      Intent.ACTION_CREATE_DOCUMENT,
      Intent.ACTION_GET_CONTENT,
      Intent.ACTION_OPEN_DOCUMENT_TREE
    };
    activity(
        packages,
        DOCUMENTS,
        "com.android.documentsui.picker.PickActivity",
        filter(documentActions, new String[] {Intent.CATEGORY_OPENABLE}, NONE, ANY_TYPE),
        filter(documentActions, NONE, NONE, ANY_TYPE),
        filter(documentActions, NONE, NONE, NONE));
    activity(
        packages,
        MEDIA,
        "com.android.providers.media.photopicker.PhotoPickerActivity",
        filter(new String[] {ACTION_PICK_IMAGES}, NONE, NONE, new String[] {"image/*", "video/*"}),
        filter(new String[] {ACTION_PICK_IMAGES}, NONE, NONE, NONE));
    activity(
        packages,
        CAMERA,
        "com.android.camera.CaptureActivity",
        filter(
            new String[] {MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.ACTION_VIDEO_CAPTURE},
            NONE,
            NONE,
            NONE));
    activity(
        packages,
        RESOLVER,
        "com.android.intentresolver.ChooserActivity",
        filter(new String[] {Intent.ACTION_CHOOSER}, NONE, NONE, NONE),
        filter(
            new String[] {Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE},
            NONE,
            NONE,
            ANY_TYPE));
    activity(
        packages,
        BROWSER,
        "com.android.browser.BrowserActivity",
        filter(
            new String[] {Intent.ACTION_VIEW},
            new String[] {Intent.CATEGORY_BROWSABLE},
            new String[] {"http", "https"},
            NONE));
    activity(
        packages,
        DIALER,
        "com.android.dialer.DialtactsActivity",
        filter(
            new String[] {Intent.ACTION_DIAL, Intent.ACTION_VIEW},
            new String[] {Intent.CATEGORY_BROWSABLE},
            new String[] {"tel"},
            NONE));
    activity(
        packages,
        EMAIL,
        "com.android.email.activity.ComposeActivity",
        filter(
            new String[] {Intent.ACTION_SENDTO, Intent.ACTION_VIEW},
            new String[] {Intent.CATEGORY_BROWSABLE},
            new String[] {"mailto"},
            NONE));
  }

  /** Whether [packageName] is one of these, which run nothing. */
  static boolean isSystemApp(String packageName) {
    switch (packageName) {
      case DOCUMENTS:
      case MEDIA:
      case CAMERA:
      case RESOLVER:
      case BROWSER:
      case DIALER:
      case EMAIL:
        return true;
      default:
        return false;
    }
  }

  private static void activity(
      ShadowPackageManager packages, String pkg, String name, IntentFilter... filters) {
    ApplicationInfo application = new ApplicationInfo();
    application.packageName = pkg;
    application.flags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_INSTALLED;
    application.enabled = true;
    PackageInfo info = new PackageInfo();
    info.packageName = pkg;
    info.applicationInfo = application;
    packages.installPackage(info);
    ComponentName component = new ComponentName(pkg, name);
    ActivityInfo activity = packages.addActivityIfNotPresent(component);
    activity.exported = true;
    activity.applicationInfo = application;
    packages.addOrUpdateActivity(activity);
    for (IntentFilter filter : filters) packages.addIntentFilterForActivity(component, filter);
  }

  private static IntentFilter filter(
      String[] actions, String[] categories, String[] schemes, String[] types) {
    IntentFilter filter = new IntentFilter();
    for (String action : actions) filter.addAction(action);
    filter.addCategory(Intent.CATEGORY_DEFAULT);
    for (String category : categories) filter.addCategory(category);
    for (String scheme : schemes) filter.addDataScheme(scheme);
    for (String type : types) {
      try {
        filter.addDataType(type);
      } catch (IntentFilter.MalformedMimeTypeException e) {
        throw new IllegalArgumentException(type, e);
      }
    }
    return filter;
  }

  private SystemApps() {}
}
