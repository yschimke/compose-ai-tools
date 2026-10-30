package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AdbShellTest {

  private static Map<String, Object> t(String line) {
    return AdbShell.translate(line);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  // Tokenizing

  @Test
  public void tokenizesQuotesAndEscapes() {
    List<AdbShell.Token> tokens = AdbShell.tokenize("a 'b c' \"d \\\"e\\\"\" f\\ g");
    assertEquals("[a, b c, d \"e\", f g]", tokens.toString());
  }

  @Test
  public void tokenizesOperators() {
    assertEquals("[a, <|>, b, <&&>, c]", AdbShell.tokenize("a | b && c").toString());
    assertEquals("[a, x|y]", AdbShell.tokenize("a 'x|y'").toString());
  }

  @Test
  public void refusesPipesAndLists() {
    assertThrows(AdbShell.UnsupportedException.class, () -> t("logcat -d | grep foo"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("input tap 1 2; input tap 3 4"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("am start 'unterminated"));
  }

  // adb prefix

  @Test
  public void dropsAdbShellPrefix() {
    assertEquals(t("input tap 10 20"), t("adb -s emulator-5554 shell input tap 10 20"));
    assertEquals(t("input tap 10 20"), t("adb shell 'input tap 10 20'"));
    assertEquals(t("logcat -d"), t("adb logcat -d"));
  }

  @Test
  public void adbPullAndPush() {
    Map<String, Object> pull = t("adb pull /data/data/com.x/files/a.txt /tmp/a.txt");
    assertEquals("files", pull.get("cmd"));
    assertEquals("pull", pull.get("op"));
    assertEquals("/data/data/com.x/files/a.txt", pull.get("path"));
    assertEquals("/tmp/a.txt", pull.get("to"));
    Map<String, Object> push = t("adb push local.db /data/data/com.x/databases/");
    assertEquals("push", push.get("op"));
    assertEquals("local.db", push.get("from"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("adb install app.apk"));
  }

  @Test
  public void execOutScreencapRedirect() {
    Map<String, Object> c = t("adb exec-out screencap -p > out.png");
    assertEquals("screenshot", c.get("cmd"));
    assertEquals("out.png", c.get("path"));
    assertEquals(null, c.get("devicePath"));
    Map<String, Object> device = t("screencap -p /sdcard/s.png");
    assertEquals("/sdcard/s.png", device.get("path"));
    assertEquals(true, device.get("devicePath"));
  }

  // am

  @Test
  public void amStartDeepLink() {
    Map<String, Object> c =
        t("am start -W -a android.intent.action.VIEW -d \"https://example.com/topic/1\" com.x");
    assertEquals("startActivity", c.get("cmd"));
    Map<String, Object> intent = map(c.get("intent"));
    assertEquals("android.intent.action.VIEW", intent.get("action"));
    assertEquals("https://example.com/topic/1", intent.get("data"));
    assertEquals("com.x", intent.get("package"));
  }

  @Test
  public void amStartBareUriGetsView() {
    Map<String, Object> intent = map(t("am start https://example.com/a").get("intent"));
    assertEquals("android.intent.action.VIEW", intent.get("action"));
    assertEquals("https://example.com/a", intent.get("data"));
  }

  @Test
  public void amStartComponentFlagsExtras() {
    Map<String, Object> c =
        t(
            "am start -S -n com.x/.Main -c android.intent.category.LAUNCHER -f 0x10000000"
                + " --activity-clear-top --es name 'a b' --ei n 3 --el big 5000000000 --ez on true"
                + " --ef f 1.5 --eu u content://x/1 --esa list 'a,b\\,c' --eia nums 1,2"
                + " --esn gone");
    assertEquals(true, c.get("forceStop"));
    Map<String, Object> intent = map(c.get("intent"));
    assertEquals("com.x/.Main", intent.get("component"));
    assertEquals(List.of("android.intent.category.LAUNCHER"), intent.get("categories"));
    assertEquals(List.of(0x10000000, "FLAG_ACTIVITY_CLEAR_TOP"), intent.get("flags"));
    Map<String, Object> extras = map(intent.get("extras"));
    assertEquals(Map.of("type", "string", "value", "a b"), extras.get("name"));
    assertEquals(Map.of("type", "int", "value", 3), extras.get("n"));
    assertEquals(Map.of("type", "long", "value", 5000000000L), extras.get("big"));
    assertEquals(Map.of("type", "boolean", "value", true), extras.get("on"));
    assertEquals(Map.of("type", "float", "value", 1.5), extras.get("f"));
    assertEquals(Map.of("type", "uri", "value", "content://x/1"), extras.get("u"));
    assertEquals(Map.of("type", "string[]", "value", List.of("a", "b,c")), extras.get("list"));
    assertEquals(Map.of("type", "int[]", "value", List.of(1, 2)), extras.get("nums"));
    assertEquals("null", map(extras.get("gone")).get("type"));
  }

  @Test
  public void amBroadcastAndServices() {
    Map<String, Object> b =
        t("am broadcast -a com.x.PING -n com.x/.Receiver --receiver-foreground --user 0");
    assertEquals("broadcast", b.get("cmd"));
    Map<String, Object> intent = map(b.get("intent"));
    assertEquals("com.x.PING", intent.get("action"));
    assertEquals(List.of("FLAG_RECEIVER_FOREGROUND"), intent.get("flags"));
    assertEquals("startService", t("am start-foreground-service -n com.x/.S").get("cmd"));
    assertEquals("stopService", t("am stopservice -n com.x/.S").get("cmd"));
  }

  @Test
  public void debugSurfaceBroadcastsBecomeTheCarouselsCommand() {
    // What Android Studio's Wear tile run configuration sends.
    Map<String, Object> add =
        t(
            "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile"
                + " --ecn component com.x/.MyTile");
    assertEquals("debugSurface", add.get("cmd"));
    assertEquals("add-tile", add.get("operation"));
    assertEquals("com.x/.MyTile", add.get("component"));
    Map<String, Object> show =
        t(
            "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation show-tile"
                + " --ei index 2");
    assertEquals("show-tile", show.get("operation"));
    assertEquals(2, show.get("index"));
  }

  @Test
  public void amForceStop() {
    Map<String, Object> c = t("am force-stop com.x");
    assertEquals("forceStop", c.get("cmd"));
    assertEquals("com.x", c.get("package"));
  }

  @Test
  public void amKillIsABackgroundKillTheTaskComesBackFrom() {
    Map<String, Object> c = t("am kill --user 0 com.x");
    assertEquals("kill", c.get("cmd"));
    assertEquals("com.x", c.get("package"));
  }

  @Test
  public void amRejectsWhatItCannotDo() {
    assertThrows(AdbShell.UnsupportedException.class, () -> t("am start --start-profiler f x/.A"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("am start"));
    assertThrows(
        AdbShell.UnsupportedException.class, () -> t("am start --ei n notanumber -n x/.A"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("am instrument x"));
  }

  // pm

  @Test
  public void pmGrantRevoke() {
    Map<String, Object> grant = t("pm grant com.x android.permission.CAMERA");
    assertEquals("permissions", grant.get("cmd"));
    assertEquals("com.x", grant.get("package"));
    assertEquals(List.of("android.permission.CAMERA"), grant.get("grant"));
    Map<String, Object> revoke = t("pm revoke --user 0 com.x POST_NOTIFICATIONS");
    assertEquals(List.of("android.permission.POST_NOTIFICATIONS"), revoke.get("deny"));
    assertEquals(true, t("pm reset-permissions").get("reset"));
  }

  @Test
  public void pmOthers() {
    assertEquals("clearData", t("pm clear com.x").get("cmd"));
    assertEquals("package", t("pm path com.x").get("cmd"));
    assertEquals("package", t("dumpsys package com.x").get("cmd"));
    assertEquals("packages", t("pm list packages -3").get("cmd"));
    assertEquals("x", t("pm list packages x").get("filter"));
    assertEquals("permissions", t("pm list permissions -g").get("cmd"));
    assertEquals("packages", t("cmd package list packages").get("cmd"));
  }

  // input

  @Test
  public void input() {
    assertEquals(Map.of("cmd", "tap", "x", 10.0, "y", 20.5), t("input touchscreen tap 10 20.5"));
    Map<String, Object> swipe = t("input swipe 1 2 3 4 500");
    assertEquals("swipe", swipe.get("cmd"));
    assertEquals(500L, swipe.get("durationMs"));
    assertEquals(800L, t("input draganddrop 1 2 3 4").get("holdMs"));
    assertEquals("hello world", t("input text hello%sworld").get("text"));
    assertEquals("hello world", t("input text 'hello world'").get("text"));
    assertEquals("BACK", t("input keyevent back").get("key"));
    assertEquals(4, t("input keyevent 4").get("key"));
    Map<String, Object> keys = t("input keyevent --longpress KEYCODE_POWER 66");
    assertEquals(List.of("KEYCODE_POWER", 66), keys.get("key"));
    assertEquals(true, keys.get("longPress"));
  }

  // settings, content

  @Test
  public void settings() {
    Map<String, Object> put = t("settings put global animator_duration_scale 0");
    assertEquals("settings", put.get("cmd"));
    assertEquals("put", put.get("op"));
    assertEquals("global", put.get("namespace"));
    assertEquals("animator_duration_scale", put.get("key"));
    assertEquals("0", put.get("value"));
    Map<String, Object> get = t("settings --user 0 get system time_12_24");
    assertEquals("get", get.get("op"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("settings get vendor x"));
  }

  @Test
  public void content() {
    Map<String, Object> q =
        t("content query --uri content://com.x.provider/items --projection _id:title"
            + " --where \"title='a b'\" --sort _id");
    assertEquals("content", q.get("cmd"));
    assertEquals("query", q.get("op"));
    assertEquals("content://com.x.provider/items", q.get("uri"));
    assertEquals(List.of("_id", "title"), q.get("projection"));
    assertEquals("title='a b'", q.get("selection"));
    Map<String, Object> insert =
        t("content insert --uri content://x/items --bind title:s:a:b --bind n:i:3 --bind z:n");
    Map<String, Object> values = map(insert.get("values"));
    assertEquals(Map.of("type", "string", "value", "a:b"), values.get("title"));
    assertEquals(Map.of("type", "int", "value", 3), values.get("n"));
    assertEquals("null", map(values.get("z")).get("type"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("content query"));
  }

  // secondary displays

  @Test
  public void aDisplayIdRoutesStartsInputAndScreenshots() {
    Map<String, Object> start = t("am start --display 1 -n com.x/.Second");
    assertEquals("startActivity", start.get("cmd"));
    assertEquals(1, start.get("display"));
    assertEquals(2, t("input -d 2 tap 10 20").get("display"));
    assertEquals("tap", t("input -d 2 tap 10 20").get("cmd"));
    assertFalse(t("input tap 10 20").containsKey("display"));
    assertEquals(1, t("screencap -d 1 -p > tv.png").get("display"));
    // A service has no display.
    assertFalse(t("am startservice --display 1 -n com.x/.S").containsKey("display"));
  }

  // display, network, dumpsys, logcat

  @Test
  public void displayAndNetwork() {
    assertEquals("+night", t("cmd uimode night yes").get("value"));
    assertEquals("+notnight", t("cmd uimode night no").get("value"));
    assertEquals("1080x2400", t("wm size 1080x2400").get("size"));
    assertEquals(420, t("wm density 420").get("density"));
    assertEquals("reset", t("wm size reset").get("size"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("wm size big"));
    assertEquals(false, t("svc wifi disable").get("wifi"));
    assertEquals(true, t("cmd connectivity airplane-mode enable").get("airplane"));
    assertEquals(false, t("svc data disable").get("data"));
    assertEquals("disable", t("svc bluetooth disable").get("op"));
    assertEquals("bluetooth", t("cmd bluetooth_manager enable").get("cmd"));
    assertEquals(true, t("cmd netpolicy set restrict-background true").get("dataSaver"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("svc power stayon true"));
  }

  @Test
  public void wifi() {
    assertEquals("wifi", t("cmd wifi status").get("cmd"));
    assertEquals("scan", t("cmd wifi start-scan").get("op"));
    Map<String, Object> connect = t("cmd wifi connect-network Cafe wpa2 secret");
    assertEquals("connect", connect.get("op"));
    assertEquals("Cafe", connect.get("ssid"));
    assertEquals(true, t("cmd wifi set-wifi-enabled enabled").get("wifi"));
    assertEquals("wifi", t("dumpsys wifi").get("cmd"));
    assertEquals("bluetooth", t("dumpsys bluetooth_manager").get("cmd"));
  }

  @Test
  public void dumpsys() {
    assertEquals("activities", t("dumpsys activity activities").get("cmd"));
    assertEquals("activities", t("dumpsys activity top").get("cmd"));
    assertEquals("services", t("dumpsys activity services com.x").get("cmd"));
    assertEquals("notifications", t("dumpsys notification --noredact").get("cmd"));
    assertEquals("info", t("dumpsys window displays").get("cmd"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("dumpsys meminfo"));
  }

  @Test
  public void logcat() {
    Map<String, Object> dump = t("logcat -d -s MyTag OtherTag:W");
    assertEquals("dump", dump.get("op"));
    assertEquals("S", dump.get("default"));
    assertEquals(Map.of("MyTag", "V", "OtherTag", "W"), dump.get("filters"));
    assertEquals("follow", t("logcat *:E").get("op"));
    assertEquals("E", t("logcat *:E").get("default"));
    assertEquals("clear", t("logcat -c").get("op"));
    Map<String, Object> tail = t("logcat -t 100 -v threadtime");
    assertEquals("dump", tail.get("op"));
    assertEquals(100, tail.get("tail"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("logcat Tag:X"));
  }

  // files, monkey, getprop

  @Test
  public void runAs() {
    Map<String, Object> ls = t("run-as com.x ls -R shared_prefs");
    assertEquals("files", ls.get("cmd"));
    assertEquals("list", ls.get("op"));
    assertEquals("com.x", ls.get("package"));
    assertEquals(true, ls.get("recursive"));
    Map<String, Object> cat = t("run-as com.x cat shared_prefs/p.xml");
    assertEquals("read", cat.get("op"));
    Map<String, Object> pull = t("run-as com.x cat databases/app.db > app.db");
    assertEquals("pull", pull.get("op"));
    assertEquals("app.db", pull.get("to"));
    assertEquals("delete", t("rm -rf /data/data/com.x/cache").get("op"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("run-as com.x sqlite3 db"));
  }

  @Test
  public void monkeyAndGetprop() {
    Map<String, Object> monkey = t("monkey -p com.x -c android.intent.category.LAUNCHER 1");
    assertEquals("launch", monkey.get("cmd"));
    assertEquals("com.x", monkey.get("package"));
    assertThrows(AdbShell.UnsupportedException.class, () -> t("monkey -p com.x 500"));
    assertEquals("ro.build.version.sdk", t("getprop ro.build.version.sdk").get("name"));
    Map<String, Object> dump = t("uiautomator dump /sdcard/window.xml");
    assertEquals("hierarchy", dump.get("cmd"));
    assertTrue((Boolean) dump.get("flat"));
  }

  @Test
  public void devicePaths() {
    // Relative names land in the app's data directory, where run-as and pull look for them.
    assertTrue(AdbShell.isDevicePath("foo.png"));
    assertTrue(AdbShell.isDevicePath("files/a.txt"));
    assertTrue(AdbShell.isDevicePath("/sdcard/s.png"));
    assertTrue(AdbShell.isDevicePath("/storage/emulated/0/s.png"));
    assertTrue(AdbShell.isDevicePath("/data/data/com.x/files"));
    assertTrue(AdbShell.isDevicePath("/data/local/tmp/x"));
    assertFalse(AdbShell.isDevicePath("/tmp/out.png"));
    assertFalse(AdbShell.isDevicePath("/sdcardx/out.png"));
    Map<String, Object> relative = t("screencap -p foo.png");
    assertEquals("foo.png", relative.get("path"));
    assertEquals(true, relative.get("devicePath"));
  }

  @Test
  public void unknownCommandsSaySo() {
    AdbShell.UnsupportedException e =
        assertThrows(AdbShell.UnsupportedException.class, () -> t("reboot"));
    assertTrue(e.getMessage(), e.getMessage().contains("reboot"));
  }
}
