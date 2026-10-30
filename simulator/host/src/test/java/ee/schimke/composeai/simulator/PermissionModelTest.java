package ee.schimke.composeai.simulator;

import static ee.schimke.composeai.simulator.PermissionModel.Answer.DENY;
import static ee.schimke.composeai.simulator.PermissionModel.Answer.DISMISS;
import static ee.schimke.composeai.simulator.PermissionModel.Answer.GRANT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ee.schimke.composeai.simulator.PermissionModel.Answer;
import ee.schimke.composeai.simulator.PermissionModel.Decision;
import ee.schimke.composeai.simulator.PermissionModel.How;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PermissionModelTest {

  private static final String CAMERA = "android.permission.CAMERA";
  private static final String MIC = "android.permission.RECORD_AUDIO";
  private static final String NOTIFY = "android.permission.POST_NOTIFICATIONS";
  private static final String READ_CONTACTS = "android.permission.READ_CONTACTS";
  private static final String WRITE_CONTACTS = "android.permission.WRITE_CONTACTS";
  private static final String CALENDAR = "android.permission.READ_CALENDAR";
  private static final String INTERNET = "android.permission.INTERNET";
  private static final String SCAN = "android.permission.BLUETOOTH_SCAN";
  private static final String CONNECT = "android.permission.BLUETOOTH_CONNECT";
  private static final String IMAGES = "android.permission.READ_MEDIA_IMAGES";
  private static final String VIDEO = "android.permission.READ_MEDIA_VIDEO";
  private static final String FINE = PermissionModel.FINE;
  private static final String COARSE = PermissionModel.COARSE;
  private static final String BACKGROUND = PermissionModel.BACKGROUND;
  private static final String SENSORS = PermissionModel.BODY_SENSORS;
  private static final String SENSORS_BG = PermissionModel.BODY_SENSORS_BACKGROUND;
  private static final String SELECTED = PermissionModel.VISUAL_USER_SELECTED;

  private static PermissionModel model(int sdk) {
    return new PermissionModel(
        Arrays.asList(
            CAMERA,
            MIC,
            NOTIFY,
            READ_CONTACTS,
            WRITE_CONTACTS,
            INTERNET,
            SCAN,
            CONNECT,
            IMAGES,
            VIDEO,
            SELECTED,
            FINE,
            COARSE,
            BACKGROUND,
            SENSORS,
            SENSORS_BG),
        name -> !name.equals(INTERNET),
        sdk,
        sdk);
  }

  /** Requests [names] and answers every waiting one with [answer]; returns the decisions. */
  private static List<Decision> request(PermissionModel m, Answer answer, String... names) {
    List<Decision> ds = m.triage(names);
    Map<String, Answer> answers = new LinkedHashMap<>();
    for (String n : PermissionModel.waiting(ds)) answers.put(n, answer);
    m.answer(ds, answers);
    return ds;
  }

  private static List<Boolean> granted(List<Decision> ds) {
    List<Boolean> out = new ArrayList<>();
    for (Decision d : ds) out.add(d.granted);
    return out;
  }

  private static List<How> hows(List<Decision> ds) {
    List<How> out = new ArrayList<>();
    for (Decision d : ds) out.add(d.how);
    return out;
  }

  @Test
  public void mixedAnswersKeepRequestOrder() {
    PermissionModel m = model(35);
    List<Decision> ds = m.triage(new String[] {CAMERA, MIC, NOTIFY});
    Map<String, Answer> answers = new LinkedHashMap<>();
    answers.put(CAMERA, GRANT);
    answers.put(MIC, DENY);
    answers.put(NOTIFY, GRANT);
    m.answer(ds, answers);
    assertEquals(Arrays.asList(true, false, true), granted(ds));
    assertEquals(CAMERA, ds.get(0).name);
    assertEquals(MIC, ds.get(1).name);
    assertTrue(m.rationale(MIC));
  }

  @Test
  public void alreadyGrantedIsNotAskedAndNotRevokedByADeny() {
    PermissionModel m = model(35);
    m.set(CAMERA, true);
    List<Decision> ds = request(m, DENY, CAMERA, MIC);
    assertEquals(Arrays.asList(How.ALREADY_GRANTED, How.DIALOG), hows(ds));
    assertEquals(Arrays.asList(true, false), granted(ds));
    assertTrue(m.isGranted(CAMERA));
  }

  @Test
  public void notInManifestIsDeniedWithoutAsking() {
    PermissionModel m = model(35);
    List<Decision> ds = request(m, GRANT, CALENDAR, CAMERA);
    assertEquals(Arrays.asList(How.NOT_IN_MANIFEST, How.DIALOG), hows(ds));
    assertEquals(Arrays.asList(false, true), granted(ds));
    assertFalse(m.isGranted(CALENDAR));
  }

  @Test
  public void installTimePermissionReportsItsState() {
    PermissionModel m = model(35);
    m.set(INTERNET, true);
    List<Decision> ds = m.triage(new String[] {INTERNET});
    assertEquals(How.INSTALL_TIME, ds.get(0).how);
    assertTrue(ds.get(0).granted);
  }

  @Test
  public void emptyRequestDecidesNothing() {
    PermissionModel m = model(35);
    List<Decision> ds = request(m, GRANT);
    assertTrue(ds.isEmpty());
  }

  @Test
  public void duplicatesGetOneAnswerAndCountOnce() {
    PermissionModel m = model(35);
    List<Decision> ds = m.triage(new String[] {MIC, MIC, CAMERA});
    assertEquals(Arrays.asList(MIC, CAMERA), PermissionModel.waiting(ds));
    Map<String, Answer> answers = new LinkedHashMap<>();
    answers.put(MIC, DENY);
    answers.put(CAMERA, GRANT);
    m.answer(ds, answers);
    assertEquals(Arrays.asList(false, false, true), granted(ds));
    // Counted once: a rationale, not "don't ask again".
    assertTrue(m.rationale(MIC));
    assertFalse(m.dontAskAgain(MIC));
  }

  @Test
  public void secondDenyIsDontAskAgainAndLaterRequestsAreNotAsked() {
    PermissionModel m = model(35);
    assertFalse(m.rationale(MIC));
    request(m, DENY, MIC);
    assertTrue(m.rationale(MIC));
    assertFalse(m.dontAskAgain(MIC));
    request(m, DENY, MIC);
    assertFalse(m.rationale(MIC));
    assertTrue(m.dontAskAgain(MIC));
    List<Decision> third = request(m, GRANT, MIC);
    assertEquals(How.DONT_ASK_AGAIN, third.get(0).how);
    assertFalse(third.get(0).granted);
    // Settings (`pm grant`) lifts it.
    m.set(MIC, true);
    assertFalse(m.dontAskAgain(MIC));
    assertFalse(m.rationale(MIC));
  }

  @Test
  public void dismissingTheDialogDoesNotCount() {
    PermissionModel m = model(35);
    request(m, DISMISS, MIC);
    request(m, DISMISS, MIC);
    assertFalse(m.rationale(MIC));
    assertFalse(m.dontAskAgain(MIC));
    assertEquals(How.DIALOG, m.triage(new String[] {MIC}).get(0).how);
  }

  @Test
  public void grantClearsTheRationale() {
    PermissionModel m = model(35);
    request(m, DENY, MIC);
    request(m, GRANT, MIC);
    assertFalse(m.rationale(MIC));
    m.set(MIC, false); // revoked in Settings
    assertFalse(m.rationale(MIC));
    assertEquals(How.DIALOG, m.triage(new String[] {MIC}).get(0).how);
  }

  @Test
  public void resetForgetsDenialsAndRevokesRuntimeOnly() {
    PermissionModel m = model(35);
    m.set(INTERNET, true);
    request(m, DENY, MIC);
    request(m, DENY, MIC);
    request(m, GRANT, CAMERA);
    m.reset();
    assertFalse(m.dontAskAgain(MIC));
    assertFalse(m.isGranted(CAMERA));
    assertTrue(m.isGranted(INTERNET));
  }

  @Test
  public void groupMemberGrantedIsGrantedWithoutAsking() {
    PermissionModel m = model(35);
    request(m, GRANT, READ_CONTACTS);
    List<Decision> ds = request(m, DENY, WRITE_CONTACTS);
    assertEquals(How.GROUP_GRANTED, ds.get(0).how);
    assertTrue(ds.get(0).granted);
    request(m, GRANT, SCAN);
    assertEquals(How.GROUP_GRANTED, m.triage(new String[] {CONNECT}).get(0).how);
  }

  @Test
  public void cameraAndMicrophoneAreNotGrouped() {
    PermissionModel m = model(35);
    request(m, GRANT, CAMERA);
    assertEquals(How.DIALOG, m.triage(new String[] {MIC}).get(0).how);
  }

  @Test
  public void preciseLocationAloneIsIgnoredFrom31() {
    PermissionModel m = model(35);
    List<Decision> ds = request(m, GRANT, FINE);
    assertEquals(How.IGNORED, ds.get(0).how);
    assertFalse(ds.get(0).granted);
    assertEquals(
        Arrays.asList(How.DIALOG, How.DIALOG), hows(m.triage(new String[] {FINE, COARSE})));
    // Before 12 there is no approximate location, and precise alone is asked about.
    assertEquals(How.DIALOG, model(30).triage(new String[] {FINE}).get(0).how);
  }

  @Test
  public void approximateGrantsCoarseOnlyAndUpgradeAsksAgain() {
    PermissionModel m = model(35);
    List<Decision> ds = m.triage(new String[] {FINE, COARSE});
    m.answer(ds, PermissionModel.expand(PermissionModel.waiting(ds), "approximate"));
    assertEquals(Arrays.asList(false, true), granted(ds));
    assertFalse(m.rationale(FINE));
    List<Decision> upgrade = m.triage(new String[] {FINE, COARSE});
    assertEquals(Arrays.asList(How.DIALOG, How.ALREADY_GRANTED), hows(upgrade));
  }

  @Test
  public void backgroundLocationIsASeparateLaterRequest() {
    PermissionModel m = model(35);
    // Alone, without foreground location: denied without asking.
    assertEquals(How.IGNORED, request(m, GRANT, BACKGROUND).get(0).how);
    // With foreground location in the same request: the whole request is ignored.
    List<Decision> together = request(m, GRANT, FINE, COARSE, BACKGROUND);
    assertEquals(Arrays.asList(How.IGNORED, How.IGNORED, How.IGNORED), hows(together));
    assertEquals(Arrays.asList(false, false, false), granted(together));
    // Foreground first, then background on its own.
    request(m, GRANT, FINE, COARSE);
    List<Decision> later = request(m, GRANT, BACKGROUND);
    assertEquals(How.DIALOG, later.get(0).how);
    assertTrue(later.get(0).granted);
  }

  @Test
  public void backgroundWithForegroundAlreadyGrantedIsAsked() {
    PermissionModel m = model(35);
    request(m, GRANT, FINE, COARSE);
    List<Decision> ds = request(m, GRANT, FINE, COARSE, BACKGROUND);
    assertEquals(Arrays.asList(How.ALREADY_GRANTED, How.ALREADY_GRANTED, How.DIALOG), hows(ds));
  }

  @Test
  public void backgroundBodySensorsNeedsBodySensorsFirst() {
    PermissionModel m = model(34);
    assertEquals(How.IGNORED, request(m, GRANT, SENSORS_BG).get(0).how);
    assertEquals(
        Arrays.asList(How.IGNORED, How.IGNORED),
        hows(request(m, GRANT, SENSORS, SENSORS_BG)));
    request(m, GRANT, SENSORS);
    assertTrue(request(m, GRANT, SENSORS_BG).get(0).granted);
  }

  @Test
  public void selectedPhotosGrantsOnlyUserSelected() {
    PermissionModel m = model(34);
    List<Decision> ds = m.triage(new String[] {IMAGES, VIDEO, SELECTED});
    m.answer(ds, PermissionModel.expand(PermissionModel.waiting(ds), "selected"));
    assertEquals(Arrays.asList(false, false, true), granted(ds));
    // Partial access asks again next time.
    assertEquals(How.DIALOG, m.triage(new String[] {IMAGES, SELECTED}).get(0).how);
  }

  @Test
  public void permissionNewerThanTheDeviceIsDeniedWithoutAsking() {
    PermissionModel m = model(33);
    List<Decision> ds = request(m, GRANT, IMAGES, SELECTED);
    assertEquals(Arrays.asList(How.DIALOG, How.IGNORED), hows(ds));
    assertEquals(Arrays.asList(true, false), granted(ds));
    assertEquals(How.IGNORED, model(32).triage(new String[] {NOTIFY}).get(0).how);
    assertEquals(How.DIALOG, model(34).triage(new String[] {SELECTED}).get(0).how);
  }

  @Test
  public void platformRuntimePermissionsIncludeXrAndLocalNetwork() {
    assertTrue(PermissionModel.isPlatformRuntime("android.permission.HEAD_TRACKING"));
    assertTrue(PermissionModel.isPlatformRuntime("android.permission.SCENE_UNDERSTANDING_COARSE"));
    assertTrue(PermissionModel.isPlatformRuntime("android.permission.ACCESS_LOCAL_NETWORK"));
    assertTrue(PermissionModel.isPlatformRuntime("android.permission.health.READ_HEART_RATE"));
    assertTrue(PermissionModel.isPlatformRuntime(CAMERA));
    assertFalse(PermissionModel.isPlatformRuntime(INTERNET));
    assertFalse(PermissionModel.isPlatformRuntime("android.permission.FOREGROUND_SERVICE"));
    assertFalse(PermissionModel.isPlatform("com.example.permission.C2D_MESSAGE"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void unknownAnswerIsRejected() {
    PermissionModel.expand(Arrays.asList(CAMERA), "maybe");
  }
}
