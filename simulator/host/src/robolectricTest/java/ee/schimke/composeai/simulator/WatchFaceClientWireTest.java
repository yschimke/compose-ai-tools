package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * {@link WatchFaceClient}'s direct {@code VersionedParcelable} wire encoding and {@link
 * SimulatorShadowPackageManager} + {@link WffWatchFaces} manifest {@code <property>} resolution.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = SimulatorShadowPackageManager.class)
public class WatchFaceClientWireTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void headlessAndRenderParamsFollowVersionedParcelLexicographicalFieldOrder() {
    Parcel headless = Parcel.obtain();
    try {
      ComponentName component =
          new ComponentName("com.google.wear.watchface.simpledigital", "");
      WatchFaceClient.writeHeadlessParams(headless, component, 454, 454, "sim-1");
      headless.setDataPosition(0);
      assertEquals("androidx.versionedparcelable.ParcelImpl", headless.readString());
      assertEquals(
          "androidx.wear.watchface.control.data.HeadlessWatchFaceInstanceParamsParcelizer",
          headless.readString());
      assertEquals(List.of(1, 2, 3, 4, 5), topLevelFieldIds(headless));
    } finally {
      headless.recycle();
    }

    Parcel render = Parcel.obtain();
    try {
      Map<Integer, Object> complications = new LinkedHashMap<>();
      Bundle dummyParcelable = new Bundle();
      dummyParcelable.putString("text", "72°");
      complications.put(10, dummyParcelable);
      WatchFaceClient.writeRenderParams(
          render, WatchFaceClient.DRAW_INTERACTIVE, 1794468300000L, complications);
      render.setDataPosition(0);
      assertEquals("androidx.versionedparcelable.ParcelImpl", render.readString());
      assertEquals(
          "androidx.wear.watchface.control.data.WatchFaceRenderParamsParcelizer",
          render.readString());
      // VersionedParcelParcel compares field IDs via String.valueOf(id), so 100 comes before 3!
      assertEquals(List.of(1, 100, 3, 5), topLevelFieldIds(render));
    } finally {
      render.recycle();
    }
  }

  @Test
  public void readSlotAdvancesCleanlyAcrossMultipleComplicationStateItems() {
    Parcel reply = Parcel.obtain();
    try {
      writeComplicationStateItem(reply, 101, new int[] {3, 4}, true);
      writeComplicationStateItem(reply, 202, new int[] {5}, false);
      int end = reply.dataPosition();

      reply.setDataPosition(0);
      WatchFaceClient.Slot first = WatchFaceClient.readSlot(reply);
      WatchFaceClient.Slot second = WatchFaceClient.readSlot(reply);
      assertEquals(end, reply.dataPosition());

      assertEquals(101, first.id);
      assertArrayEquals(new int[] {3, 4}, first.supportedTypes);
      assertTrue(first.enabled);

      assertEquals(202, second.id);
      assertArrayEquals(new int[] {5}, second.supportedTypes);
      assertFalse(second.enabled);
    } finally {
      reply.recycle();
    }
  }

  @Test
  public void wffManifestPropertyIsExposedThroughPackageManagerGetProperty() throws Exception {
    File wffApk = temp.newFile("SimpleDigital.apk");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(wffApk.toPath()))) {
      zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
      zip.write(wffManifestBytes("com.google.wear.watchface.simpledigital", 1));
      zip.closeEntry();
    }

    String previous = System.getProperty(WffWatchFaces.PROPERTY);
    try {
      System.setProperty(WffWatchFaces.PROPERTY, wffApk.getAbsolutePath());
      Application app = RuntimeEnvironment.getApplication();
      WffWatchFaces.install(app);

      PackageManager pm = app.getPackageManager();
      PackageManager.Property prop =
          pm.getProperty(
              WffWatchFaces.FORMAT_VERSION_PROPERTY, "com.google.wear.watchface.simpledigital");
      assertTrue(prop.isInteger());
      assertEquals(1, prop.getInteger());
      assertTrue(WffWatchFaces.packages().contains("com.google.wear.watchface.simpledigital"));

      try {
        pm.getProperty("com.example.nonexistent", "com.google.wear.watchface.simpledigital");
        fail("expected NameNotFoundException");
      } catch (PackageManager.NameNotFoundException expected) {
        // Expected when property is absent.
      }
    } finally {
      if (previous == null) System.clearProperty(WffWatchFaces.PROPERTY);
      else System.setProperty(WffWatchFaces.PROPERTY, previous);
    }
  }

  private static List<Integer> topLevelFieldIds(Parcel parcel) {
    List<Integer> ids = new ArrayList<>();
    while (parcel.dataPosition() < parcel.dataSize()) {
      int start = parcel.dataPosition();
      int size = parcel.readInt();
      int id = parcel.readInt();
      ids.add(id);
      parcel.setDataPosition(start + size);
    }
    return ids;
  }

  private static void writeComplicationStateItem(
      Parcel parcel, int slotId, int[] supportedTypes, boolean enabled) {
    parcel.writeString("androidx.versionedparcelable.ParcelImpl");
    parcel.writeString(
        "androidx.wear.watchface.data.IdAndComplicationStateWireFormatParcelizer");
    int f1 = openField(parcel, 1);
    parcel.writeInt(slotId);
    closeField(parcel, f1);

    int f2 = openField(parcel, 2);
    parcel.writeString("androidx.wear.watchface.data.ComplicationStateWireFormatParcelizer");
    int sub3 = openField(parcel, 3);
    parcel.writeInt(supportedTypes.length);
    for (int t : supportedTypes) parcel.writeInt(t);
    closeField(parcel, sub3);
    int sub7 = openField(parcel, 7);
    parcel.writeInt(enabled ? 1 : 0);
    closeField(parcel, sub7);
    closeField(parcel, f2);
  }

  private static int openField(Parcel parcel, int id) {
    int start = parcel.dataPosition();
    parcel.writeInt(0);
    parcel.writeInt(id);
    return start;
  }

  private static void closeField(Parcel parcel, int start) {
    int end = parcel.dataPosition();
    parcel.setDataPosition(start);
    parcel.writeInt(end - start);
    parcel.setDataPosition(end);
  }

  /** Minimal binary AndroidManifest.xml with `<manifest>` -> `<application>` -> `<property>`. */
  private static byte[] wffManifestBytes(String pkg, int formatVersion) throws Exception {
    List<String> strings =
        List.of(
            "manifest",
            "package",
            pkg,
            "application",
            "property",
            "name",
            WffWatchFaces.FORMAT_VERSION_PROPERTY,
            "value");
    ByteArrayOutputStream chunks = new ByteArrayOutputStream();
    // <manifest package="...">
    chunks.write(startElement(0, List.of(attrString(1, 2))));
    // <application>
    chunks.write(startElement(3, List.of()));
    // <property name="com.google.wear.watchface.format.version" value="1">
    chunks.write(startElement(4, List.of(attrString(5, 6), attrInt(7, formatVersion))));
    chunks.write(endElement(4));
    chunks.write(endElement(3));
    chunks.write(endElement(0));

    ByteArrayOutputStream poolData = new ByteArrayOutputStream();
    int[] offsets = new int[strings.size()];
    for (int i = 0; i < strings.size(); i++) {
      offsets[i] = poolData.size();
      String s = strings.get(i);
      ByteBuffer b = ByteBuffer.allocate(2 + s.length() * 2 + 2).order(ByteOrder.LITTLE_ENDIAN);
      b.putShort((short) s.length());
      for (int c = 0; c < s.length(); c++) b.putChar(s.charAt(c));
      b.putShort((short) 0);
      poolData.write(b.array());
    }
    while (poolData.size() % 4 != 0) poolData.write(0);
    int poolHeader = 28;
    int poolSize = poolHeader + strings.size() * 4 + poolData.size();
    ByteBuffer pool = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN);
    pool.putShort((short) 0x0001).putShort((short) poolHeader).putInt(poolSize);
    pool.putInt(strings.size()).putInt(0).putInt(0).putInt(poolHeader + strings.size() * 4).putInt(0);
    for (int off : offsets) pool.putInt(off);
    pool.put(poolData.toByteArray());

    int total = 8 + poolSize + chunks.size();
    ByteBuffer file = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
    file.putShort((short) 0x0003).putShort((short) 8).putInt(total);
    file.put(pool.array()).put(chunks.toByteArray());
    return file.array();
  }

  private static byte[] attrString(int nameIdx, int valIdx) {
    ByteBuffer b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(-1).putInt(nameIdx).putInt(valIdx).putShort((short) 8).put((byte) 0).put((byte) 0x03).putInt(valIdx);
    return b.array();
  }

  private static byte[] attrInt(int nameIdx, int val) {
    ByteBuffer b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(-1).putInt(nameIdx).putInt(-1).putShort((short) 8).put((byte) 0).put((byte) 0x10).putInt(val);
    return b.array();
  }

  private static byte[] startElement(int nameIdx, List<byte[]> attrs) {
    int size = 16 + 20 + attrs.size() * 20;
    ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    b.putShort((short) 0x0102).putShort((short) 16).putInt(size).putInt(1).putInt(-1);
    b.putInt(-1).putInt(nameIdx);
    b.putShort((short) 20).putShort((short) 20).putShort((short) attrs.size()).putShort((short) 0);
    b.putShort((short) 0).putShort((short) 0);
    for (byte[] a : attrs) b.put(a);
    return b.array();
  }

  private static byte[] endElement(int nameIdx) {
    ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
    b.putShort((short) 0x0103).putShort((short) 16).putInt(24).putInt(1).putInt(-1);
    b.putInt(-1).putInt(nameIdx);
    return b.array();
  }
}
