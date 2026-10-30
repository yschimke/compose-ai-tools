# Running an APK in the Robolectric simulator

`compose-preview launch <app.apk>` runs an Android app in Robolectric's simulator: a desktop
window showing the app, driven by mouse and keyboard, or — with `--control stdio` — a JSON-lines
control channel an agent drives instead. No emulator, no device, no `adb`.

```
compose-preview launch app-debug.apk                               # a window
compose-preview launch app-debug.apk --control stdio               # a window an agent drives
compose-preview launch app-debug.apk --control stdio --headless    # the agent, no window
compose-preview launch app-debug.apk --prepare                     # convert and cache only
compose-preview launch app-debug.apk --display 1280x720,name=TV    # with an external display
```

With `--control stdio` the window stays up, so a person can watch the agent work: every tap
the agent sends is drawn as a ring (filled for a long press), every swipe as a trail, and the
command along the bottom of the window and in its title. The overlay lives on the Swing window's
glass pane, not in the Android display, so the agent's own screenshots never contain it, and the
person's clicks still reach the app. `--hide-agent` turns it off; `--headless` drops the window
(the default when there is no display, as in CI or a cloud sandbox).

It needs Java 21+ for the simulator JVM (`--java <jdk>`, or `COMPOSE_PREVIEW_SIMULATOR_JAVA`; the
CLI itself still runs on 17) and an Android SDK platform's `android.jar` (`ANDROID_HOME`, or
`local.properties` `sdk.dir`).

## What Robolectric already has

Robolectric ships the window half itself: the `simulator` module (beta since 4.15, on both
`master` and `google`, published as `org.robolectric:simulator`) and a `simulator-gradle-plugin`
that adds a `simulate<Variant>` task to an app module. `SimulatorMain <apk> [extra jars]` builds a
sandbox, loads the APK as its *resource* APK, starts the launcher activity, and runs a loop that
paints `UiAutomation.takeScreenshot()` into a Swing panel and turns mouse and keys into injected
events. Its `pluginapi.RemoteControl` (found by `ServiceLoader`, ranked by `@Priority`) is called on
every loop turn, on the main thread — the hook this command builds on.

What it does not do, and this command adds:

- **Read code from an APK.** The APK supplies resources and the binary manifest only; the
  Gradle plugin passes the unit-test classpath as JVM classes. Here the APK's dex is converted.
- **Navigate.** Under Robolectric `startActivity` / `startService` are recorded, not acted on, so a
  simulated app stops at its first screen.
- **Remote control.** The default `RemoteControl` is a no-op; Google's internal one is not
  published.

## How it works

```
compose-preview launch app.apk
 ├─ ApkClassConverter (own JVM, lib-dex2jar/)      APK dex ─► classes.jar   [cached by APK sha256]
 └─ simulator JVM (Java 21+)
     classpath: lib-simulator/  (Robolectric simulator + :simulator-host)
                lib-daemon-android/  (Robolectric 4.x + native graphics; the Android daemon sidecar)
                android.jar
     LaunchMain ─► SimulatorMain app.apk classes.jar
                     └─ SimulatorControl (RemoteControl)
                          ├─ Navigator   app startActivity/startService/stopService → real lifecycle
                          └─ Session     --control stdio commands
```

**Converting dex.** `ApkClassConverter` runs [dex2jar](https://github.com/ThexXTURBOXx/dex2jar)
over every `classes*.dex`, with two repairs of its own:

- an integer comparison with the zero on the left (`if-le 0, x`) is mirrored before code
  generation, which dex2jar gets backwards: it loads `x` and keeps the operator, so `0 <= x`
  became `x <= 0`. Compose's `MutableVector.contains` has that shape, and Now in Android's UI froze
  on the `ArrayIndexOutOfBoundsException` that followed;
- a class dex2jar cannot translate with debug info is translated again without it (Now in
  Android has one).

Then an ASM pass makes the output acceptable to a *verifying* JVM:

- static and private interface calls become interface method references (dex does not record the
  owner's kind, so dex2jar writes class `Methodref`s, which fail every Kotlin `$default` call with
  `IncompatibleClassChangeError`);
- stack-map frames and `max_stack` are computed from scratch, resolving supertypes from the APK,
  then `android.jar`, then the JDK. dex2jar writes no frames and undercounts `max_stack` around
  exception handlers; with verification simply switched off, the first GC that builds an oop map
  for such a frame crashes the JVM;
- protected members become public. The JVM lets another package's subclass reach a protected
  member only through its own type, ART does not check, and shrunk code (Play Services') relies
  on it.

On the sample app that is 21,870 classes, 1,818 interface calls fixed, 0 failed methods, in about
40 seconds; ASM's `BasicVerifier` passes every one of its 142,691 methods.

**The cache** lives under `<cache>/composeai/simulator/<apk sha256>/<converter stamp>/`. The APK is
keyed by content and the stamp by the converter's jars and the platform jar, so a rebuilt APK or a
newer converter converts again, and anything else is a cache hit (about 0.25 s). The eight most
recently used APKs are kept. A launch from a warm cache reaches its first frame in about 7 seconds.

**Navigation.** `Navigator` drains Robolectric's recorded intents every loop turn and does what the
system would: activities go onto a back stack through `ActivityController` (the one below is paused
and stopped, so its window is neither drawn nor touchable), `setResult` reaches a
`startActivityForResult` caller, `CLEAR_TOP` / `SINGLE_TOP` / `CLEAR_TASK` are honored, a finished
activity resumes the one below, and services are created, started, bound and destroyed through
`ServiceController` (including `stopSelf`). The launcher activity takes the same path, so the stack
has one owner.

**Startup** follows a device's order, through a Robolectric test environment of our own: the
manifest's content providers are created after `attachBaseContext()` and before
`Application.onCreate()`, so `androidx.startup` (WorkManager, OkHttp's platform) is ready when
`onCreate()` uses it. Before that, three things a device has and Robolectric does not are filled in:

- the manifest's `appComponentFactory`, which Robolectric reads from a source manifest and finds
  unset in an APK, comes from the framework's parse of the APK. The application, its content
  providers, and every activity, service and manifest receiver `Navigator` creates are built
  through that factory, as dependency-injected apps need (Cadence's Metro factory constructs its
  activities);
- the process name, unset for an APK too, defaults to the package as on a device: WorkManager
  compares it with its own to decide it may run work at all;
- a launcher declared as an `<activity-alias>` starts its `targetActivity`;
- a validated Wi-Fi network: the active network gets internet capabilities and each registered
  `NetworkCallback` is told it is available, so offline banners clear and `CONNECTED` work runs;
- a binder for every manifest service, so the app's own `bindService` connects; the service is
  created and bound when the app first calls the binder. Robolectric matches a bind by its whole
  intent, so each bind's own intent is registered as the app makes it, resolved by component as
  on a device (Firebase Sessions binds with an action of `"0"`).

**Classes** come from the APK first, except Guava, which Robolectric's own code links against: an
app's older copy is not served to it (Now in Android's lacks `toImmutableList`). A platform class
the simulated SDK lacks comes from the SDK's `android.jar` stub, the newest platform installed:
apps reference newer platform classes behind `SDK_INT` checks, which ART leaves unresolved and the
JVM's verifier does not.

## Form factors

`launch` reads the APK's binary manifest and its dex for the device it was built for, and picks the
display and the input that device has. `--form-factor phone|wear|glimmer|xr` overrides the
detection, `--qualifiers` the display.

| Form factor | Detected by | Display | Input beyond touch |
| --- | --- | --- | --- |
| phone | anything else | `w360dp-h640dp-hdpi` | |
| wear | a required `<uses-feature android:name="android.hardware.type.watch">` | `w227dp-h227dp-round-watch-xhdpi`, 454×454, drawn round | `rotary` (the crown) |
| glimmer | an activity whose `requiredDisplayCategory` is `xr_projected`, or code referencing `androidx.xr.glimmer` | `w960dp-h720dp-land-mdpi`, the AI-glasses display the Glimmer previews use (30 pixels per degree at density 1) | |
| xr | a `<uses-feature android:name="android.software.xr.api.*">`, or code referencing `androidx.xr.compose` / `androidx.xr.scenecore` | `w1024dp-h720dp-land-mdpi`, a Home Space panel | |

**Wear.** The display is Android Studio's `wearos_large_round`, with the `round` and `watch`
qualifiers, so `Configuration.isScreenRound()` and the `watch` UI mode read as on a watch and Wear
Compose lays out for a round screen. As the preview renderer does for a round `@Preview`, a
`screenshot` is cropped to the inscribed circle, transparent outside it (`"square":true` keeps the
corners), and the window paints the corners black. `rotary` sends the crown's rotary-encoder
`ACTION_SCROLL` events to the focused view, which is what `rotaryScrollable` (Wear's
`ScalingLazyColumn` and `TransformingLazyColumn`) scrolls on: Robolectric's `UiAutomation` would
deliver them as touches.

**Glimmer.** Jetpack Compose Glimmer is plain Compose: the app runs as is, on the glasses display, a
pure-black additive canvas, and its cards and list items answer `tap`. When the app declares an
activity for the projected display, as a glasses app beside its phone launcher does, that activity
is started instead of the launcher (on a device the phone app sends it to the glasses); otherwise,
as with `--form-factor glimmer` on an app without one, the launcher is. The glasses' touchpad is not
simulated yet: Glimmer navigates by moving focus, and in the simulator neither a touch-navigation
stroke (`SOURCE_TOUCH_NAVIGATION`) nor a Tab key moved focus into a Glimmer list; the window's
Compose view never took input focus.

**XR.** An Android XR app runs in Home Space, the way it runs on a phone or on a headset before it
asks for Full Space: `LocalSpatialCapabilities` reports no spatial UI, so the app composes its 2D
branch, and spatial UI takes its 2D fallback (an `Orbiter` renders inline at the panel's edge). With `androidx.xr.compose` 1.0.0-beta02 that fallback,
`Orbiter` included, composes with no XR runtime on the classpath.

## The control channel

With `--control stdio`, each line on stdin is one JSON command and each line on stdout is one JSON
object; everything else the app and Robolectric print goes to stderr. Closing stdin ends the
simulator.

```
→ {"id":1,"cmd":"tap","text":"Open Now Playing"}
← {"event":"activity","state":"resumed","activity":"com.example.NowPlayingActivity",...}
← {"id":1,"ok":true,"result":{"x":183.5,"y":348,"node":{...}}}
→ {"id":2,"cmd":"screenshot","path":"/tmp/now-playing.png"}
← {"id":2,"ok":true,"result":{"width":540,"height":960,"path":"/tmp/now-playing.png"}}
```

Replies are `{"id":…,"ok":true,"result":{…}}` or `{"id":…,"ok":false,"error":"…"}`; an exception
the app throws while handling a command becomes an error reply, and the simulator keeps running.
Events arrive unasked: `ready` (once, with `info`), `activity` (`resumed` / `newIntent` /
`destroyed`, or `empty` when the last one finishes), `service`, `providers`, `permissions`, `log`
(while `logcat` follows), and `error` for an intent the app sent that nothing handles.

Commands run in order. Those that change the UI answer after `settleMs` of simulated time (default
250), so a `tap` followed by a `screenshot` sees the result; any command takes `settleMs`.

| Command | Arguments | Does |
| --- | --- | --- |
| `info` | | package, version, SDK, form factor, qualifiers, `round`, display size, `display` (as below), top activity |
| `ping` | | uptime |
| `wait` | `ms` (or `settleMs`) | lets the app run |
| `screenshot` | `path`?, `display`? (an id, or `"all"`), `square`? | PNG to `path`, else base64 in `png`; `"all"` writes one per display, `<path>-display<id>.png` beside `path`; a round display's is cropped to the circle unless `square` |
| `hierarchy` | `flat`?, `path`? (JSON written there) | windows → views and Compose semantics (via accessibility nodes): `cls`, `text`, `desc`, `resourceId`, `hint`, `state`, `bounds`, `flags` |
| `find` | selector | matching nodes |
| `tap` | `x`,`y` or selector, `index`? | touch down/up at the point or the node's center |
| `longPress` | as `tap`, `durationMs`? | |
| `swipe` | `x1`,`y1`,`x2`,`y2`, `durationMs`?, `steps`?, `holdMs`? | `holdMs` holds still first, as a drag starts |
| `rotary` | `delta`? (-1), `count`? (1), `intervalMs`? (20) | turns a watch's crown: rotary-encoder `ACTION_SCROLL`s to the focused view; negative scrolls down. The scroll animates: `wait` a second or two before a `screenshot` |
| `key` | `key` (`"BACK"`, `"ENTER"`, a code, or an array of them), `longPress`? | key down/up |
| `back` | | the Back key — the app's own back handling decides |
| `text` | `text` | types into the focused field |
| `launch` | `display`? | starts the launcher activity |
| `forceStop` | | destroys every activity and service and cancels notifications (see [adb](#adb-equivalents)) |
| `clearData` | | `forceStop`, deletes the app's data and external files, resets permissions |
| `finish` | `display`? | finishes the top activity without a key press |
| `activities` | | the back stack, each activity with its `display` |
| `startActivity` | `intent`, `forceStop`?, `display`? | deep links resolve through the manifest's real intent filters; `display` launches there, as `ActivityOptions.setLaunchDisplayId` does |
| `startService` / `stopService` | `intent` | |
| `bindService` / `unbindService` | `intent` | `onBind`, reports the binder's interface |
| `services` | | running services, started/bound state, foreground notification |
| `broadcast` | `intent` | runtime-registered and manifest receivers; reports who received it |
| `notifications` | | what the app posted |
| `query` | `uri`, `projection`?, `selection`?, `args`?, `sort`?, `limit`? | a content provider query |
| `content` | `op` (`query` / `insert` / `update` / `delete`), `uri`, `values`?, as `query` | provider reads and writes; `values` is `{"col":value}` or `{"col":{"type":"long","value":1}}` |
| `settings` | `op` (`get` / `put` / `delete`), `namespace` (`system` / `secure` / `global`), `key`, `value`? | `Settings.*`; a put the system acts on is applied (below) |
| `network` | `wifi`?, `airplane`? | switches the simulated Wi-Fi network off and on; returns the state |
| `logcat` | `op` (`dump` / `clear` / `follow` / `stop`), `filters`? (`{tag: level}`), `default`? (level), `regex`?, `tail`? | the app's `android.util.Log`, as `threadtime` lines; `follow` streams `log` events |
| `files` | `op` (`list` / `read` / `write` / `mkdir` / `delete` / `pull` / `push`), `path`, `to`? / `from`?, `text`? / `base64`?, `recursive`? | the app's storage; every answer carries `hostPath` |
| `getprop` | `name`? | `ro.build.*`, `ro.product.*` and a few more, from `Build` |
| `package` / `packages` | `filter`? | version, APK path, data dir, permission state / the one package |
| `shell` | `command` (an adb shell line) or `args` (its argv) | runs the [equivalent command](#adb-equivalents); replies `{command, result}` |
| `components` | | the manifest's activities, services, receivers, providers |
| `permissions` | `policy`? (`grant` / `deny`), `grant`? / `deny`? (permission names), `reset`? | how the app's runtime permission requests are answered; `grant` / `deny` also apply now (a deny revokes). Returns the policy, the per-permission answers, and the state of each requested permission |
| `qualifiers` | `value` (e.g. `+night`, `+fr-rFR`) | a configuration change on the top activity |
| `resize` | `widthDp`, `heightDp`, `density`? (`hdpi`, `420dpi`, or a number), `posture`? | resizes the display, as dragging a resizable emulator or a desktop window does |
| `device` | `value` (`phone`, `foldable`, `foldable-folded`, `tablet`, `desktop`), `density`? | switches to a reference device's size and fold, upright |
| `rotate` | `value`? (`0`/`90`/`180`/`270`, `portrait`, `landscape`; default a quarter turn) | turns the display; the fold turns with it |
| `posture` | `value` (`none`, `flat`, `half-opened`, `book`, `tabletop`), `orientation`? (`vertical` / `horizontal`), `hingeDp`? | the fold the app's `WindowInfoTracker` reports |
| `fontScale` | `value` | the system font size, as a configuration change |
| `display` | `size`? (`"WxH"` px or `reset`), `density`? (dpi or `reset`) | without arguments: size, density, orientation, rotation, font scale, night, locale, fold and qualifiers; with them, as `wm size` / `wm density` |
| `displays` | `action`? (`list`, `add`, `remove`, `change`), `display`, `name`?, `type`? (`external` / `virtual` / `rear`), `widthDp`?, `heightDp`?, `density`?, `qualifiers`? | the displays, and secondary ones plugged in, unplugged and resized; see [More than one display](#more-than-one-display) |
| `battery` | `level`?, `plugged`? (`ac` / `usb` / `wireless` / `none`) or `charging`?, `powerSave`? | `BatteryManager`, the sticky `BATTERY_CHANGED`, and the power connected / disconnected, low / okay (at 15% / 20%, as `BatteryService` does) and power-save broadcasts |
| `location` | `lat`, `lng`, `accuracy`?, `altitude`?, `bearing`?, `speed`? | a fix from the platform's GPS, network and fused providers |
| `timezone` | `value` (`Asia/Tokyo`) | the default time zone, and `TIMEZONE_CHANGED` |
| `quit` | | exits |

### Display, fold and device state

The emulator's extended controls, over the same channel. Every display command is a
configuration change made as a device makes one: Robolectric's display and resources change, then
the top activity gets `onConfigurationChanged` if its manifest `configChanges` covers what changed
(`screenSize|smallestScreenSize|screenLayout|orientation` for a resize), and is recreated
otherwise. A resize derives the smallest width, the `small`…`xlarge` and `long` buckets and the
orientation from the new size, as the framework does (Robolectric's `+` merge would keep the old
ones); locale, night mode and density carry over. Each reply is the `display` object.

The presets are Android Studio's reference devices: `phone` 411×891dp, `foldable` 673×841dp open
with a flat vertical fold, `foldable-folded` its 411×891dp outer screen with none, `tablet`
1280×800dp, `desktop` 1920×1080dp at mdpi. The others use the launch's density (the window
draws one device pixel per screen pixel). `launch --device <preset>` starts on one.

**Folds** reach the app through Jetpack WindowManager, the way a device's do. A device's system
image ships the WindowManager *extensions* (`androidx.window.extensions`), which apps compile
against and do not bundle; Robolectric has none, so an app's `WindowInfoTracker` found nothing and
reported no display features. `:simulator-host` carries its own extensions at vendor API level 2
(`Foldables`), which the app's `androidx.window` finds and validates by reflection as on a device.
They report one `FoldingFeature` across the middle of the window — `FLAT` or `HALF_OPENED`,
vertical or horizontal, a zero-width `FOLD` or, with `hingeDp`, a `HINGE` band (a dual-screen
device) — or none. So Compose's `currentWindowAdaptiveInfo()` sees the posture (`isTabletop`) and
the hinge list, `NavigationSuiteScaffold` moves to a bottom bar in tabletop, and Material 3's pane
scaffolds avoid a separating hinge. Activity embedding and rear-display window areas are not
implemented; the library finds them missing and reports them unsupported.

### More than one display

An app can use more than one display, as a phone with a monitor or TV attached does. `launch
--display <spec>` (repeatable) connects one before the app starts — a size in dp with an optional
density, then `name=` and `type=`: `--display 1280x720@mdpi,name=TV`, `--display rear,411x891`
— and `displays` adds, changes and removes them while it runs:

```
→ {"cmd":"displays","action":"add","name":"TV","widthDp":960,"heightDp":540,"density":"mdpi"}
← {"event":"display","state":"added","display":{"id":1,"name":"TV","type":"external",...}}
→ {"cmd":"tap","textContains":"tap the presentation","display":1}
→ {"cmd":"screenshot","display":"all","path":"/tmp/shot.png"}   # shot.png, shot-display1.png
→ {"cmd":"startActivity","intent":{"component":".SecondActivity"},"display":1}
→ {"cmd":"displays","action":"change","display":1,"widthDp":640,"heightDp":360,"density":"xhdpi"}
→ {"cmd":"displays","action":"remove","display":1}
```

The app hears of each one as from a device's display manager: `DisplayListener.onDisplayAdded`,
`onDisplayChanged` and `onDisplayRemoved`. An `external` display (the default type; 960×540dp at
mdpi unless given) and a `virtual` one are presentation displays — `getDisplays(
DISPLAY_CATEGORY_PRESENTATION)` finds them, and an `android.app.Presentation`
shown on one draws there; a `rear` display is a built-in one flagged as the rear screen. Each
display draws and takes input on its own:

- **Pictures.** `screenshot` takes `display`, or `"all"`. Robolectric composes every window into
  one picture whatever its display, so a presentation would cover the phone; `:simulator-host`
  replaces its `UiAutomation` shadow (`SimulatorShadowUiAutomation`) so the default display's
  picture — the one its window paints — holds only that display's windows, and `Displays` draws
  the others.
- **Windows.** With a screen, each secondary display gets a Swing window of its own, titled with
  its name and display id, beside the phone's. It takes the mouse and keyboard; closing it
  unplugs the display.
- **Input.** `tap`, `longPress`, `swipe`, `key`, `back`, `text`, `hierarchy`, `find` and `finish`
  take `display`, and act on that display's windows alone: a touch goes to the top-most window
  under it there, a key to that display's focused window. Without `display`, the default display.
- **Activities.** An activity started with `ActivityOptions.setLaunchDisplayId` — by the app, or
  by `startActivity` / `launch` with `display` — goes on that display, above that display's own top
  activity and leaving the others resumed, as a device keeps a task stack per display. It gets that
  display's configuration (size, density, orientation, window bounds): Now in Android started on a
  1280×720dp display lays itself out with a navigation rail and list-detail panes while the phone
  stays on its bottom bar. `activities` and `displays` say which display each is on. When a display
  changes size, its activities get `onConfigurationChanged` if their manifest `configChanges` covers
  the change and are otherwise re-created there from their saved state; when it is removed, they
  finish.

A presentation window sits above the activities on its display, as on a device: an activity the
app launches onto a display it is presenting on stays under the presentation until the app
dismisses it. `Presentation` itself dismisses on a display size change, as the framework does.

A **selector** is any of `text` / `desc` (exact), `textContains` / `descContains` (case-insensitive),
`resourceId` (whole, or the part after `:id/` — a Compose `testTag` once the app sets
`testTagsAsResourceId`), `cls` (class-name suffix) and `clickable`; given inline or as
`"selector":{…}`.

An **intent** is `{"action","data","type","package","component","categories","flags","extras"}`.
`component` takes `pkg/cls`, `pkg/.Cls`, `.Cls` or a class name; `flags` an int or names
(`"NEW_TASK"`, `"CLEAR_TOP"`); an extra's JSON type picks its Java type, or
`{"type":"long","value":1}` names it (`string int long float double boolean uri component null
string[] int[]`).

### adb equivalents

`{"cmd":"shell","command":"am start -a android.intent.action.VIEW -d https://…"}` takes an `adb
shell` line and runs the control command it means, so an agent can use the adb it already knows. A
leading `adb [-s serial] shell` is dropped, and `adb exec-out`, `adb logcat`, `adb pull` and `adb
push` are understood too. The line is split as `sh` would (quotes, backslashes); pipes, `;`, `&&`
and redirection are refused — one command per line — except a trailing `> file` on `screencap`,
`uiautomator dump` and `run-as … cat`. The reply is `{"command":{…},"result":{…}}`: the translated
command, then its usual JSON result, not adb's text (commands whose adb output is a value, such as
`settings get`, `getprop`, `wm size` and `pm path`, add it as `output`).

Paths: `screencap`, `uiautomator dump`, `run-as`, `pull` and `push` take device paths. A relative
path, `/data/data/<pkg>/…` and `/data/user/0/<pkg>/…` are the app's data directory; `/sdcard/…` and
`/storage/emulated/0/…` its simulated external storage; `/data/local/tmp/…` a scratch directory.
All are directories on the host (Robolectric's temporary ones), returned as `hostPath`. Any other
absolute path given to `screencap` is a host path. Other packages' paths are refused.

| adb | Simulator | Notes |
| --- | --- | --- |
| `am start` / `start-activity` | `startActivity` | `-a -d -t -c -n -p -f`, `--es --esn --ez --ei --el --ef --ed --eu --ecn --esa --eia`, `--activity-*` / `--grant-*` flags, a trailing URI (with `VIEW`), component or package; `-W` is implicit; `-S` force-stops first; `--display <id>` starts it on that display |
| `am start-service` / `start-foreground-service` / `stopservice` | `startService` / `stopService` | |
| `am broadcast` | `broadcast` | runtime and manifest receivers; `--receiver-*` flags; `--user` and `--receiver-permission` ignored |
| `am force-stop` / `kill` | `forceStop` | partly: see below |
| `am stack list` | `activities` | |
| `pm grant` / `revoke` | `permissions` `grant` / `deny` | short names (`CAMERA`) take `android.permission.`; the answer also applies to later requests |
| `pm reset-permissions` | `permissions` `reset` | runtime permissions revoked, policy back to grant |
| `pm clear` | `clearData` | partly: see below |
| `pm list packages` / `pm path` / `pm dump` | `packages` / `package` | the one installed package |
| `pm list permissions` | `permissions` | the app's requested permissions and their state, not the device's |
| `input tap` / `swipe` / `text` / `keyevent` / `draganddrop` / `press` | `tap` / `swipe` / `text` / `key` / `swipe` with `holdMs` | a source word (`touchscreen`…) is accepted; `text` turns `%s` into a space; `keyevent --longpress`; `-d <id>` acts on that display |
| `settings get` / `put` / `delete` (`--user` ignored) | `settings` | puts applied: `global airplane_mode_on`, `global wifi_on` (network), `global animator_duration_scale` (`ValueAnimator`), `system font_scale` (configuration change), `system time_12_24` (`TIME_SET` broadcast); others are stored for the app to read |
| `content query` / `insert` / `update` / `delete` | `content` | `--uri --projection a:b --where --sort --bind name:type:value` (`s b i l f d n`) |
| `cmd uimode night yes` / `no` / `auto` | `qualifiers` `+night` / `+notnight` | |
| `wm size [WxH\|reset]` / `wm density [N\|reset]` | `display` | pixels, as on a device: a density change keeps the pixel size; `reset` restores the launch qualifiers (both) |
| `svc wifi enable` / `disable`, `cmd wifi set-wifi-enabled`, `cmd connectivity airplane-mode` | `network` | `onLost` / `onAvailable` to `NetworkCallback`s, `CONNECTIVITY_ACTION`; airplane mode also sets the setting and broadcasts `AIRPLANE_MODE` |
| `dumpsys activity [activities\|top\|services]`, `dumpsys notification`, `dumpsys window`, `dumpsys package`, `dumpsys connectivity` | `activities`, `services`, `notifications`, `info`, `package`, `network` | JSON, not dumpsys text |
| `logcat` `-d -c -s -t -e`, `TAG:LEVEL` / `*:LEVEL` | `logcat` | without `-d` / `-c` / `-t` it follows: `log` events until `{"cmd":"logcat","op":"stop"}`; `-v -b --pid` ignored; times are wall-clock, pid/tid are Robolectric's |
| `screencap -p [file]` | `screenshot` | PNG only; `-d <id>` captures that display (the simulator's display id, from `displays`) |
| `uiautomator dump [file]` | `hierarchy` `flat` | JSON nodes, not UI Automator XML |
| `run-as <pkg> ls [-R]` / `cat` / `rm [-r]` / `mkdir`; the same on `/data/data/<pkg>` paths | `files` | `cat … > file` pulls |
| `adb pull` / `adb push` | `files` `pull` / `push` | files or directories |
| `getprop [name]` | `getprop` | |
| `monkey -p <pkg> 1` | `launch` | only as a launcher; random events are refused |

**Partly:** a single-process simulator cannot kill the process. `am force-stop` destroys every
activity (top first) and service, and cancels the app's notifications; the `Application`, its
singletons, open databases and threads live on, so the next `launch` or `am start` starts
activities into a warm process rather than a cold one. `pm clear` does that, then deletes the data
and external-files directories and resets runtime permissions — but state already in memory
(a Room database's open connection, cached `SharedPreferences`, a DataStore) still holds the old
data. Robolectric's data directory is a fresh temporary one per launch, so for a truly clean start,
quit and launch again.

**Not supported**, with the reason:

| adb | Why |
| --- | --- |
| `install`, `uninstall`, `pm install` | the simulator runs the one APK it was launched with |
| `am instrument`, `am profile`, `am start -P/--start-profiler`, `am dumpheap` | no instrumentation runner, profiler or ART heap |
| `intent:` URIs, `--selector`, `--ela`/`--efa` array extras | not mapped; use `-a -d -n` and the scalar or `--esa`/`--eia` extras |
| `svc data`, `svc power`, `svc bluetooth` | the only simulated network is Wi-Fi; no power or radio model (Bluetooth is on, nothing in range) |
| `settings list` | Robolectric's settings store cannot be enumerated |
| `dumpsys meminfo` / `gfxinfo` / `battery` / others | no such services: one JVM, Robolectric's graphics |
| `input roll` / `motionevent`, `monkey` with events | not mapped; use `tap`, `swipe` and `key` |
| `reboot`, `root`, `sh` scripts, pipes, `&&` | no device to reboot; one command per line |
| `pm disable` / `enable` components | not mapped |
| `logcat -b crash/events/radio`, `bugreport` | there is one buffer: the app's `android.util.Log` |

## Constraints

What runs: apps whose UI is Compose or Views over the framework Robolectric shadows, with their
real `Application.onCreate()`. The sample app's Compose screens, `androidx.startup`, WorkManager
and its Room database start as they would on a device.

Two real apps, from their debug APKs: [Now in Android](https://github.com/android/nowinandroid)'s
demo flavor runs its Hilt graph, WorkManager sync and Room database, and every tab and Settings
work; [Confetti](https://github.com/joreilly/Confetti) starts with Firebase, Koin and Compose
Multiplatform resources on `--sdk 36` (its OkHttp needs API 36's classes) and runs its onboarding.
Home Assistant RemoteCompose (alias launchers), Cadence (Metro's `AppComponentFactory`) and
MeshCore run too. Renders are in
[`design/evidence/simulator-launch/`](design/evidence/simulator-launch/).

What does not, or not yet:

- **Native code.** `.so` files are built for Android; anything that needs its own JNI fails.
- **Google Play services, Maps, billing, DRM** — not present in Robolectric's framework; their
  APIs report `SERVICE_INVALID`. Firebase starts, and its backends answer as they would any
  unregistered client.
- **GPU content.** No `SurfaceView` / `TextureView` producer, video, camera or GL.
- **Bindings across processes.** An app's binding to its own service connects; one to another
  package's (Play services) does not.
- **Process and task model.** One process, one task (so `am force-stop` and `pm clear` are partial:
  see [adb equivalents](#adb-equivalents)); `launch` while running pushes a new launcher
  instance rather than bringing the task forward; `HOME` does nothing.
- **Content providers** start before `Application.onCreate()` as on a device; `--no-providers`
  skips them.
- **Runtime permissions** are answered when the app asks, with no dialog: granted, as a user
  tapping "Allow" would, unless the `permissions` command says to deny them (all, or by name).
  Each request is reported as a `permissions` event naming what was granted and denied; there is
  no "don't ask again".
- **System UI.** An intent only the system handles (the live-wallpaper picker Muzei opens, a
  share sheet, settings screens) reaches no activity and is reported as an `error` event.
- **Bluetooth.** The adapter is on with nothing in range: scans run and find no devices.
- **Network.** Reported as connected Wi-Fi; requests go out through the host JVM, its proxy and
  its trust store.
- **Split APKs and App Bundles** need merging into one APK first; protected APKs are out.
- **Fidelity.** Robolectric's native graphics, not a device's GPU: close, not pixel-identical. The
  window draws one device pixel per screen pixel, hence the `w360dp-h640dp-hdpi` default, and
  resizes to the display after each display command; a tablet or desktop at hdpi may be larger
  than the screen.
- **Device controls.** Secondary displays are separate screens (above); there is no multi-window
  or freeform on one display, and no activity embedding. A `rear` display is a display only:
  Jetpack WindowManager's `WindowAreaController` (rear display mode, dual screen) needs the
  extensions' `WindowAreaComponent`, which `Foldables` does not provide, so it reports them
  unsupported. Activities on a removed display finish rather than move to the default display. A configuration change reaches the top activity; activities below it in the back
  stack keep the old configuration until they are next shown, and are not recreated then. The
  fold is always at the middle of the window. Location reaches the platform `LocationManager`
  only: Play services' fused provider is absent. Time zone changes reach `java.util.TimeZone` and
  the broadcast; the wall clock itself follows Robolectric's simulated clock and cannot be set.
- **Wear.** No watch face, launcher or system tiles carousel; a Tile, complication or widget
  service is reachable only through `bindService`. Ambient mode is not entered (no
  `AmbientLifecycleObserver` callbacks). The Data Layer has no paired phone: `Wearable` APIs fail with
  `SERVICE_INVALID`, as Play services' other APIs do.
- **XR.** No Full Space: nothing renders a `Subspace`, `SpatialPanel` or 3D content, and a request
  to enter Full Space is not acted on. An app that calls `Session.create` itself gets no session:
  Jetpack XR's `*-testing` runtime fakes, which the preview renderer adds for XR previews, are not
  on the simulator's classpath (`--jar` can add them).
- **Glimmer.** No touchpad and no input focus (above): focus-driven navigation does not work;
  the display is shown as is, not composited onto a scene as the preview environment backdrops
  do, and there is no projection from a phone app.
- **Stack traces say `Unknown Source`** for app frames (dex2jar keeps line numbers, not the source
  file attribute).
- **SDK level.** One of Robolectric's supported levels (`--sdk`, default 35), fetched as
  `android-all-instrumented` on first use like any Robolectric run.
