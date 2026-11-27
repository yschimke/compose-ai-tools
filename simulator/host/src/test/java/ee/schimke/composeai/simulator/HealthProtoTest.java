package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

public class HealthProtoTest {

  @Test
  public void roundTripsDataTypeProto() {
    byte[] hr = HealthProto.HEART_RATE_BPM.toProto();
    HealthProto.ParsedDataType parsed = HealthProto.parseDataType(hr);
    assertEquals("HeartRate", parsed.name);
    assertEquals(HealthProto.TIME_TYPE_SAMPLE, parsed.timeType);
    assertEquals(HealthProto.FORMAT_DOUBLE, parsed.format);

    byte[] steps = HealthProto.STEPS_DAILY.toProto();
    HealthProto.ParsedDataType parsedSteps = HealthProto.parseDataType(steps);
    assertEquals("Daily Steps", parsedSteps.name);
    assertEquals(HealthProto.TIME_TYPE_INTERVAL, parsedSteps.timeType);
    assertEquals(HealthProto.FORMAT_LONG, parsedSteps.format);
  }

  @Test
  public void parsesMeasureRegistrationRequest() {
    HealthProto.Writer req = new HealthProto.Writer();
    req.writeString(1, "com.example.measure");
    req.writeBytes(2, HealthProto.HEART_RATE_BPM.toProto());
    byte[] bytes = req.toByteArray();

    assertEquals("com.example.measure", HealthProto.readPackageName(bytes));
    HealthProto.ParsedDataType dt = HealthProto.parseMeasureDataType(bytes);
    assertNotNull(dt);
    assertEquals("HeartRate", dt.name);
  }

  @Test
  public void parsesPassiveServiceRegistrationWithGoalsAndActivity() {
    HealthProto.Writer cond = new HealthProto.Writer();
    cond.writeBytes(1, HealthProto.STEPS_DAILY.toProto());
    cond.writeBytes(2, HealthProto.valueLong(10_000));
    cond.writeVarint(3, HealthProto.COMPARISON_GREATER_THAN_OR_EQUAL);

    HealthProto.Writer goal = new HealthProto.Writer();
    goal.writeBytes(1, cond.toByteArray());
    goal.writeVarint(2, HealthProto.TRIGGER_FREQUENCY_ONCE);

    HealthProto.Writer config = new HealthProto.Writer();
    config.writeBytes(1, HealthProto.STEPS_DAILY.toProto());
    config.writeBytes(1, HealthProto.FLOORS_DAILY.toProto());
    config.writeBool(2, true);
    config.writeBytes(3, goal.toByteArray());
    config.writeVarint(4, HealthProto.HEALTH_EVENT_FALL_DETECTED);

    HealthProto.Writer req = new HealthProto.Writer();
    req.writeString(1, "com.example.passive");
    req.writeString(2, "com.example.passive.MyService");
    req.writeBytes(3, config.toByteArray());
    byte[] bytes = req.toByteArray();

    assertEquals("com.example.passive", HealthProto.readPackageName(bytes));
    assertEquals("com.example.passive.MyService", HealthProto.readListenerServiceClass(bytes));

    HealthProto.ParsedPassiveConfig parsed = HealthProto.parsePassiveServiceConfig(bytes);
    assertEquals(Set.of("Daily Steps", "Daily Floors"), parsed.dataTypeNames);
    assertTrue(parsed.includeUserActivityState);
    assertEquals(1, parsed.passiveGoals.size());
    HealthProto.ParsedPassiveGoal parsedGoal = parsed.passiveGoals.get(0);
    assertEquals(HealthProto.TRIGGER_FREQUENCY_ONCE, parsedGoal.triggerFrequency);
    assertEquals("Daily Steps", parsedGoal.condition.dataType.name);
    assertEquals(10_000.0, parsedGoal.condition.threshold, 0.001);
    assertFalse(parsedGoal.condition.matches(9_999));
    assertTrue(parsedGoal.condition.matches(10_000));
    assertTrue(parsed.healthEventTypes.contains(HealthProto.HEALTH_EVENT_FALL_DETECTED));
  }

  @Test
  public void parsesPrepareAndStartExerciseRequests() {
    HealthProto.Writer warmUp = new HealthProto.Writer();
    warmUp.writeVarint(1, HealthProto.EXERCISE_TYPE_RUNNING);
    warmUp.writeBytes(2, HealthProto.HEART_RATE_BPM.toProto());
    warmUp.writeBytes(2, HealthProto.LOCATION.toProto());
    HealthProto.Writer prepReq = new HealthProto.Writer();
    prepReq.writeString(1, "com.example.exercise");
    prepReq.writeBytes(2, warmUp.toByteArray());

    HealthProto.ParsedWarmUpConfig parsedWarmUp =
        HealthProto.parsePrepareExerciseConfig(prepReq.toByteArray());
    assertEquals(HealthProto.EXERCISE_TYPE_RUNNING, parsedWarmUp.exerciseType);
    assertEquals(Set.of("HeartRate", "Location"), parsedWarmUp.dataTypeNames);

    HealthProto.Writer cond = new HealthProto.Writer();
    cond.writeBytes(1, HealthProto.CALORIES_TOTAL.toProto());
    cond.writeBytes(2, HealthProto.valueDouble(250.0));
    cond.writeVarint(3, HealthProto.COMPARISON_GREATER_THAN_OR_EQUAL);

    HealthProto.Writer exGoal = new HealthProto.Writer();
    exGoal.writeVarint(1, HealthProto.EXERCISE_GOAL_ONE_TIME);
    exGoal.writeBytes(2, cond.toByteArray());

    HealthProto.Writer exCfg = new HealthProto.Writer();
    exCfg.writeVarint(1, HealthProto.EXERCISE_TYPE_RUNNING);
    exCfg.writeBytes(2, HealthProto.HEART_RATE_BPM.toProto());
    exCfg.writeBytes(3, HealthProto.CALORIES_TOTAL.toProto());
    exCfg.writeBytes(3, HealthProto.DISTANCE_TOTAL.toProto());
    exCfg.writeBool(4, true);
    exCfg.writeBool(5, true);
    exCfg.writeBytes(6, exGoal.toByteArray());

    HealthProto.Writer startReq = new HealthProto.Writer();
    startReq.writeString(1, "com.example.exercise");
    startReq.writeBytes(2, exCfg.toByteArray());

    HealthProto.ParsedExerciseConfig parsedCfg =
        HealthProto.parseStartExerciseConfig(startReq.toByteArray());
    assertEquals(HealthProto.EXERCISE_TYPE_RUNNING, parsedCfg.exerciseType);
    assertEquals(Set.of("HeartRate"), parsedCfg.deltaDataTypeNames);
    assertEquals(Set.of("Calories", "Distance"), parsedCfg.aggregateDataTypeNames);
    assertTrue(parsedCfg.autoPauseAndResumeEnabled);
    assertTrue(parsedCfg.gpsUsageEnabled);
    assertEquals(1, parsedCfg.exerciseGoals.size());
    assertEquals(250.0, parsedCfg.exerciseGoals.get(0).condition.threshold, 0.001);
  }

  @Test
  public void conditionComparisonTypesMatchExpectedSemantics() {
    HealthProto.ParsedDataType dt = new HealthProto.ParsedDataType("Calories", 1, 1);
    HealthProto.ParsedCondition gt =
        new HealthProto.ParsedCondition(dt, 100.0, false, 100L, HealthProto.COMPARISON_GREATER_THAN, new byte[0]);
    assertFalse(gt.matches(100.0));
    assertTrue(gt.matches(100.1));

    HealthProto.ParsedCondition lte =
        new HealthProto.ParsedCondition(
            dt, 100.0, false, 100L, HealthProto.COMPARISON_LESS_THAN_OR_EQUAL, new byte[0]);
    assertTrue(lte.matches(100.0));
    assertFalse(lte.matches(100.1));
  }

  @Test
  public void parsesControlCommandEnums() {
    assertEquals(HealthProto.EXERCISE_TYPE_RUNNING, HealthProto.parseExerciseType("running"));
    assertEquals(HealthProto.EXERCISE_TYPE_BIKING, HealthProto.parseExerciseType("BIKING"));
    assertEquals(
        HealthProto.LOCATION_AVAILABILITY_ACQUIRED_TETHERED,
        HealthProto.parseLocationAvailability("acquired_tethered"));
    assertEquals(
        HealthProto.DATA_AVAILABILITY_UNAVAILABLE_DEVICE_OFF_BODY,
        HealthProto.parseDataTypeAvailability("off_body"));
    assertEquals(
        HealthProto.HEALTH_EVENT_FALL_DETECTED, HealthProto.parseHealthEventType("fall_detected"));
    assertEquals(
        HealthProto.HR_ACCURACY_HIGH, HealthProto.hrAccuracyFromSystemData(4));
  }
}
