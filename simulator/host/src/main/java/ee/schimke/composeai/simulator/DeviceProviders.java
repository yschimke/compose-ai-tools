package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *       a share flow's {@code FileProvider} URI is read; and for writing: the caller writes a copy,
 *       which the owning app writes through its provider when the caller closes it.
 * </ul>
 *
 * <p>Access is the owning app's to grant. A provider that is not exported answers another app only
 * for a URI it granted that app: an intent this app sent it with {@code FLAG_GRANT_READ_URI_
 * PERMISSION} or {@code FLAG_GRANT_WRITE_URI_PERMISSION} (its data, clip or {@code EXTRA_STREAM};
 * {@code FLAG_GRANT_PREFIX_URI_PERMISSION} covers the URIs under it), or {@code
 * grantUriPermission}, until {@code revokeUriPermission}. An exported one with a read or write
 * permission answers a caller that holds it, or has a grant. A change one app's provider reports
 * ({@code notifyChange} on its own authority) reaches the other apps' {@code ContentObserver}s. A
 * call waits up to ten seconds for its answer.
 */
final class DeviceProviders {

  private static final String OP = "provider";
  private static final String NOTIFY = "notifyChange";

  private static volatile Application self;

  /** Set while another app's change is reported here, which is not sent on again. */
  private static boolean receivingNotify;

  // ---------------------------------------------------------------------------------------------
  // URI grants, kept by the app whose provider they open

  private static final class Grant {
    final String pkg;
    final Uri uri;
    final int flags;

    Grant(String pkg, Uri uri, int flags) {
      this.pkg = pkg;
      this.uri = uri;
      this.flags = flags;
    }
  }

  private static final List<Grant> grants = new CopyOnWriteArrayList<>();

  private static final int GRANT_FLAGS =
      Intent.FLAG_GRANT_READ_URI_PERMISSION
          | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
          | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

  /** Whether [uri] is one of this app's own providers'. */
  private static boolean own(Uri uri) {
    return ownProvider(uri) != null;
  }

  /** This app's provider of [uri], or null when it is not one of this app's. */
  private static ProviderInfo ownProvider(Uri uri) {
    Application app = self;
    if (app == null || uri == null || !"content".equals(uri.getScheme())) return null;
    ProviderInfo provider = app.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
    return provider != null && app.getPackageName().equals(provider.packageName) ? provider : null;
  }

  /**
   * Whether [uri]'s provider lets this app grant it: {@code grantUriPermissions}, or a {@code
   * <grant-uri-permission>} path that matches it.
   */
  private static boolean grantable(ProviderInfo provider, Uri uri) {
    if (provider.grantUriPermissions) return true;
    if (provider.uriPermissionPatterns == null) return false;
    String path = uri.getPath() == null ? "" : uri.getPath();
    for (android.os.PatternMatcher pattern : provider.uriPermissionPatterns) {
      if (pattern.match(path)) return true;
    }
    return false;
  }

  /** {@code grantUriPermission}: [pkg] may open [uri], one of this app's, as [flags] allow. */
  static void grant(String pkg, Uri uri, int flags) {
    if ((flags & GRANT_FLAGS) == 0) return;
    ProviderInfo provider = ownProvider(uri);
    if (provider == null || !grantable(provider, uri)) return;
    grants.add(new Grant(pkg, uri, flags & GRANT_FLAGS));
  }

  /**
   * {@code revokeUriPermission}: the modes [flags] names are taken back from every app, for [uri]
   * and the URIs under it; a grant of a URI above it keeps them.
   */
  static void revoke(Uri uri, int flags) {
    int modes =
        flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    for (Grant grant : grants) {
      if (!grant.uri.equals(uri) && !under(uri, grant.uri)) continue;
      grants.remove(grant);
      int left = grant.flags & ~modes;
      if ((left
              & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
          != 0) {
        grants.add(new Grant(grant.pkg, grant.uri, left));
      }
    }
  }

  /** The URIs [intent] carries: its data, clip and {@code EXTRA_STREAM}. */
  @SuppressWarnings("deprecation")
  private static List<Uri> urisOf(Intent intent) {
    List<Uri> uris = new java.util.ArrayList<>();
    if (intent.getData() != null) uris.add(intent.getData());
    android.content.ClipData clip = intent.getClipData();
    if (clip != null) {
      for (int i = 0; i < clip.getItemCount(); i++) {
        if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
      }
    }
    Object stream = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_STREAM);
    if (stream instanceof Uri) uris.add((Uri) stream);
    if (stream instanceof java.util.List) {
      for (Object each : (java.util.List<?>) stream) if (each instanceof Uri) uris.add((Uri) each);
    }
    return uris;
  }

  /**
   * [intent], which this app sends to [pkg]: the URIs it carries are granted to it as the intent's
   * {@code FLAG_GRANT_*} flags say.
   */
  static void grantFrom(Intent intent, String pkg) {
    if (intent == null || (intent.getFlags() & GRANT_FLAGS) == 0) return;
    for (Uri uri : urisOf(intent)) grant(pkg, uri, intent.getFlags());
  }

  /** URIs an implicit broadcast of this app's offered, granted to the apps that received it. */
  private static final Map<Uri, Integer> offered = new ConcurrentHashMap<>();

  private static final String CLAIM = "grantClaim";

  /**
   * [intent], an implicit broadcast of this app's: the URIs it grants are offered, and granted to
   * each app that receives it, which claims them ({@link #claim}).
   */
  static void offer(Intent intent) {
    if (intent == null || (intent.getFlags() & GRANT_FLAGS) == 0) return;
    for (Uri uri : urisOf(intent)) {
      if (own(uri)) offered.merge(uri, intent.getFlags() & GRANT_FLAGS, (a, b) -> a | b);
    }
  }

  /**
   * [intent], a broadcast from [sender] that a receiver of this app's got: the URIs it grants are
   * now this app's to open, as the sender offered them.
   */
  static void claim(String sender, Intent intent) {
    if (intent == null || (intent.getFlags() & GRANT_FLAGS) == 0) return;
    JSONArray uris = new JSONArray();
    for (Uri uri : urisOf(intent)) uris.put(uri.toString());
    if (uris.length() == 0) return;
    DeviceBus.request(
        sender,
        CLAIM,
        new Protocol.Json().put("uris", uris).put("flags", intent.getFlags()).object());
  }

  private static void answerClaim(String from, JSONObject body) throws JSONException {
    JSONArray uris = body.optJSONArray("uris");
    if (uris == null) return;
    for (int i = 0; i < uris.length(); i++) {
      Uri uri = Uri.parse(uris.getString(i));
      Integer flags = offered.get(uri);
      // Only what this app's broadcast offered: a claim cannot ask for more.
      if (flags != null) grant(from, uri, flags & body.optInt("flags"));
    }
  }

  private static boolean under(Uri prefix, Uri uri) {
    String p = prefix.toString();
    String u = uri.toString();
    return u.startsWith(p.endsWith("/") ? p : p + "/");
  }

  /** Whether [pkg] has a grant to read, or [write], [uri]. */
  private static boolean granted(String pkg, Uri uri, boolean write) {
    int need = write ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_READ_URI_PERMISSION;
    for (Grant grant : grants) {
      if (!grant.pkg.equals(pkg) || (grant.flags & need) == 0) continue;
      if (grant.uri.equals(uri)) return true;
      if ((grant.flags & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0 && under(grant.uri, uri)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Refuses [from] the call [body] makes into [provider] at [uri] when it may not make it: a
   * provider that is not exported needs a grant, and an exported one's read or write permission
   * needs holding, or a grant.
   */
  private static void checkAccess(ProviderInfo provider, String from, Uri uri, JSONObject body) {
    boolean write;
    switch (body.optString("method")) {
      case "query":
        write = false;
        break;
      case "openFile":
        write = body.optBoolean("write");
        break;
      case "insert":
      case "update":
      case "delete":
        write = true;
        break;
      default:
        // getType answers anyone, and call is the provider's own to check; but neither reaches a
        // provider that is not exported and grants nothing.
        if (!provider.exported && !provider.grantUriPermissions) throw notExported(provider);
        return;
    }
    String access = write ? "writing" : "reading";
    if (!provider.exported) {
      if (!provider.grantUriPermissions) throw notExported(provider);
      if (!granted(from, uri, write)) {
        throw new SecurityException(
            "Permission Denial: "
                + access
                + " "
                + provider.name
                + " uri "
                + uri
                + " from "
                + from
                + " requires a URI grant");
      }
      return;
    }
    String permission = write ? provider.writePermission : provider.readPermission;
    if (permission != null
        && !body.optBoolean(write ? "holdsWrite" : "holdsRead")
        && !granted(from, uri, write)) {
      throw new SecurityException(
          "Permission Denial: "
              + access
              + " "
              + provider.name
              + " uri "
              + uri
              + " from "
              + from
              + " requires "
              + permission);
    }
  }

  private static SecurityException notExported(ProviderInfo provider) {
    return new SecurityException(
        "Permission Denial: " + provider.name + " is not exported to other apps");
  }

  // ---------------------------------------------------------------------------------------------
  // Change notifications

  /**
   * This app's {@code notifyChange} of [uris]: the device's other apps' observers hear of the
   * changes to this app's own providers.
   */
  static void notified(java.util.Collection<Uri> uris, int flags) {
    if (receivingNotify || self == null || !DeviceBus.enabled()) return;
    JSONArray own = new JSONArray();
    for (Uri uri : uris) if (own(uri)) own.put(uri.toString());
    if (own.length() == 0) return;
    DeviceBus.broadcast(NOTIFY, new Protocol.Json().put("uris", own).put("flags", flags).object());
  }
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
          Forwarding forwarding = new Forwarding(peer, authority, provider);
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
    self = app;
    DeviceBus.on(
        CLAIM,
        (from, body, reply) -> {
          answerClaim(from, body);
          reply.send(new JSONObject());
        });
    DeviceBus.onBroadcast(
        NOTIFY,
        (from, body) -> {
          JSONArray uris = body.optJSONArray("uris");
          if (uris == null) return;
          receivingNotify = true;
          try {
            for (int i = 0; i < uris.length(); i++) {
              app.getContentResolver()
                  .notifyChange(Uri.parse(uris.getString(i)), null, body.optInt("flags"));
            }
          } finally {
            receivingNotify = false;
          }
        });
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
    checkAccess(provider, from, uri, body);
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
        if (body.optBoolean("write")) {
          byte[] bytes = Base64.getDecoder().decode(body.getString("bytes"));
          try (java.io.OutputStream o = resolver.openOutputStream(uri, body.getString("mode"))) {
            if (o == null) throw new FileNotFoundException(uri.toString());
            o.write(bytes);
          }
          return out.put("written", bytes.length).object();
        }
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
    private final ProviderInfo info;

    Forwarding(String owner, String authority, ProviderInfo info) {
      this.owner = owner;
      this.authority = authority;
      this.info = info;
    }

    /** Whether this app holds [permission], one the provider asks of its callers. */
    private boolean holds(String permission) {
      return permission == null
          || getContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public boolean onCreate() {
      return true;
    }

    private JSONObject forward(String method, Uri uri, Protocol.Json args) {
      JSONObject body =
          args.put("method", method)
              .put("uri", uri.toString())
              .put("holdsRead", holds(info.readPermission))
              .put("holdsWrite", holds(info.writePermission))
              .object();
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
      if (!"r".equals(mode)) return openForWriting(uri, mode);
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

    /**
     * [uri] opened for writing: a copy here, filled first for appending or reading too, which the
     * owning app writes through its provider when it is closed, before the close returns.
     */
    private ParcelFileDescriptor openForWriting(Uri uri, String mode) throws FileNotFoundException {
      try {
        File copy = File.createTempFile("provider", ".bin", getContext().getCacheDir());
        copy.deleteOnExit();
        int[] prefilled = {0};
        if (mode.contains("a") || mode.equals("rw")) {
          try {
            String text = Protocol.string(forward("openFile", uri, new Protocol.Json()), "bytes");
            if (text != null) {
              byte[] existing = Base64.getDecoder().decode(text);
              Files.write(copy.toPath(), existing);
              prefilled[0] = existing.length;
            }
          } catch (RuntimeException e) {
            // Nothing there yet: written from the start.
          }
        }
        // A handler that runs the close listener at once, not on a later turn of the looper, so
        // the owning app has the bytes when the caller's close() returns.
        android.os.Handler now =
            new android.os.Handler(android.os.Looper.getMainLooper()) {
              @Override
              public boolean sendMessageAtTime(android.os.Message message, long uptimeMillis) {
                if (message.getCallback() != null) message.getCallback().run();
                return true;
              }
            };
        // Appending sends what was appended; reading and writing sends the whole file, which
        // replaces the owner's; writing sends what was written, as the mode says.
        boolean append = mode.contains("a");
        String written = append ? "wa" : mode.contains("t") || mode.startsWith("rw") ? "wt" : "w";
        return ParcelFileDescriptor.open(
            copy,
            ParcelFileDescriptor.parseMode(mode),
            now,
            error -> {
              try {
                forward(
                    "openFile",
                    uri,
                    new Protocol.Json()
                        .put("write", true)
                        .put("mode", written)
                        .put(
                            "bytes",
                            Base64.getEncoder()
                                .encodeToString(tail(copy, append, prefilled[0]))));
              } catch (IOException | RuntimeException e) {
                System.err.println("provider: cannot write " + uri + " back to " + owner + ": " + e);
              }
            });
      } catch (IOException e) {
        throw new FileNotFoundException(e.toString());
      }
    }

    /** What to send back of [copy]: all of it, or what follows [prefilled] bytes when appending. */
    private static byte[] tail(File copy, boolean append, int prefilled) throws IOException {
      byte[] all = Files.readAllBytes(copy.toPath());
      if (!append || prefilled <= 0 || prefilled > all.length) return all;
      return java.util.Arrays.copyOfRange(all, prefilled, all.length);
    }
  }

  private DeviceProviders() {}
}
