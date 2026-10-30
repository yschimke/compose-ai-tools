package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The system's pickers, which a device shows over the app and the agent answers here: the
 * Storage Access Framework's ({@code OPEN_DOCUMENT}, {@code GET_CONTENT}, {@code CREATE_DOCUMENT},
 * {@code OPEN_DOCUMENT_TREE}), the photo picker ({@code PICK_IMAGES}, which androidx's {@code
 * PickVisualMedia} starts), and the camera's capture ({@code IMAGE_CAPTURE}, androidx's {@code
 * TakePicture}).
 *
 * <p>Under the {@code ask} policy (the default with a control channel) each request is reported as
 * a {@code pick} event and waits, as the picker would on screen, until the {@code pick} command
 * answers it: with {@code files} (device paths in the simulated storage, or host files, which are
 * copied to {@code /sdcard/Download/} first), with a {@code path} to create or a directory to
 * grant, with an image for the camera, or {@code cancel}. Under {@code cancel} (the default without
 * one) every request comes back {@code RESULT_CANCELED} at once, as a person backing out. Picked
 * files are {@link DocumentsStore} URIs the app can query and open.
 */
final class SystemPickers {

  enum Kind {
    OPEN("open"),
    CREATE("create"),
    TREE("tree"),
    MEDIA("media"),
    CAMERA("camera");

    final String id;

    Kind(String id) {
      this.id = id;
    }
  }

  private static final class Request {
    final int id;
    final Kind kind;
    final Intent intent;
    final int requestCode;
    final Activity requester;

    Request(int id, Kind kind, Intent intent, int requestCode, Activity requester) {
      this.id = id;
      this.kind = kind;
      this.intent = intent;
      this.requestCode = requestCode;
      this.requester = requester;
    }
  }

  private final Application app;
  private final Protocol protocol;
  private final Navigator navigator;
  private final AppFiles files;
  private final List<Request> pending = new ArrayList<>();
  private int nextId = 1;
  private String policy;

  SystemPickers(Application app, Protocol protocol, Navigator navigator, AppFiles files) {
    this.app = app;
    this.protocol = protocol;
    this.navigator = navigator;
    this.files = files;
    this.policy = protocol.enabled() ? "ask" : "cancel";
    navigator.addSystemUi(this::handle);
  }

  /** Which picker [intent] opens, or null when it is not one of the system's. */
  static Kind kindOf(Intent intent) {
    String action = intent.getAction();
    if (action == null) return null;
    switch (action) {
      case Intent.ACTION_OPEN_DOCUMENT:
      case Intent.ACTION_GET_CONTENT:
        return Kind.OPEN;
      case Intent.ACTION_CREATE_DOCUMENT:
        return Kind.CREATE;
      case Intent.ACTION_OPEN_DOCUMENT_TREE:
        return Kind.TREE;
      case SystemApps.ACTION_PICK_IMAGES:
        return Kind.MEDIA;
      case MediaStore.ACTION_IMAGE_CAPTURE:
        return Kind.CAMERA;
      default:
        return null;
    }
  }

  private boolean handle(Intent intent, int requestCode, Activity requester) {
    Kind kind = kindOf(intent);
    if (kind == null) return false;
    if (intent.getComponent() != null
        && !SystemApps.isSystemApp(intent.getComponent().getPackageName())) {
      // Addressed to a component of the app's own: it is the app's to run.
      return false;
    }
    Request request = new Request(nextId++, kind, intent, requestCode, requester);
    JSONObject described = describe(request);
    if (requestCode < 0 || requester == null || policy.equals("cancel")) {
      protocol.event("pick", put(described, "answer", "RESULT_CANCELED"));
      deliver(request, Activity.RESULT_CANCELED, null);
      return true;
    }
    pending.add(request);
    protocol.event("pick", put(described, "waiting", true));
    return true;
  }

  /** The {@code pick} command. */
  JSONObject command(JSONObject c) throws JSONException, IOException {
    if (c.has("policy")) {
      String value = c.getString("policy");
      if (!value.equals("ask") && !value.equals("cancel")) {
        throw new IllegalArgumentException("pick policy is ask or cancel, not " + value);
      }
      policy = value;
    }
    if (c.has("answer") || c.optBoolean("cancel", false) || c.has("files") || c.has("path")) {
      return answer(c);
    }
    JSONArray waiting = new JSONArray();
    for (Request request : pending) waiting.put(describe(request));
    return new Protocol.Json().put("policy", policy).put("pending", waiting).object();
  }

  private JSONObject answer(JSONObject c) throws JSONException, IOException {
    // `answer` names the request's id; "next", or none, the oldest one waiting.
    Object which = c.opt("answer");
    Request request = take(which instanceof Number ? ((Number) which).intValue() : -1);
    if (c.optBoolean("cancel", false)) {
      deliver(request, Activity.RESULT_CANCELED, null);
      return put(describe(request), "answer", "RESULT_CANCELED");
    }
    Intent data;
    JSONArray uris = new JSONArray();
    switch (request.kind) {
      case OPEN:
      case MEDIA:
        data = picked(request, c, uris);
        break;
      case CREATE:
        data = created(request, c, uris);
        break;
      case TREE:
        data = tree(c, uris);
        break;
      case CAMERA:
        data = captured(request, c, uris);
        break;
      default:
        throw new IllegalStateException(String.valueOf(request.kind));
    }
    deliver(request, Activity.RESULT_OK, data);
    JSONObject reply = put(describe(request), "answer", "RESULT_OK");
    return put(reply, "uris", uris);
  }

  /** The waiting request [id], or the oldest when [id] is -1. */
  private Request take(int id) {
    for (Iterator<Request> it = pending.iterator(); it.hasNext(); ) {
      Request request = it.next();
      if (id < 0 || request.id == id) {
        it.remove();
        return request;
      }
    }
    throw new IllegalArgumentException(
        id < 0 ? "no picker is waiting" : "no picker request " + id + " is waiting");
  }

  private Intent picked(Request request, JSONObject c, JSONArray uris)
      throws JSONException, IOException {
    JSONArray paths = c.optJSONArray("files");
    if (paths == null || paths.length() == 0) {
      throw new IllegalArgumentException("answer a " + request.kind.id + " picker with files");
    }
    boolean multiple = allowsMultiple(request.intent);
    if (paths.length() > 1 && !multiple) {
      throw new IllegalArgumentException("this picker takes one file, not " + paths.length());
    }
    List<Uri> picked = new ArrayList<>();
    for (int i = 0; i < paths.length(); i++) {
      File file = inStorage(paths.getString(i));
      if (!file.isFile()) throw new IllegalArgumentException("no such file: " + paths.getString(i));
      picked.add(DocumentsStore.documentUri(file));
    }
    Intent data = new Intent();
    if (picked.size() == 1) data.setData(picked.get(0));
    if (multiple) {
      ClipData clip = ClipData.newRawUri(null, picked.get(0));
      for (int i = 1; i < picked.size(); i++) clip.addItem(new ClipData.Item(picked.get(i)));
      data.setClipData(clip);
    }
    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    if (Intent.ACTION_OPEN_DOCUMENT.equals(request.intent.getAction())) {
      flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
    }
    data.addFlags(flags);
    for (Uri uri : picked) uris.put(uri.toString());
    return data;
  }

  private Intent created(Request request, JSONObject c, JSONArray uris) throws IOException {
    String title = request.intent.getStringExtra(Intent.EXTRA_TITLE);
    String path =
        c.optString("path", "/sdcard/Download/" + (title == null ? "Untitled" : title));
    File file = files.resolve(path);
    if (file.isDirectory()) {
      file = new File(file, title == null ? "Untitled" : title);
    }
    if (!c.has("path")) {
      File dir = file.getParentFile();
      String name = DocumentIds.unique(file.getName(), n -> new File(dir, n).exists());
      file = new File(dir, name);
    }
    checkInStorage(file, path);
    file.getParentFile().mkdirs();
    if (!file.exists() && !file.createNewFile()) throw new IOException("cannot create " + file);
    Uri uri = DocumentsStore.documentUri(file);
    uris.put(uri.toString());
    return new Intent()
        .setData(uri)
        .addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
  }

  private Intent tree(JSONObject c, JSONArray uris) {
    String path = c.optString("path", "/sdcard/Download");
    File dir = files.resolve(path);
    checkInStorage(dir, path);
    dir.mkdirs();
    Uri uri = DocumentsStore.treeUri(dir);
    uris.put(uri.toString());
    return new Intent()
        .setData(uri)
        .addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
  }

  /**
   * The camera's answer: [c]'s {@code files} first entry (an image file) written to the request's
   * {@code EXTRA_OUTPUT}, else a generated test image; without an output, a thumbnail in {@code
   * data}, as the camera returns one.
   */
  private Intent captured(Request request, JSONObject c, JSONArray uris)
      throws JSONException, IOException {
    JSONArray paths = c.optJSONArray("files");
    File source = paths == null || paths.length() == 0 ? null : inStorage(paths.getString(0));
    // The untyped overload: the typed one is API 33.
    @SuppressWarnings("deprecation")
    Uri output = request.intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
    if (output == null) {
      Bitmap thumbnail = testImage(160, 120);
      return new Intent().putExtra("data", thumbnail);
    }
    try (OutputStream out = app.getContentResolver().openOutputStream(output)) {
      if (out == null) throw new IOException("cannot write " + output);
      if (source != null) {
        Files.copy(source.toPath(), out);
      } else {
        testImage(640, 480).compress(Bitmap.CompressFormat.JPEG, 90, out);
      }
    }
    uris.put(output.toString());
    return new Intent();
  }

  /** A gradient with a cross, so a picture the app shows is visibly the simulator's. */
  private static Bitmap testImage(int width, int height) {
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint();
    for (int y = 0; y < height; y++) {
      paint.setColor(Color.rgb(40, 80 + 120 * y / height, 160));
      canvas.drawLine(0, y, width, y, paint);
    }
    paint.setColor(Color.WHITE);
    paint.setStrokeWidth(Math.max(2, width / 80f));
    canvas.drawLine(0, 0, width, height, paint);
    canvas.drawLine(width, 0, 0, height, paint);
    return bitmap;
  }

  /**
   * [path] as a file in the simulated storage: a device path as is, a host file copied into {@code
   * /sdcard/Download/} (once; a file of that name already there is replaced).
   */
  private File inStorage(String path) throws IOException {
    if (files.isDevicePath(path)) {
      File file = files.resolve(path);
      checkInStorage(file, path);
      return file;
    }
    File host = new File(path).getAbsoluteFile();
    if (!host.isFile()) throw new IllegalArgumentException("no such file: " + path);
    File downloads = new File(DocumentsStore.root(), "Download");
    downloads.mkdirs();
    File copy = new File(downloads, host.getName());
    Files.copy(host.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
    return copy;
  }

  private static void checkInStorage(File file, String path) {
    try {
      DocumentsStore.idOf(file);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          path + " is not in the shared storage (/sdcard/…), which the pickers show");
    }
  }

  private static boolean allowsMultiple(Intent intent) {
    if (intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)) return true;
    // MediaStore.EXTRA_PICK_IMAGES_MAX, API 33.
    return intent.getIntExtra("android.provider.extra.PICK_IMAGES_MAX", 1) > 1;
  }

  private void deliver(Request request, int resultCode, Intent data) {
    if (request.requester == null || request.requestCode < 0) return;
    if (!navigator.isRunning(request.requester)) return;
    navigator.deliverResult(request.requester, request.requestCode, resultCode, data);
  }

  private static JSONObject describe(Request request) {
    Intent intent = request.intent;
    JSONArray types = new JSONArray();
    if (intent.getType() != null) types.put(intent.getType());
    String[] extra = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
    if (extra != null) for (String type : extra) types.put(type);
    return new Protocol.Json()
        .put("id", request.id)
        .put("kind", request.kind.id)
        .put("action", intent.getAction())
        .put("mimeTypes", types)
        .put("multiple", allowsMultiple(intent))
        .put("title", intent.getStringExtra(Intent.EXTRA_TITLE))
        .put("requester", request.requester == null ? null : request.requester.getClass().getName())
        .object();
  }

  private static JSONObject put(JSONObject json, String key, Object value) {
    try {
      return json.put(key, value);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }
}
