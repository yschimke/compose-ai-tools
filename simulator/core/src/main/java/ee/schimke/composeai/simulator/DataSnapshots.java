package ee.schimke.composeai.simulator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * The file side of the app-data snapshot a {@code restart} or {@code update} carries over ({@link
 * AppFiles#snapshot}, {@link AppFiles#restore}): where a snapshot may be written, and how one is
 * put back. Nothing of Android's, so it is tested on its own.
 */
final class DataSnapshots {

  /**
   * Refuses a snapshot destination that exists and is anything but an empty directory: a snapshot
   * never deletes or overwrites what is already there, whatever path a client names.
   */
  static void checkTarget(File to) {
    if (!to.exists()) return;
    String[] children = to.isDirectory() ? to.list() : null;
    if (children == null || children.length > 0) {
      throw new IllegalArgumentException(
          "snapshotData needs a new or empty directory, and " + to + " is not one");
    }
  }

  /**
   * Copies a snapshot's {@code data} and {@code external} into {@code dataDir} and {@code
   * storage}; the number of files. A snapshot that is not there is an error, not nothing.
   */
  static int restoreInto(File from, File dataDir, File storage) throws IOException {
    if (!from.isDirectory()) throw new IOException("no snapshot at " + from);
    int count = 0;
    File data = new File(from, "data");
    if (data.isDirectory()) count += copy(data, dataDir);
    File external = new File(from, "external");
    if (external.isDirectory() && storage != null) count += copy(external, storage);
    return count;
  }

  private static int copy(File from, File to) throws IOException {
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

  private DataSnapshots() {}
}
