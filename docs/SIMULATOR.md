# Running an APK in the Robolectric simulator

`compose-preview launch <app.apk>` runs an Android app in Robolectric's simulator: a desktop
window showing the app, driven by mouse and keyboard, or — with `--control stdio` — a JSON-lines
control channel an agent drives instead. No emulator, no device, no `adb`.

```
compose-preview launch app-debug.apk                               # a window
compose-preview launch app-debug.apk --control stdio               # a window an agent drives
compose-preview launch app-debug.apk --control stdio --headless    # the agent, no window
compose-preview launch app-debug.apk --prepare                     # convert and cache only
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
- a validated Wi-Fi network (or what `launch --network` names): `ConnectivityManager` answers
  from the simulator's own network model and each registered `NetworkCallback` is told what a
  device would tell it, so offline banners clear and `CONNECTED` work runs (see
  [Network, Wi-Fi and Bluetooth](#network-wi-fi-and-bluetooth));
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
| `screenshot` | `path`?, `square`? | PNG to `path`, else base64 in `png`; a round display's is cropped to the circle unless `square` |
| `hierarchy` | `flat`?, `path`? (JSON written there) | windows → views and Compose semantics (via accessibility nodes): `cls`, `text`, `desc`, `resourceId`, `hint`, `state`, `bounds`, `flags` |
| `find` | selector | matching nodes |
| `tap` | `x`,`y` or selector, `index`? | touch down/up at the point or the node's center |
| `longPress` | as `tap`, `durationMs`? | |
| `swipe` | `x1`,`y1`,`x2`,`y2`, `durationMs`?, `steps`?, `holdMs`? | `holdMs` holds still first, as a drag starts |
| `rotary` | `delta`? (-1), `count`? (1), `intervalMs`? (20) | turns a watch's crown: rotary-encoder `ACTION_SCROLL`s to the focused view; negative scrolls down. The scroll animates: `wait` a second or two before a `screenshot` |
| `key` | `key` (`"BACK"`, `"ENTER"`, a code, or an array of them), `longPress`? | key down/up |
| `back` | | the Back key — the app's own back handling decides |
| `text` | `text` | types into the focused field |
| `launch` | | starts the launcher activity |
| `forceStop` | | destroys every activity and service and cancels notifications (see [adb](#adb-equivalents)) |
| `clearData` | | `forceStop`, deletes the app's data and external files, resets permissions |
| `finish` | | finishes the top activity without a key press |
| `activities` | | the back stack |
| `startActivity` | `intent`, `forceStop`? | deep links resolve through the manifest's real intent filters |
| `startService` / `stopService` | `intent` | |
| `bindService` / `unbindService` | `intent` | `onBind`, reports the binder's interface |
| `services` | | running services, started/bound state, foreground notification |
| `broadcast` | `intent` | runtime-registered and manifest receivers; reports who received it |
| `notifications` | | what the app posted |
| `query` | `uri`, `projection`?, `selection`?, `args`?, `sort`?, `limit`? | a content provider query |
| `content` | `op` (`query` / `insert` / `update` / `delete`), `uri`, `values`?, as `query` | provider reads and writes; `values` is `{"col":value}` or `{"col":{"type":"long","value":1}}` |
| `settings` | `op` (`get` / `put` / `delete`), `namespace` (`system` / `secure` / `global`), `key`, `value`? | `Settings.*`; a put the system acts on is applied (below) |
| `network` | `mode`? (`wifi` / `cellular` / `ethernet` / `vpn` / `captive` / `offline`), `wifi`?, `data`?, `airplane`?, `metered`?, `cellular`? (`5g` / `lte` / `3g` / `2g`), `downKbps`? / `upKbps`?, `latencyMs`?, `kbps`?, `block`? (hosts), `dataSaver`? | the networks, radios and the app's real traffic ([below](#network-wi-fi-and-bluetooth)); returns the state |
| `wifi` | `op` (`status` / `scan` / `connect` / `disconnect` / `request`), `networks`?, `ssid`?, `bssid`?, `rssi`?, `answer`?, `policy`?, `requestId`? | access points in range, the one associated with, and the app's peer-to-peer requests |
| `bluetooth` | `op` (`status` / `enable` / `disable` / `request` / `advertise` / `found` / `remove` / `bond` / `unbond` / `notify` / `disconnect`), `address`?, `name`?, `rssi`?, `serviceUuids`?, `serviceData`?, `manufacturerData`?, `gatt`?, `characteristic`?, `value`?, `answer`?, `policy`? | the adapter, its enable dialog, peripherals in range and a GATT server behind each |
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

A **selector** is any of `text` / `desc` (exact), `textContains` / `descContains` (case-insensitive),
`resourceId` (whole, or the part after `:id/` — a Compose `testTag` once the app sets
`testTagsAsResourceId`), `cls` (class-name suffix) and `clickable`; given inline or as
`"selector":{…}`.

An **intent** is `{"action","data","type","package","component","categories","flags","extras"}`.
`component` takes `pkg/cls`, `pkg/.Cls`, `.Cls` or a class name; `flags` an int or names
(`"NEW_TASK"`, `"CLEAR_TOP"`); an extra's JSON type picks its Java type, or
`{"type":"long","value":1}` names it (`string int long float double boolean uri component null
string[] int[]`).

### Network, Wi-Fi and Bluetooth

The device's radios and what it is connected through, as an app sees them and as its traffic meets
them. `launch --network wifi|cellular|ethernet|vpn|captive|offline` (with `,airplane`, or
`airplane` alone) starts in a mode; `network` changes it at run time.

**Networks.** `ConnectivityManager` answers from the simulator's networks, each with consistent
transports, capabilities, link properties (`wlan0`, `rmnet_data0`, `eth0`, `tun0`) and legacy
`NetworkInfo`:

| Mode | Default network | Notes |
| --- | --- | --- |
| `wifi` | Wi-Fi: `INTERNET`, `VALIDATED`, `NOT_METERED`; its `WifiInfo` as transport info | the default |
| `cellular` | cellular, metered (no `NOT_METERED`); `TelephonyManager` data network type `LTE` (`NR`, `HSPAP`, `EDGE` for `cellular` `5g` / `3g` / `2g`), data state connected | needs `data` (mobile data) on and airplane mode off |
| `ethernet` | Ethernet, unmetered | stays in airplane mode |
| `vpn` | a VPN (`TRANSPORT_VPN` and Wi-Fi, no `NOT_VPN`) over a Wi-Fi network | a `NOT_VPN` request (the default `NetworkRequest`) sees the Wi-Fi network |
| `captive` | Wi-Fi behind a captive portal: `CAPTIVE_PORTAL`, never `VALIDATED`; no internet | |
| `offline` | none: `getActiveNetwork()` and `getActiveNetworkInfo()` are null | |

`metered` overrides metering (a metered hotspot, an unmetered plan); `downKbps` / `upKbps` override
the bandwidth estimates. A change reaches each callback as a device delivers it: a
default-network callback follows the default network, a `registerNetworkCallback` sees each network
its request is satisfied by (`canBeSatisfiedBy`) — so WorkManager's `UNMETERED` constraint, a
`NOT_METERED` request, waits on cellular and runs on Wi-Fi — and a `requestNetwork` gets the best
match. A network that appears is `onAvailable`, `onCapabilitiesChanged`, `onLinkPropertiesChanged`
and `onBlockedStatusChanged`; one that changes is `onCapabilitiesChanged`; one that goes is
`onLost`. A callback registered later hears the current state on the next cycle.
`CONNECTIVITY_ACTION` is sent when the default network changes, and `isActiveNetworkMetered`,
`getNetworkCapabilities` and `NetworkInfo` agree with the callbacks. `dataSaver` sets
`getRestrictBackgroundStatus` and sends `ACTION_RESTRICT_BACKGROUND_CHANGED`.

**Radios.** `wifi`, `data` and `airplane` are the device's switches, and `airplane_mode_on`,
`wifi_on`, `mobile_data` and `bluetooth_on` in `Settings.Global` follow them (a `settings put` of
any of them does the same). Airplane mode takes cellular away and turns Wi-Fi and Bluetooth off,
sends `AIRPLANE_MODE` and puts `TelephonyManager`'s service state at `STATE_POWER_OFF`; Wi-Fi or
Bluetooth can be turned back on while it lasts, and turning it off restores what was on before.

**Real traffic.** The app's own connections meet the simulated network. With no network, or one
without internet (`captive`), a connection to a host name fails with `UnknownHostException: Unable
to resolve host "…": No address associated with hostname`, one to an address with `ConnectException:
… ENETUNREACH (Network is unreachable)`, and connections already open are cut (`Software caused
connection abort`), so a pooled keep-alive connection does not carry a request through an outage.
The machine itself (loopback) stays reachable, except through a proxy the host JVM is configured
with. Online, `latencyMs` delays each connect and each response, `kbps` caps each socket's
throughput both ways (and the bandwidth estimates with it), and `block` refuses hosts (`api.x.com`,
`*.ads.net`) as if they did not resolve. Three `java.net` seams do this (`NetworkGate`): a
`ProxySelector` that answers `DIRECT` for an unreachable host, so a host JVM behind an HTTP proxy
still fails on the name; the JVM's `InetAddress` resolver, wrapped; and a `SocketImplFactory` around
the platform's sockets. Clients on `java.net.Socket` — OkHttp, `HttpURLConnection`, Ktor's OkHttp
and Android engines — are covered; NIO channels (Ktor CIO, `java.net.http.HttpClient`) and SOCKS
proxies see only the resolver. The reply's `traffic` counts what was `refused` and `cut`.

**Wi-Fi.** `WifiManager` reports the radio (`WIFI_STATE_CHANGED` when it changes), the access point
(SSID `AndroidWifi` unless `wifi connect` names another; `NETWORK_STATE_CHANGED` on each
association), and the scan results `wifi scan` puts in range (`SCAN_RESULTS_AVAILABLE`); `wifi
connect` associates with one, Wi-Fi becoming the network. The app's requests:

- a `requestNetwork` with a `WifiNetworkSpecifier` (Android 10+'s peer-to-peer connect), which a
  device shows as a system dialog: a `wifiRequest` event names the SSID or pattern, and it is
  answered by `wifi request` `policy` (`accept`, the default; `reject`; `ask` to hold it) or by
  `answer`. Accepted, the callback gets `onAvailable` for a local-only Wi-Fi network (no
  `INTERNET`) while the default network stays; rejected, `onUnavailable`. Releasing the callback
  disconnects it;
- `addNetworkSuggestions`, reported as a `wifiSuggestions` event; connecting to a suggestion that
  asked for it (`setIsAppInteractionRequired`) sends the app
  `WIFI_NETWORK_SUGGESTION_POST_CONNECTION`;
- the settings screens and panels (`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`, `ACTION_WIFI`,
  `WIFI_SETTINGS`, `WIFI_ADD_NETWORKS`, `BLUETOOTH_SETTINGS`, airplane mode, data usage…), reported
  as a `systemUi` event and, if started for a result, closed with `RESULT_CANCELED`.

**Bluetooth.** The adapter is on unless airplane mode or `bluetooth disable` turned it off; each
change sends `ACTION_STATE_CHANGED` through `TURNING_ON` / `TURNING_OFF`, including the app's own
`enable()`. `ACTION_REQUEST_ENABLE`, a dialog on a device, is reported as a `bluetoothRequest` event
and answered by `bluetooth request` `policy` (`accept`, the default; `reject`; `ask`) or `answer`,
the activity result following. In range:

- `bluetooth advertise` puts a BLE peripheral on air: its name, service UUIDs (16- or 128-bit),
  service and manufacturer data and TX power become real advertising bytes, parsed by the framework
  into a `ScanRecord`, and each `BluetoothLeScanner` scan whose filters match gets it
  (`onScanResult`, `onBatchScanResults` with a report delay, a `PendingIntent` scan's intent, or the
  old `startLeScan` callback) — now, and when a scan starts later, reported as a `bleScan` event;
- `bluetooth found` answers classic discovery: `startDiscovery()` sends `DISCOVERY_STARTED`,
  `ACTION_FOUND` for each such device, and `DISCOVERY_FINISHED` after 12 seconds;
- `bluetooth bond` adds to `getBondedDevices()` (`ACTION_BOND_STATE_CHANGED`).

A connectable peripheral with a `gatt` spec (`{"services":[{"uuid","characteristics":[{"uuid",
"properties":["read","write","writeNoResponse","notify","indicate"],"value"}]}]}`) is a GATT server:
`connectGatt` connects on the next cycle, `discoverServices` finds those services (each notifying
characteristic with its `2902` descriptor), a read answers with the value, and the app's writes,
reads, MTU requests and subscriptions are `gatt` events — the agent plays the peripheral, answering
with `bluetooth notify` (`onCharacteristicChanged`) or `disconnect`. A `connectGatt` to a device not
in range fails with status 133, as a device's times out. Bytes are hex (`"0a1b"`), `{"text":…}`,
`{"base64":…}` or a number array.

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
| `am start` / `start-activity` | `startActivity` | `-a -d -t -c -n -p -f`, `--es --esn --ez --ei --el --ef --ed --eu --ecn --esa --eia`, `--activity-*` / `--grant-*` flags, a trailing URI (with `VIEW`), component or package; `-W` is implicit; `-S` force-stops first |
| `am start-service` / `start-foreground-service` / `stopservice` | `startService` / `stopService` | |
| `am broadcast` | `broadcast` | runtime and manifest receivers; `--receiver-*` flags; `--user` and `--receiver-permission` ignored |
| `am force-stop` / `kill` | `forceStop` | partly: see below |
| `am stack list` | `activities` | |
| `pm grant` / `revoke` | `permissions` `grant` / `deny` | short names (`CAMERA`) take `android.permission.`; the answer also applies to later requests |
| `pm reset-permissions` | `permissions` `reset` | runtime permissions revoked, policy back to grant |
| `pm clear` | `clearData` | partly: see below |
| `pm list packages` / `pm path` / `pm dump` | `packages` / `package` | the one installed package |
| `pm list permissions` | `permissions` | the app's requested permissions and their state, not the device's |
| `input tap` / `swipe` / `text` / `keyevent` / `draganddrop` / `press` | `tap` / `swipe` / `text` / `key` / `swipe` with `holdMs` | a source word (`touchscreen`…) is accepted; `text` turns `%s` into a space; `keyevent --longpress` |
| `settings get` / `put` / `delete` (`--user` ignored) | `settings` | puts applied: `global airplane_mode_on`, `global wifi_on`, `global mobile_data`, `global bluetooth_on` (radios), `global animator_duration_scale` (`ValueAnimator`), `system font_scale` (configuration change), `system time_12_24` (`TIME_SET` broadcast); others are stored for the app to read |
| `content query` / `insert` / `update` / `delete` | `content` | `--uri --projection a:b --where --sort --bind name:type:value` (`s b i l f d n`) |
| `cmd uimode night yes` / `no` / `auto` | `qualifiers` `+night` / `+notnight` | |
| `wm size [WxH\|reset]` / `wm density [N\|reset]` | `display` | pixels, as on a device: a density change keeps the pixel size; `reset` restores the launch qualifiers (both) |
| `svc wifi` / `svc data enable` / `disable`, `cmd wifi set-wifi-enabled`, `cmd connectivity airplane-mode`, `cmd netpolicy set restrict-background` | `network` | the radios and data saver, as [above](#network-wi-fi-and-bluetooth) |
| `cmd wifi status` / `list-scan-results` / `start-scan` / `connect-network <ssid> …` | `wifi` | |
| `svc bluetooth enable` / `disable`, `cmd bluetooth_manager enable` / `disable` | `bluetooth` | |
| `dumpsys activity [activities\|top\|services]`, `dumpsys notification`, `dumpsys window`, `dumpsys package`, `dumpsys connectivity` / `wifi` / `bluetooth_manager` | `activities`, `services`, `notifications`, `info`, `package`, `network` / `wifi` / `bluetooth` | JSON, not dumpsys text |
| `logcat` `-d -c -s -t -e`, `TAG:LEVEL` / `*:LEVEL` | `logcat` | without `-d` / `-c` / `-t` it follows: `log` events until `{"cmd":"logcat","op":"stop"}`; `-v -b --pid` ignored; times are wall-clock, pid/tid are Robolectric's |
| `screencap -p [file]` | `screenshot` | PNG only |
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
| `svc power`, `svc nfc`, `svc usb` | no power, NFC or USB model |
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
  share sheet, other settings screens) reaches no activity and is reported as an `error` event;
  the radio dialogs and settings panels are answered ([above](#network-wi-fi-and-bluetooth)).
- **Bluetooth.** On, with nothing in range until the agent puts peripherals there
  ([above](#network-wi-fi-and-bluetooth)). Robolectric's adapter reports LE off in airplane mode
  even when Bluetooth is turned back on. No classic sockets (RFCOMM), audio profiles, LE
  advertising by the app, or GATT server in the app. `CompanionDeviceManager.associate` is not
  answered.
- **Network.** Online, requests go out through the host JVM, its proxy and its trust store; the
  simulated network decides only whether they may, and how fast. A mode is one default network (a
  VPN adds the Wi-Fi beneath it): no Wi-Fi-to-cellular fallback when Wi-Fi drops, no roaming,
  no per-app blocked status under data saver.
- **Split APKs and App Bundles** need merging into one APK first; protected APKs are out.
- **Fidelity.** Robolectric's native graphics, not a device's GPU: close, not pixel-identical. The
  window draws one device pixel per screen pixel, hence the `w360dp-h640dp-hdpi` default, and
  resizes to the display after each display command; a tablet or desktop at hdpi may be larger
  than the screen.
- **Device controls.** One display: no rear display, no multi-window or freeform, no activity
  embedding. A configuration change reaches the top activity; activities below it in the back
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
