package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** End-to-end AIDL wire tests for {@link WearHealthServices} (Exercise, Measure, Passive). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WearHealthServicesWireTest {

  private Application app;
  private SystemData systemData;
  private WearHealthServices whs;

  @Before
  public void setUp() {
    app = (Application) RuntimeEnvironment.getApplication();
    systemData = new SystemData(app);
    new WatchState(app, systemData);
    whs = WearHealthServices.install(app);
  }

  @After
  public void tearDown() {
    WearHealthServices.resetCurrent();
    WatchState.resetCurrent();
  }

  @Test
  public void healthServicesAndVersionBindersReportVersions() throws Exception {
    assertEquals(1, transactInt(whs.healthServicesBinder(), WearHealthServices.DESCRIPTOR_HEALTH_SERVICES, 1));
    assertEquals(1, transactInt(whs.versionBinder(), WearHealthServices.DESCRIPTOR_VERSION, 1));
    assertEquals(27, transactInt(whs.versionBinder(), WearHealthServices.DESCRIPTOR_VERSION, 2));
  }

  @Test
  public void measureClientCapabilitiesRegisterUpdatesAndAvailability() throws Exception {
    IBinder measure = whs.measureBinder();
    assertEquals(1, transactInt(measure, WearHealthServices.DESCRIPTOR_MEASURE, 1));

    // getCapabilities
    byte[] capsResp = transactProtoReply(measure, WearHealthServices.DESCRIPTOR_MEASURE, 4, packageReq());
    assertNotNull(capsResp);
    assertTrue(capsResp.length > 10);

    // registerCallback for HEART_RATE_BPM
    HealthProto.Writer regReq = new HealthProto.Writer();
    regReq.writeString(1, app.getPackageName());
    regReq.writeBytes(2, HealthProto.HEART_RATE_BPM.toProto());

    RecordingCallback cb = new RecordingCallback();
    RecordingCallback status = new RecordingCallback();
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(WearHealthServices.DESCRIPTOR_MEASURE);
      data.writeInt(1);
      data.writeByteArray(regReq.toByteArray());
      data.writeStrongBinder(cb);
      data.writeStrongBinder(status);
      measure.transact(2, data, reply, 0);
      reply.readException();
    } finally {
      reply.recycle();
      data.recycle();
    }
    assertEquals(List.of(1), status.codes);
    // Initial delivery: availability event + data points event
    assertEquals(2, cb.payloads.size());

    // Mutate heartRate via SystemData -> pushes updated data point
    cb.payloads.clear();
    systemData.command(new JSONObject().put("heartRate", 142.0));
    assertEquals(1, cb.payloads.size());
    assertNotNull(HealthProto.readBytes(cb.payloads.get(0), 1)); // data_point_response

    // Availability change via whs command -> pushes availability event
    cb.payloads.clear();
    whs.command(
        new JSONObject()
            .put("op", "availability")
            .put("dataType", "HEART_RATE_BPM")
            .put("availability", "ACQUIRING"));
    assertEquals(1, cb.payloads.size());
    assertNotNull(HealthProto.readBytes(cb.payloads.get(0), 2)); // availability_response

    // Unregister stops further deliveries
    cb.payloads.clear();
    status.codes.clear();
    data = Parcel.obtain();
    reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(WearHealthServices.DESCRIPTOR_MEASURE);
      data.writeInt(1);
      data.writeByteArray(regReq.toByteArray());
      data.writeStrongBinder(cb);
      data.writeStrongBinder(status);
      measure.transact(3, data, reply, 0);
      reply.readException();
    } finally {
      reply.recycle();
      data.recycle();
    }
    assertEquals(List.of(1), status.codes);
    systemData.command(new JSONObject().put("heartRate", 150.0));
    assertTrue(cb.payloads.isEmpty());
  }

  @Test
  public void passiveClientEvaluatesGoalsAndDeliversHealthEvents() throws Exception {
    IBinder passive = whs.passiveBinder();
    byte[] capsResp = transactProtoReply(passive, WearHealthServices.DESCRIPTOR_PASSIVE, 6, packageReq());
    assertNotNull(capsResp);
    byte[] caps = HealthProto.readBytes(capsResp, 1);
    // Field 2 is supported_data_types_passive_goals
    assertNotNull(HealthProto.readBytes(caps, 2));

    // Register callback with STEPS_DAILY >= 10000 goal
    HealthProto.Writer cond = new HealthProto.Writer();
    cond.writeBytes(1, HealthProto.STEPS_DAILY.toProto());
    cond.writeBytes(2, HealthProto.valueLong(10_000));
    cond.writeVarint(3, HealthProto.COMPARISON_GREATER_THAN_OR_EQUAL);

    HealthProto.Writer goal = new HealthProto.Writer();
    goal.writeBytes(1, cond.toByteArray());
    goal.writeVarint(2, HealthProto.TRIGGER_FREQUENCY_ONCE);

    HealthProto.Writer cfg = new HealthProto.Writer();
    cfg.writeBytes(1, HealthProto.STEPS_DAILY.toProto());
    cfg.writeBool(2, true);
    cfg.writeBytes(3, goal.toByteArray());
    cfg.writeVarint(4, HealthProto.HEALTH_EVENT_FALL_DETECTED);

    HealthProto.Writer req = new HealthProto.Writer();
    req.writeString(1, app.getPackageName());
    req.writeBytes(2, cfg.toByteArray());

    RecordingCallback listener = new RecordingCallback();
    RecordingCallback status = new RecordingCallback();
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(WearHealthServices.DESCRIPTOR_PASSIVE);
      data.writeInt(1);
      data.writeByteArray(req.toByteArray());
      data.writeStrongBinder(listener);
      data.writeStrongBinder(status);
      passive.transact(12, data, reply, 0);
      reply.readException();
    } finally {
      reply.recycle();
      data.recycle();
    }
    assertEquals(List.of(1), status.codes);
    // Initial update (steps=4200 < 10000, so only update event, no goal yet)
    assertEquals(1, listener.payloads.size());
    assertNotNull(HealthProto.readBytes(listener.payloads.get(0), 1));

    // Cross 10,000 steps -> update event + goal event!
    listener.payloads.clear();
    systemData.command(new JSONObject().put("steps", 10_500));
    assertEquals(2, listener.payloads.size());
    assertNotNull(HealthProto.readBytes(listener.payloads.get(1), 2)); // passive_goal_response

    // Trigger fall detected health event
    listener.payloads.clear();
    whs.command(new JSONObject().put("op", "healthEvent").put("type", "FALL_DETECTED"));
    assertEquals(1, listener.payloads.size());
    assertNotNull(HealthProto.readBytes(listener.payloads.get(0), 3)); // health_event_response

    // Trigger permission lost
    listener.payloads.clear();
    whs.command(new JSONObject().put("op", "permissionLost"));
    assertEquals(1, listener.payloads.size());
    assertNotNull(HealthProto.readBytes(listener.payloads.get(0), 4)); // permission_lost_response
  }

  @Test
  public void exerciseClientFullLifecycleGoalsLapsAndOtherAppStatus() throws Exception {
    IBinder exercise = whs.exerciseBinder();
    assertEquals(7, transactInt(exercise, WearHealthServices.DESCRIPTOR_EXERCISE, 1));

    // Capabilities
    byte[] capsResp = transactProtoReply(exercise, WearHealthServices.DESCRIPTOR_EXERCISE, 12, packageReq());
    assertNotNull(capsResp);
    assertTrue(capsResp.length > 50);

    // Initial getCurrentExerciseInfo -> NO_EXERCISE_IN_PROGRESS (3)
    assertEquals(
        HealthProto.TRACKED_STATUS_NO_EXERCISE_IN_PROGRESS, queryExerciseTrackedStatus(exercise));

    // Register update listener
    RecordingCallback listener = new RecordingCallback();
    RecordingCallback status = new RecordingCallback();
    transactSetExerciseListener(exercise, 8, listener, status);
    assertEquals(List.of(1), status.codes);

    // prepareExercise (RUNNING, HR + LOCATION)
    HealthProto.Writer warmUp = new HealthProto.Writer();
    warmUp.writeVarint(1, HealthProto.EXERCISE_TYPE_RUNNING);
    warmUp.writeBytes(2, HealthProto.HEART_RATE_BPM.toProto());
    warmUp.writeBytes(2, HealthProto.LOCATION.toProto());
    HealthProto.Writer prepReq = new HealthProto.Writer();
    prepReq.writeString(1, app.getPackageName());
    prepReq.writeBytes(2, warmUp.toByteArray());
    transactProtoStatus(exercise, WearHealthServices.DESCRIPTOR_EXERCISE, 15, prepReq.toByteArray(), status);
    // Expect PREPARING update + 2 availability events (HR, Location)
    assertEquals(3, listener.payloads.size());
    assertEquals(
        HealthProto.TRACKED_STATUS_OWNED_EXERCISE_IN_PROGRESS, queryExerciseTrackedStatus(exercise));

    // startExercise with 250 calorie goal
    listener.payloads.clear();
    HealthProto.Writer cond = new HealthProto.Writer();
    cond.writeBytes(1, HealthProto.CALORIES_TOTAL.toProto());
    cond.writeBytes(2, HealthProto.valueDouble(250.0));
    cond.writeVarint(3, HealthProto.COMPARISON_GREATER_THAN_OR_EQUAL);
    HealthProto.Writer goal = new HealthProto.Writer();
    goal.writeVarint(1, HealthProto.EXERCISE_GOAL_ONE_TIME);
    goal.writeBytes(2, cond.toByteArray());

    HealthProto.Writer exCfg = new HealthProto.Writer();
    exCfg.writeVarint(1, HealthProto.EXERCISE_TYPE_RUNNING);
    exCfg.writeBytes(2, HealthProto.HEART_RATE_BPM.toProto());
    exCfg.writeBytes(3, HealthProto.HEART_RATE_BPM_STATS.toProto());
    exCfg.writeBytes(3, HealthProto.CALORIES_TOTAL.toProto());
    exCfg.writeBytes(3, HealthProto.DISTANCE_TOTAL.toProto());
    exCfg.writeBool(4, true);
    exCfg.writeBool(5, true);
    exCfg.writeBytes(6, goal.toByteArray());

    HealthProto.Writer startReq = new HealthProto.Writer();
    startReq.writeString(1, app.getPackageName());
    startReq.writeBytes(2, exCfg.toByteArray());
    transactProtoStatus(exercise, WearHealthServices.DESCRIPTOR_EXERCISE, 2, startReq.toByteArray(), status);
    assertEquals(1, listener.payloads.size());

    // Push exercise update crossing 250 calories -> achieved goal included!
    listener.payloads.clear();
    JSONObject state =
        whs.command(
            new JSONObject()
                .put("op", "exerciseUpdate")
                .put("heartRate", 162.0)
                .put("calories", 275.0)
                .put("distanceMeters", 3200.0)
                .put("activeDurationSeconds", 900));
    assertEquals("ACTIVE", state.getJSONObject("exercise").getString("state"));
    assertEquals(1, listener.payloads.size());
    byte[] updResp = HealthProto.readBytes(listener.payloads.get(0), 1);
    byte[] upd = HealthProto.readBytes(updResp, 1);
    assertNotNull(HealthProto.readBytes(upd, 5)); // latest_achieved_goals

    // markLap -> lap summary event (field 2 of ExerciseUpdateListenerEvent)
    listener.payloads.clear();
    transactPackageStatus(exercise, WearHealthServices.DESCRIPTOR_EXERCISE, 6, status);
    assertEquals(1, listener.payloads.size());
    assertNotNull(HealthProto.readBytes(listener.payloads.get(0), 2));

    // pause -> resume -> end
    transactPackageStatus(exercise, WearHealthServices.DESCRIPTOR_EXERCISE, 3, status);
    assertEquals("USER_PAUSED", whs.describe().getJSONObject("exercise").getString("state"));
    transactPackageStatus(exercise, WearHealthServices.DESCRIPTOR_EXERCISE, 4, status);
    assertEquals("ACTIVE", whs.describe().getJSONObject("exercise").getString("state"));
    transactPackageStatus(exercise, WearHealthServices.DESCRIPTOR_EXERCISE, 5, status);
    assertEquals("ENDED", whs.describe().getJSONObject("exercise").getString("state"));
    assertEquals(
        HealthProto.TRACKED_STATUS_NO_EXERCISE_IN_PROGRESS, queryExerciseTrackedStatus(exercise));

    // Simulate another app tracking an exercise
    whs.command(new JSONObject().put("op", "otherAppExercise").put("active", true));
    assertEquals(
        HealthProto.TRACKED_STATUS_OTHER_APP_IN_PROGRESS, queryExerciseTrackedStatus(exercise));
  }

  private byte[] packageReq() {
    HealthProto.Writer w = new HealthProto.Writer();
    w.writeString(1, app.getPackageName());
    return w.toByteArray();
  }

  private int queryExerciseTrackedStatus(IBinder exercise) throws Exception {
    RecordingCallback infoCb = new RecordingCallback();
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(WearHealthServices.DESCRIPTOR_EXERCISE);
      data.writeString(app.getPackageName());
      data.writeStrongBinder(infoCb);
      exercise.transact(7, data, reply, 0);
      reply.readException();
    } finally {
      reply.recycle();
      data.recycle();
    }
    assertEquals(1, infoCb.payloads.size());
    byte[] info = HealthProto.readBytes(infoCb.payloads.get(0), 1);
    HealthProto.Reader r = new HealthProto.Reader(info);
    while (r.next()) {
      if (r.field == 1) return (int) r.varint();
      r.skip();
    }
    return -1;
  }

  private void transactSetExerciseListener(
      IBinder exercise, int code, IBinder listener, IBinder status) throws Exception {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(WearHealthServices.DESCRIPTOR_EXERCISE);
      data.writeString(app.getPackageName());
      data.writeStrongBinder(listener);
      data.writeStrongBinder(status);
      exercise.transact(code, data, reply, 0);
      reply.readException();
    } finally {
      reply.recycle();
      data.recycle();
    }
  }

  private void transactProtoStatus(
      IBinder binder, String descriptor, int code, byte[] reqProto, RecordingCallback status)
      throws Exception {
    status.codes.clear();
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(descriptor);
      data.writeInt(1);
      data.writeByteArray(reqProto);
      data.writeStrongBinder(status);
      binder.transact(code, data, reply, 0);
      reply.readException();
    } finally {
      reply.recycle();
      data.recycle();
    }
    assertEquals(List.of(1), status.codes);
  }

  private void transactPackageStatus(
      IBinder binder, String descriptor, int code, RecordingCallback status) throws Exception {
    status.codes.clear();
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(descriptor);
      data.writeString(app.getPackageName());
      data.writeStrongBinder(status);
      binder.transact(code, data, reply, 0);
      reply.readException();
    } finally {
      reply.recycle();
      data.recycle();
    }
    assertEquals(List.of(1), status.codes);
  }

  private static int transactInt(IBinder binder, String descriptor, int code) throws Exception {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(descriptor);
      binder.transact(code, data, reply, 0);
      reply.readException();
      return reply.readInt();
    } finally {
      reply.recycle();
      data.recycle();
    }
  }

  private static byte[] transactProtoReply(
      IBinder binder, String descriptor, int code, byte[] reqProto) throws Exception {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(descriptor);
      data.writeInt(1);
      data.writeByteArray(reqProto);
      binder.transact(code, data, reply, 0);
      reply.readException();
      return reply.readInt() != 0 ? reply.createByteArray() : null;
    } finally {
      reply.recycle();
      data.recycle();
    }
  }

  private static final class RecordingCallback extends Binder {
    final List<Integer> codes = new ArrayList<>();
    final List<byte[]> payloads = new ArrayList<>();

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
      codes.add(code);
      // Skip interface token written by writeInterfaceToken (strict-mode int + flags + descriptor).
      // Since RecordingCallback is used across multiple callback descriptors, use enforceInterface
      // on the descriptor we know or parse the payload after enforcing the matching descriptor.
      int startPos = data.dataPosition();
      String[] known = {
        WearHealthServices.DESCRIPTOR_STATUS_CALLBACK,
        WearHealthServices.DESCRIPTOR_MEASURE_CALLBACK,
        WearHealthServices.DESCRIPTOR_PASSIVE_CALLBACK,
        WearHealthServices.DESCRIPTOR_EXERCISE_LISTENER,
        WearHealthServices.DESCRIPTOR_EXERCISE_INFO_CALLBACK,
      };
      for (String desc : known) {
        data.setDataPosition(startPos);
        try {
          data.enforceInterface(desc);
          break;
        } catch (SecurityException ignored) {
          // Try next descriptor.
        }
      }
      if (data.dataAvail() > 0) {
        int present = data.readInt();
        if (present != 0) {
          payloads.add(data.createByteArray());
        }
      }
      if (reply != null) {
        reply.writeNoException();
      }
      return true;
    }
  }
}
