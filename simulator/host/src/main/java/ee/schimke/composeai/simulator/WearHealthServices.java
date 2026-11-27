package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowApplication;

/**
 * Comprehensive Wear Health Services (WHS) platform emulation over real Android AIDL binders:
 * {@code ExerciseClient}, {@code MeasureClient}, {@code PassiveMonitoringClient}, {@code
 * HealthServicesClient}, and {@code VersionApiService}.
 *
 * <p>Installed into {@code com.google.android.wearable.healthservices} by {@link
 * WearPlatformServices#install(Application)} so apps execute their unmodified {@code
 * androidx.health.services.client} IPC code paths.
 */
final class WearHealthServices {

  static final String HEALTH_SERVICES_PACKAGE = "com.google.android.wearable.healthservices";

  static final String ACTION_EXERCISE = "hs.exerciseclient.BIND";
  static final String ACTION_MEASURE = "hs.measureclient.BIND";
  static final String ACTION_PASSIVE = "hs.passiveclient.BIND";
  static final String ACTION_HEALTH_SERVICES = "hs.healthservicesclient.BIND";
  static final String ACTION_VERSION = "hs.versionclient.BIND";

  private static final String CLASS_EXERCISE =
      "com.google.android.wearable.healthservices.ExerciseService";
  private static final String CLASS_MEASURE =
      "com.google.android.wearable.healthservices.MeasureService";
  private static final String CLASS_PASSIVE =
      "com.google.android.wearable.healthservices.PassiveMonitoringService";
  private static final String CLASS_HEALTH_SERVICES =
      "com.google.android.wearable.healthservices.HealthServicesService";
  private static final String CLASS_VERSION =
      "com.google.android.wearable.healthservices.VersionService";

  // Service descriptors
  static final String DESCRIPTOR_EXERCISE =
      "androidx.health.services.client.impl.IExerciseApiService";
  static final String DESCRIPTOR_MEASURE =
      "androidx.health.services.client.impl.IMeasureApiService";
  static final String DESCRIPTOR_PASSIVE =
      "androidx.health.services.client.impl.IPassiveMonitoringApiService";
  static final String DESCRIPTOR_HEALTH_SERVICES =
      "androidx.health.services.client.impl.IHealthServicesApiService";
  static final String DESCRIPTOR_VERSION =
      "androidx.health.services.client.impl.IVersionApiService";

  // Callback descriptors
  static final String DESCRIPTOR_EXERCISE_LISTENER =
      "androidx.health.services.client.impl.IExerciseUpdateListener";
  static final String DESCRIPTOR_EXERCISE_INFO_CALLBACK =
      "androidx.health.services.client.impl.internal.IExerciseInfoCallback";
  static final String DESCRIPTOR_MEASURE_CALLBACK =
      "androidx.health.services.client.impl.IMeasureCallback";
  static final String DESCRIPTOR_PASSIVE_CALLBACK =
      "androidx.health.services.client.impl.IPassiveListenerCallback";
  static final String DESCRIPTOR_PASSIVE_LISTENER_SERVICE =
      "androidx.health.services.client.impl.IPassiveListenerService";
  static final String DESCRIPTOR_STATUS_CALLBACK =
      "androidx.health.services.client.impl.internal.IStatusCallback";

  // IExerciseApiService transaction codes
  private static final int EX_GET_API_VERSION = 1;
  private static final int EX_START_EXERCISE = 2;
  private static final int EX_PAUSE_EXERCISE = 3;
  private static final int EX_RESUME_EXERCISE = 4;
  private static final int EX_END_EXERCISE = 5;
  private static final int EX_MARK_LAP = 6;
  private static final int EX_GET_CURRENT_EXERCISE_INFO = 7;
  private static final int EX_SET_UPDATE_LISTENER = 8;
  private static final int EX_CLEAR_UPDATE_LISTENER = 9;
  private static final int EX_ADD_GOAL = 10;
  private static final int EX_OVERRIDE_AUTO_PAUSE = 11;
  private static final int EX_GET_CAPABILITIES = 12;
  private static final int EX_FLUSH_EXERCISE = 13;
  private static final int EX_REMOVE_GOAL = 14;
  private static final int EX_PREPARE_EXERCISE = 15;
  private static final int EX_UPDATE_EXERCISE_TYPE_CONFIG = 17;
  private static final int EX_OVERRIDE_BATCHING_MODES = 18;
  private static final int EX_ADD_DEBOUNCED_GOAL = 23;
  private static final int EX_REMOVE_DEBOUNCED_GOAL = 24;

  // IMeasureApiService transaction codes
  private static final int MS_GET_API_VERSION = 1;
  private static final int MS_REGISTER_CALLBACK = 2;
  private static final int MS_UNREGISTER_CALLBACK = 3;
  private static final int MS_GET_CAPABILITIES = 4;

  // IPassiveMonitoringApiService transaction codes
  private static final int PM_GET_API_VERSION = 1;
  private static final int PM_GET_CAPABILITIES = 6;
  private static final int PM_FLUSH = 7;
  private static final int PM_REGISTER_LISTENER_SERVICE = 11;
  private static final int PM_REGISTER_LISTENER_CALLBACK = 12;
  private static final int PM_UNREGISTER_LISTENER_SERVICE = 13;
  private static final int PM_UNREGISTER_LISTENER_CALLBACK = 14;

  // Callback transaction codes
  private static final int CALLBACK_STATUS_ON_SUCCESS = 1;
  private static final int CALLBACK_STATUS_ON_FAILURE = 2;
  private static final int CALLBACK_EXERCISE_INFO_ON_INFO = 1;
  private static final int CALLBACK_EXERCISE_INFO_ON_FAILURE = 2;
  private static final int CALLBACK_EXERCISE_ON_EVENT = 1;
  private static final int CALLBACK_MEASURE_ON_EVENT = 1;
  private static final int CALLBACK_PASSIVE_CALLBACK_ON_EVENT = 1;
  private static final int CALLBACK_PASSIVE_SERVICE_ON_EVENT = 2;

  private static volatile WearHealthServices active;

  private final Application app;
  private final ExerciseApiBinder exerciseBinder;
  private final MeasureApiBinder measureBinder;
  private final PassiveMonitoringApiBinder passiveBinder;
  private final HealthServicesApiBinder healthServicesBinder;
  private final VersionApiBinder versionBinder;

  // Shared WHS capabilities (customizable via `whs` command)
  private final Set<Integer> supportedExerciseTypes =
      Collections.synchronizedSet(new LinkedHashSet<>());
  private final List<HealthProto.DataTypeSpec> supportedMeasureTypes =
      Collections.synchronizedList(new ArrayList<>());
  private final List<HealthProto.DataTypeSpec> supportedPassiveMonitoringTypes =
      Collections.synchronizedList(new ArrayList<>());
  private final List<HealthProto.DataTypeSpec> supportedPassiveGoalTypes =
      Collections.synchronizedList(new ArrayList<>());
  private volatile boolean autoPauseSupported = true;
  private volatile boolean lapsSupported = true;

  // Extended live metrics (base metrics also synced with WatchState / SystemData)
  private volatile double speedMps = 2.8;
  private volatile double paceMillisPerKm = 357_000.0;
  private volatile double elevationGainMeters = 12.0;
  private volatile double elevationLossMeters = 5.0;
  private volatile double absoluteElevationMeters = 45.0;
  private volatile long stepsPerMinute = 165L;
  private volatile double vo2Max = 48.0;
  private volatile double latitude = 37.4220;
  private volatile double longitude = -122.0841;
  private volatile double altitude = 45.0;
  private volatile double bearing = 90.0;
  private volatile int userActivityState = HealthProto.USER_ACTIVITY_PASSIVE;
  private volatile long userActivityChangeTimeMs;

  // Availability map (dataType name -> DataTypeAvailability or LocationAvailability)
  private final Map<String, Integer> availabilities =
      Collections.synchronizedMap(new LinkedHashMap<>());

  private volatile boolean watchStateListening;

  WearHealthServices(Application app) {
    this.app = app;
    this.userActivityChangeTimeMs = nowEpochMs();
    resetDefaults();
    this.exerciseBinder = new ExerciseApiBinder();
    this.measureBinder = new MeasureApiBinder();
    this.passiveBinder = new PassiveMonitoringApiBinder();
    this.healthServicesBinder = new HealthServicesApiBinder();
    this.versionBinder = new VersionApiBinder();
  }

  static WearHealthServices install(Application app) {
    WearHealthServices whs = new WearHealthServices(app);
    active = whs;
    ShadowApplication shadowApp = Shadows.shadowOf(app);
    registerService(shadowApp, ACTION_EXERCISE, CLASS_EXERCISE, whs.exerciseBinder);
    registerService(shadowApp, ACTION_MEASURE, CLASS_MEASURE, whs.measureBinder);
    registerService(shadowApp, ACTION_PASSIVE, CLASS_PASSIVE, whs.passiveBinder);
    registerService(
        shadowApp, ACTION_HEALTH_SERVICES, CLASS_HEALTH_SERVICES, whs.healthServicesBinder);
    registerService(shadowApp, ACTION_VERSION, CLASS_VERSION, whs.versionBinder);
    return whs;
  }

  private static void registerService(
      ShadowApplication shadowApp, String action, String className, IBinder binder) {
    Intent intent = new Intent(action).setPackage(HEALTH_SERVICES_PACKAGE);
    shadowApp.setComponentNameAndServiceForBindServiceForIntent(
        intent, new ComponentName(HEALTH_SERVICES_PACKAGE, className), binder);
    Navigator.registerForeignBind(intent);
  }

  static WearHealthServices current() {
    return active;
  }

  static void resetCurrent() {
    active = null;
  }

  IBinder exerciseBinder() {
    return exerciseBinder;
  }

  IBinder measureBinder() {
    return measureBinder;
  }

  IBinder passiveBinder() {
    return passiveBinder;
  }

  IBinder healthServicesBinder() {
    return healthServicesBinder;
  }

  IBinder versionBinder() {
    return versionBinder;
  }

  private void resetDefaults() {
    supportedExerciseTypes.clear();
    supportedExerciseTypes.addAll(
        Arrays.asList(
            HealthProto.EXERCISE_TYPE_RUNNING,
            HealthProto.EXERCISE_TYPE_WALKING,
            HealthProto.EXERCISE_TYPE_BIKING,
            HealthProto.EXERCISE_TYPE_BIKING_STATIONARY,
            HealthProto.EXERCISE_TYPE_RUNNING_TREADMILL,
            HealthProto.EXERCISE_TYPE_HIKING,
            HealthProto.EXERCISE_TYPE_SWIMMING_POOL,
            HealthProto.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            HealthProto.EXERCISE_TYPE_WORKOUT,
            HealthProto.EXERCISE_TYPE_YOGA,
            HealthProto.EXERCISE_TYPE_STRENGTH_TRAINING,
            HealthProto.EXERCISE_TYPE_ELLIPTICAL,
            HealthProto.EXERCISE_TYPE_ROWING,
            HealthProto.EXERCISE_TYPE_ROWING_MACHINE,
            HealthProto.EXERCISE_TYPE_STAIR_CLIMBING,
            HealthProto.EXERCISE_TYPE_GOLF,
            HealthProto.EXERCISE_TYPE_SKIING,
            HealthProto.EXERCISE_TYPE_SNOWBOARDING,
            HealthProto.EXERCISE_TYPE_HIIT,
            HealthProto.EXERCISE_TYPE_PILATES,
            HealthProto.EXERCISE_TYPE_CALISTHENICS,
            HealthProto.EXERCISE_TYPE_STRETCHING));

    supportedMeasureTypes.clear();
    supportedMeasureTypes.addAll(
        Arrays.asList(
            HealthProto.HEART_RATE_BPM,
            HealthProto.STEPS_PER_MINUTE,
            HealthProto.SPEED,
            HealthProto.PACE,
            HealthProto.ABSOLUTE_ELEVATION,
            HealthProto.LOCATION,
            HealthProto.VO2_MAX));

    supportedPassiveMonitoringTypes.clear();
    supportedPassiveMonitoringTypes.addAll(
        Arrays.asList(
            HealthProto.HEART_RATE_BPM,
            HealthProto.STEPS,
            HealthProto.STEPS_DAILY,
            HealthProto.STEPS_PER_MINUTE,
            HealthProto.CALORIES,
            HealthProto.CALORIES_DAILY,
            HealthProto.DISTANCE,
            HealthProto.DISTANCE_DAILY,
            HealthProto.FLOORS,
            HealthProto.FLOORS_DAILY,
            HealthProto.ELEVATION_GAIN,
            HealthProto.ELEVATION_GAIN_DAILY,
            HealthProto.ELEVATION_LOSS,
            HealthProto.ABSOLUTE_ELEVATION,
            HealthProto.SPEED,
            HealthProto.PACE,
            HealthProto.VO2_MAX));

    supportedPassiveGoalTypes.clear();
    supportedPassiveGoalTypes.addAll(
        Arrays.asList(
            HealthProto.STEPS_DAILY,
            HealthProto.FLOORS_DAILY,
            HealthProto.CALORIES_DAILY,
            HealthProto.DISTANCE_DAILY,
            HealthProto.ELEVATION_GAIN_DAILY));

    autoPauseSupported = true;
    lapsSupported = true;

    availabilities.clear();
    availabilities.put(HealthProto.HEART_RATE_BPM.name, HealthProto.DATA_AVAILABILITY_AVAILABLE);
    availabilities.put(
        HealthProto.LOCATION.name, HealthProto.LOCATION_AVAILABILITY_ACQUIRED_TETHERED);
  }

  private void ensureWatchStateListener() {
    if (watchStateListening) return;
    WatchState state = WatchState.current();
    if (state == null) return;
    watchStateListening = true;
    state.onChange(this::onWatchStateChanged);
  }

  /** Called when {@code WatchState} / {@code health} command updates metrics. */
  void onWatchStateChanged() {
    passiveBinder.onMetricsChanged();
    measureBinder.onMetricsChanged();
    exerciseBinder.onHealthStateChanged();
  }

  private long nowEpochMs() {
    return WatchState.currentTimeMillis();
  }

  private static long bootElapsedMs() {
    try {
      long v = SystemClock.elapsedRealtime();
      return v > 0 ? v : 3_600_000L;
    } catch (Throwable ignored) {
      return 3_600_000L;
    }
  }

  private double currentHeartRate() {
    WatchState ws = WatchState.current();
    return ws != null ? ws.heartRate() : 72.0;
  }

  private int currentHrAccuracy() {
    WatchState ws = WatchState.current();
    return ws != null
        ? HealthProto.hrAccuracyFromSystemData(ws.heartRateAccuracyCode())
        : HealthProto.HR_ACCURACY_MEDIUM;
  }

  private long currentDailySteps() {
    WatchState ws = WatchState.current();
    return ws != null ? ws.steps() : 4200L;
  }

  private double currentDailyCalories() {
    WatchState ws = WatchState.current();
    return ws != null ? ws.calories() : 180.0;
  }

  private double currentDailyDistance() {
    WatchState ws = WatchState.current();
    return ws != null ? ws.distanceMeters() : 3150.0;
  }

  private double currentDailyFloors() {
    WatchState ws = WatchState.current();
    return ws != null ? ws.floors() : 4.0;
  }

  private int availabilityFor(String dataTypeName) {
    Integer val = availabilities.get(dataTypeName);
    if (val != null) return val;
    return HealthProto.LOCATION.name.equals(dataTypeName)
        ? HealthProto.LOCATION_AVAILABILITY_ACQUIRED_TETHERED
        : HealthProto.DATA_AVAILABILITY_AVAILABLE;
  }

  /** Builds a single current {@code DataPoint} proto for {@code spec}, or null if unsupported. */
  private byte[] buildCurrentDataPoint(HealthProto.DataTypeSpec spec) {
    long elapsed = bootElapsedMs();
    long start = Math.max(0L, elapsed - 1_000L);
    if (spec == HealthProto.HEART_RATE_BPM) {
      return HealthProto.sampleDataPoint(
          spec,
          HealthProto.valueDouble(currentHeartRate()),
          elapsed,
          HealthProto.hrAccuracy(currentHrAccuracy()));
    } else if (spec == HealthProto.STEPS_DAILY) {
      return HealthProto.intervalDataPoint(
          spec, HealthProto.valueLong(currentDailySteps()), 0L, elapsed);
    } else if (spec == HealthProto.STEPS) {
      return HealthProto.intervalDataPoint(
          spec, HealthProto.valueLong(Math.max(1L, stepsPerMinute / 6L)), start, elapsed);
    } else if (spec == HealthProto.STEPS_PER_MINUTE) {
      return HealthProto.sampleDataPoint(
          spec, HealthProto.valueLong(stepsPerMinute), elapsed, null);
    } else if (spec == HealthProto.CALORIES_DAILY) {
      return HealthProto.intervalDataPoint(
          spec, HealthProto.valueDouble(currentDailyCalories()), 0L, elapsed);
    } else if (spec == HealthProto.CALORIES) {
      return HealthProto.intervalDataPoint(spec, HealthProto.valueDouble(1.5), start, elapsed);
    } else if (spec == HealthProto.DISTANCE_DAILY) {
      return HealthProto.intervalDataPoint(
          spec, HealthProto.valueDouble(currentDailyDistance()), 0L, elapsed);
    } else if (spec == HealthProto.DISTANCE) {
      return HealthProto.intervalDataPoint(
          spec, HealthProto.valueDouble(speedMps), start, elapsed);
    } else if (spec == HealthProto.FLOORS_DAILY) {
      return HealthProto.intervalDataPoint(
          spec, HealthProto.valueDouble(currentDailyFloors()), 0L, elapsed);
    } else if (spec == HealthProto.FLOORS) {
      return HealthProto.intervalDataPoint(spec, HealthProto.valueDouble(0.0), start, elapsed);
    } else if (spec == HealthProto.ELEVATION_GAIN_DAILY) {
      return HealthProto.intervalDataPoint(
          spec, HealthProto.valueDouble(elevationGainMeters), 0L, elapsed);
    } else if (spec == HealthProto.ELEVATION_GAIN) {
      return HealthProto.intervalDataPoint(
          spec, HealthProto.valueDouble(elevationGainMeters), start, elapsed);
    } else if (spec == HealthProto.ELEVATION_LOSS) {
      return HealthProto.intervalDataPoint(
          spec, HealthProto.valueDouble(elevationLossMeters), start, elapsed);
    } else if (spec == HealthProto.ABSOLUTE_ELEVATION) {
      return HealthProto.sampleDataPoint(
          spec, HealthProto.valueDouble(absoluteElevationMeters), elapsed, null);
    } else if (spec == HealthProto.SPEED) {
      return HealthProto.sampleDataPoint(spec, HealthProto.valueDouble(speedMps), elapsed, null);
    } else if (spec == HealthProto.PACE) {
      return HealthProto.sampleDataPoint(
          spec, HealthProto.valueDouble(paceMillisPerKm), elapsed, null);
    } else if (spec == HealthProto.VO2_MAX) {
      return HealthProto.sampleDataPoint(spec, HealthProto.valueDouble(vo2Max), elapsed, null);
    } else if (spec == HealthProto.LOCATION) {
      return HealthProto.sampleDataPoint(
          spec,
          HealthProto.valueDoubleArray(latitude, longitude, altitude, bearing),
          elapsed,
          HealthProto.locationAccuracy(5.0, 8.0));
    }
    return null;
  }

  private double currentPassiveMetricValue(String dataTypeName) {
    if (HealthProto.STEPS_DAILY.name.equals(dataTypeName)) return currentDailySteps();
    if (HealthProto.FLOORS_DAILY.name.equals(dataTypeName)) return currentDailyFloors();
    if (HealthProto.CALORIES_DAILY.name.equals(dataTypeName)) return currentDailyCalories();
    if (HealthProto.DISTANCE_DAILY.name.equals(dataTypeName)) return currentDailyDistance();
    if (HealthProto.ELEVATION_GAIN_DAILY.name.equals(dataTypeName)) return elevationGainMeters;
    if (HealthProto.HEART_RATE_BPM.name.equals(dataTypeName)) return currentHeartRate();
    return 0.0;
  }

  // ---------------------------------------------------------------------------
  // Control channel command (`whs`)

  JSONObject command(JSONObject c) throws JSONException {
    String op = c.optString("op", "status").trim();
    switch (op) {
      case "status":
        return describe();
      case "reset":
        resetDefaults();
        exerciseBinder.reset();
        return describe();
      case "availability":
        {
          String dtKey = c.optString("dataType", "HEART_RATE_BPM");
          HealthProto.DataTypeSpec spec = HealthProto.findDataType(dtKey);
          if (spec == null) {
            throw new JSONException("Unknown WHS dataType: " + dtKey);
          }
          String availStr = c.optString("availability", "AVAILABLE");
          int code =
              HealthProto.LOCATION.name.equals(spec.name)
                  ? HealthProto.parseLocationAvailability(availStr)
                  : HealthProto.parseDataTypeAvailability(availStr);
          availabilities.put(spec.name, code);
          measureBinder.pushAvailability(spec, code);
          exerciseBinder.pushAvailability(spec, code);
          return describe();
        }
      case "exerciseUpdate":
      case "exercise":
        {
          applyExtendedMetrics(c);
          exerciseBinder.applyControlUpdate(c);
          return describe();
        }
      case "autoPause":
        exerciseBinder.autoPause();
        return describe();
      case "autoResume":
        exerciseBinder.autoResume();
        return describe();
      case "endExercise":
        {
          int reason =
              HealthProto.parseExerciseEndReason(c.optString("reason", "USER_END"));
          exerciseBinder.forceEnd(reason);
          return describe();
        }
      case "otherAppExercise":
        {
          boolean isActive = c.optBoolean("active", true);
          int type = HealthProto.parseExerciseType(c.optString("exerciseType", "RUNNING"));
          exerciseBinder.setOtherAppExercise(isActive, type);
          return describe();
        }
      case "healthEvent":
        {
          int type = HealthProto.parseHealthEventType(c.optString("type", "FALL_DETECTED"));
          passiveBinder.pushHealthEvent(type);
          return describe();
        }
      case "userActivity":
        {
          userActivityState =
              HealthProto.parseUserActivityState(c.optString("state", "PASSIVE"));
          userActivityChangeTimeMs = nowEpochMs();
          passiveBinder.onMetricsChanged();
          return describe();
        }
      case "permissionLost":
        passiveBinder.pushPermissionLost();
        return describe();
      case "capabilities":
        {
          applyCapabilitiesCommand(c);
          return describe();
        }
      case "metrics":
        {
          applyExtendedMetrics(c);
          exerciseBinder.recordExtendedMetrics(c);
          onWatchStateChanged();
          return describe();
        }
      default:
        throw new JSONException("Unknown whs op: " + op);
    }
  }

  private void applyExtendedMetrics(JSONObject c) throws JSONException {
    if (c.has("speedMps")) speedMps = c.getDouble("speedMps");
    if (c.has("paceMillisPerKm")) paceMillisPerKm = c.getDouble("paceMillisPerKm");
    if (c.has("elevationGainMeters")) elevationGainMeters = c.getDouble("elevationGainMeters");
    if (c.has("elevationLossMeters")) elevationLossMeters = c.getDouble("elevationLossMeters");
    if (c.has("absoluteElevationMeters")) {
      absoluteElevationMeters = c.getDouble("absoluteElevationMeters");
    }
    if (c.has("stepsPerMinute")) stepsPerMinute = c.getLong("stepsPerMinute");
    if (c.has("vo2Max")) vo2Max = c.getDouble("vo2Max");
    if (c.has("latitude")) latitude = c.getDouble("latitude");
    if (c.has("longitude")) longitude = c.getDouble("longitude");
    if (c.has("altitude")) altitude = c.getDouble("altitude");
    if (c.has("bearing")) bearing = c.getDouble("bearing");
    if (c.has("userActivityState")) {
      userActivityState = HealthProto.parseUserActivityState(c.getString("userActivityState"));
      userActivityChangeTimeMs = nowEpochMs();
    }
  }

  private void applyCapabilitiesCommand(JSONObject c) throws JSONException {
    if (c.has("exerciseTypes")) {
      supportedExerciseTypes.clear();
      JSONArray arr = c.getJSONArray("exerciseTypes");
      for (int i = 0; i < arr.length(); i++) {
        supportedExerciseTypes.add(HealthProto.parseExerciseType(arr.getString(i)));
      }
    }
    if (c.has("measureDataTypes")) {
      supportedMeasureTypes.clear();
      JSONArray arr = c.getJSONArray("measureDataTypes");
      for (int i = 0; i < arr.length(); i++) {
        HealthProto.DataTypeSpec spec = HealthProto.findDataType(arr.getString(i));
        if (spec != null) supportedMeasureTypes.add(spec);
      }
    }
    if (c.has("passiveDataTypes")) {
      supportedPassiveMonitoringTypes.clear();
      JSONArray arr = c.getJSONArray("passiveDataTypes");
      for (int i = 0; i < arr.length(); i++) {
        HealthProto.DataTypeSpec spec = HealthProto.findDataType(arr.getString(i));
        if (spec != null) supportedPassiveMonitoringTypes.add(spec);
      }
    }
    if (c.has("passiveGoalDataTypes")) {
      supportedPassiveGoalTypes.clear();
      JSONArray arr = c.getJSONArray("passiveGoalDataTypes");
      for (int i = 0; i < arr.length(); i++) {
        HealthProto.DataTypeSpec spec = HealthProto.findDataType(arr.getString(i));
        if (spec != null) supportedPassiveGoalTypes.add(spec);
      }
    }
    if (c.has("autoPause")) autoPauseSupported = c.getBoolean("autoPause");
    if (c.has("laps")) lapsSupported = c.getBoolean("laps");
  }

  JSONObject describe() {
    Protocol.Json json =
        new Protocol.Json()
            .put("exercise", exerciseBinder.describe())
            .put("measureCallbacks", measureBinder.callbackCount())
            .put("passiveCallbacks", passiveBinder.callbackCount())
            .put("passiveServices", passiveBinder.serviceCount())
            .put("passiveGoalsTriggered", passiveBinder.goalsTriggeredCount());
    return json.object();
  }

  // ---------------------------------------------------------------------------
  // 1. IHealthServicesApiService & IVersionApiService

  private static final class HealthServicesApiBinder extends Binder {
    HealthServicesApiBinder() {
      attachInterface(null, DESCRIPTOR_HEALTH_SERVICES);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(DESCRIPTOR_HEALTH_SERVICES);
        return true;
      }
      if (code == 1) {
        data.enforceInterface(DESCRIPTOR_HEALTH_SERVICES);
        reply.writeNoException();
        reply.writeInt(1);
        return true;
      }
      return super.onTransact(code, data, reply, flags);
    }
  }

  private static final class VersionApiBinder extends Binder {
    VersionApiBinder() {
      attachInterface(null, DESCRIPTOR_VERSION);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(DESCRIPTOR_VERSION);
        return true;
      }
      if (code == 1) {
        data.enforceInterface(DESCRIPTOR_VERSION);
        reply.writeNoException();
        reply.writeInt(1);
        return true;
      }
      if (code == 2) {
        data.enforceInterface(DESCRIPTOR_VERSION);
        reply.writeNoException();
        reply.writeInt(27); // IVersionApiService.CANONICAL_SDK_VERSION
        return true;
      }
      return super.onTransact(code, data, reply, flags);
    }
  }

  // ---------------------------------------------------------------------------
  // 2. IMeasureApiService

  private final class MeasureApiBinder extends Binder {
    private final List<MeasureRegistration> registrations =
        Collections.synchronizedList(new ArrayList<>());

    MeasureApiBinder() {
      attachInterface(null, DESCRIPTOR_MEASURE);
    }

    int callbackCount() {
      return registrations.size();
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(DESCRIPTOR_MEASURE);
        return true;
      }
      switch (code) {
        case MS_GET_API_VERSION:
          data.enforceInterface(DESCRIPTOR_MEASURE);
          reply.writeNoException();
          reply.writeInt(1);
          return true;
        case MS_GET_CAPABILITIES:
          {
            data.enforceInterface(DESCRIPTOR_MEASURE);
            if (data.readInt() != 0) data.createByteArray();
            List<HealthProto.DataTypeSpec> types;
            synchronized (supportedMeasureTypes) {
              types = new ArrayList<>(supportedMeasureTypes);
            }
            byte[] resp = HealthProto.measureCapabilitiesResponse(types);
            reply.writeNoException();
            reply.writeInt(1);
            reply.writeByteArray(resp);
            return true;
          }
        case MS_REGISTER_CALLBACK:
          {
            data.enforceInterface(DESCRIPTOR_MEASURE);
            byte[] req = data.readInt() != 0 ? data.createByteArray() : null;
            IBinder callback = data.readStrongBinder();
            IBinder status = data.readStrongBinder();
            String pkg = HealthProto.readPackageName(req);
            if (pkg == null) pkg = app.getPackageName();
            HealthProto.ParsedDataType dt = HealthProto.parseMeasureDataType(req);
            String dtName = dt != null ? dt.name : HealthProto.HEART_RATE_BPM.name;
            HealthProto.DataTypeSpec found = HealthProto.findDeltaByName(dtName);
            HealthProto.DataTypeSpec spec = found != null ? found : HealthProto.HEART_RATE_BPM;

            MeasureRegistration reg = new MeasureRegistration(pkg, spec, callback);
            synchronized (registrations) {
              registrations.removeIf(r -> r.callback == callback && r.spec.name.equals(spec.name));
              if (callback != null) registrations.add(reg);
            }
            ensureWatchStateListener();
            reply.writeNoException();
            sendStatusSuccess(status);
            if (callback != null) {
              int avail = availabilityFor(spec.name);
              sendMeasureEvent(callback, HealthProto.measureAvailabilityEvent(spec, avail));
              byte[] dp = buildCurrentDataPoint(spec);
              if (dp != null) {
                sendMeasureEvent(
                    callback, HealthProto.measureDataPointsEvent(Collections.singletonList(dp)));
              }
            }
            return true;
          }
        case MS_UNREGISTER_CALLBACK:
          {
            data.enforceInterface(DESCRIPTOR_MEASURE);
            byte[] req = data.readInt() != 0 ? data.createByteArray() : null;
            IBinder callback = data.readStrongBinder();
            IBinder status = data.readStrongBinder();
            HealthProto.ParsedDataType dt = HealthProto.parseMeasureDataType(req);
            String dtName = dt != null ? dt.name : null;
            synchronized (registrations) {
              registrations.removeIf(
                  r -> r.callback == callback && (dtName == null || r.spec.name.equals(dtName)));
            }
            reply.writeNoException();
            sendStatusSuccess(status);
            return true;
          }
        default:
          return super.onTransact(code, data, reply, flags);
      }
    }

    void onMetricsChanged() {
      List<MeasureRegistration> snapshot;
      synchronized (registrations) {
        snapshot = new ArrayList<>(registrations);
      }
      for (MeasureRegistration reg : snapshot) {
        byte[] dp = buildCurrentDataPoint(reg.spec);
        if (dp == null) continue;
        try {
          sendMeasureEvent(
              reg.callback, HealthProto.measureDataPointsEvent(Collections.singletonList(dp)));
        } catch (RemoteException e) {
          registrations.remove(reg);
        }
      }
    }

    void pushAvailability(HealthProto.DataTypeSpec spec, int code) {
      List<MeasureRegistration> snapshot;
      synchronized (registrations) {
        snapshot = new ArrayList<>(registrations);
      }
      byte[] ev = HealthProto.measureAvailabilityEvent(spec, code);
      for (MeasureRegistration reg : snapshot) {
        if (!reg.spec.name.equals(spec.name)) continue;
        try {
          sendMeasureEvent(reg.callback, ev);
        } catch (RemoteException e) {
          registrations.remove(reg);
        }
      }
    }

    private void sendMeasureEvent(IBinder callback, byte[] eventProto) throws RemoteException {
      if (callback == null) return;
      Parcel out = Parcel.obtain();
      try {
        out.writeInterfaceToken(DESCRIPTOR_MEASURE_CALLBACK);
        out.writeInt(1);
        out.writeByteArray(eventProto);
        callback.transact(CALLBACK_MEASURE_ON_EVENT, out, null, IBinder.FLAG_ONEWAY);
      } finally {
        out.recycle();
      }
    }
  }

  private static final class MeasureRegistration {
    final String packageName;
    final HealthProto.DataTypeSpec spec;
    final IBinder callback;

    MeasureRegistration(String packageName, HealthProto.DataTypeSpec spec, IBinder callback) {
      this.packageName = packageName;
      this.spec = spec;
      this.callback = callback;
    }
  }

  // ---------------------------------------------------------------------------
  // 3. IPassiveMonitoringApiService

  private final class PassiveMonitoringApiBinder extends Binder {
    private final Map<String, PassiveCallbackEntry> registeredCallbacks =
        Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, PassiveServiceEntry> registeredServices =
        Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile int goalsTriggered;

    PassiveMonitoringApiBinder() {
      attachInterface(null, DESCRIPTOR_PASSIVE);
    }

    int callbackCount() {
      return registeredCallbacks.size();
    }

    int serviceCount() {
      return registeredServices.size();
    }

    int goalsTriggeredCount() {
      return goalsTriggered;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(DESCRIPTOR_PASSIVE);
        return true;
      }
      switch (code) {
        case PM_GET_API_VERSION:
          data.enforceInterface(DESCRIPTOR_PASSIVE);
          reply.writeNoException();
          reply.writeInt(4);
          return true;
        case PM_GET_CAPABILITIES:
          {
            data.enforceInterface(DESCRIPTOR_PASSIVE);
            if (data.readInt() != 0) data.createByteArray();
            List<HealthProto.DataTypeSpec> monitoring;
            List<HealthProto.DataTypeSpec> goals;
            synchronized (supportedPassiveMonitoringTypes) {
              monitoring = new ArrayList<>(supportedPassiveMonitoringTypes);
            }
            synchronized (supportedPassiveGoalTypes) {
              goals = new ArrayList<>(supportedPassiveGoalTypes);
            }
            byte[] resp =
                HealthProto.passiveCapabilitiesResponse(
                    monitoring,
                    goals,
                    Arrays.asList(
                        HealthProto.HEALTH_EVENT_FALL_DETECTED,
                        HealthProto.HEALTH_EVENT_LOW_HR_DETECTED,
                        HealthProto.HEALTH_EVENT_ELEVATED_HR_DETECTED),
                    Arrays.asList(
                        HealthProto.USER_ACTIVITY_UNKNOWN,
                        HealthProto.USER_ACTIVITY_EXERCISE,
                        HealthProto.USER_ACTIVITY_PASSIVE,
                        HealthProto.USER_ACTIVITY_ASLEEP));
            reply.writeNoException();
            reply.writeInt(1);
            reply.writeByteArray(resp);
            return true;
          }
        case PM_REGISTER_LISTENER_CALLBACK:
          {
            data.enforceInterface(DESCRIPTOR_PASSIVE);
            byte[] req = data.readInt() != 0 ? data.createByteArray() : null;
            IBinder listener = data.readStrongBinder();
            IBinder status = data.readStrongBinder();
            String pkg = HealthProto.readPackageName(req);
            if (pkg == null) pkg = app.getPackageName();
            HealthProto.ParsedPassiveConfig config =
                HealthProto.parsePassiveCallbackConfig(req);
            PassiveCallbackEntry entry = null;
            if (listener != null) {
              entry = new PassiveCallbackEntry(listener, config);
              registeredCallbacks.put(pkg, entry);
              ensureWatchStateListener();
            }
            reply.writeNoException();
            sendStatusSuccess(status);
            if (entry != null) {
              deliverUpdate(
                  entry.binder,
                  DESCRIPTOR_PASSIVE_CALLBACK,
                  CALLBACK_PASSIVE_CALLBACK_ON_EVENT,
                  entry.config);
              evaluateGoals(
                  entry.binder,
                  DESCRIPTOR_PASSIVE_CALLBACK,
                  CALLBACK_PASSIVE_CALLBACK_ON_EVENT,
                  entry.config,
                  entry.firedOnceGoals,
                  entry.lastGoalValues);
            }
            return true;
          }
        case PM_REGISTER_LISTENER_SERVICE:
          {
            data.enforceInterface(DESCRIPTOR_PASSIVE);
            byte[] req = data.readInt() != 0 ? data.createByteArray() : null;
            IBinder status = data.readStrongBinder();
            String pkg = HealthProto.readPackageName(req);
            if (pkg == null) pkg = app.getPackageName();
            String serviceClass = HealthProto.readListenerServiceClass(req);
            HealthProto.ParsedPassiveConfig config = HealthProto.parsePassiveServiceConfig(req);
            reply.writeNoException();
            if (serviceClass == null) {
              sendStatusError(status, "Missing listener service class");
              return true;
            }
            registerService(pkg, new ComponentName(pkg, serviceClass), config);
            sendStatusSuccess(status);
            return true;
          }
        case PM_UNREGISTER_LISTENER_CALLBACK:
        case PM_UNREGISTER_LISTENER_SERVICE:
          {
            data.enforceInterface(DESCRIPTOR_PASSIVE);
            String pkg = data.readString();
            IBinder status = data.readStrongBinder();
            String key = pkg != null ? pkg : app.getPackageName();
            if (code == PM_UNREGISTER_LISTENER_CALLBACK) {
              registeredCallbacks.remove(key);
            } else {
              unregisterService(key);
            }
            reply.writeNoException();
            sendStatusSuccess(status);
            return true;
          }
        case PM_FLUSH:
          {
            data.enforceInterface(DESCRIPTOR_PASSIVE);
            if (data.readInt() != 0) data.createByteArray();
            IBinder status = data.readStrongBinder();
            reply.writeNoException();
            sendStatusSuccess(status);
            onMetricsChanged();
            return true;
          }
        default:
          return super.onTransact(code, data, reply, flags);
      }
    }

    void onMetricsChanged() {
      List<PassiveCallbackEntry> callbacks;
      synchronized (registeredCallbacks) {
        callbacks = new ArrayList<>(registeredCallbacks.values());
      }
      for (PassiveCallbackEntry entry : callbacks) {
        try {
          deliverUpdate(
              entry.binder,
              DESCRIPTOR_PASSIVE_CALLBACK,
              CALLBACK_PASSIVE_CALLBACK_ON_EVENT,
              entry.config);
          evaluateGoals(
              entry.binder,
              DESCRIPTOR_PASSIVE_CALLBACK,
              CALLBACK_PASSIVE_CALLBACK_ON_EVENT,
              entry.config,
              entry.firedOnceGoals,
              entry.lastGoalValues);
        } catch (RemoteException e) {
          registeredCallbacks.values().remove(entry);
        }
      }
      List<PassiveServiceEntry> services;
      synchronized (registeredServices) {
        services = new ArrayList<>(registeredServices.values());
      }
      for (PassiveServiceEntry svc : services) {
        svc.push();
      }
    }

    void pushHealthEvent(int healthEventType) {
      byte[] ev = HealthProto.passiveHealthEvent(healthEventType, nowEpochMs());
      List<PassiveCallbackEntry> callbacks;
      synchronized (registeredCallbacks) {
        callbacks = new ArrayList<>(registeredCallbacks.values());
      }
      for (PassiveCallbackEntry entry : callbacks) {
        if (entry.config.healthEventTypes.isEmpty()
            || entry.config.healthEventTypes.contains(healthEventType)) {
          try {
            sendPassiveEvent(
                entry.binder, DESCRIPTOR_PASSIVE_CALLBACK, CALLBACK_PASSIVE_CALLBACK_ON_EVENT, ev);
          } catch (RemoteException ignored) {
            registeredCallbacks.values().remove(entry);
          }
        }
      }
      List<PassiveServiceEntry> services;
      synchronized (registeredServices) {
        services = new ArrayList<>(registeredServices.values());
      }
      for (PassiveServiceEntry svc : services) {
        if (svc.config.healthEventTypes.isEmpty()
            || svc.config.healthEventTypes.contains(healthEventType)) {
          svc.sendEvent(ev);
        }
      }
    }

    void pushPermissionLost() {
      byte[] ev = HealthProto.passivePermissionLostEvent();
      List<PassiveCallbackEntry> callbacks;
      synchronized (registeredCallbacks) {
        callbacks = new ArrayList<>(registeredCallbacks.values());
      }
      for (PassiveCallbackEntry entry : callbacks) {
        try {
          sendPassiveEvent(
              entry.binder, DESCRIPTOR_PASSIVE_CALLBACK, CALLBACK_PASSIVE_CALLBACK_ON_EVENT, ev);
        } catch (RemoteException ignored) {
          registeredCallbacks.values().remove(entry);
        }
      }
      List<PassiveServiceEntry> services;
      synchronized (registeredServices) {
        services = new ArrayList<>(registeredServices.values());
      }
      for (PassiveServiceEntry svc : services) {
        svc.sendEvent(ev);
      }
    }

    private void registerService(
        String pkg, ComponentName component, HealthProto.ParsedPassiveConfig config) {
      unregisterService(pkg);
      PassiveServiceEntry entry = new PassiveServiceEntry(component, config);
      registeredServices.put(pkg, entry);
      ensureWatchStateListener();
      boolean bound =
          app.bindService(new Intent().setComponent(component), entry, Context.BIND_AUTO_CREATE);
      if (!bound) {
        registeredServices.remove(pkg, entry);
      }
    }

    private void unregisterService(String pkg) {
      PassiveServiceEntry prev = registeredServices.remove(pkg);
      if (prev != null) {
        try {
          app.unbindService(prev);
        } catch (IllegalArgumentException ignored) {
          // Never connected.
        }
      }
    }

    private void deliverUpdate(
        IBinder target,
        String descriptor,
        int transaction,
        HealthProto.ParsedPassiveConfig config)
        throws RemoteException {
      if (target == null) return;
      List<byte[]> points = new ArrayList<>();
      Set<String> requested = config.dataTypeNames;
      if (requested.isEmpty()) {
        // Default set for callers that register without filtering dataTypes.
        for (HealthProto.DataTypeSpec spec :
            Arrays.asList(
                HealthProto.HEART_RATE_BPM,
                HealthProto.STEPS_DAILY,
                HealthProto.CALORIES_DAILY,
                HealthProto.DISTANCE_DAILY,
                HealthProto.FLOORS_DAILY)) {
          byte[] dp = buildCurrentDataPoint(spec);
          if (dp != null) points.add(dp);
        }
      } else {
        for (String dtName : requested) {
          HealthProto.DataTypeSpec spec = HealthProto.findDeltaByName(dtName);
          if (spec != null) {
            byte[] dp = buildCurrentDataPoint(spec);
            if (dp != null) points.add(dp);
          }
        }
      }
      List<byte[]> activityInfos = new ArrayList<>();
      if (config.includeUserActivityState) {
        activityInfos.add(
            HealthProto.userActivityInfo(userActivityState, null, userActivityChangeTimeMs));
      }
      if (points.isEmpty() && activityInfos.isEmpty()) return;
      byte[] ev = HealthProto.passiveUpdateEvent(points, activityInfos);
      sendPassiveEvent(target, descriptor, transaction, ev);
    }

    private void evaluateGoals(
        IBinder target,
        String descriptor,
        int transaction,
        HealthProto.ParsedPassiveConfig config,
        Set<Integer> firedOnceGoals,
        Map<Integer, Double> lastGoalValues)
        throws RemoteException {
      if (target == null || config.passiveGoals.isEmpty()) return;
      for (int i = 0; i < config.passiveGoals.size(); i++) {
        HealthProto.ParsedPassiveGoal goal = config.passiveGoals.get(i);
        double value = currentPassiveMetricValue(goal.condition.dataType.name);
        Double prev = lastGoalValues.put(i, value);
        if (!goal.condition.matches(value)) continue;
        if (goal.triggerFrequency == HealthProto.TRIGGER_FREQUENCY_ONCE) {
          if (!firedOnceGoals.add(i)) continue;
        } else {
          // REPEATED: fire on initial match or when the metric actually changed.
          if (prev != null && Double.compare(prev, value) == 0) continue;
        }
        goalsTriggered++;
        sendPassiveEvent(target, descriptor, transaction, HealthProto.passiveGoalEvent(goal.rawBytes));
      }
    }

    private void sendPassiveEvent(
        IBinder target, String descriptor, int transaction, byte[] eventProto)
        throws RemoteException {
      Parcel out = Parcel.obtain();
      try {
        out.writeInterfaceToken(descriptor);
        out.writeInt(1);
        out.writeByteArray(eventProto);
        target.transact(transaction, out, null, IBinder.FLAG_ONEWAY);
      } finally {
        out.recycle();
      }
    }

    private final class PassiveCallbackEntry {
      final IBinder binder;
      final HealthProto.ParsedPassiveConfig config;
      final Set<Integer> firedOnceGoals = new HashSet<>();
      final Map<Integer, Double> lastGoalValues = new HashMap<>();

      PassiveCallbackEntry(IBinder binder, HealthProto.ParsedPassiveConfig config) {
        this.binder = binder;
        this.config = config;
      }
    }

    private final class PassiveServiceEntry implements ServiceConnection {
      final ComponentName component;
      final HealthProto.ParsedPassiveConfig config;
      final Set<Integer> firedOnceGoals = new HashSet<>();
      final Map<Integer, Double> lastGoalValues = new HashMap<>();
      volatile IBinder binder;

      PassiveServiceEntry(ComponentName component, HealthProto.ParsedPassiveConfig config) {
        this.component = component;
        this.config = config;
      }

      @Override
      public void onServiceConnected(ComponentName name, IBinder service) {
        binder = service;
        push();
      }

      @Override
      public void onServiceDisconnected(ComponentName name) {
        binder = null;
      }

      void push() {
        IBinder target = binder;
        if (target == null) return;
        try {
          deliverUpdate(
              target,
              DESCRIPTOR_PASSIVE_LISTENER_SERVICE,
              CALLBACK_PASSIVE_SERVICE_ON_EVENT,
              config);
          evaluateGoals(
              target,
              DESCRIPTOR_PASSIVE_LISTENER_SERVICE,
              CALLBACK_PASSIVE_SERVICE_ON_EVENT,
              config,
              firedOnceGoals,
              lastGoalValues);
        } catch (RemoteException e) {
          binder = null;
        }
      }

      void sendEvent(byte[] eventProto) {
        IBinder target = binder;
        if (target == null) return;
        try {
          sendPassiveEvent(
              target,
              DESCRIPTOR_PASSIVE_LISTENER_SERVICE,
              CALLBACK_PASSIVE_SERVICE_ON_EVENT,
              eventProto);
        } catch (RemoteException e) {
          binder = null;
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 4. IExerciseApiService

  private final class ExerciseApiBinder extends Binder {
    private final Map<String, IBinder> listeners =
        Collections.synchronizedMap(new LinkedHashMap<>());

    // Active / preparing exercise session state
    private String ownerPackage;
    private int exerciseType = HealthProto.EXERCISE_TYPE_UNKNOWN;
    private int exerciseState = HealthProto.EXERCISE_STATE_UNKNOWN;
    private int endReason = HealthProto.EXERCISE_END_REASON_UNKNOWN;
    private HealthProto.ParsedExerciseConfig config;
    private Set<String> warmUpDataTypes = new LinkedHashSet<>();
    private final List<TrackedGoal> activeGoals = new ArrayList<>();

    // Timing
    private long startTimeEpochMs;
    private long accumulatedActiveDurationMs;
    private long lastResumedEpochMs;
    private long lastCheckpointEpochMs;
    private int lapCount;
    private long lapStartEpochMs;
    private long lapStartActiveDurationMs;
    private double lapStartDistance;
    private double lapStartCalories;
    private long lapStartSteps;

    // Exercise metrics
    private double exerciseCalories;
    private double exerciseDistance;
    private long exerciseSteps;
    private double exerciseFloors;
    private double exerciseElevationGain;
    private double exerciseElevationLoss;
    private final StatsAccumulator hrStats = new StatsAccumulator();
    private final StatsAccumulator lapHrStats = new StatsAccumulator();
    private final StatsAccumulator speedStats = new StatsAccumulator();
    private final StatsAccumulator paceStats = new StatsAccumulator();
    private final StatsAccumulator elevationStats = new StatsAccumulator();
    private final StatsAccumulator spmStats = new StatsAccumulator();
    private final StatsAccumulator vo2Stats = new StatsAccumulator();
    private final List<byte[]> lastAchievedGoalProtos = new ArrayList<>();

    // Optional simulated other-app exercise
    private boolean otherAppActive;
    private int otherAppExerciseType = HealthProto.EXERCISE_TYPE_RUNNING;

    ExerciseApiBinder() {
      attachInterface(null, DESCRIPTOR_EXERCISE);
    }

    synchronized void reset() {
      ownerPackage = null;
      exerciseType = HealthProto.EXERCISE_TYPE_UNKNOWN;
      exerciseState = HealthProto.EXERCISE_STATE_UNKNOWN;
      endReason = HealthProto.EXERCISE_END_REASON_UNKNOWN;
      config = null;
      warmUpDataTypes.clear();
      activeGoals.clear();
      startTimeEpochMs = 0L;
      accumulatedActiveDurationMs = 0L;
      lastResumedEpochMs = 0L;
      lastCheckpointEpochMs = 0L;
      lapCount = 0;
      exerciseCalories = 0.0;
      exerciseDistance = 0.0;
      exerciseSteps = 0L;
      exerciseFloors = 0.0;
      exerciseElevationGain = 0.0;
      exerciseElevationLoss = 0.0;
      hrStats.reset();
      lapHrStats.reset();
      speedStats.reset();
      paceStats.reset();
      elevationStats.reset();
      spmStats.reset();
      vo2Stats.reset();
      lastAchievedGoalProtos.clear();
      otherAppActive = false;
    }

    synchronized JSONObject describe() {
      return new Protocol.Json()
          .put("state", HealthProto.exerciseStateName(exerciseState))
          .put("exerciseType", HealthProto.exerciseTypeName(exerciseType))
          .put("ownerPackage", ownerPackage)
          .put("activeDurationMs", currentActiveDurationMs())
          .put("lapCount", lapCount)
          .put("calories", exerciseCalories)
          .put("distanceMeters", exerciseDistance)
          .put("steps", exerciseSteps)
          .put("heartRate", currentHeartRate())
          .put("listeners", listeners.size())
          .put("otherAppActive", otherAppActive)
          .object();
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(DESCRIPTOR_EXERCISE);
        return true;
      }
      switch (code) {
        case EX_GET_API_VERSION:
          data.enforceInterface(DESCRIPTOR_EXERCISE);
          reply.writeNoException();
          // Version 7 unlocks 1.1.0 APIs (debounced goals, batching modes, exercise type config).
          reply.writeInt(7);
          return true;
        case EX_GET_CAPABILITIES:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            if (data.readInt() != 0) data.createByteArray();
            byte[] resp = buildExerciseCapabilitiesResponse();
            reply.writeNoException();
            reply.writeInt(1);
            reply.writeByteArray(resp);
            return true;
          }
        case EX_PREPARE_EXERCISE:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            byte[] req = data.readInt() != 0 ? data.createByteArray() : null;
            IBinder status = data.readStrongBinder();
            String pkg = HealthProto.readPackageName(req);
            if (pkg == null) pkg = app.getPackageName();
            HealthProto.ParsedWarmUpConfig warmUp = HealthProto.parsePrepareExerciseConfig(req);
            prepareExercise(pkg, warmUp);
            reply.writeNoException();
            sendStatusSuccess(status);
            dispatchExerciseUpdate(pkg, Collections.emptyList());
            for (String dtName : warmUp.dataTypeNames) {
              HealthProto.DataTypeSpec spec = HealthProto.findDeltaByName(dtName);
              if (spec != null) {
                dispatchAvailability(pkg, spec, availabilityFor(spec.name));
              }
            }
            return true;
          }
        case EX_START_EXERCISE:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            byte[] req = data.readInt() != 0 ? data.createByteArray() : null;
            IBinder status = data.readStrongBinder();
            String pkg = HealthProto.readPackageName(req);
            if (pkg == null) pkg = app.getPackageName();
            HealthProto.ParsedExerciseConfig cfg = HealthProto.parseStartExerciseConfig(req);
            startExercise(pkg, cfg);
            reply.writeNoException();
            sendStatusSuccess(status);
            List<byte[]> achieved = evaluateExerciseGoals();
            dispatchExerciseUpdate(pkg, achieved);
            return true;
          }
        case EX_PAUSE_EXERCISE:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            String pkg = data.readString();
            IBinder status = data.readStrongBinder();
            pauseExercise(HealthProto.EXERCISE_STATE_USER_PAUSED);
            reply.writeNoException();
            sendStatusSuccess(status);
            dispatchExerciseUpdate(pkg != null ? pkg : ownerPackage, Collections.emptyList());
            return true;
          }
        case EX_RESUME_EXERCISE:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            String pkg = data.readString();
            IBinder status = data.readStrongBinder();
            resumeExercise();
            reply.writeNoException();
            sendStatusSuccess(status);
            dispatchExerciseUpdate(pkg != null ? pkg : ownerPackage, Collections.emptyList());
            return true;
          }
        case EX_END_EXERCISE:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            String pkg = data.readString();
            IBinder status = data.readStrongBinder();
            String target = pkg != null ? pkg : ownerPackage;
            endExercise(
                HealthProto.EXERCISE_STATE_ENDED, HealthProto.EXERCISE_END_REASON_USER_END);
            reply.writeNoException();
            sendStatusSuccess(status);
            dispatchExerciseUpdate(target, Collections.emptyList());
            clearEndedOwnership();
            return true;
          }
        case EX_MARK_LAP:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            String pkg = data.readString();
            IBinder status = data.readStrongBinder();
            byte[] lapEvent = markLap();
            reply.writeNoException();
            sendStatusSuccess(status);
            if (lapEvent != null) {
              sendExerciseEvent(pkg != null ? pkg : ownerPackage, lapEvent);
            }
            return true;
          }
        case EX_GET_CURRENT_EXERCISE_INFO:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            String pkg = data.readString();
            IBinder callback = data.readStrongBinder();
            byte[] infoResp = buildCurrentExerciseInfo(pkg != null ? pkg : app.getPackageName());
            reply.writeNoException();
            sendExerciseInfo(callback, infoResp);
            return true;
          }
        case EX_SET_UPDATE_LISTENER:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            String pkg = data.readString();
            IBinder listener = data.readStrongBinder();
            IBinder status = data.readStrongBinder();
            String key = pkg != null ? pkg : app.getPackageName();
            if (listener != null) {
              listeners.put(key, listener);
              ensureWatchStateListener();
            }
            reply.writeNoException();
            sendStatusSuccess(status);
            // If an exercise is already preparing, active, or paused, immediately deliver state.
            if (isInProgressFor(key)) {
              dispatchExerciseUpdate(key, Collections.emptyList());
              if (exerciseState == HealthProto.EXERCISE_STATE_PREPARING) {
                for (String dtName : warmUpDataTypes) {
                  HealthProto.DataTypeSpec spec = HealthProto.findDeltaByName(dtName);
                  if (spec != null) {
                    dispatchAvailability(key, spec, availabilityFor(spec.name));
                  }
                }
              }
            }
            return true;
          }
        case EX_CLEAR_UPDATE_LISTENER:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            String pkg = data.readString();
            IBinder listener = data.readStrongBinder();
            IBinder status = data.readStrongBinder();
            String key = pkg != null ? pkg : app.getPackageName();
            synchronized (listeners) {
              if (listener == null || listeners.get(key) == listener) {
                listeners.remove(key);
              }
            }
            reply.writeNoException();
            sendStatusSuccess(status);
            return true;
          }
        case EX_ADD_GOAL:
        case EX_REMOVE_GOAL:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            byte[] goalProto = null;
            if (data.readInt() != 0) {
              // ExerciseGoalRequest writes String packageName + writeParcelable(ExerciseGoal).
              data.readString();
              String parcelableClass = data.readString();
              if (parcelableClass != null) {
                goalProto = data.createByteArray();
              }
            }
            IBinder status = data.readStrongBinder();
            HealthProto.ParsedExerciseGoal goal = HealthProto.parseExerciseGoal(goalProto);
            if (goal != null) {
              synchronized (this) {
                if (code == EX_ADD_GOAL) {
                  activeGoals.add(new TrackedGoal(goal));
                } else {
                  activeGoals.removeIf(g -> Arrays.equals(g.goal.rawBytes, goal.rawBytes));
                }
              }
            }
            reply.writeNoException();
            sendStatusSuccess(status);
            return true;
          }
        case EX_OVERRIDE_AUTO_PAUSE:
        case EX_OVERRIDE_BATCHING_MODES:
        case EX_UPDATE_EXERCISE_TYPE_CONFIG:
        case EX_ADD_DEBOUNCED_GOAL:
        case EX_REMOVE_DEBOUNCED_GOAL:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            if (data.readInt() != 0) data.createByteArray();
            IBinder status = data.readStrongBinder();
            reply.writeNoException();
            sendStatusSuccess(status);
            return true;
          }
        case EX_FLUSH_EXERCISE:
          {
            data.enforceInterface(DESCRIPTOR_EXERCISE);
            byte[] req = data.readInt() != 0 ? data.createByteArray() : null;
            IBinder status = data.readStrongBinder();
            String pkg = HealthProto.readPackageName(req);
            reply.writeNoException();
            sendStatusSuccess(status);
            if (isInProgress()) {
              List<byte[]> achieved = evaluateExerciseGoals();
              dispatchExerciseUpdate(pkg != null ? pkg : ownerPackage, achieved);
            }
            return true;
          }
        default:
          return super.onTransact(code, data, reply, flags);
      }
    }

    private synchronized boolean isInProgress() {
      return exerciseState == HealthProto.EXERCISE_STATE_PREPARING
          || exerciseState == HealthProto.EXERCISE_STATE_ACTIVE
          || exerciseState == HealthProto.EXERCISE_STATE_USER_PAUSED
          || exerciseState == HealthProto.EXERCISE_STATE_AUTO_PAUSED;
    }

    private synchronized boolean isInProgressFor(String pkg) {
      return isInProgress() && (ownerPackage == null || ownerPackage.equals(pkg));
    }

    private synchronized void prepareExercise(String pkg, HealthProto.ParsedWarmUpConfig warmUp) {
      ownerPackage = pkg;
      exerciseType =
          warmUp.exerciseType != HealthProto.EXERCISE_TYPE_UNKNOWN
              ? warmUp.exerciseType
              : HealthProto.EXERCISE_TYPE_RUNNING;
      exerciseState = HealthProto.EXERCISE_STATE_PREPARING;
      endReason = HealthProto.EXERCISE_END_REASON_UNKNOWN;
      warmUpDataTypes = new LinkedHashSet<>(warmUp.dataTypeNames);
      startTimeEpochMs = 0L;
      accumulatedActiveDurationMs = 0L;
      lastResumedEpochMs = 0L;
      lastCheckpointEpochMs = nowEpochMs();
      hrStats.reset();
      lapHrStats.reset();
    }

    private synchronized void startExercise(String pkg, HealthProto.ParsedExerciseConfig cfg) {
      long now = nowEpochMs();
      ownerPackage = pkg;
      config = cfg;
      exerciseType =
          cfg.exerciseType != HealthProto.EXERCISE_TYPE_UNKNOWN
              ? cfg.exerciseType
              : HealthProto.EXERCISE_TYPE_RUNNING;
      exerciseState = HealthProto.EXERCISE_STATE_ACTIVE;
      endReason = HealthProto.EXERCISE_END_REASON_UNKNOWN;
      activeGoals.clear();
      for (HealthProto.ParsedExerciseGoal g : cfg.exerciseGoals) {
        activeGoals.add(new TrackedGoal(g));
      }
      lastAchievedGoalProtos.clear();
      startTimeEpochMs = now;
      accumulatedActiveDurationMs = 0L;
      lastResumedEpochMs = now;
      lastCheckpointEpochMs = now;
      lapCount = 0;
      lapStartEpochMs = now;
      lapStartActiveDurationMs = 0L;
      lapStartDistance = 0.0;
      lapStartCalories = 0.0;
      lapStartSteps = 0L;
      exerciseCalories = 0.0;
      exerciseDistance = 0.0;
      exerciseSteps = 0L;
      exerciseFloors = 0.0;
      exerciseElevationGain = 0.0;
      exerciseElevationLoss = 0.0;
      double initialHr = currentHeartRate();
      hrStats.reset();
      hrStats.record(initialHr);
      lapHrStats.reset();
      lapHrStats.record(initialHr);
      speedStats.reset();
      speedStats.record(speedMps);
      paceStats.reset();
      paceStats.record(paceMillisPerKm);
      elevationStats.reset();
      elevationStats.record(absoluteElevationMeters);
      spmStats.reset();
      spmStats.record(stepsPerMinute);
      vo2Stats.reset();
      vo2Stats.record(vo2Max);
    }

    private synchronized void pauseExercise(int pausedState) {
      if (exerciseState != HealthProto.EXERCISE_STATE_ACTIVE) return;
      long now = nowEpochMs();
      accumulatedActiveDurationMs = currentActiveDurationMs();
      lastCheckpointEpochMs = now;
      exerciseState = pausedState;
    }

    private synchronized void resumeExercise() {
      if (exerciseState != HealthProto.EXERCISE_STATE_USER_PAUSED
          && exerciseState != HealthProto.EXERCISE_STATE_AUTO_PAUSED) {
        return;
      }
      long now = nowEpochMs();
      lastResumedEpochMs = now;
      lastCheckpointEpochMs = now;
      exerciseState = HealthProto.EXERCISE_STATE_ACTIVE;
    }

    private synchronized void endExercise(int endedState, int reason) {
      long now = nowEpochMs();
      if (exerciseState == HealthProto.EXERCISE_STATE_ACTIVE) {
        accumulatedActiveDurationMs = currentActiveDurationMs();
      }
      lastCheckpointEpochMs = now;
      exerciseState = endedState;
      endReason = reason;
    }

    private synchronized void clearEndedOwnership() {
      ownerPackage = null;
    }

    private synchronized byte[] markLap() {
      if (!isInProgress()) return null;
      long now = nowEpochMs();
      long totalActive = currentActiveDurationMs();
      long lapActive = Math.max(0L, totalActive - lapStartActiveDurationMs);
      lapCount++;

      Map<HealthProto.DataTypeSpec, byte[]> lapMetrics = new LinkedHashMap<>();
      double lapDist = Math.max(0.0, exerciseDistance - lapStartDistance);
      double lapCal = Math.max(0.0, exerciseCalories - lapStartCalories);
      long lapStp = Math.max(0L, exerciseSteps - lapStartSteps);
      lapMetrics.put(
          HealthProto.DISTANCE_TOTAL,
          HealthProto.cumulativeAggregateDataPoint(
              HealthProto.DISTANCE_TOTAL,
              HealthProto.valueDouble(lapDist),
              lapStartEpochMs,
              now));
      lapMetrics.put(
          HealthProto.CALORIES_TOTAL,
          HealthProto.cumulativeAggregateDataPoint(
              HealthProto.CALORIES_TOTAL,
              HealthProto.valueDouble(lapCal),
              lapStartEpochMs,
              now));
      lapMetrics.put(
          HealthProto.STEPS_TOTAL,
          HealthProto.cumulativeAggregateDataPoint(
              HealthProto.STEPS_TOTAL, HealthProto.valueLong(lapStp), lapStartEpochMs, now));
      if (lapHrStats.count > 0) {
        lapMetrics.put(
            HealthProto.HEART_RATE_BPM_STATS,
            HealthProto.statisticalAggregateDataPoint(
                HealthProto.HEART_RATE_BPM_STATS,
                HealthProto.valueDouble(lapHrStats.min),
                HealthProto.valueDouble(lapHrStats.max),
                HealthProto.valueDouble(lapHrStats.avg()),
                lapStartEpochMs,
                now));
      }

      byte[] event =
          HealthProto.exerciseLapSummaryEvent(
              lapCount, lapStartEpochMs, now, lapActive, lapMetrics);

      lapStartEpochMs = now;
      lapStartActiveDurationMs = totalActive;
      lapStartDistance = exerciseDistance;
      lapStartCalories = exerciseCalories;
      lapStartSteps = exerciseSteps;
      lapHrStats.reset();
      return event;
    }

    private synchronized long currentActiveDurationMs() {
      if (exerciseState == HealthProto.EXERCISE_STATE_ACTIVE && lastResumedEpochMs > 0) {
        return accumulatedActiveDurationMs + Math.max(0L, nowEpochMs() - lastResumedEpochMs);
      }
      return accumulatedActiveDurationMs;
    }

    private synchronized byte[] buildCurrentExerciseInfo(String pkg) {
      if (otherAppActive) {
        return HealthProto.exerciseInfoResponse(
            HealthProto.TRACKED_STATUS_OTHER_APP_IN_PROGRESS, otherAppExerciseType);
      }
      if (isInProgress()) {
        if (ownerPackage == null || ownerPackage.equals(pkg)) {
          return HealthProto.exerciseInfoResponse(
              HealthProto.TRACKED_STATUS_OWNED_EXERCISE_IN_PROGRESS, exerciseType);
        }
        return HealthProto.exerciseInfoResponse(
            HealthProto.TRACKED_STATUS_OTHER_APP_IN_PROGRESS, exerciseType);
      }
      return HealthProto.exerciseInfoResponse(
          HealthProto.TRACKED_STATUS_NO_EXERCISE_IN_PROGRESS, HealthProto.EXERCISE_TYPE_UNKNOWN);
    }

    private byte[] buildExerciseCapabilitiesResponse() {
      List<HealthProto.DataTypeSpec> commonTypes =
          Arrays.asList(
              HealthProto.HEART_RATE_BPM,
              HealthProto.HEART_RATE_BPM_STATS,
              HealthProto.CALORIES,
              HealthProto.CALORIES_TOTAL,
              HealthProto.DISTANCE,
              HealthProto.DISTANCE_TOTAL,
              HealthProto.STEPS,
              HealthProto.STEPS_TOTAL,
              HealthProto.STEPS_PER_MINUTE,
              HealthProto.STEPS_PER_MINUTE_STATS,
              HealthProto.SPEED,
              HealthProto.SPEED_STATS,
              HealthProto.PACE,
              HealthProto.PACE_STATS,
              HealthProto.LOCATION,
              HealthProto.FLOORS,
              HealthProto.FLOORS_TOTAL,
              HealthProto.ELEVATION_GAIN,
              HealthProto.ELEVATION_GAIN_TOTAL,
              HealthProto.ELEVATION_LOSS,
              HealthProto.ELEVATION_LOSS_TOTAL,
              HealthProto.ABSOLUTE_ELEVATION,
              HealthProto.ABSOLUTE_ELEVATION_STATS,
              HealthProto.ACTIVE_EXERCISE_DURATION_TOTAL,
              HealthProto.VO2_MAX,
              HealthProto.VO2_MAX_STATS);
      List<HealthProto.DataTypeSpec> goalTypes =
          Arrays.asList(
              HealthProto.CALORIES_TOTAL,
              HealthProto.DISTANCE_TOTAL,
              HealthProto.STEPS_TOTAL,
              HealthProto.ACTIVE_EXERCISE_DURATION_TOTAL,
              HealthProto.FLOORS_TOTAL,
              HealthProto.ELEVATION_GAIN_TOTAL);
      byte[] typeCaps =
          HealthProto.exerciseTypeCapabilities(
              commonTypes, goalTypes, goalTypes, autoPauseSupported, lapsSupported);
      Map<Integer, byte[]> map = new LinkedHashMap<>();
      synchronized (supportedExerciseTypes) {
        for (int t : supportedExerciseTypes) {
          map.put(t, typeCaps);
        }
      }
      return HealthProto.exerciseCapabilitiesResponse(
          map, Collections.singletonList(HealthProto.BATCHING_MODE_HEART_RATE_5_SECONDS));
    }

    synchronized void recordExtendedMetrics(JSONObject c) {
      if (exerciseState != HealthProto.EXERCISE_STATE_ACTIVE) return;
      recordExtendedMetricsLocked(c);
    }

    private void recordExtendedMetricsLocked(JSONObject c) {
      if (c.has("speedMps")) speedStats.record(speedMps);
      if (c.has("paceMillisPerKm")) paceStats.record(paceMillisPerKm);
      if (c.has("absoluteElevationMeters")) elevationStats.record(absoluteElevationMeters);
      if (c.has("stepsPerMinute")) spmStats.record(stepsPerMinute);
      if (c.has("vo2Max")) vo2Stats.record(vo2Max);
    }

    void onHealthStateChanged() {
      String pkg;
      List<byte[]> achieved;
      synchronized (this) {
        if (exerciseState == HealthProto.EXERCISE_STATE_PREPARING) {
          pkg = ownerPackage;
          achieved = Collections.emptyList();
        } else if (exerciseState == HealthProto.EXERCISE_STATE_ACTIVE) {
          double hr = currentHeartRate();
          hrStats.record(hr);
          lapHrStats.record(hr);
          pkg = ownerPackage;
          achieved = evaluateExerciseGoals();
        } else {
          return;
        }
      }
      dispatchExerciseUpdate(pkg, achieved);
    }

    void applyControlUpdate(JSONObject c) throws JSONException {
      String pkg;
      List<byte[]> achieved;
      synchronized (this) {
        if (!isInProgress()) return;
        if (c.has("calories")) exerciseCalories = c.getDouble("calories");
        if (c.has("distanceMeters")) exerciseDistance = c.getDouble("distanceMeters");
        if (c.has("steps")) exerciseSteps = c.getLong("steps");
        if (c.has("floors")) exerciseFloors = c.getDouble("floors");
        if (c.has("elevationGainMeters")) {
          exerciseElevationGain = c.getDouble("elevationGainMeters");
        }
        if (c.has("elevationLossMeters")) {
          exerciseElevationLoss = c.getDouble("elevationLossMeters");
        }
        if (c.has("activeDurationSeconds")) {
          accumulatedActiveDurationMs = Math.round(c.getDouble("activeDurationSeconds") * 1000.0);
          long now = nowEpochMs();
          lastResumedEpochMs = now;
          lastCheckpointEpochMs = now;
        } else if (c.has("activeDurationMs")) {
          accumulatedActiveDurationMs = c.getLong("activeDurationMs");
          long now = nowEpochMs();
          lastResumedEpochMs = now;
          lastCheckpointEpochMs = now;
        }
        double hr = c.has("heartRate") ? c.getDouble("heartRate") : currentHeartRate();
        hrStats.record(hr);
        lapHrStats.record(hr);
        recordExtendedMetricsLocked(c);
        pkg = ownerPackage;
        achieved = evaluateExerciseGoals();
      }
      dispatchExerciseUpdate(pkg, achieved);
    }

    void autoPause() {
      String pkg;
      synchronized (this) {
        if (exerciseState != HealthProto.EXERCISE_STATE_ACTIVE) return;
        pauseExercise(HealthProto.EXERCISE_STATE_AUTO_PAUSED);
        pkg = ownerPackage;
      }
      dispatchExerciseUpdate(pkg, Collections.emptyList());
    }

    void autoResume() {
      String pkg;
      synchronized (this) {
        if (exerciseState != HealthProto.EXERCISE_STATE_AUTO_PAUSED
            && exerciseState != HealthProto.EXERCISE_STATE_USER_PAUSED) {
          return;
        }
        resumeExercise();
        pkg = ownerPackage;
      }
      dispatchExerciseUpdate(pkg, Collections.emptyList());
    }

    void forceEnd(int reason) {
      String pkg;
      synchronized (this) {
        if (!isInProgress()) return;
        pkg = ownerPackage;
        int state =
            reason == HealthProto.EXERCISE_END_REASON_AUTO_END_PERMISSION_LOST
                ? HealthProto.EXERCISE_STATE_AUTO_ENDED_PERMISSION_LOST
                : HealthProto.EXERCISE_STATE_ENDED;
        endExercise(state, reason);
      }
      dispatchExerciseUpdate(pkg, Collections.emptyList());
      clearEndedOwnership();
    }

    synchronized void setOtherAppExercise(boolean isActive, int type) {
      this.otherAppActive = isActive;
      this.otherAppExerciseType = type;
    }

    void pushAvailability(HealthProto.DataTypeSpec spec, int code) {
      List<String> keys;
      synchronized (listeners) {
        keys = new ArrayList<>(listeners.keySet());
      }
      for (String key : keys) {
        dispatchAvailability(key, spec, code);
      }
    }

    private synchronized List<byte[]> evaluateExerciseGoals() {
      List<byte[]> newlyAchieved = new ArrayList<>();
      for (TrackedGoal tracked : activeGoals) {
        HealthProto.ParsedExerciseGoal goal = tracked.goal;
        double value = currentExerciseMetricValue(goal.condition.dataType.name);
        if (goal.goalType == HealthProto.EXERCISE_GOAL_MILESTONE && goal.hasPeriod && goal.period > 0) {
          double nextThreshold = goal.condition.threshold + tracked.milestoneCount * goal.period;
          boolean strict = goal.condition.comparisonType == HealthProto.COMPARISON_GREATER_THAN;
          boolean matched = strict ? value > nextThreshold : value >= nextThreshold;
          if (matched) {
            double steps = (value - goal.condition.threshold) / goal.period;
            int newCount = strict ? (int) Math.ceil(steps) : (int) Math.floor(steps) + 1;
            if (newCount <= tracked.milestoneCount) newCount = tracked.milestoneCount + 1;
            tracked.milestoneCount = newCount;
            newlyAchieved.add(goal.rawBytes);
          }
        } else {
          if (!tracked.achieved && goal.condition.matches(value)) {
            tracked.achieved = true;
            newlyAchieved.add(goal.rawBytes);
          }
        }
      }
      if (!newlyAchieved.isEmpty()) {
        lastAchievedGoalProtos.clear();
        lastAchievedGoalProtos.addAll(newlyAchieved);
      }
      return newlyAchieved;
    }

    private double currentExerciseMetricValue(String dataTypeName) {
      if (HealthProto.CALORIES_TOTAL.name.equals(dataTypeName)) return exerciseCalories;
      if (HealthProto.DISTANCE_TOTAL.name.equals(dataTypeName)) return exerciseDistance;
      if (HealthProto.STEPS_TOTAL.name.equals(dataTypeName)) return exerciseSteps;
      if (HealthProto.FLOORS_TOTAL.name.equals(dataTypeName)) return exerciseFloors;
      if (HealthProto.ELEVATION_GAIN_TOTAL.name.equals(dataTypeName)) return exerciseElevationGain;
      if (HealthProto.ACTIVE_EXERCISE_DURATION_TOTAL.name.equals(dataTypeName)) {
        return currentActiveDurationMs() / 1000.0;
      }
      if (HealthProto.HEART_RATE_BPM.name.equals(dataTypeName)) return currentHeartRate();
      return 0.0;
    }

    private void dispatchAvailability(String pkg, HealthProto.DataTypeSpec spec, int code) {
      byte[] ev = HealthProto.exerciseAvailabilityEvent(spec, code);
      sendExerciseEvent(pkg, ev);
    }

    private void dispatchExerciseUpdate(String pkg, List<byte[]> achievedGoals) {
      byte[] ev;
      synchronized (this) {
        long now = nowEpochMs();
        long elapsed = bootElapsedMs();
        long activeMs = currentActiveDurationMs();
        long checkpointEpochMs =
            exerciseState == HealthProto.EXERCISE_STATE_ACTIVE
                ? now
                : (lastCheckpointEpochMs > 0 ? lastCheckpointEpochMs : now);
        Map<HealthProto.DataTypeSpec, List<byte[]>> latestMetrics = new LinkedHashMap<>();
        List<byte[]> aggregateMetrics = new ArrayList<>();

        if (exerciseState == HealthProto.EXERCISE_STATE_PREPARING) {
          for (String dtName : warmUpDataTypes) {
            HealthProto.DataTypeSpec spec = HealthProto.findDeltaByName(dtName);
            if (spec == null) continue;
            byte[] dp = buildExerciseDeltaPoint(spec, elapsed);
            if (dp != null) {
              latestMetrics.put(spec, Collections.singletonList(dp));
            }
          }
        } else {
          Set<String> deltas =
              config != null ? config.deltaDataTypeNames : Collections.emptySet();
          Set<String> aggregates =
              config != null ? config.aggregateDataTypeNames : Collections.emptySet();

          // Delta data points
          for (String dtName : deltas) {
            HealthProto.DataTypeSpec spec = HealthProto.findDeltaByName(dtName);
            if (spec == null) continue;
            byte[] dp = buildExerciseDeltaPoint(spec, elapsed);
            if (dp != null) {
              latestMetrics.put(spec, Collections.singletonList(dp));
            }
          }
          // Aggregate data points (cumulative & statistical)
          long aggStart = startTimeEpochMs > 0 ? startTimeEpochMs : now;
          for (String dtName : aggregates) {
            HealthProto.DataTypeSpec spec = HealthProto.findAggregateByName(dtName);
            if (spec == null) continue;
            byte[] agg = buildExerciseAggregatePoint(spec, aggStart, now, activeMs);
            if (agg != null) {
              aggregateMetrics.add(agg);
            }
          }
        }

        ev =
            HealthProto.exerciseUpdateEvent(
                exerciseState,
                startTimeEpochMs,
                activeMs,
                elapsed,
                checkpointEpochMs,
                endReason,
                config != null ? config.rawBytes : null,
                latestMetrics,
                aggregateMetrics,
                achievedGoals);
      }
      sendExerciseEvent(pkg, ev);
    }

    private byte[] buildExerciseDeltaPoint(HealthProto.DataTypeSpec spec, long elapsed) {
      if (spec == HealthProto.HEART_RATE_BPM) {
        double hr =
            exerciseState != HealthProto.EXERCISE_STATE_PREPARING && hrStats.count > 0
                ? hrStats.last
                : currentHeartRate();
        return HealthProto.sampleDataPoint(
            spec,
            HealthProto.valueDouble(hr),
            elapsed,
            HealthProto.hrAccuracy(currentHrAccuracy()));
      }
      return buildCurrentDataPoint(spec);
    }

    private byte[] buildExerciseAggregatePoint(
        HealthProto.DataTypeSpec spec, long startEpochMs, long endEpochMs, long activeMs) {
      if (spec == HealthProto.CALORIES_TOTAL) {
        return HealthProto.cumulativeAggregateDataPoint(
            spec, HealthProto.valueDouble(exerciseCalories), startEpochMs, endEpochMs);
      } else if (spec == HealthProto.DISTANCE_TOTAL) {
        return HealthProto.cumulativeAggregateDataPoint(
            spec, HealthProto.valueDouble(exerciseDistance), startEpochMs, endEpochMs);
      } else if (spec == HealthProto.STEPS_TOTAL) {
        return HealthProto.cumulativeAggregateDataPoint(
            spec, HealthProto.valueLong(exerciseSteps), startEpochMs, endEpochMs);
      } else if (spec == HealthProto.FLOORS_TOTAL) {
        return HealthProto.cumulativeAggregateDataPoint(
            spec, HealthProto.valueDouble(exerciseFloors), startEpochMs, endEpochMs);
      } else if (spec == HealthProto.ELEVATION_GAIN_TOTAL) {
        return HealthProto.cumulativeAggregateDataPoint(
            spec, HealthProto.valueDouble(exerciseElevationGain), startEpochMs, endEpochMs);
      } else if (spec == HealthProto.ELEVATION_LOSS_TOTAL) {
        return HealthProto.cumulativeAggregateDataPoint(
            spec, HealthProto.valueDouble(exerciseElevationLoss), startEpochMs, endEpochMs);
      } else if (spec == HealthProto.ACTIVE_EXERCISE_DURATION_TOTAL) {
        return HealthProto.cumulativeAggregateDataPoint(
            spec, HealthProto.valueLong(activeMs / 1000L), startEpochMs, endEpochMs);
      } else if (spec == HealthProto.HEART_RATE_BPM_STATS && hrStats.count > 0) {
        return HealthProto.statisticalAggregateDataPoint(
            spec,
            HealthProto.valueDouble(hrStats.min),
            HealthProto.valueDouble(hrStats.max),
            HealthProto.valueDouble(hrStats.avg()),
            startEpochMs,
            endEpochMs);
      } else if (spec == HealthProto.SPEED_STATS && speedStats.count > 0) {
        return HealthProto.statisticalAggregateDataPoint(
            spec,
            HealthProto.valueDouble(speedStats.min),
            HealthProto.valueDouble(speedStats.max),
            HealthProto.valueDouble(speedStats.avg()),
            startEpochMs,
            endEpochMs);
      } else if (spec == HealthProto.PACE_STATS && paceStats.count > 0) {
        return HealthProto.statisticalAggregateDataPoint(
            spec,
            HealthProto.valueDouble(paceStats.min),
            HealthProto.valueDouble(paceStats.max),
            HealthProto.valueDouble(paceStats.avg()),
            startEpochMs,
            endEpochMs);
      } else if (spec == HealthProto.ABSOLUTE_ELEVATION_STATS && elevationStats.count > 0) {
        return HealthProto.statisticalAggregateDataPoint(
            spec,
            HealthProto.valueDouble(elevationStats.min),
            HealthProto.valueDouble(elevationStats.max),
            HealthProto.valueDouble(elevationStats.avg()),
            startEpochMs,
            endEpochMs);
      } else if (spec == HealthProto.STEPS_PER_MINUTE_STATS && spmStats.count > 0) {
        return HealthProto.statisticalAggregateDataPoint(
            spec,
            HealthProto.valueLong(Math.round(spmStats.min)),
            HealthProto.valueLong(Math.round(spmStats.max)),
            HealthProto.valueLong(Math.round(spmStats.avg())),
            startEpochMs,
            endEpochMs);
      } else if (spec == HealthProto.VO2_MAX_STATS && vo2Stats.count > 0) {
        return HealthProto.statisticalAggregateDataPoint(
            spec,
            HealthProto.valueDouble(vo2Stats.min),
            HealthProto.valueDouble(vo2Stats.max),
            HealthProto.valueDouble(vo2Stats.avg()),
            startEpochMs,
            endEpochMs);
      }
      return null;
    }

    private void sendExerciseEvent(String pkg, byte[] eventProto) {
      List<IBinder> targets = new ArrayList<>();
      synchronized (listeners) {
        if (pkg != null && listeners.containsKey(pkg)) {
          targets.add(listeners.get(pkg));
        } else {
          targets.addAll(listeners.values());
        }
      }
      for (IBinder target : targets) {
        if (target == null) continue;
        Parcel out = Parcel.obtain();
        try {
          out.writeInterfaceToken(DESCRIPTOR_EXERCISE_LISTENER);
          out.writeInt(1);
          out.writeByteArray(eventProto);
          target.transact(CALLBACK_EXERCISE_ON_EVENT, out, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
          listeners.values().remove(target);
        } finally {
          out.recycle();
        }
      }
    }

    private void sendExerciseInfo(IBinder callback, byte[] infoRespProto) throws RemoteException {
      if (callback == null) return;
      Parcel out = Parcel.obtain();
      Parcel reply = Parcel.obtain();
      try {
        out.writeInterfaceToken(DESCRIPTOR_EXERCISE_INFO_CALLBACK);
        out.writeInt(1);
        out.writeByteArray(infoRespProto);
        callback.transact(CALLBACK_EXERCISE_INFO_ON_INFO, out, reply, 0);
        reply.readException();
      } finally {
        reply.recycle();
        out.recycle();
      }
    }
  }

  private static final class StatsAccumulator {
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    double sum;
    double last;
    int count;

    void record(double value) {
      if (Double.isNaN(value) || Double.isInfinite(value)) return;
      if (value < min) min = value;
      if (value > max) max = value;
      sum += value;
      last = value;
      count++;
    }

    double avg() {
      return count > 0 ? sum / count : 0.0;
    }

    void reset() {
      min = Double.POSITIVE_INFINITY;
      max = Double.NEGATIVE_INFINITY;
      sum = 0.0;
      last = 0.0;
      count = 0;
    }
  }

  private static final class TrackedGoal {
    final HealthProto.ParsedExerciseGoal goal;
    boolean achieved;
    int milestoneCount;

    TrackedGoal(HealthProto.ParsedExerciseGoal goal) {
      this.goal = goal;
    }
  }

  // ---------------------------------------------------------------------------
  // Shared IStatusCallback helpers

  private static void sendStatusSuccess(IBinder statusCallback) throws RemoteException {
    if (statusCallback == null) return;
    Parcel out = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      out.writeInterfaceToken(DESCRIPTOR_STATUS_CALLBACK);
      statusCallback.transact(CALLBACK_STATUS_ON_SUCCESS, out, reply, 0);
      reply.readException();
    } finally {
      reply.recycle();
      out.recycle();
    }
  }

  private static void sendStatusError(IBinder statusCallback, String message)
      throws RemoteException {
    if (statusCallback == null) return;
    Parcel out = Parcel.obtain();
    try {
      out.writeInterfaceToken(DESCRIPTOR_STATUS_CALLBACK);
      out.writeString(message);
      statusCallback.transact(CALLBACK_STATUS_ON_FAILURE, out, null, IBinder.FLAG_ONEWAY);
    } finally {
      out.recycle();
    }
  }
}
