package ee.schimke.composeai.simulator;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The app's storage, as {@code adb shell run-as <pkg>} and {@code adb pull} / {@code push} reach
 * it. Robolectric keeps it in a temporary directory on the host, so every answer also carries the
 * host path, which a client on the same machine can read directly.
 *
 * <p>Device paths map onto it: a relative path and {@code /data/data/<pkg>/…}, {@code
 * /data/user/0/<pkg>/…} or {@code /data/user_de/0/<pkg>/…} are the app's data directory ({@code
 * files/}, {@code shared_prefs/}, {@code databases/}, {@code cache/}); {@code /sdcard/…} and
 * {@code /storage/emulated/0/…} are the simulated external storage, and {@code
 * /data/local/tmp/…} a scratch directory beside it, so {@code screencap /sdcard/s.png} then
 * {@code pull /sdcard/s.png} works.
 */
final class AppFiles {

  private static final int MAX_READ = 4 * 1024 * 1024;

  private final Context app;

  AppFiles(Context app) {
    this.app = app;
  }

  File dataDir() {
    return app.getDataDir();
  }

  /** Whether [path] is one of the device paths {@link #resolve} maps, not a host path. */
  boolean isDevicePath(String path) {
    for (String prefix :
        new String[] {
          "/data/data/", "/data/user/", "/data/user_de/", "/sdcard", "/storage/", "/data/local/tmp"
        }) {
      if (path.startsWith(prefix)) return true;
    }
    return !path.startsWith("/");
  }

  /** The host file behind a device path. */
  File resolve(String path) {
    String pkg = app.getPackageName();
    for (String prefix :
        new String[] {"/data/data/" + pkg, "/data/user/0/" + pkg, "/data/user_de/0/" + pkg}) {
      if (path.equals(prefix) || path.startsWith(prefix + "/")) {
        return inside(dataDir(), path.substring(prefix.length()));
      }
    }
    for (String prefix : new String[] {"/sdcard", "/storage/emulated/0", "/storage/self/primary"}) {
      if (path.equals(prefix) || path.startsWith(prefix + "/")) {
        return inside(Environment.getExternalStorageDirectory(), path.substring(prefix.length()));
      }
    }
    if (path.equals("/data/local/tmp") || path.startsWith("/data/local/tmp/")) {
      File tmp = new File(Environment.getExternalStorageDirectory().getParentFile(), "local-tmp");
      return inside(tmp, path.substring("/data/local/tmp".length()));
    }
    if (path.startsWith("/")) {
      throw new IllegalArgumentException(
          path
              + " is outside the app's storage (/data/data/"
              + pkg
              + ", /sdcard, /data/local/tmp)");
    }
    return inside(dataDir(), path);
  }

  /** [relative] under [root], refusing a {@code ..} that climbs out of it. */
  private static File inside(File root, String relative) {
    Path base = root.toPath().toAbsolutePath().normalize();
    String trimmed = relative.startsWith("/") ? relative.substring(1) : relative;
    Path resolved = base.resolve(trimmed).normalize();
    if (!resolved.startsWith(base)) {
      throw new IllegalArgumentException(relative + " climbs out of " + base);
    }
    return resolved.toFile();
  }

  JSONObject command(JSONObject c) throws JSONException, IOException {
    String op = c.getString("op");
    File file = resolve(c.optString("path", "."));
    Protocol.Json json =
        new Protocol.Json().put("path", c.optString("path", ".")).put("hostPath", file.getPath());
    switch (op) {
      case "list":
        {
          if (!file.exists()) throw new IllegalArgumentException("no such file: " + file);
          JSONArray entries = new JSONArray();
          list(file, file, c.optBoolean("recursive", false), entries);
          return json.put("entries", entries).object();
        }
      case "read":
        {
          if (!file.isFile()) throw new IllegalArgumentException("not a file: " + file);
          if (file.length() > MAX_READ) {
            throw new IllegalArgumentException(
                file + " is " + file.length() + " bytes; pull it, or read hostPath directly");
          }
          byte[] bytes = Files.readAllBytes(file.toPath());
          String text = utf8(bytes);
          json.put("size", bytes.length);
          if (text != null) return json.put("text", text).object();
          return json.put("base64", Base64.getEncoder().encodeToString(bytes)).object();
        }
      case "write":
        {
          byte[] bytes =
              c.has("base64")
                  ? Base64.getDecoder().decode(c.getString("base64"))
                  : c.getString("text").getBytes(StandardCharsets.UTF_8);
          file.getParentFile().mkdirs();
          Files.write(file.toPath(), bytes);
          return json.put("size", bytes.length).object();
        }
      case "mkdir":
        file.mkdirs();
        return json.put("exists", file.isDirectory()).object();
      case "delete":
        {
          if (file.isDirectory() && !c.optBoolean("recursive", false)) {
            String[] children = file.list();
            if (children != null && children.length > 0) {
              throw new IllegalArgumentException(file + " is a directory; delete it recursively");
            }
          }
          return json.put("deleted", delete(file)).object();
        }
      case "pull":
        {
          if (!file.exists()) throw new IllegalArgumentException("no such file: " + file);
          File to = new File(c.optString("to", ".")).getAbsoluteFile();
          if (to.isDirectory()) to = new File(to, file.getName());
          return json.put("to", to.getPath()).put("files", copy(file, to)).object();
        }
      case "push":
        {
          File from = new File(c.getString("from")).getAbsoluteFile();
          if (!from.exists()) throw new IllegalArgumentException("no such file: " + from);
          File to = file.isDirectory() ? new File(file, from.getName()) : file;
          return json.put("hostPath", to.getPath()).put("files", copy(from, to)).object();
        }
      default:
        throw new IllegalArgumentException(
            "files op is list, read, write, mkdir, delete, pull or push, not " + op);
    }
  }

  /** Deletes everything in the app's data and external storage: {@code pm clear}. */
  int clear() throws IOException {
    int deleted = 0;
    File external = app.getExternalFilesDir(null);
    // Android/data/<pkg>/, the app's part of external storage.
    for (File root : new File[] {dataDir(), external == null ? null : external.getParentFile()}) {
      if (root == null) continue;
      File[] children = root.listFiles();
      if (children == null) continue;
      for (File child : children) deleted += delete(child);
    }
    return deleted;
  }

  private static void list(File root, File file, boolean recursive, JSONArray out)
      throws JSONException {
    File[] children = file.isDirectory() ? file.listFiles() : new File[] {file};
    if (children == null) return;
    List<File> sorted = new ArrayList<>(List.of(children));
    sorted.sort(Comparator.comparing(File::getName));
    for (File child : sorted) {
      String name =
          root.equals(child)
              ? child.getName()
              : root.toPath().relativize(child.toPath()).toString();
      out.put(
          new Protocol.Json()
              .put("name", name)
              .put("dir", child.isDirectory())
              .put("size", child.isDirectory() ? null : child.length())
              .object());
      if (recursive && child.isDirectory()) list(root, child, true, out);
    }
  }

  private static String utf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  private static int delete(File file) throws IOException {
    if (!file.exists()) return 0;
    int[] count = {0};
    try (Stream<Path> walk = Files.walk(file.toPath())) {
      for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
        Files.delete(path);
        count[0]++;
      }
    }
    return count[0];
  }

  private static int copy(File from, File to) throws IOException {
    if (from.isFile()) {
      to.getAbsoluteFile().getParentFile().mkdirs();
      Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
      return 1;
    }
    int count = 0;
    try (Stream<Path> walk = Files.walk(from.toPath())) {
      for (Path path : (Iterable<Path>) walk::iterator) {
        Path target = to.toPath().resolve(from.toPath().relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
          count++;
        }
      }
    }
    return count;
  }
}
