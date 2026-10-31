package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * Content providers between the device's apps, each in a simulator of its own. Every authority
 * another app's manifest declares is registered here as a provider that forwards each call over the
 * {@link DeviceBus} to that app, which answers it from its own provider through its own {@code
 * ContentResolver}, on its main thread, as the binder would carry it:
 *
 * <ul>
 *   <li>{@code query} (its rows, typed, as a cursor), {@code insert}, {@code update}, {@code
 *       delete}, {@code getType} and {@code call} (its extras marshalled, {@link Parcels});
 *   <li>{@code openFile} for reading: the owning app reads the file and the caller gets a copy, as
 *       a share flow's {@code FileProvider} URI is read. Writing to another app's file is not.
 * </ul>
 *
 * <p>A provider that is neither exported nor grants URI permissions refuses another app with a
 * {@code SecurityException}; one that grants them answers, as if the grant had been made. Change
 * notifications ({@code notifyChange}) do not cross between apps. A call waits up to ten seconds
 * for its answer.
 */
final class DeviceProviders {

  private static final String OP = "provider";
  private static final long TIMEOUT_S = 10;

  /** Before {@code Application.onCreate()}: the other apps' authorities, forwarded. */
  static void install(Application app) {
    if (!DeviceBus.enabled()) return;
    for (String peer : DevicePeers.packages()) {
      PackageInfo info;
      try {
        info = app.getPackageManager().getPackageInfo(peer, PackageManager.GET_PROVIDERS);
      } catch (PackageManager.NameNotFoundException e) {
        continue;
      }
      if (info.providers == null) continue;
      for (ProviderInfo provider : info.providers) {
        if (provider.authority == null) continue;
        for (String authority : provider.authority.split(";")) {
          Forwarding forwarding = new Forwarding(peer, authority);
          forwarding.attachInfo(app, provider);
          ShadowContentResolver.registerProviderInternal(authority, forwarding);
        }
      }
    }
  }

  /**
   * Answers the other apps' calls into this app's providers, with the caller as the provider's
   * {@code getCallingPackage()}, and its exceptions, {@code SecurityException}, {@code
   * IllegalArgumentException} and {@code FileNotFoundException}, thrown in the caller as such.
   */
  static void serve(Application app) {
    DeviceBus.on(
        OP,
        (from, body, reply) -> {
          try {
            reply.send(answer(app, from, body));
          } catch (SecurityException e) {
            reply.send(new Protocol.Json().put("security", e.getMessage()).object());
          } catch (IllegalArgumentException e) {
            reply.send(new Protocol.Json().put("illegalArgument", e.getMessage()).object());
          } catch (FileNotFoundException e) {
            reply.send(new Protocol.Json().put("fileNotFound", e.getMessage()).object());
          } catch (Exception e) {
            reply.send(DeviceBus.error(String.valueOf(e)));
          }
        });
  }

  private static JSONObject answer(Application app, String from, JSONObject body)
      throws Exception {
    Uri uri = Uri.parse(body.getString("uri"));
    ProviderInfo provider = app.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
    if (provider == null || !provider.packageName.equals(app.getPackageName())) {
      throw new IllegalArgumentException("no provider of " + app.getPackageName() + " for " + uri);
    }
    if (!provider.exported && !provider.grantUriPermissions) {
      throw new SecurityException(
          "Permission Denial: " + provider.name + " is not exported to other apps");
    }
    ThreadLocal<Object> caller = callerOf(ShadowContentResolver.getProvider(uri));
    Object before = caller == null ? null : caller.get();
    if (caller != null) caller.set(attributionSource(app, from));
    try {
      return run(app, uri, body);
    } finally {
      if (caller != null) caller.set(before);
    }
  }

  /**
   * [provider]'s calling-app slot, which {@code getCallingPackage()} reads (API 31+) and the binder
   * transport fills on a device; null when there is none to fill.
   */
  @SuppressWarnings("unchecked")
  private static ThreadLocal<Object> callerOf(ContentProvider provider) {
    if (provider == null || android.os.Build.VERSION.SDK_INT < 31) return null;
    try {
      return (ThreadLocal<Object>)
          org.robolectric.util.ReflectionHelpers.getField(provider, "mCallingAttributionSource");
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The calling app, [from], as the binder names it to a provider. */
  private static Object attributionSource(Application app, String from) {
    int uid;
    try {
      uid = app.getPackageManager().getPackageUid(from, 0);
    } catch (PackageManager.NameNotFoundException e) {
      uid = android.os.Process.myUid();
    }
    return new android.content.AttributionSource.Builder(uid).setPackageName(from).build();
  }

  private static JSONObject run(Application app, Uri uri, JSONObject body) throws Exception {
    android.content.ContentResolver resolver = app.getContentResolver();
    Protocol.Json out = new Protocol.Json();
    switch (body.getString("method")) {
      case "query":
        try (Cursor cursor =
            resolver.query(
                uri,
                strings(body.optJSONArray("projection")),
                Protocol.string(body, "selection"),
                strings(body.optJSONArray("selectionArgs")),
                Protocol.string(body, "sortOrder"))) {
          return out.put("cursor", cursor == null ? null : rows(cursor)).object();
        }
      case "insert":
        {
          Uri inserted =
              resolver.insert(uri, Parcels.decode(Protocol.string(body, "values"), ContentValues.CREATOR));
          return out.put("uri", inserted == null ? null : inserted.toString()).object();
        }
      case "update":
        return out.put(
                "count",
                resolver.update(
                    uri,
                    Parcels.decode(Protocol.string(body, "values"), ContentValues.CREATOR),
                    Protocol.string(body, "selection"),
                    strings(body.optJSONArray("selectionArgs"))))
            .object();
      case "delete":
        return out.put(
                "count",
                resolver.delete(
                    uri,
                    Protocol.string(body, "selection"),
                    strings(body.optJSONArray("selectionArgs"))))
            .object();
      case "getType":
        return out.put("type", resolver.getType(uri)).object();
      case "call":
        {
          Bundle result =
              resolver.call(
                  uri,
                  body.getString("callMethod"),
                  Protocol.string(body, "arg"),
                  Parcels.decode(Protocol.string(body, "extras"), Bundle.CREATOR));
          return out.put("extras", result == null ? null : Parcels.encode(result)).object();
        }
      case "openFile":
        try (InputStream in = resolver.openInputStream(uri)) {
          if (in == null) throw new FileNotFoundException(uri.toString());
          return out.put("bytes", Base64.getEncoder().encodeToString(in.readAllBytes())).object();
        }
      default:
        throw new IllegalArgumentException("no provider method " + body.getString("method"));
    }
  }

  /** [cursor]'s columns and rows, each value typed: null, integer, float, string or blob. */
  private static JSONObject rows(Cursor cursor) throws JSONException {
    JSONArray rows = new JSONArray();
    int columns = cursor.getColumnCount();
    while (cursor.moveToNext()) {
      JSONArray row = new JSONArray();
      for (int i = 0; i < columns; i++) {
        switch (cursor.getType(i)) {
          case Cursor.FIELD_TYPE_INTEGER:
            row.put(new JSONArray().put("i").put(cursor.getLong(i)));
            break;
          case Cursor.FIELD_TYPE_FLOAT:
            row.put(new JSONArray().put("f").put(cursor.getDouble(i)));
            break;
          case Cursor.FIELD_TYPE_STRING:
            row.put(new JSONArray().put("s").put(cursor.getString(i)));
            break;
          case Cursor.FIELD_TYPE_BLOB:
            row.put(
                new JSONArray().put("b").put(Base64.getEncoder().encodeToString(cursor.getBlob(i))));
            break;
          default:
            row.put(new JSONArray().put("n"));
        }
      }
      rows.put(row);
    }
    return new Protocol.Json()
        .put("columns", new JSONArray(cursor.getColumnNames()))
        .put("rows", rows)
        .object();
  }

  private static Cursor cursor(JSONObject json) throws JSONException {
    if (json == null) return null;
    JSONArray names = json.getJSONArray("columns");
    String[] columns = new String[names.length()];
    for (int i = 0; i < columns.length; i++) columns[i] = names.getString(i);
    MatrixCursor cursor = new MatrixCursor(columns);
    JSONArray rows = json.getJSONArray("rows");
    for (int r = 0; r < rows.length(); r++) {
      JSONArray row = rows.getJSONArray(r);
      Object[] values = new Object[columns.length];
      for (int i = 0; i < columns.length; i++) {
        JSONArray cell = row.getJSONArray(i);
        switch (cell.getString(0)) {
          case "i":
            values[i] = cell.getLong(1);
            break;
          case "f":
            values[i] = cell.getDouble(1);
            break;
          case "s":
            values[i] = cell.getString(1);
            break;
          case "b":
            values[i] = Base64.getDecoder().decode(cell.getString(1));
            break;
          default:
            values[i] = null;
        }
      }
      cursor.addRow(values);
    }
    return cursor;
  }

  private static String[] strings(JSONArray array) throws JSONException {
    if (array == null) return null;
    String[] out = new String[array.length()];
    for (int i = 0; i < out.length; i++) out[i] = array.isNull(i) ? null : array.getString(i);
    return out;
  }

  private static JSONArray json(String[] values) {
    if (values == null) return null;
    JSONArray array = new JSONArray();
    for (String value : values) array.put(value == null ? JSONObject.NULL : value);
    return array;
  }

  /** Another app's provider, as this app reaches it: each call forwarded to that app. */
  static final class Forwarding extends ContentProvider {
    private final String owner;
    private final String authority;

    Forwarding(String owner, String authority) {
      this.owner = owner;
      this.authority = authority;
    }

    @Override
    public boolean onCreate() {
      return true;
    }

    private JSONObject forward(String method, Uri uri, Protocol.Json args) {
      JSONObject body = args.put("method", method).put("uri", uri.toString()).object();
      JSONObject answer;
      try {
        answer = DeviceBus.request(owner, OP, body).get(TIMEOUT_S, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      } catch (ExecutionException | TimeoutException e) {
        throw new IllegalStateException(owner + " did not answer " + method + " " + uri, e);
      }
      if (answer.has("security")) throw new SecurityException(answer.optString("security"));
      if (answer.has("illegalArgument")) {
        throw new IllegalArgumentException(answer.optString("illegalArgument"));
      }
      if (answer.has("busError")) throw new IllegalStateException(answer.optString("busError"));
      return answer;
    }

    @Override
    public Cursor query(
        Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
      JSONObject answer =
          forward(
              "query",
              uri,
              new Protocol.Json()
                  .put("projection", json(projection))
                  .put("selection", selection)
                  .put("selectionArgs", json(selectionArgs))
                  .put("sortOrder", sortOrder));
      try {
        return cursor(answer.optJSONObject("cursor"));
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public String getType(Uri uri) {
      return Protocol.string(forward("getType", uri, new Protocol.Json()), "type");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
      String inserted =
          Protocol.string(
              forward(
                  "insert",
                  uri,
                  new Protocol.Json().put("values", values == null ? null : Parcels.encode(values))),
              "uri");
      return inserted == null ? null : Uri.parse(inserted);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
      return forward(
              "update",
              uri,
              new Protocol.Json()
                  .put("values", values == null ? null : Parcels.encode(values))
                  .put("selection", selection)
                  .put("selectionArgs", json(selectionArgs)))
          .optInt("count");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
      return forward(
              "delete",
              uri,
              new Protocol.Json().put("selection", selection).put("selectionArgs", json(selectionArgs)))
          .optInt("count");
    }

    /** Robolectric's {@code ContentResolver.call} reaches a provider here, with no authority. */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
      return call(authority, method, arg, extras);
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
      String result =
          Protocol.string(
              forward(
                  "call",
                  Uri.parse("content://" + authority),
                  new Protocol.Json()
                      .put("callMethod", method)
                      .put("arg", arg)
                      .put("extras", extras == null ? null : Parcels.encode(extras))),
              "extras");
      return Parcels.decode(result, Bundle.CREATOR);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
      if (!"r".equals(mode)) {
        throw new FileNotFoundException(
            "writing another app's file (" + uri + ", mode " + mode + ") is not simulated");
      }
      JSONObject answer;
      try {
        answer = forward("openFile", uri, new Protocol.Json());
      } catch (IllegalStateException e) {
        throw new FileNotFoundException(e.getMessage());
      }
      if (answer.has("fileNotFound")) throw new FileNotFoundException(answer.optString("fileNotFound"));
      String text = Protocol.string(answer, "bytes");
      byte[] bytes = Base64.getDecoder().decode(text);
      try {
        File copy = File.createTempFile("provider", ".bin", getContext().getCacheDir());
        copy.deleteOnExit();
        Files.write(copy.toPath(), bytes);
        return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY);
      } catch (IOException e) {
        throw new FileNotFoundException(e.toString());
      }
    }
  }

  private DeviceProviders() {}
}
