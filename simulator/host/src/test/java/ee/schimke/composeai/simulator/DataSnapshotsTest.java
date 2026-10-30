package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DataSnapshotsTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void aSnapshotGoesOnlyToANewOrEmptyDirectory() throws IOException {
    DataSnapshots.checkTarget(new File(tmp.getRoot(), "missing"));
    DataSnapshots.checkTarget(tmp.newFolder("empty"));

    File busy = tmp.newFolder("busy");
    File precious = new File(busy, "keep.txt");
    Files.writeString(precious.toPath(), "mine");
    assertThrows(IllegalArgumentException.class, () -> DataSnapshots.checkTarget(busy));
    assertThrows(
        IllegalArgumentException.class, () -> DataSnapshots.checkTarget(tmp.newFile("a-file")));
    assertTrue("nothing was deleted", precious.isFile());
  }

  @Test
  public void restoreCopiesDataAndExternalStorage() throws IOException {
    File snapshot = tmp.newFolder("snapshot");
    write(new File(snapshot, "data/shared_prefs/p.xml"), "<map/>");
    write(new File(snapshot, "external/Download/a.txt"), "a");
    File dataDir = tmp.newFolder("dataDir");
    File storage = tmp.newFolder("sdcard");

    assertEquals(2, DataSnapshots.restoreInto(snapshot, dataDir, storage));
    assertTrue(new File(dataDir, "shared_prefs/p.xml").isFile());
    assertTrue(new File(storage, "Download/a.txt").isFile());
  }

  @Test
  public void aMissingSnapshotFailsRatherThanRestoringNothing() {
    assertThrows(
        IOException.class,
        () ->
            DataSnapshots.restoreInto(
                new File(tmp.getRoot(), "gone"), tmp.getRoot(), tmp.getRoot()));
  }

  private static void write(File file, String text) throws IOException {
    file.getParentFile().mkdirs();
    Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
  }
}
