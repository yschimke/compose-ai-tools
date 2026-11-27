package ee.schimke.composeai.simulator;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java wire codec for Wear Health Services ({@code androidx.health.services.client.proto.*})
 * protobuf messages carried inside {@code ProtoParcelable}s.
 *
 * <p>Speaking the protobuf wire format directly avoids reflecting into app-side Kotlin classes that
 * R8 may shrink or rename, and stays wire-compatible across {@code health-services-client} 1.0 and
 * 1.1+.
 */
final class HealthProto {

  // Wire types
  static final int WIRE_VARINT = 0;
  static final int WIRE_FIXED64 = 1;
  static final int WIRE_LENGTH_DELIMITED = 2;
  static final int WIRE_FIXED32 = 5;

  // DataProto.DataType.TimeType
  static final int TIME_TYPE_INTERVAL = 1;
  static final int TIME_TYPE_SAMPLE = 2;

  // androidx.health.services.client.data.DataType.FORMAT_*
  static final int FORMAT_DOUBLE = 1;
  static final int FORMAT_LONG = 2;
  static final int FORMAT_DOUBLE_ARRAY = 3;
  static final int FORMAT_BOOLEAN = 4;
  static final int FORMAT_BYTE_ARRAY = 5;

  // DataProto.DataPointAccuracy.HrAccuracy.SensorStatus
  static final int HR_ACCURACY_UNKNOWN = 0;
  static final int HR_ACCURACY_NO_CONTACT = 1;
  static final int HR_ACCURACY_UNRELIABLE = 2;
  static final int HR_ACCURACY_LOW = 3;
  static final int HR_ACCURACY_MEDIUM = 4;
  static final int HR_ACCURACY_HIGH = 5;

  // DataProto.Availability.DataTypeAvailability
  static final int DATA_AVAILABILITY_UNKNOWN = 0;
  static final int DATA_AVAILABILITY_AVAILABLE = 1;
  static final int DATA_AVAILABILITY_ACQUIRING = 2;
  static final int DATA_AVAILABILITY_UNAVAILABLE = 3;
  static final int DATA_AVAILABILITY_UNAVAILABLE_DEVICE_OFF_BODY = 4;

  // DataProto.Availability.LocationAvailability
  static final int LOCATION_AVAILABILITY_UNKNOWN = 0;
  static final int LOCATION_AVAILABILITY_UNAVAILABLE = 1;
  static final int LOCATION_AVAILABILITY_NO_GNSS = 2;
  static final int LOCATION_AVAILABILITY_ACQUIRING = 3;
  static final int LOCATION_AVAILABILITY_ACQUIRED_TETHERED = 4;
  static final int LOCATION_AVAILABILITY_ACQUIRED_UNTETHERED = 5;

  // DataProto.ComparisonType
  static final int COMPARISON_UNKNOWN = 0;
  static final int COMPARISON_GREATER_THAN = 1;
  static final int COMPARISON_GREATER_THAN_OR_EQUAL = 2;
  static final int COMPARISON_LESS_THAN = 3;
  static final int COMPARISON_LESS_THAN_OR_EQUAL = 4;

  // DataProto.PassiveGoal.TriggerFrequency
  static final int TRIGGER_FREQUENCY_UNKNOWN = 0;
  static final int TRIGGER_FREQUENCY_ONCE = 1;
  static final int TRIGGER_FREQUENCY_REPEATED = 2;

  // DataProto.ExerciseGoalType
  static final int EXERCISE_GOAL_UNKNOWN = 0;
  static final int EXERCISE_GOAL_ONE_TIME = 1;
  static final int EXERCISE_GOAL_MILESTONE = 2;

  // DataProto.UserActivityState
  static final int USER_ACTIVITY_UNKNOWN = 0;
  static final int USER_ACTIVITY_EXERCISE = 1;
  static final int USER_ACTIVITY_PASSIVE = 2;
  static final int USER_ACTIVITY_ASLEEP = 3;

  // DataProto.HealthEvent.HealthEventType
  static final int HEALTH_EVENT_UNKNOWN = 0;
  static final int HEALTH_EVENT_LOW_HR_DETECTED = 1;
  static final int HEALTH_EVENT_ELEVATED_HR_DETECTED = 2;
  static final int HEALTH_EVENT_FALL_DETECTED = 3;

  // DataProto.ExerciseState
  static final int EXERCISE_STATE_UNKNOWN = 0;
  static final int EXERCISE_STATE_USER_STARTING = 1;
  static final int EXERCISE_STATE_ACTIVE = 2;
  static final int EXERCISE_STATE_USER_PAUSING = 3;
  static final int EXERCISE_STATE_USER_PAUSED = 4;
  static final int EXERCISE_STATE_AUTO_PAUSING = 5;
  static final int EXERCISE_STATE_AUTO_PAUSED = 6;
  static final int EXERCISE_STATE_USER_RESUMING = 7;
  static final int EXERCISE_STATE_AUTO_RESUMING = 8;
  static final int EXERCISE_STATE_USER_ENDING = 9;
  static final int EXERCISE_STATE_USER_ENDED = 10;
  static final int EXERCISE_STATE_AUTO_ENDING = 11;
  static final int EXERCISE_STATE_AUTO_ENDED = 12;
  static final int EXERCISE_STATE_TERMINATING = 13;
  static final int EXERCISE_STATE_TERMINATED = 14;
  static final int EXERCISE_STATE_PREPARING = 15;
  static final int EXERCISE_STATE_AUTO_ENDING_PERMISSION_LOST = 16;
  static final int EXERCISE_STATE_AUTO_ENDED_PERMISSION_LOST = 17;
  static final int EXERCISE_STATE_ENDED = 18;
  static final int EXERCISE_STATE_ENDING = 19;

  // DataProto.ExerciseEndReason
  static final int EXERCISE_END_REASON_UNKNOWN = 0;
  static final int EXERCISE_END_REASON_AUTO_END_PERMISSION_LOST = 1;
  static final int EXERCISE_END_REASON_AUTO_END_PAUSE_EXPIRED = 2;
  static final int EXERCISE_END_REASON_AUTO_END_MISSING_LISTENER = 3;
  static final int EXERCISE_END_REASON_USER_END = 4;
  static final int EXERCISE_END_REASON_AUTO_END_SUPERSEDED = 5;
  static final int EXERCISE_END_REASON_AUTO_END_PREPARE_EXPIRED = 6;

  // DataProto.ExerciseTrackedStatus
  static final int TRACKED_STATUS_UNKNOWN = 0;
  static final int TRACKED_STATUS_OTHER_APP_IN_PROGRESS = 1;
  static final int TRACKED_STATUS_OWNED_EXERCISE_IN_PROGRESS = 2;
  static final int TRACKED_STATUS_NO_EXERCISE_IN_PROGRESS = 3;

  // Common DataProto.ExerciseType values
  static final int EXERCISE_TYPE_UNKNOWN = 0;
  static final int EXERCISE_TYPE_BIKING = 8;
  static final int EXERCISE_TYPE_BIKING_STATIONARY = 9;
  static final int EXERCISE_TYPE_CALISTHENICS = 13;
  static final int EXERCISE_TYPE_ELLIPTICAL = 25;
  static final int EXERCISE_TYPE_GOLF = 32;
  static final int EXERCISE_TYPE_HIIT = 36;
  static final int EXERCISE_TYPE_HIKING = 37;
  static final int EXERCISE_TYPE_PILATES = 48;
  static final int EXERCISE_TYPE_ROWING = 53;
  static final int EXERCISE_TYPE_ROWING_MACHINE = 54;
  static final int EXERCISE_TYPE_RUNNING = 55;
  static final int EXERCISE_TYPE_RUNNING_TREADMILL = 56;
  static final int EXERCISE_TYPE_SKIING = 61;
  static final int EXERCISE_TYPE_SNOWBOARDING = 62;
  static final int EXERCISE_TYPE_STAIR_CLIMBING = 68;
  static final int EXERCISE_TYPE_STRENGTH_TRAINING = 70;
  static final int EXERCISE_TYPE_STRETCHING = 71;
  static final int EXERCISE_TYPE_SWIMMING_OPEN_WATER = 73;
  static final int EXERCISE_TYPE_SWIMMING_POOL = 74;
  static final int EXERCISE_TYPE_WALKING = 79;
  static final int EXERCISE_TYPE_WORKOUT = 82;
  static final int EXERCISE_TYPE_YOGA = 83;

  /** BatchingMode.BATCHING_MODE_HEART_RATE_5_SECONDS */
  static final int BATCHING_MODE_HEART_RATE_5_SECONDS = 1;

  /** Canonical description of a Wear Health Services {@code DataType}. */
  static final class DataTypeSpec {
    final String id;
    final String name;
    final int timeType;
    final int format;
    final boolean aggregate;

    DataTypeSpec(String id, String name, int timeType, int format, boolean aggregate) {
      this.id = id;
      this.name = name;
      this.timeType = timeType;
      this.format = format;
      this.aggregate = aggregate;
    }

    byte[] toProto() {
      Writer w = new Writer();
      w.writeString(1, name);
      w.writeVarint(2, timeType);
      w.writeVarint(3, format);
      return w.toByteArray();
    }
  }

  // Standard DataType specs matching androidx.health.services.client.data.DataType.
  static final DataTypeSpec HEART_RATE_BPM =
      new DataTypeSpec("HEART_RATE_BPM", "HeartRate", TIME_TYPE_SAMPLE, FORMAT_DOUBLE, false);
  static final DataTypeSpec HEART_RATE_BPM_STATS =
      new DataTypeSpec("HEART_RATE_BPM_STATS", "HeartRate", TIME_TYPE_SAMPLE, FORMAT_DOUBLE, true);
  static final DataTypeSpec STEPS =
      new DataTypeSpec("STEPS", "Steps", TIME_TYPE_INTERVAL, FORMAT_LONG, false);
  static final DataTypeSpec STEPS_TOTAL =
      new DataTypeSpec("STEPS_TOTAL", "Steps", TIME_TYPE_INTERVAL, FORMAT_LONG, true);
  static final DataTypeSpec STEPS_DAILY =
      new DataTypeSpec("STEPS_DAILY", "Daily Steps", TIME_TYPE_INTERVAL, FORMAT_LONG, false);
  static final DataTypeSpec STEPS_PER_MINUTE =
      new DataTypeSpec(
          "STEPS_PER_MINUTE", "Step per minute", TIME_TYPE_SAMPLE, FORMAT_LONG, false);
  static final DataTypeSpec STEPS_PER_MINUTE_STATS =
      new DataTypeSpec(
          "STEPS_PER_MINUTE_STATS", "Step per minute", TIME_TYPE_SAMPLE, FORMAT_LONG, true);
  static final DataTypeSpec CALORIES =
      new DataTypeSpec("CALORIES", "Calories", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, false);
  static final DataTypeSpec CALORIES_TOTAL =
      new DataTypeSpec("CALORIES_TOTAL", "Calories", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, true);
  static final DataTypeSpec CALORIES_DAILY =
      new DataTypeSpec(
          "CALORIES_DAILY", "Daily Calories", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, false);
  static final DataTypeSpec DISTANCE =
      new DataTypeSpec("DISTANCE", "Distance", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, false);
  static final DataTypeSpec DISTANCE_TOTAL =
      new DataTypeSpec("DISTANCE_TOTAL", "Distance", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, true);
  static final DataTypeSpec DISTANCE_DAILY =
      new DataTypeSpec(
          "DISTANCE_DAILY", "Daily Distance", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, false);
  static final DataTypeSpec FLOORS =
      new DataTypeSpec("FLOORS", "Floors", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, false);
  static final DataTypeSpec FLOORS_TOTAL =
      new DataTypeSpec("FLOORS_TOTAL", "Floors", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, true);
  static final DataTypeSpec FLOORS_DAILY =
      new DataTypeSpec("FLOORS_DAILY", "Daily Floors", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, false);
  static final DataTypeSpec ELEVATION_GAIN =
      new DataTypeSpec(
          "ELEVATION_GAIN", "Elevation Gain", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, false);
  static final DataTypeSpec ELEVATION_GAIN_TOTAL =
      new DataTypeSpec(
          "ELEVATION_GAIN_TOTAL", "Elevation Gain", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, true);
  static final DataTypeSpec ELEVATION_GAIN_DAILY =
      new DataTypeSpec(
          "ELEVATION_GAIN_DAILY", "Daily Elevation Gain", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, false);
  static final DataTypeSpec ELEVATION_LOSS =
      new DataTypeSpec(
          "ELEVATION_LOSS", "Elevation Loss", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, false);
  static final DataTypeSpec ELEVATION_LOSS_TOTAL =
      new DataTypeSpec(
          "ELEVATION_LOSS_TOTAL", "Elevation Loss", TIME_TYPE_INTERVAL, FORMAT_DOUBLE, true);
  static final DataTypeSpec ABSOLUTE_ELEVATION =
      new DataTypeSpec(
          "ABSOLUTE_ELEVATION", "Absolute Elevation", TIME_TYPE_SAMPLE, FORMAT_DOUBLE, false);
  static final DataTypeSpec ABSOLUTE_ELEVATION_STATS =
      new DataTypeSpec(
          "ABSOLUTE_ELEVATION_STATS", "Absolute Elevation", TIME_TYPE_SAMPLE, FORMAT_DOUBLE, true);
  static final DataTypeSpec SPEED =
      new DataTypeSpec("SPEED", "Speed", TIME_TYPE_SAMPLE, FORMAT_DOUBLE, false);
  static final DataTypeSpec SPEED_STATS =
      new DataTypeSpec("SPEED_STATS", "Speed", TIME_TYPE_SAMPLE, FORMAT_DOUBLE, true);
  static final DataTypeSpec PACE =
      new DataTypeSpec("PACE", "Pace", TIME_TYPE_SAMPLE, FORMAT_DOUBLE, false);
  static final DataTypeSpec PACE_STATS =
      new DataTypeSpec("PACE_STATS", "Pace", TIME_TYPE_SAMPLE, FORMAT_DOUBLE, true);
  static final DataTypeSpec LOCATION =
      new DataTypeSpec("LOCATION", "Location", TIME_TYPE_SAMPLE, FORMAT_DOUBLE_ARRAY, false);
  static final DataTypeSpec VO2_MAX =
      new DataTypeSpec("VO2_MAX", "VO2 Max", TIME_TYPE_SAMPLE, FORMAT_DOUBLE, false);
  static final DataTypeSpec VO2_MAX_STATS =
      new DataTypeSpec("VO2_MAX_STATS", "VO2 Max", TIME_TYPE_SAMPLE, FORMAT_DOUBLE, true);
  static final DataTypeSpec ACTIVE_EXERCISE_DURATION_TOTAL =
      new DataTypeSpec(
          "ACTIVE_EXERCISE_DURATION_TOTAL",
          "Active Exercise Duration",
          TIME_TYPE_INTERVAL,
          FORMAT_LONG,
          true);
  static final DataTypeSpec RESTING_EXERCISE_DURATION =
      new DataTypeSpec(
          "RESTING_EXERCISE_DURATION",
          "Resting Exercise Duration",
          TIME_TYPE_INTERVAL,
          FORMAT_LONG,
          false);
  static final DataTypeSpec RESTING_EXERCISE_DURATION_TOTAL =
      new DataTypeSpec(
          "RESTING_EXERCISE_DURATION_TOTAL",
          "Resting Exercise Duration",
          TIME_TYPE_INTERVAL,
          FORMAT_LONG,
          true);

  private static final Map<String, DataTypeSpec> SPECS_BY_ID = new LinkedHashMap<>();
  private static final Map<String, DataTypeSpec> DELTA_SPECS_BY_NAME = new LinkedHashMap<>();
  private static final Map<String, DataTypeSpec> AGGREGATE_SPECS_BY_NAME = new LinkedHashMap<>();

  static {
    DataTypeSpec[] all = {
      HEART_RATE_BPM,
      HEART_RATE_BPM_STATS,
      STEPS,
      STEPS_TOTAL,
      STEPS_DAILY,
      STEPS_PER_MINUTE,
      STEPS_PER_MINUTE_STATS,
      CALORIES,
      CALORIES_TOTAL,
      CALORIES_DAILY,
      DISTANCE,
      DISTANCE_TOTAL,
      DISTANCE_DAILY,
      FLOORS,
      FLOORS_TOTAL,
      FLOORS_DAILY,
      ELEVATION_GAIN,
      ELEVATION_GAIN_TOTAL,
      ELEVATION_GAIN_DAILY,
      ELEVATION_LOSS,
      ELEVATION_LOSS_TOTAL,
      ABSOLUTE_ELEVATION,
      ABSOLUTE_ELEVATION_STATS,
      SPEED,
      SPEED_STATS,
      PACE,
      PACE_STATS,
      LOCATION,
      VO2_MAX,
      VO2_MAX_STATS,
      ACTIVE_EXERCISE_DURATION_TOTAL,
      RESTING_EXERCISE_DURATION,
      RESTING_EXERCISE_DURATION_TOTAL,
    };
    for (DataTypeSpec spec : all) {
      SPECS_BY_ID.put(spec.id, spec);
      if (spec.aggregate) {
        AGGREGATE_SPECS_BY_NAME.put(spec.name, spec);
      } else {
        DELTA_SPECS_BY_NAME.put(spec.name, spec);
      }
    }
  }

  private HealthProto() {}

  static DataTypeSpec findDataType(String key) {
    if (key == null) return null;
    DataTypeSpec byId = SPECS_BY_ID.get(key.toUpperCase(Locale.ROOT));
    if (byId != null) return byId;
    DataTypeSpec delta = DELTA_SPECS_BY_NAME.get(key);
    if (delta != null) return delta;
    return AGGREGATE_SPECS_BY_NAME.get(key);
  }

  static DataTypeSpec findDeltaByName(String name) {
    return DELTA_SPECS_BY_NAME.get(name);
  }

  static DataTypeSpec findAggregateByName(String name) {
    return AGGREGATE_SPECS_BY_NAME.get(name);
  }

  // ---------------------------------------------------------------------------
  // Parsed request DTOs

  static final class ParsedDataType {
    final String name;
    final int timeType;
    final int format;

    ParsedDataType(String name, int timeType, int format) {
      this.name = name != null ? name : "";
      this.timeType = timeType;
      this.format = format;
    }
  }

  static final class ParsedCondition {
    final ParsedDataType dataType;
    final double threshold;
    final boolean isLongThreshold;
    final long longThreshold;
    final int comparisonType;
    final byte[] rawBytes;

    ParsedCondition(
        ParsedDataType dataType,
        double threshold,
        boolean isLongThreshold,
        long longThreshold,
        int comparisonType,
        byte[] rawBytes) {
      this.dataType = dataType;
      this.threshold = threshold;
      this.isLongThreshold = isLongThreshold;
      this.longThreshold = longThreshold;
      this.comparisonType = comparisonType;
      this.rawBytes = rawBytes;
    }

    boolean matches(double value) {
      switch (comparisonType) {
        case COMPARISON_GREATER_THAN:
          return value > threshold;
        case COMPARISON_GREATER_THAN_OR_EQUAL:
          return value >= threshold;
        case COMPARISON_LESS_THAN:
          return value < threshold;
        case COMPARISON_LESS_THAN_OR_EQUAL:
          return value <= threshold;
        default:
          return false;
      }
    }
  }

  static final class ParsedPassiveGoal {
    final ParsedCondition condition;
    final int triggerFrequency;
    final byte[] rawBytes;

    ParsedPassiveGoal(ParsedCondition condition, int triggerFrequency, byte[] rawBytes) {
      this.condition = condition;
      this.triggerFrequency = triggerFrequency;
      this.rawBytes = rawBytes;
    }
  }

  static final class ParsedPassiveConfig {
    final Set<String> dataTypeNames;
    final boolean includeUserActivityState;
    final List<ParsedPassiveGoal> passiveGoals;
    final Set<Integer> healthEventTypes;

    ParsedPassiveConfig(
        Set<String> dataTypeNames,
        boolean includeUserActivityState,
        List<ParsedPassiveGoal> passiveGoals,
        Set<Integer> healthEventTypes) {
      this.dataTypeNames = dataTypeNames;
      this.includeUserActivityState = includeUserActivityState;
      this.passiveGoals = passiveGoals;
      this.healthEventTypes = healthEventTypes;
    }
  }

  static final class ParsedExerciseGoal {
    final int goalType;
    final ParsedCondition condition;
    final double period;
    final boolean hasPeriod;
    final byte[] rawBytes;

    ParsedExerciseGoal(
        int goalType,
        ParsedCondition condition,
        double period,
        boolean hasPeriod,
        byte[] rawBytes) {
      this.goalType = goalType;
      this.condition = condition;
      this.period = period;
      this.hasPeriod = hasPeriod;
      this.rawBytes = rawBytes;
    }
  }

  static final class ParsedWarmUpConfig {
    final int exerciseType;
    final Set<String> dataTypeNames;

    ParsedWarmUpConfig(int exerciseType, Set<String> dataTypeNames) {
      this.exerciseType = exerciseType;
      this.dataTypeNames = dataTypeNames;
    }
  }

  static final class ParsedExerciseConfig {
    final int exerciseType;
    final Set<String> deltaDataTypeNames;
    final Set<String> aggregateDataTypeNames;
    final boolean autoPauseAndResumeEnabled;
    final boolean gpsUsageEnabled;
    final List<ParsedExerciseGoal> exerciseGoals;
    final byte[] rawBytes;

    ParsedExerciseConfig(
        int exerciseType,
        Set<String> deltaDataTypeNames,
        Set<String> aggregateDataTypeNames,
        boolean autoPauseAndResumeEnabled,
        boolean gpsUsageEnabled,
        List<ParsedExerciseGoal> exerciseGoals,
        byte[] rawBytes) {
      this.exerciseType = exerciseType;
      this.deltaDataTypeNames = deltaDataTypeNames;
      this.aggregateDataTypeNames = aggregateDataTypeNames;
      this.autoPauseAndResumeEnabled = autoPauseAndResumeEnabled;
      this.gpsUsageEnabled = gpsUsageEnabled;
      this.exerciseGoals = exerciseGoals;
      this.rawBytes = rawBytes;
    }
  }

  // ---------------------------------------------------------------------------
  // Request parsers

  static String readPackageName(byte[] proto) {
    return readString(proto, 1);
  }

  static String readListenerServiceClass(byte[] proto) {
    return readString(proto, 2);
  }

  static ParsedDataType parseMeasureDataType(byte[] requestProto) {
    byte[] dt = readBytes(requestProto, 2);
    return dt != null ? parseDataType(dt) : null;
  }

  static ParsedPassiveConfig parsePassiveServiceConfig(byte[] requestProto) {
    byte[] cfg = readBytes(requestProto, 3);
    return parsePassiveConfig(cfg);
  }

  static ParsedPassiveConfig parsePassiveCallbackConfig(byte[] requestProto) {
    byte[] cfg = readBytes(requestProto, 2);
    return parsePassiveConfig(cfg);
  }

  static ParsedWarmUpConfig parsePrepareExerciseConfig(byte[] requestProto) {
    byte[] cfg = readBytes(requestProto, 2);
    if (cfg == null) return new ParsedWarmUpConfig(EXERCISE_TYPE_UNKNOWN, Collections.emptySet());
    int exerciseType = EXERCISE_TYPE_UNKNOWN;
    Set<String> dataTypes = new LinkedHashSet<>();
    Reader r = new Reader(cfg);
    while (r.next()) {
      if (r.field == 1 && r.wireType == WIRE_VARINT) {
        exerciseType = (int) r.varint();
      } else if (r.field == 2 && r.wireType == WIRE_LENGTH_DELIMITED) {
        ParsedDataType dt = parseDataType(r.bytes());
        if (!dt.name.isEmpty()) dataTypes.add(dt.name);
      } else {
        r.skip();
      }
    }
    return new ParsedWarmUpConfig(exerciseType, dataTypes);
  }

  static ParsedExerciseConfig parseStartExerciseConfig(byte[] requestProto) {
    byte[] cfg = readBytes(requestProto, 2);
    return parseExerciseConfig(cfg);
  }

  static boolean parseAutoPauseShouldEnable(byte[] requestProto) {
    if (requestProto == null) return false;
    Reader r = new Reader(requestProto);
    while (r.next()) {
      if (r.field == 2 && r.wireType == WIRE_VARINT) {
        return r.varint() != 0;
      } else {
        r.skip();
      }
    }
    return false;
  }

  static ParsedDataType parseDataType(byte[] proto) {
    String name = "";
    int timeType = 0;
    int format = 0;
    if (proto != null) {
      Reader r = new Reader(proto);
      while (r.next()) {
        if (r.field == 1 && r.wireType == WIRE_LENGTH_DELIMITED) {
          name = r.string();
        } else if (r.field == 2 && r.wireType == WIRE_VARINT) {
          timeType = (int) r.varint();
        } else if (r.field == 3 && r.wireType == WIRE_VARINT) {
          format = (int) r.varint();
        } else {
          r.skip();
        }
      }
    }
    return new ParsedDataType(name, timeType, format);
  }

  static ParsedPassiveConfig parsePassiveConfig(byte[] proto) {
    Set<String> dataTypes = new LinkedHashSet<>();
    boolean includeActivity = false;
    List<ParsedPassiveGoal> goals = new ArrayList<>();
    Set<Integer> healthEvents = new LinkedHashSet<>();
    if (proto != null) {
      Reader r = new Reader(proto);
      while (r.next()) {
        if (r.field == 1 && r.wireType == WIRE_LENGTH_DELIMITED) {
          ParsedDataType dt = parseDataType(r.bytes());
          if (!dt.name.isEmpty()) dataTypes.add(dt.name);
        } else if (r.field == 2 && r.wireType == WIRE_VARINT) {
          includeActivity = r.varint() != 0;
        } else if (r.field == 3 && r.wireType == WIRE_LENGTH_DELIMITED) {
          ParsedPassiveGoal goal = parsePassiveGoal(r.bytes());
          if (goal != null) goals.add(goal);
        } else if (r.field == 4 && r.wireType == WIRE_VARINT) {
          healthEvents.add((int) r.varint());
        } else if (r.field == 4 && r.wireType == WIRE_LENGTH_DELIMITED) {
          // Packed repeated enum
          Reader packed = new Reader(r.bytes());
          while (packed.hasRemaining()) {
            long v = packed.readRawVarint();
            if (v < 0) break;
            healthEvents.add((int) v);
          }
        } else {
          r.skip();
        }
      }
    }
    return new ParsedPassiveConfig(dataTypes, includeActivity, goals, healthEvents);
  }

  static ParsedPassiveGoal parsePassiveGoal(byte[] proto) {
    if (proto == null) return null;
    ParsedCondition condition = null;
    int triggerFrequency = TRIGGER_FREQUENCY_REPEATED;
    Reader r = new Reader(proto);
    while (r.next()) {
      if (r.field == 1 && r.wireType == WIRE_LENGTH_DELIMITED) {
        condition = parseCondition(r.bytes());
      } else if (r.field == 2 && r.wireType == WIRE_VARINT) {
        triggerFrequency = (int) r.varint();
      } else {
        r.skip();
      }
    }
    if (condition == null) return null;
    return new ParsedPassiveGoal(condition, triggerFrequency, proto);
  }

  static ParsedExerciseConfig parseExerciseConfig(byte[] proto) {
    int exerciseType = EXERCISE_TYPE_UNKNOWN;
    Set<String> deltaTypes = new LinkedHashSet<>();
    Set<String> aggregateTypes = new LinkedHashSet<>();
    boolean autoPause = false;
    boolean gps = false;
    List<ParsedExerciseGoal> goals = new ArrayList<>();
    if (proto != null) {
      Reader r = new Reader(proto);
      while (r.next()) {
        if (r.field == 1 && r.wireType == WIRE_VARINT) {
          exerciseType = (int) r.varint();
        } else if (r.field == 2 && r.wireType == WIRE_LENGTH_DELIMITED) {
          ParsedDataType dt = parseDataType(r.bytes());
          if (!dt.name.isEmpty()) deltaTypes.add(dt.name);
        } else if (r.field == 3 && r.wireType == WIRE_LENGTH_DELIMITED) {
          ParsedDataType dt = parseDataType(r.bytes());
          if (!dt.name.isEmpty()) aggregateTypes.add(dt.name);
        } else if (r.field == 4 && r.wireType == WIRE_VARINT) {
          autoPause = r.varint() != 0;
        } else if (r.field == 5 && r.wireType == WIRE_VARINT) {
          gps = r.varint() != 0;
        } else if (r.field == 6 && r.wireType == WIRE_LENGTH_DELIMITED) {
          ParsedExerciseGoal goal = parseExerciseGoal(r.bytes());
          if (goal != null) goals.add(goal);
        } else {
          r.skip();
        }
      }
    }
    return new ParsedExerciseConfig(
        exerciseType, deltaTypes, aggregateTypes, autoPause, gps, goals, proto);
  }

  static ParsedExerciseGoal parseExerciseGoal(byte[] proto) {
    if (proto == null) return null;
    int goalType = EXERCISE_GOAL_ONE_TIME;
    ParsedCondition condition = null;
    double period = 0.0;
    boolean hasPeriod = false;
    Reader r = new Reader(proto);
    while (r.next()) {
      if (r.field == 1 && r.wireType == WIRE_VARINT) {
        goalType = (int) r.varint();
      } else if (r.field == 2 && r.wireType == WIRE_LENGTH_DELIMITED) {
        condition = parseCondition(r.bytes());
      } else if (r.field == 3 && r.wireType == WIRE_LENGTH_DELIMITED) {
        double[] parsed = parseNumericValue(r.bytes());
        if (parsed != null) {
          period = parsed[0];
          hasPeriod = true;
        }
      } else {
        r.skip();
      }
    }
    if (condition == null) return null;
    return new ParsedExerciseGoal(goalType, condition, period, hasPeriod, proto);
  }

  static ParsedCondition parseCondition(byte[] proto) {
    if (proto == null) return null;
    ParsedDataType dataType = null;
    double threshold = 0.0;
    boolean isLong = false;
    long longThreshold = 0L;
    int comparisonType = COMPARISON_UNKNOWN;
    Reader r = new Reader(proto);
    while (r.next()) {
      if (r.field == 1 && r.wireType == WIRE_LENGTH_DELIMITED) {
        dataType = parseDataType(r.bytes());
      } else if (r.field == 2 && r.wireType == WIRE_LENGTH_DELIMITED) {
        double[] parsed = parseNumericValue(r.bytes());
        if (parsed != null) {
          threshold = parsed[0];
          isLong = parsed[1] != 0.0;
          longThreshold = Math.round(parsed[0]);
        }
      } else if (r.field == 3 && r.wireType == WIRE_VARINT) {
        comparisonType = (int) r.varint();
      } else {
        r.skip();
      }
    }
    if (dataType == null) return null;
    return new ParsedCondition(dataType, threshold, isLong, longThreshold, comparisonType, proto);
  }

  /** Returns {@code {doubleValue, isLongFlag}} from a {@code DataProto.Value}, or null. */
  private static double[] parseNumericValue(byte[] proto) {
    if (proto == null) return null;
    Reader r = new Reader(proto);
    while (r.next()) {
      if (r.field == 2 && r.wireType == WIRE_VARINT) {
        return new double[] {(double) r.varint(), 1.0};
      } else if (r.field == 3 && r.wireType == WIRE_FIXED64) {
        return new double[] {Double.longBitsToDouble(r.fixed64()), 0.0};
      } else {
        r.skip();
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Value, DataPoint & AggregateDataPoint encoders

  static byte[] valueLong(long v) {
    Writer w = new Writer();
    w.writeVarint(2, v);
    return w.toByteArray();
  }

  static byte[] valueDouble(double v) {
    Writer w = new Writer();
    w.writeDouble(3, v);
    return w.toByteArray();
  }

  static byte[] valueDoubleArray(double... values) {
    Writer arr = new Writer();
    for (double v : values) {
      arr.writeDouble(1, v);
    }
    Writer w = new Writer();
    w.writeBytes(4, arr.toByteArray());
    return w.toByteArray();
  }

  static byte[] hrAccuracy(int sensorStatus) {
    Writer hr = new Writer();
    hr.writeVarint(1, sensorStatus);
    Writer acc = new Writer();
    acc.writeBytes(1, hr.toByteArray());
    return acc.toByteArray();
  }

  static byte[] locationAccuracy(double horizontalErrorMeters, double verticalErrorMeters) {
    Writer loc = new Writer();
    loc.writeDouble(1, horizontalErrorMeters);
    loc.writeDouble(2, verticalErrorMeters);
    Writer acc = new Writer();
    acc.writeBytes(2, loc.toByteArray());
    return acc.toByteArray();
  }

  static byte[] sampleDataPoint(
      DataTypeSpec spec, byte[] valueProto, long durationFromBootMs, byte[] accuracyProto) {
    Writer w = new Writer();
    w.writeBytes(1, spec.toProto());
    w.writeBytes(2, valueProto);
    w.writeVarint(3, durationFromBootMs);
    w.writeVarint(4, durationFromBootMs);
    if (accuracyProto != null) {
      w.writeBytes(6, accuracyProto);
    }
    return w.toByteArray();
  }

  static byte[] intervalDataPoint(
      DataTypeSpec spec,
      byte[] valueProto,
      long startDurationFromBootMs,
      long endDurationFromBootMs) {
    Writer w = new Writer();
    w.writeBytes(1, spec.toProto());
    w.writeBytes(2, valueProto);
    w.writeVarint(3, startDurationFromBootMs);
    w.writeVarint(4, Math.max(startDurationFromBootMs, endDurationFromBootMs));
    return w.toByteArray();
  }

  static byte[] cumulativeAggregateDataPoint(
      DataTypeSpec spec, byte[] totalValueProto, long startEpochMs, long endEpochMs) {
    Writer cum = new Writer();
    cum.writeVarint(1, startEpochMs);
    cum.writeVarint(2, Math.max(startEpochMs, endEpochMs));
    cum.writeBytes(3, spec.toProto());
    cum.writeBytes(4, totalValueProto);
    Writer agg = new Writer();
    agg.writeBytes(1, cum.toByteArray());
    return agg.toByteArray();
  }

  static byte[] statisticalAggregateDataPoint(
      DataTypeSpec spec,
      byte[] minValueProto,
      byte[] maxValueProto,
      byte[] avgValueProto,
      long startEpochMs,
      long endEpochMs) {
    Writer stat = new Writer();
    stat.writeVarint(1, startEpochMs);
    stat.writeVarint(2, Math.max(startEpochMs, endEpochMs));
    stat.writeBytes(4, spec.toProto());
    stat.writeBytes(5, minValueProto);
    stat.writeBytes(6, maxValueProto);
    stat.writeBytes(7, avgValueProto);
    Writer agg = new Writer();
    agg.writeBytes(2, stat.toByteArray());
    return agg.toByteArray();
  }

  // ---------------------------------------------------------------------------
  // Availability encoders

  static byte[] availabilityResponse(DataTypeSpec spec, boolean isLocation, int availabilityCode) {
    Writer avail = new Writer();
    if (isLocation) {
      avail.writeVarint(2, availabilityCode);
    } else {
      avail.writeVarint(1, availabilityCode);
    }
    Writer resp = new Writer();
    resp.writeBytes(1, spec.toProto());
    resp.writeBytes(2, avail.toByteArray());
    return resp.toByteArray();
  }

  // ---------------------------------------------------------------------------
  // MeasureClient encoders

  static byte[] measureCapabilitiesResponse(List<DataTypeSpec> supportedTypes) {
    Writer caps = new Writer();
    for (DataTypeSpec spec : supportedTypes) {
      caps.writeBytes(1, spec.toProto());
    }
    Writer resp = new Writer();
    resp.writeBytes(1, caps.toByteArray());
    return resp.toByteArray();
  }

  static byte[] measureDataPointsEvent(List<byte[]> dataPointProtos) {
    Writer dpResp = new Writer();
    for (byte[] dp : dataPointProtos) {
      dpResp.writeBytes(1, dp);
    }
    Writer ev = new Writer();
    ev.writeBytes(1, dpResp.toByteArray());
    return ev.toByteArray();
  }

  static byte[] measureAvailabilityEvent(DataTypeSpec spec, int availabilityCode) {
    Writer ev = new Writer();
    ev.writeBytes(2, availabilityResponse(spec, LOCATION.name.equals(spec.name), availabilityCode));
    return ev.toByteArray();
  }

  // ---------------------------------------------------------------------------
  // PassiveMonitoringClient encoders

  static byte[] passiveCapabilitiesResponse(
      List<DataTypeSpec> supportedMonitoring,
      List<DataTypeSpec> supportedGoals,
      List<Integer> supportedHealthEvents,
      List<Integer> supportedUserActivityStates) {
    Writer caps = new Writer();
    for (DataTypeSpec spec : supportedMonitoring) {
      caps.writeBytes(1, spec.toProto());
    }
    for (DataTypeSpec spec : supportedGoals) {
      caps.writeBytes(2, spec.toProto());
    }
    for (int ev : supportedHealthEvents) {
      caps.writeVarint(4, ev);
    }
    for (int st : supportedUserActivityStates) {
      caps.writeVarint(5, st);
    }
    Writer resp = new Writer();
    resp.writeBytes(1, caps.toByteArray());
    return resp.toByteArray();
  }

  static byte[] userActivityInfo(int state, byte[] exerciseInfoProto, long stateChangeTimeEpochMs) {
    Writer w = new Writer();
    w.writeVarint(1, state);
    if (exerciseInfoProto != null) {
      w.writeBytes(2, exerciseInfoProto);
    }
    w.writeVarint(3, stateChangeTimeEpochMs);
    return w.toByteArray();
  }

  static byte[] passiveUpdateEvent(
      List<byte[]> dataPointProtos, List<byte[]> userActivityInfoProtos) {
    Writer upd = new Writer();
    for (byte[] dp : dataPointProtos) {
      upd.writeBytes(1, dp);
    }
    for (byte[] uai : userActivityInfoProtos) {
      upd.writeBytes(2, uai);
    }
    Writer resp = new Writer();
    resp.writeBytes(1, upd.toByteArray());
    Writer ev = new Writer();
    ev.writeBytes(1, resp.toByteArray());
    return ev.toByteArray();
  }

  static byte[] passiveGoalEvent(byte[] passiveGoalProto) {
    Writer resp = new Writer();
    resp.writeBytes(1, passiveGoalProto);
    Writer ev = new Writer();
    ev.writeBytes(2, resp.toByteArray());
    return ev.toByteArray();
  }

  static byte[] passiveHealthEvent(int healthEventType, long eventTimeEpochMs) {
    Writer he = new Writer();
    he.writeVarint(1, healthEventType);
    he.writeVarint(2, eventTimeEpochMs);
    Writer resp = new Writer();
    resp.writeBytes(1, he.toByteArray());
    Writer ev = new Writer();
    ev.writeBytes(3, resp.toByteArray());
    return ev.toByteArray();
  }

  static byte[] passivePermissionLostEvent() {
    Writer ev = new Writer();
    ev.writeBytes(4, new byte[0]);
    return ev.toByteArray();
  }

  // ---------------------------------------------------------------------------
  // ExerciseClient encoders

  static byte[] exerciseInfo(int trackedStatus, int exerciseType) {
    Writer info = new Writer();
    info.writeVarint(1, trackedStatus);
    info.writeVarint(2, exerciseType);
    return info.toByteArray();
  }

  static byte[] exerciseInfoResponse(int trackedStatus, int exerciseType) {
    Writer resp = new Writer();
    resp.writeBytes(1, exerciseInfo(trackedStatus, exerciseType));
    return resp.toByteArray();
  }

  static byte[] exerciseTypeCapabilities(
      List<DataTypeSpec> supportedDataTypes,
      List<DataTypeSpec> supportedGoals,
      List<DataTypeSpec> supportedMilestones,
      boolean supportsAutoPauseAndResume,
      boolean supportsLaps) {
    Writer caps = new Writer();
    for (DataTypeSpec spec : supportedDataTypes) {
      caps.writeBytes(1, spec.toProto());
    }
    for (DataTypeSpec goalType : supportedGoals) {
      Writer entry = new Writer();
      entry.writeBytes(1, goalType.toProto());
      entry.writeVarint(2, COMPARISON_GREATER_THAN);
      entry.writeVarint(2, COMPARISON_GREATER_THAN_OR_EQUAL);
      caps.writeBytes(2, entry.toByteArray());
    }
    for (DataTypeSpec milestoneType : supportedMilestones) {
      Writer entry = new Writer();
      entry.writeBytes(1, milestoneType.toProto());
      entry.writeVarint(2, COMPARISON_GREATER_THAN);
      entry.writeVarint(2, COMPARISON_GREATER_THAN_OR_EQUAL);
      caps.writeBytes(3, entry.toByteArray());
    }
    caps.writeBool(4, supportsAutoPauseAndResume);
    caps.writeBool(5, supportsLaps);
    return caps.toByteArray();
  }

  static byte[] exerciseCapabilitiesResponse(
      Map<Integer, byte[]> typeToCapabilities, List<Integer> batchingModes) {
    Writer caps = new Writer();
    for (Map.Entry<Integer, byte[]> entry : typeToCapabilities.entrySet()) {
      Writer mapEntry = new Writer();
      mapEntry.writeVarint(1, entry.getKey());
      mapEntry.writeBytes(2, entry.getValue());
      caps.writeBytes(1, mapEntry.toByteArray());
    }
    for (int mode : batchingModes) {
      caps.writeVarint(2, mode);
    }
    Writer resp = new Writer();
    resp.writeBytes(1, caps.toByteArray());
    return resp.toByteArray();
  }

  static byte[] exerciseUpdateEvent(
      int state,
      long startTimeEpochMs,
      long activeDurationMs,
      long updateDurationFromBootMs,
      long checkpointTimeEpochMs,
      int endReason,
      byte[] exerciseConfigProto,
      Map<DataTypeSpec, List<byte[]>> latestMetrics,
      List<byte[]> latestAggregateMetrics,
      List<byte[]> achievedGoalProtos) {
    Writer upd = new Writer();
    upd.writeVarint(1, state);
    if (startTimeEpochMs > 0) {
      upd.writeVarint(2, startTimeEpochMs);
    }
    upd.writeVarint(3, Math.max(0L, activeDurationMs));
    for (Map.Entry<DataTypeSpec, List<byte[]>> entry : latestMetrics.entrySet()) {
      if (entry.getValue().isEmpty()) continue;
      Writer metricEntry = new Writer();
      metricEntry.writeBytes(1, entry.getKey().toProto());
      for (byte[] dp : entry.getValue()) {
        metricEntry.writeBytes(2, dp);
      }
      upd.writeBytes(4, metricEntry.toByteArray());
    }
    for (byte[] goalProto : achievedGoalProtos) {
      Writer achieved = new Writer();
      achieved.writeBytes(1, goalProto);
      upd.writeBytes(5, achieved.toByteArray());
    }
    if (exerciseConfigProto != null && exerciseConfigProto.length > 0) {
      upd.writeBytes(7, exerciseConfigProto);
    }
    for (byte[] aggProto : latestAggregateMetrics) {
      upd.writeBytes(8, aggProto);
    }
    upd.writeVarint(9, Math.max(0L, updateDurationFromBootMs));
    if (checkpointTimeEpochMs > 0) {
      Writer cp = new Writer();
      cp.writeVarint(1, checkpointTimeEpochMs);
      cp.writeVarint(2, Math.max(0L, activeDurationMs));
      upd.writeBytes(10, cp.toByteArray());
    }
    if (endReason != EXERCISE_END_REASON_UNKNOWN) {
      upd.writeVarint(11, endReason);
    }
    Writer resp = new Writer();
    resp.writeBytes(1, upd.toByteArray());
    Writer ev = new Writer();
    ev.writeBytes(1, resp.toByteArray());
    return ev.toByteArray();
  }

  static byte[] exerciseLapSummaryEvent(
      int lapCount,
      long startTimeEpochMs,
      long endTimeEpochMs,
      long activeDurationMs,
      Map<DataTypeSpec, byte[]> lapMetrics) {
    Writer summary = new Writer();
    summary.writeVarint(1, lapCount);
    summary.writeVarint(2, startTimeEpochMs);
    summary.writeVarint(3, Math.max(startTimeEpochMs, endTimeEpochMs));
    summary.writeVarint(4, Math.max(0L, activeDurationMs));
    for (Map.Entry<DataTypeSpec, byte[]> entry : lapMetrics.entrySet()) {
      Writer lm = new Writer();
      lm.writeBytes(1, entry.getKey().toProto());
      lm.writeBytes(2, entry.getValue());
      summary.writeBytes(5, lm.toByteArray());
    }
    Writer resp = new Writer();
    resp.writeBytes(1, summary.toByteArray());
    Writer ev = new Writer();
    ev.writeBytes(2, resp.toByteArray());
    return ev.toByteArray();
  }

  static byte[] exerciseAvailabilityEvent(DataTypeSpec spec, int availabilityCode) {
    Writer ev = new Writer();
    ev.writeBytes(3, availabilityResponse(spec, LOCATION.name.equals(spec.name), availabilityCode));
    return ev.toByteArray();
  }

  // ---------------------------------------------------------------------------
  // Low-level protobuf Reader / Writer

  static String readString(byte[] proto, int targetField) {
    byte[] bytes = readBytes(proto, targetField);
    return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
  }

  static byte[] readBytes(byte[] proto, int targetField) {
    if (proto == null) return null;
    Reader r = new Reader(proto);
    while (r.next()) {
      if (r.field == targetField && r.wireType == WIRE_LENGTH_DELIMITED) {
        return r.bytes();
      }
      r.skip();
    }
    return null;
  }

  static final class Writer {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    void writeVarint(int field, long value) {
      writeRawVarint(((long) field << 3) | WIRE_VARINT);
      writeRawVarint(value);
    }

    void writeBool(int field, boolean value) {
      writeVarint(field, value ? 1L : 0L);
    }

    void writeDouble(int field, double value) {
      writeRawVarint(((long) field << 3) | WIRE_FIXED64);
      long bits = Double.doubleToRawLongBits(value);
      for (int i = 0; i < 8; i++) {
        out.write((int) ((bits >>> (i * 8)) & 0xFF));
      }
    }

    void writeString(int field, String value) {
      if (value == null) return;
      writeBytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    void writeBytes(int field, byte[] bytes) {
      if (bytes == null) return;
      writeRawVarint(((long) field << 3) | WIRE_LENGTH_DELIMITED);
      writeRawVarint(bytes.length);
      out.write(bytes, 0, bytes.length);
    }

    private void writeRawVarint(long value) {
      long v = value;
      while ((v & ~0x7FL) != 0) {
        out.write((int) ((v & 0x7F) | 0x80));
        v >>>= 7;
      }
      out.write((int) v);
    }

    byte[] toByteArray() {
      return out.toByteArray();
    }
  }

  static final class Reader {
    private final byte[] buf;
    private int pos;
    int field;
    int wireType;
    private boolean consumed = true;

    Reader(byte[] buf) {
      this.buf = buf != null ? buf : new byte[0];
    }

    boolean hasRemaining() {
      return pos < buf.length;
    }

    boolean next() {
      if (!consumed) skip();
      if (pos >= buf.length) return false;
      long key = readRawVarint();
      if (key < 0) return false;
      wireType = (int) (key & 0x7);
      field = (int) (key >>> 3);
      consumed = false;
      return field > 0;
    }

    long varint() {
      consumed = true;
      return readRawVarint();
    }

    long fixed64() {
      consumed = true;
      if (pos + 8 > buf.length) {
        pos = buf.length;
        return 0L;
      }
      long v = 0L;
      for (int i = 0; i < 8; i++) {
        v |= ((long) (buf[pos++] & 0xFF)) << (i * 8);
      }
      return v;
    }

    byte[] bytes() {
      consumed = true;
      long len = readRawVarint();
      if (len < 0 || len > buf.length - pos) {
        pos = buf.length;
        return null;
      }
      byte[] copy = new byte[(int) len];
      System.arraycopy(buf, pos, copy, 0, (int) len);
      pos += (int) len;
      return copy;
    }

    String string() {
      byte[] b = bytes();
      return b != null ? new String(b, StandardCharsets.UTF_8) : "";
    }

    void skip() {
      if (consumed) return;
      consumed = true;
      switch (wireType) {
        case WIRE_VARINT:
          readRawVarint();
          break;
        case WIRE_FIXED64:
          pos = Math.min(buf.length, pos + 8);
          break;
        case WIRE_FIXED32:
          pos = Math.min(buf.length, pos + 4);
          break;
        case WIRE_LENGTH_DELIMITED:
          long len = readRawVarint();
          if (len < 0 || len > buf.length - pos) {
            pos = buf.length;
          } else {
            pos += (int) len;
          }
          break;
        default:
          pos = buf.length;
          break;
      }
    }

    long readRawVarint() {
      long value = 0;
      for (int shift = 0; shift < 64; shift += 7) {
        if (pos >= buf.length) return -1;
        int b = buf[pos++] & 0xFF;
        value |= (long) (b & 0x7F) << shift;
        if ((b & 0x80) == 0) return value;
      }
      return -1;
    }
  }

  // ---------------------------------------------------------------------------
  // Enum name parsing helpers for control commands

  static int parseExerciseType(String name) {
    if (name == null) return EXERCISE_TYPE_RUNNING;
    switch (name.trim().toUpperCase(Locale.ROOT)) {
      case "RUNNING":
        return EXERCISE_TYPE_RUNNING;
      case "WALKING":
        return EXERCISE_TYPE_WALKING;
      case "BIKING":
        return EXERCISE_TYPE_BIKING;
      case "BIKING_STATIONARY":
        return EXERCISE_TYPE_BIKING_STATIONARY;
      case "RUNNING_TREADMILL":
      case "TREADMILL":
        return EXERCISE_TYPE_RUNNING_TREADMILL;
      case "HIKING":
        return EXERCISE_TYPE_HIKING;
      case "SWIMMING_POOL":
        return EXERCISE_TYPE_SWIMMING_POOL;
      case "SWIMMING_OPEN_WATER":
        return EXERCISE_TYPE_SWIMMING_OPEN_WATER;
      case "WORKOUT":
        return EXERCISE_TYPE_WORKOUT;
      case "YOGA":
        return EXERCISE_TYPE_YOGA;
      case "STRENGTH_TRAINING":
        return EXERCISE_TYPE_STRENGTH_TRAINING;
      case "ELLIPTICAL":
        return EXERCISE_TYPE_ELLIPTICAL;
      case "ROWING":
        return EXERCISE_TYPE_ROWING;
      case "ROWING_MACHINE":
        return EXERCISE_TYPE_ROWING_MACHINE;
      case "STAIR_CLIMBING":
        return EXERCISE_TYPE_STAIR_CLIMBING;
      case "GOLF":
        return EXERCISE_TYPE_GOLF;
      case "SKIING":
        return EXERCISE_TYPE_SKIING;
      case "SNOWBOARDING":
        return EXERCISE_TYPE_SNOWBOARDING;
      case "HIIT":
      case "HIGH_INTENSITY_INTERVAL_TRAINING":
        return EXERCISE_TYPE_HIIT;
      case "PILATES":
        return EXERCISE_TYPE_PILATES;
      case "CALISTHENICS":
        return EXERCISE_TYPE_CALISTHENICS;
      case "STRETCHING":
        return EXERCISE_TYPE_STRETCHING;
      default:
        try {
          return Integer.parseInt(name.trim());
        } catch (NumberFormatException ignored) {
          return EXERCISE_TYPE_UNKNOWN;
        }
    }
  }

  static String exerciseTypeName(int type) {
    switch (type) {
      case EXERCISE_TYPE_RUNNING:
        return "RUNNING";
      case EXERCISE_TYPE_WALKING:
        return "WALKING";
      case EXERCISE_TYPE_BIKING:
        return "BIKING";
      case EXERCISE_TYPE_BIKING_STATIONARY:
        return "BIKING_STATIONARY";
      case EXERCISE_TYPE_RUNNING_TREADMILL:
        return "RUNNING_TREADMILL";
      case EXERCISE_TYPE_HIKING:
        return "HIKING";
      case EXERCISE_TYPE_SWIMMING_POOL:
        return "SWIMMING_POOL";
      case EXERCISE_TYPE_SWIMMING_OPEN_WATER:
        return "SWIMMING_OPEN_WATER";
      case EXERCISE_TYPE_WORKOUT:
        return "WORKOUT";
      case EXERCISE_TYPE_YOGA:
        return "YOGA";
      case EXERCISE_TYPE_STRENGTH_TRAINING:
        return "STRENGTH_TRAINING";
      case EXERCISE_TYPE_ELLIPTICAL:
        return "ELLIPTICAL";
      case EXERCISE_TYPE_ROWING:
        return "ROWING";
      case EXERCISE_TYPE_ROWING_MACHINE:
        return "ROWING_MACHINE";
      case EXERCISE_TYPE_STAIR_CLIMBING:
        return "STAIR_CLIMBING";
      case EXERCISE_TYPE_GOLF:
        return "GOLF";
      case EXERCISE_TYPE_SKIING:
        return "SKIING";
      case EXERCISE_TYPE_SNOWBOARDING:
        return "SNOWBOARDING";
      case EXERCISE_TYPE_HIIT:
        return "HIGH_INTENSITY_INTERVAL_TRAINING";
      case EXERCISE_TYPE_PILATES:
        return "PILATES";
      case EXERCISE_TYPE_CALISTHENICS:
        return "CALISTHENICS";
      case EXERCISE_TYPE_STRETCHING:
        return "STRETCHING";
      default:
        return "UNKNOWN(" + type + ")";
    }
  }

  static String exerciseStateName(int state) {
    switch (state) {
      case EXERCISE_STATE_PREPARING:
        return "PREPARING";
      case EXERCISE_STATE_USER_STARTING:
        return "USER_STARTING";
      case EXERCISE_STATE_ACTIVE:
        return "ACTIVE";
      case EXERCISE_STATE_USER_PAUSING:
        return "USER_PAUSING";
      case EXERCISE_STATE_USER_PAUSED:
        return "USER_PAUSED";
      case EXERCISE_STATE_AUTO_PAUSING:
        return "AUTO_PAUSING";
      case EXERCISE_STATE_AUTO_PAUSED:
        return "AUTO_PAUSED";
      case EXERCISE_STATE_USER_RESUMING:
        return "USER_RESUMING";
      case EXERCISE_STATE_AUTO_RESUMING:
        return "AUTO_RESUMING";
      case EXERCISE_STATE_USER_ENDING:
        return "USER_ENDING";
      case EXERCISE_STATE_USER_ENDED:
        return "USER_ENDED";
      case EXERCISE_STATE_AUTO_ENDING:
        return "AUTO_ENDING";
      case EXERCISE_STATE_AUTO_ENDED:
        return "AUTO_ENDED";
      case EXERCISE_STATE_ENDED:
        return "ENDED";
      case EXERCISE_STATE_ENDING:
        return "ENDING";
      case EXERCISE_STATE_TERMINATED:
        return "TERMINATED";
      default:
        return "UNKNOWN";
    }
  }

  static int parseDataTypeAvailability(String name) {
    if (name == null) return DATA_AVAILABILITY_AVAILABLE;
    switch (name.trim().toUpperCase(Locale.ROOT)) {
      case "AVAILABLE":
        return DATA_AVAILABILITY_AVAILABLE;
      case "ACQUIRING":
        return DATA_AVAILABILITY_ACQUIRING;
      case "UNAVAILABLE":
        return DATA_AVAILABILITY_UNAVAILABLE;
      case "UNAVAILABLE_DEVICE_OFF_BODY":
      case "OFF_BODY":
        return DATA_AVAILABILITY_UNAVAILABLE_DEVICE_OFF_BODY;
      default:
        return DATA_AVAILABILITY_UNKNOWN;
    }
  }

  static int parseLocationAvailability(String name) {
    if (name == null) return LOCATION_AVAILABILITY_ACQUIRED_TETHERED;
    switch (name.trim().toUpperCase(Locale.ROOT)) {
      case "UNAVAILABLE":
        return LOCATION_AVAILABILITY_UNAVAILABLE;
      case "NO_GNSS":
        return LOCATION_AVAILABILITY_NO_GNSS;
      case "ACQUIRING":
        return LOCATION_AVAILABILITY_ACQUIRING;
      case "ACQUIRED_TETHERED":
      case "AVAILABLE":
        return LOCATION_AVAILABILITY_ACQUIRED_TETHERED;
      case "ACQUIRED_UNTETHERED":
        return LOCATION_AVAILABILITY_ACQUIRED_UNTETHERED;
      default:
        return LOCATION_AVAILABILITY_UNKNOWN;
    }
  }

  static int parseUserActivityState(String name) {
    if (name == null) return USER_ACTIVITY_PASSIVE;
    switch (name.trim().toUpperCase(Locale.ROOT)) {
      case "EXERCISE":
        return USER_ACTIVITY_EXERCISE;
      case "PASSIVE":
        return USER_ACTIVITY_PASSIVE;
      case "ASLEEP":
        return USER_ACTIVITY_ASLEEP;
      default:
        return USER_ACTIVITY_UNKNOWN;
    }
  }

  static int parseHealthEventType(String name) {
    if (name == null) return HEALTH_EVENT_UNKNOWN;
    switch (name.trim().toUpperCase(Locale.ROOT)) {
      case "FALL_DETECTED":
      case "FALL":
        return HEALTH_EVENT_FALL_DETECTED;
      case "LOW_HR_DETECTED":
      case "LOW_HR":
        return HEALTH_EVENT_LOW_HR_DETECTED;
      case "ELEVATED_HR_DETECTED":
      case "ELEVATED_HR":
        return HEALTH_EVENT_ELEVATED_HR_DETECTED;
      default:
        return HEALTH_EVENT_UNKNOWN;
    }
  }

  static int parseExerciseEndReason(String name) {
    if (name == null) return EXERCISE_END_REASON_USER_END;
    switch (name.trim().toUpperCase(Locale.ROOT)) {
      case "USER_END":
        return EXERCISE_END_REASON_USER_END;
      case "AUTO_END_PERMISSION_LOST":
        return EXERCISE_END_REASON_AUTO_END_PERMISSION_LOST;
      case "AUTO_END_PAUSE_EXPIRED":
        return EXERCISE_END_REASON_AUTO_END_PAUSE_EXPIRED;
      case "AUTO_END_MISSING_LISTENER":
        return EXERCISE_END_REASON_AUTO_END_MISSING_LISTENER;
      case "AUTO_END_SUPERSEDED":
        return EXERCISE_END_REASON_AUTO_END_SUPERSEDED;
      case "AUTO_END_PREPARE_EXPIRED":
        return EXERCISE_END_REASON_AUTO_END_PREPARE_EXPIRED;
      default:
        return EXERCISE_END_REASON_UNKNOWN;
    }
  }

  /** Maps {@link SystemData}'s accuracy index (0..4) to WHS {@code HrAccuracy.SensorStatus}. */
  static int hrAccuracyFromSystemData(int systemDataAccuracy) {
    switch (systemDataAccuracy) {
      case 0:
        return HR_ACCURACY_UNKNOWN;
      case 1:
        return HR_ACCURACY_UNRELIABLE;
      case 2:
        return HR_ACCURACY_LOW;
      case 3:
        return HR_ACCURACY_MEDIUM;
      case 4:
        return HR_ACCURACY_HIGH;
      default:
        return HR_ACCURACY_MEDIUM;
    }
  }
}
