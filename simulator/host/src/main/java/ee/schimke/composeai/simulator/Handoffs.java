package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Intents that leave the app: the share sheet ({@code ACTION_CHOOSER}, {@code SEND}), a link for
 * the browser, a number for the dialer, an address for email — anything that resolves only to one
 * of {@link SystemApps}, which run nothing. A device opens another app; here the intent is reported
 * as a {@code handoff} event with what it carried, and the app goes on as if the other app had come
 * and gone: a caller waiting for a result gets {@code RESULT_CANCELED}.
 *
 * <p>Content the intent hands over ({@code data}, {@code EXTRA_STREAM}, its clip) is read through
 * the app's own grant, as the receiving app would read it, and copied to {@code
 * /data/local/tmp/handoff/}, whose host path the event names: what the app shared from its {@code
 * FileProvider} can be inspected after.
 *
 * <p>An intent that one of the app's own activities handles is left alone, unless it is a chooser:
 * a device's share sheet lists the app's own targets beside the others, and the event names them
 * ({@code appTargets}) for the agent to start if it wants.
 */
final class Handoffs {

  private final Application app;
  private final Protocol protocol;
  private final Navigator navigator;
  private int sequence;

  Handoffs(Application app, Protocol protocol, Navigator navigator) {
    this.app = app;
    this.protocol = protocol;
    this.navigator = navigator;
    navigator.addSystemUi(this::handle);
  }

  private boolean handle(Intent intent, int requestCode, Activity requester) {
    boolean chooser = Intent.ACTION_CHOOSER.equals(intent.getAction());
    @SuppressWarnings("deprecation")
    Intent target = chooser ? intent.getParcelableExtra(Intent.EXTRA_INTENT) : intent;
    String handler;
    JSONArray appTargets = new JSONArray();
    if (chooser) {
      handler = SystemApps.RESOLVER;
      if (target != null) {
        for (ComponentName own : appActivities(target)) appTargets.put(own.flattenToShortString());
      }
    } else {
      if (intent.getComponent() != null) {
        handler = intent.getComponent().getPackageName();
        if (!SystemApps.isSystemApp(handler)) return false;
      } else {
        if (!appActivities(intent).isEmpty()) return false;
        handler = systemHandler(intent);
        if (handler == null) return false;
      }
    }
    Protocol.Json event =
        new Protocol.Json()
            .put("action", intent.getAction())
            .put("handler", handler)
            .put("intent", Intents.toJson(target != null ? target : intent))
            .put("requestCode", requestCode)
            .put("answer", requestCode >= 0 ? "RESULT_CANCELED" : null);
    if (chooser) {
      CharSequence title = intent.getCharSequenceExtra(Intent.EXTRA_TITLE);
      event.put("title", title == null ? null : title.toString()).put("appTargets", appTargets);
    }
    if (target != null) {
      CharSequence text = target.getCharSequenceExtra(Intent.EXTRA_TEXT);
      event.put("text", text == null ? null : text.toString());
      event.put("files", copyShared(target));
    }
    protocol.event("handoff", event.object());
    if (requester != null && requestCode >= 0 && navigator.isRunning(requester)) {
      navigator.deliverResult(requester, requestCode, Activity.RESULT_CANCELED, null);
    }
    return true;
  }

  /** The app's own activities that handle [intent]. */
  private List<ComponentName> appActivities(Intent intent) {
    List<ComponentName> own = new ArrayList<>();
    if (intent.getComponent() != null) {
      if (intent.getComponent().getPackageName().equals(app.getPackageName())) {
        own.add(intent.getComponent());
      }
      return own;
    }
    for (ResolveInfo info : app.getPackageManager().queryIntentActivities(intent, 0)) {
      if (info.activityInfo != null && info.activityInfo.packageName.equals(app.getPackageName())) {
        own.add(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
      }
    }
    return own;
  }

  /** The system app that would take [intent], or null when none does. */
  private String systemHandler(Intent intent) {
    for (ResolveInfo info : app.getPackageManager().queryIntentActivities(intent, 0)) {
      if (info.activityInfo != null && SystemApps.isSystemApp(info.activityInfo.packageName)) {
        return info.activityInfo.packageName;
      }
    }
    return null;
  }

  /** The content URIs [intent] hands over, each copied out; one that cannot be read says why. */
  private JSONArray copyShared(Intent intent) {
    Set<Uri> uris = new LinkedHashSet<>();
    Uri data = intent.getData();
    if (data != null && "content".equals(data.getScheme())) uris.add(data);
    Object stream = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_STREAM);
    if (stream instanceof Uri) uris.add((Uri) stream);
    if (stream instanceof List) {
      for (Object item : (List<?>) stream) if (item instanceof Uri) uris.add((Uri) item);
    }
    ClipData clip = intent.getClipData();
    if (clip != null) {
      for (int i = 0; i < clip.getItemCount(); i++) {
        Uri uri = clip.getItemAt(i).getUri();
        if (uri != null) uris.add(uri);
      }
    }
    JSONArray files = new JSONArray();
    if (uris.isEmpty()) return files;
    File dir =
        new File(
            Environment.getExternalStorageDirectory().getParentFile(),
            "local-tmp/handoff/" + (++sequence));
    dir.mkdirs();
    for (Uri uri : uris) files.put(copy(uri, dir));
    return files;
  }

  private JSONObject copy(Uri uri, File dir) {
    Protocol.Json json = new Protocol.Json().put("uri", uri.toString());
    String name = displayName(uri);
    json.put("name", name).put("type", app.getContentResolver().getType(uri));
    File out = new File(dir, name == null || name.contains("/") ? "file" : name);
    try (InputStream in = app.getContentResolver().openInputStream(uri)) {
      if (in == null) throw new java.io.FileNotFoundException("no content for " + uri);
      long size = Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
      json.put("size", size)
          .put("path", "/data/local/tmp/handoff/" + dir.getName() + "/" + out.getName())
          .put("hostPath", out.getPath());
    } catch (Exception e) {
      json.put("error", String.valueOf(e));
    }
    return json.object();
  }

  private String displayName(Uri uri) {
    try (Cursor cursor =
        app.getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
    } catch (RuntimeException e) {
      // The provider has no name for it; fall back to the path.
    }
    return uri.getLastPathSegment();
  }
}
