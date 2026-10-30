package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDownloadManager;
import org.robolectric.util.ReflectionHelpers;

/**
 * The app's {@code DownloadManager} requests download. Robolectric records an enqueued request and
 * leaves it pending for good. Here each one waits for a network, as the download service does, is
 * fetched on a thread of its own over the simulated network (the host's, through {@code
 * NetworkGate}: the {@code network} command's offline and slow modes apply), with the request's
 * headers, into its destination (the public {@code Download} directory by default), and then
 * reads {@code STATUS_SUCCESSFUL} (or {@code STATUS_FAILED}) through {@code DownloadManager.query}
 * and sends {@code ACTION_DOWNLOAD_COMPLETE} to the app's receivers. Each is a {@code download}
 * event; the {@code downloads} command lists them.
 */
final class Downloads {

  private final Application app;
  private final Protocol protocol;
  private final Navigator navigator;
  private final Handler main = new Handler(Looper.getMainLooper());

  /** What happened to each request seen, by id. */
  private final Map<Long, JSONObject> handled = new HashMap<>();

  Downloads(Application app, Protocol protocol, Navigator navigator) {
    this.app = app;
    this.protocol = protocol;
    this.navigator = navigator;
  }

  /** Every request Robolectric holds, by id. */
  private static Map<Long, DownloadManager.Request> requests() {
    Map<Long, DownloadManager.Request> all =
        ReflectionHelpers.getStaticField(ShadowDownloadManager.class, "requestMap");
    return new TreeMap<>(all);
  }

  void cycle() {
    Map<Long, DownloadManager.Request> requests = requests();
    if (requests.size() == handled.size()) return;
    boolean online = app.getSystemService(ConnectivityManager.class).getActiveNetwork() != null;
    for (Map.Entry<Long, DownloadManager.Request> e : requests.entrySet()) {
      if (handled.containsKey(e.getKey())) continue;
      // The download service holds a request until there is a network to fetch it over.
      if (!online) return;
      start(e.getKey(), e.getValue());
    }
  }

  private void start(long id, DownloadManager.Request request) {
    ShadowDownloadManager.ShadowRequest shadow = Shadow.extract(request);
    shadow.setStatus(DownloadManager.STATUS_RUNNING);
    File destination = destination(shadow);
    JSONObject state =
        new Protocol.Json()
            .put("id", id)
            .put("uri", String.valueOf(shadow.getUri()))
            .put("title", shadow.getTitle() == null ? null : shadow.getTitle().toString())
            .put("destination", destination.getPath())
            .put("state", "running")
            .object();
    handled.put(id, state);
    protocol.event("download", state);
    Thread thread =
        new Thread(
            () -> {
              long size;
              try {
                size = fetch(shadow, destination);
              } catch (Exception e) {
                main.post(() -> finish(id, shadow, -1, String.valueOf(e)));
                return;
              }
              main.post(() -> finish(id, shadow, size, null));
            },
            "simulator-download-" + id);
    thread.setDaemon(true);
    thread.start();
  }

  private static File destination(ShadowDownloadManager.ShadowRequest shadow) {
    Uri uri = shadow.getDestination();
    if (uri != null && "file".equals(uri.getScheme()) && uri.getPath() != null) {
      return new File(uri.getPath());
    }
    String name = shadow.getUri() == null ? null : shadow.getUri().getLastPathSegment();
    return new File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        name == null || name.isEmpty() ? "downloadfile" : name);
  }

  private static long fetch(ShadowDownloadManager.ShadowRequest shadow, File destination)
      throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL(String.valueOf(shadow.getUri())).openConnection();
    connection.setInstanceFollowRedirects(true);
    connection.setConnectTimeout(30_000);
    connection.setReadTimeout(60_000);
    for (Pair<String, String> header : shadow.getRequestHeaders()) {
      connection.addRequestProperty(header.first, header.second);
    }
    int code = connection.getResponseCode();
    if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
    File parent = destination.getParentFile();
    if (parent != null) parent.mkdirs();
    try (InputStream in = connection.getInputStream();
        OutputStream out = Files.newOutputStream(destination.toPath())) {
      return in.transferTo(out);
    } finally {
      connection.disconnect();
    }
  }

  private void finish(long id, ShadowDownloadManager.ShadowRequest shadow, long size, String error) {
    boolean ok = error == null;
    shadow.setStatus(ok ? DownloadManager.STATUS_SUCCESSFUL : DownloadManager.STATUS_FAILED);
    if (ok) {
      shadow.setTotalSize(size);
      shadow.setBytesSoFar(size);
    }
    JSONObject state = handled.get(id);
    try {
      state.put("state", ok ? "successful" : "failed").put("bytes", ok ? size : null);
      state.put("error", error);
    } catch (org.json.JSONException e) {
      throw new IllegalStateException(e);
    }
    protocol.event("download", state);
    // Sent to the app that enqueued it, so its manifest receivers hear it as on a device.
    navigator.broadcast(
        new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            .setPackage(app.getPackageName())
            .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, id));
  }

  /** The {@code downloads} command. */
  JSONObject command() {
    JSONArray list = new JSONArray();
    for (Map.Entry<Long, DownloadManager.Request> e : requests().entrySet()) {
      JSONObject state = handled.get(e.getKey());
      if (state != null) {
        list.put(state);
        continue;
      }
      ShadowDownloadManager.ShadowRequest shadow = Shadow.extract(e.getValue());
      list.put(
          new Protocol.Json()
              .put("id", e.getKey())
              .put("uri", String.valueOf(shadow.getUri()))
              .put("state", "pending")
              .object());
    }
    return new Protocol.Json().put("downloads", list).object();
  }
}
