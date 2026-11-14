package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.provider.Settings;
import android.support.wearable.complications.ComplicationData;
import android.support.wearable.complications.ComplicationText;
import android.util.TypedValue;
import com.google.wear.Sdk;
import com.google.wear.services.watchfaces.watchfacepush.ListWatchFaceSlotsResponse;
import com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager;
import com.google.wear.services.watchfaces.watchfacepush.WatchFaceSlot;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    shadows = {SimulatorShadowPackageManager.class, SimulatorShadowResources.class})
public class WatchFaceScenariosRobolectricTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private Application app;

  @Before
  public void setUp() {
    app = RuntimeEnvironment.getApplication();
    WatchFacePushStore.reset();
    WatchFaceDynamicColors.clear(app);
    WatchState.resetCurrent();
  }

  @After
  public void tearDown() {
    WatchFacePushStore.reset();
    WatchFaceDynamicColors.clear(app);
    WatchState.resetCurrent();
  }

  @Test
  public void complicationParcelsBuildsEncodesResolvesTimelineAndSummarizesWireData()
      throws Exception {
    ClassLoader cl = app.getClassLoader();
    DwfWatchFaceClient.FakeComplication fakeSpec =
        DwfWatchFaceClient.FakeComplication.fromJson(
            new JSONObject()
                .put("slot", 10)
                .put("type", "GOAL_PROGRESS")
                .put("text", "8,200")
                .put("title", "Steps")
                .put("value", 8200.0)
                .put("max", 10000.0),
            10);
    Parcelable fakeGoal = ComplicationParcels.buildFakeParcelable(cl, fakeSpec, null);
    assertNotNull(fakeGoal);
    assertTrue(fakeGoal instanceof ComplicationData);

    String encoded = Parcels.encode(fakeGoal);
    assertNotNull(encoded);
    Object decoded = ComplicationParcels.decodeParcelable(cl, encoded);
    assertTrue(decoded instanceof ComplicationData);
    ComplicationData goalData = (ComplicationData) decoded;
    assertEquals(13, goalData.getType());
    assertEquals("8,200", goalData.getShortText().getTextAt(app.getResources(), 1000L).toString());
    assertEquals(8200f, goalData.getRangedValue(), 0.01f);
    assertEquals(10000f, goalData.getTargetValue(), 0.01f);

    JSONObject summary = ComplicationParcels.summarizeToJson(goalData, app.getResources(), 1000L, 10);
    assertEquals(10, summary.getInt("slot"));
    assertEquals(13, summary.getInt("type"));
    assertEquals("8,200", summary.getString("text"));
    assertEquals("Steps", summary.getString("title"));
    assertEquals(8200.0, summary.getDouble("value"), 0.01);
    assertEquals(10000.0, summary.getDouble("max"), 0.01);

    // Timeline entry selection + tapAction inspection.
    PendingIntent pi =
        PendingIntent.getBroadcast(
            app, 7, new Intent("com.example.COMP_TAP"), PendingIntent.FLAG_IMMUTABLE);
    Bundle entryFields = new Bundle();
    entryFields.putParcelable("SHORT_TEXT", ComplicationText.plainText("Rain 15°"));
    entryFields.putLong("TIMELINE_START_TIME", 1000L);
    entryFields.putLong("TIMELINE_END_TIME", 2000L);
    ComplicationData entry = new ComplicationData(3, entryFields);

    Bundle parentFields = new Bundle();
    parentFields.putParcelable("SHORT_TEXT", ComplicationText.plainText("Clear 18°"));
    parentFields.putParcelable("TAP_ACTION", pi);
    parentFields.putParcelableArray("TIMELINE_ENTRIES", new Parcelable[] {entry});
    ComplicationData parent = new ComplicationData(3, parentFields);

    assertEquals(pi, ComplicationParcels.tapAction(parent));
    ComplicationData at500 =
        (ComplicationData) ComplicationParcels.activeTimelineEntry(parent, 500_000L);
    assertEquals("Clear 18°", at500.getShortText().getTextAt(app.getResources(), 500_000L));
    ComplicationData at1500 =
        (ComplicationData) ComplicationParcels.activeTimelineEntry(parent, 1_500_000L);
    assertEquals("Rain 15°", at1500.getShortText().getTextAt(app.getResources(), 1_500_000L));
  }

  @Test
  public void simulatorShadowResourcesAppliesDynamicThemeColorsFromWatchFacePalette() {
    Resources res = app.getResources();
    int accentId = res.getIdentifier("system_accent1_200", "color", "android");
    assertTrue("expected android.R.color.system_accent1_200", accentId != 0);

    WatchFaceDynamicColors.Palette violet =
        new WatchFaceDynamicColors.Palette(0xFF7B61FF, 0xFF68587A, 0xFFFF6B9E, "config");
    WatchFaceDynamicColors.apply(app, violet);
    assertTrue(WatchFaceDynamicColors.isActive());
    assertEquals(
        1, Settings.Global.getInt(app.getContentResolver(), "dynamic_color_theme_enabled", 0));
    int violetAccent = res.getColor(accentId, null);
    ColorStateList csl = res.getColorStateList(accentId, null);
    assertEquals(violetAccent, csl.getDefaultColor());

    TypedValue tv = new TypedValue();
    res.getValue(accentId, tv, true);
    assertEquals(TypedValue.TYPE_INT_COLOR_ARGB8, tv.type);
    assertEquals(violetAccent, tv.data);

    // Switching the watch face flavor updates the system color table in-place!
    WatchFaceDynamicColors.Palette coral =
        new WatchFaceDynamicColors.Palette(0xFFFF5252, 0xFF8D6E63, 0xFFFFAB40, "config");
    WatchFaceDynamicColors.apply(app, coral);
    int coralAccent = res.getColor(accentId, null);
    assertNotEquals(violetAccent, coralAccent);

    // Clearing disables dynamic colors.
    WatchFaceDynamicColors.clear(app);
    assertFalse(WatchFaceDynamicColors.isActive());
    assertNull(WatchFaceDynamicColors.lookup(accentId));
    assertEquals(
        0, Settings.Global.getInt(app.getContentResolver(), "dynamic_color_theme_enabled", 1));
  }

  @Test
  public void watchFaceClientEncodesUserStyleMapReadsSchemaColorsAndSlotBounds() throws Exception {
    Parcel mapParcel = Parcel.obtain();
    try {
      Map<String, byte[]> style = new LinkedHashMap<>();
      style.put("color_style_setting", "emerald".getBytes(StandardCharsets.UTF_8));
      WatchFaceClient.writeUserStyleMap(mapParcel, style);
      mapParcel.setDataPosition(0);
      assertEquals(1, mapParcel.readInt()); // map size
      assertEquals(1, mapParcel.readInt()); // keys collection size
      assertEquals(4, mapParcel.readInt()); // COLLECTION_TYPE_STRING
      assertEquals("color_style_setting", mapParcel.readString());
      assertEquals(1, mapParcel.readInt()); // values collection size
      assertEquals(3, mapParcel.readInt()); // COLLECTION_TYPE_SERIALIZABLE
      assertEquals("[B", mapParcel.readString());
      int serializedLen = mapParcel.readInt();
      assertTrue(serializedLen > 0);
      byte[] serialized = mapParcel.createByteArray();
      try (java.io.ObjectInputStream ois =
          new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(serialized))) {
        byte[] decoded = (byte[]) ois.readObject();
        assertEquals("emerald", new String(decoded, StandardCharsets.UTF_8));
      }
    } finally {
      mapParcel.recycle();
    }

    // Slot with Rect bounds in field #1 of ComplicationStateWireFormat.
    Parcel slotParcel = Parcel.obtain();
    try {
      slotParcel.writeString("androidx.versionedparcelable.ParcelImpl");
      slotParcel.writeString(
          "androidx.wear.watchface.data.IdAndComplicationStateWireFormatParcelizer");
      int f1 = openField(slotParcel, 1);
      slotParcel.writeInt(42);
      closeField(slotParcel, f1);

      int f2 = openField(slotParcel, 2);
      slotParcel.writeString("androidx.wear.watchface.data.ComplicationStateWireFormatParcelizer");
      int sub1 = openField(slotParcel, 1);
      slotParcel.writeParcelable(new Rect(80, 120, 180, 220), 0);
      closeField(slotParcel, sub1);
      int sub3 = openField(slotParcel, 3);
      slotParcel.writeInt(1);
      slotParcel.writeInt(3);
      closeField(slotParcel, sub3);
      int sub7 = openField(slotParcel, 7);
      slotParcel.writeInt(1);
      closeField(slotParcel, sub7);
      closeField(slotParcel, f2);

      slotParcel.setDataPosition(0);
      WatchFaceClient.Slot slot = WatchFaceClient.readSlot(slotParcel);
      assertEquals(42, slot.id);
      assertTrue(slot.hasBounds);
      assertEquals(80, slot.left);
      assertEquals(120, slot.top);
      assertEquals(180, slot.right);
      assertEquals(220, slot.bottom);
      assertTrue(slot.contains(130f, 170f));
      assertFalse(slot.contains(20f, 20f));
    } finally {
      slotParcel.recycle();
    }
  }

  @Test
  public void watchFacePushManagerSupportsLifecycleAndEnforcesValidationErrors() throws Exception {
    WatchFacePushManager mgr = Sdk.getWearManager(app, WatchFacePushManager.class);
    assertNotNull(mgr);

    String clientPkg = app.getPackageName();
    String pushedPkg = clientPkg + ".watchfacepush.neon";
    File validApk = buildPushTestWffApk(pushedPkg, true, false);

    // 1. Reject blank validation token -> ERROR_INVALID_VALIDATION_TOKEN.
    try (ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(validApk, ParcelFileDescriptor.MODE_READ_ONLY)) {
      Outcome<WatchFaceSlot, WatchFacePushManager.AddException> badToken =
          callPush(r -> mgr.addWatchFace(pfd, "   ", Runnable::run, r));
      assertNotNull(badToken.error);
      assertEquals(
          WatchFacePushManager.AddException.ERROR_INVALID_VALIDATION_TOKEN,
          badToken.error.getErrorCode());
    }

    // 2. Reject wrong package prefix -> ERROR_INVALID_PACKAGE_NAME.
    File wrongPkgApk = buildPushTestWffApk("com.other.unrelated.face", true, false);
    try (ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(wrongPkgApk, ParcelFileDescriptor.MODE_READ_ONLY)) {
      Outcome<WatchFaceSlot, WatchFacePushManager.AddException> badPkg =
          callPush(r -> mgr.addWatchFace(pfd, "valid-token", Runnable::run, r));
      assertNotNull(badPkg.error);
      assertEquals(
          WatchFacePushManager.AddException.ERROR_INVALID_PACKAGE_NAME,
          badPkg.error.getErrorCode());
    }

    // 3. Reject executable code inside pushed WFF APK -> ERROR_UNEXPECTED_CONTENT.
    File codeApk = buildPushTestWffApk(clientPkg + ".watchfacepush.evil", true, true);
    try (ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(codeApk, ParcelFileDescriptor.MODE_READ_ONLY)) {
      Outcome<WatchFaceSlot, WatchFacePushManager.AddException> badCode =
          callPush(r -> mgr.addWatchFace(pfd, "valid-token", Runnable::run, r));
      assertNotNull(badCode.error);
      assertEquals(
          WatchFacePushManager.AddException.ERROR_UNEXPECTED_CONTENT,
          badCode.error.getErrorCode());
    }

    // 4. Add valid watch face -> succeeds, registers in WffWatchFaces, lists in slot response!
    WatchFaceSlot added;
    try (ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(validApk, ParcelFileDescriptor.MODE_READ_ONLY)) {
      Outcome<WatchFaceSlot, WatchFacePushManager.AddException> res =
          callPush(r -> mgr.addWatchFace(pfd, "tok-v1", Runnable::run, r));
      assertNull("unexpected addWatchFace error: " + res.error, res.error);
      added = res.value;
    }
    assertNotNull(added);
    assertEquals(pushedPkg, added.getPackageName());
    assertTrue(WffWatchFaces.packages().contains(pushedPkg));

    Outcome<ListWatchFaceSlotsResponse, WatchFacePushManager.ListException> listed =
        callPush(r -> mgr.listWatchFaceSlots(Runnable::run, r));
    assertNull(listed.error);
    assertEquals(1, listed.value.getInstalledWatchFaceSlots().size());
    assertEquals(0, listed.value.getAvailableSlotCount());

    // 5. Slot limit reached on second add -> ERROR_SLOT_LIMIT_REACHED.
    File secondApk = buildPushTestWffApk(clientPkg + ".watchfacepush.second", true, false);
    try (ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(secondApk, ParcelFileDescriptor.MODE_READ_ONLY)) {
      Outcome<WatchFaceSlot, WatchFacePushManager.AddException> limitRes =
          callPush(r -> mgr.addWatchFace(pfd, "tok-v2", Runnable::run, r));
      assertNotNull(limitRes.error);
      assertEquals(
          WatchFacePushManager.AddException.ERROR_SLOT_LIMIT_REACHED,
          limitRes.error.getErrorCode());
    }

    // 6. Activate pushed face and verify isWatchFaceActive == true!
    WatchFaceHost host =
        new WatchFaceHost(
            app,
            null,
            Protocol.create(),
            new WatchFaceHost.Display() {
              @Override
              public void frame(Bitmap frame) {}

              @Override
              public void builtInComplication(String text) {}
            });
    Outcome<Void, WatchFacePushManager.SetActiveException> activated =
        callPush(r -> mgr.setWatchFaceAsActive(added.getSlotId(), Runnable::run, r));
    assertNull("unexpected setWatchFaceAsActive error: " + activated.error, activated.error);

    Outcome<Boolean, WatchFacePushManager.IsActiveException> isActive =
        callPush(r -> mgr.isWatchFaceActive(pushedPkg, Runnable::run, r));
    assertNull(isActive.error);
    assertTrue(isActive.value.booleanValue());

    // Second setWatchFaceAsActive call exceeds Wear OS 6 one-shot budget -> ERROR_MAXIMUM_ATTEMPTS_REACHED.
    Outcome<Void, WatchFacePushManager.SetActiveException> secondActivate =
        callPush(r -> mgr.setWatchFaceAsActive(added.getSlotId(), Runnable::run, r));
    assertNotNull(secondActivate.error);
    assertEquals(
        WatchFacePushManager.SetActiveException.ERROR_MAXIMUM_ATTEMPTS_REACHED,
        secondActivate.error.getErrorCode());

    // 7. Update slot in-place and then remove it.
    File updatedApk = buildPushTestWffApk(clientPkg + ".watchfacepush.solar", true, false);
    try (ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(updatedApk, ParcelFileDescriptor.MODE_READ_ONLY)) {
      Outcome<WatchFaceSlot, WatchFacePushManager.UpdateException> upd =
          callPush(r -> mgr.updateWatchFace(added.getSlotId(), pfd, "tok-v3", Runnable::run, r));
      assertNull(upd.error);
      assertEquals(clientPkg + ".watchfacepush.solar", upd.value.getPackageName());
    }

    Outcome<Void, WatchFacePushManager.RemoveException> removed =
        callPush(r -> mgr.removeWatchFace(added.getSlotId(), Runnable::run, r));
    assertNull(removed.error);
    assertFalse(WffWatchFaces.packages().contains(clientPkg + ".watchfacepush.solar"));
    host.unset();
    WatchFaceHost.resetCurrent();
  }

  private File buildPushTestWffApk(String pkg, boolean includeWatchFaceXml, boolean includeDex)
      throws Exception {
    File apk = temp.newFile(pkg.replace('.', '_') + ".apk");
    String manifest =
        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\""
            + pkg
            + "\">\n"
            + "  <application android:hasCode=\"false\">\n"
            + "    <property android:name=\"com.google.wear.watchface.format.version\" android:value=\"2\"/>\n"
            + "  </application>\n"
            + "</manifest>\n";
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
      zos.putNextEntry(new ZipEntry("AndroidManifest.xml"));
      zos.write(manifest.getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
      if (includeWatchFaceXml) {
        zos.putNextEntry(new ZipEntry("res/raw/watchface.xml"));
        zos.write(
            "<WatchFace width=\"450\" height=\"450\"><Scene backgroundColor=\"#FF102030\"/></WatchFace>"
                .getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
      if (includeDex) {
        zos.putNextEntry(new ZipEntry("classes.dex"));
        zos.write(new byte[] {'d', 'e', 'x', '\n'});
        zos.closeEntry();
      }
    }
    return apk;
  }

  private interface AsyncCall<T, E extends Throwable> {
    void invoke(OutcomeReceiver<T, E> receiver);
  }

  private static final class Outcome<T, E extends Throwable> {
    final T value;
    final E error;

    Outcome(T value, E error) {
      this.value = value;
      this.error = error;
    }
  }

  private static <T, E extends Throwable> Outcome<T, E> callPush(AsyncCall<T, E> call)
      throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<T> valRef = new AtomicReference<>();
    AtomicReference<E> errRef = new AtomicReference<>();
    call.invoke(
        new OutcomeReceiver<T, E>() {
          @Override
          public void onResult(T result) {
            valRef.set(result);
            latch.countDown();
          }

          @Override
          public void onError(E error) {
            errRef.set(error);
            latch.countDown();
          }
        });
    assertTrue("timed out awaiting push callback", latch.await(5, TimeUnit.SECONDS));
    return new Outcome<>(valRef.get(), errRef.get());
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
}
