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
`destroyed`, or `empty` when the last one finishes), `service`, `providers`, and `error` for an
intent the app sent that nothing handles.

Commands run in order. Those that change the UI answer after `settleMs` of simulated time (default
250), so a `tap` followed by a `screenshot` sees the result; any command takes `settleMs`.

| Command | Arguments | Does |
| --- | --- | --- |
| `info` | | package, version, SDK, qualifiers, display size, top activity |
| `ping` | | uptime |
| `wait` | `ms` (or `settleMs`) | lets the app run |
| `screenshot` | `path`? | PNG to `path`, else base64 in `png` |
| `hierarchy` | `flat`? | windows → views and Compose semantics (via accessibility nodes): `cls`, `text`, `desc`, `resourceId`, `hint`, `state`, `bounds`, `flags` |
| `find` | selector | matching nodes |
| `tap` | `x`,`y` or selector, `index`? | touch down/up at the point or the node's center |
| `longPress` | as `tap`, `durationMs`? | |
| `swipe` | `x1`,`y1`,`x2`,`y2`, `durationMs`?, `steps`? | |
| `key` | `key` (`"BACK"`, `"ENTER"`, a code) | key down/up |
| `back` | | the Back key — the app's own back handling decides |
| `text` | `text` | types into the focused field |
| `launch` | | starts the launcher activity |
| `finish` | | finishes the top activity without a key press |
| `activities` | | the back stack |
| `startActivity` | `intent` | deep links resolve through the manifest's real intent filters |
| `startService` / `stopService` | `intent` | |
| `bindService` / `unbindService` | `intent` | `onBind`, reports the binder's interface |
| `services` | | running services, started/bound state, foreground notification |
| `broadcast` | `intent` | runtime-registered and manifest receivers; reports who received it |
| `notifications` | | what the app posted |
| `query` | `uri`, `projection`?, `selection`?, `args`?, `sort`?, `limit`? | a content provider query |
| `components` | | the manifest's activities, services, receivers, providers |
| `permissions` | `policy`? (`grant` / `deny`), `grant`? / `deny`? (permission names) | how the app's runtime permission requests are answered; `grant` / `deny` also apply now (a deny revokes). Returns the policy, the per-permission answers, and the state of each requested permission |
| `qualifiers` | `value` (e.g. `+land`, `+night`) | a configuration change on the top activity |
| `quit` | | exits |

A **selector** is any of `text` / `desc` (exact), `textContains` / `descContains` (case-insensitive),
`resourceId` (whole, or the part after `:id/` — a Compose `testTag` once the app sets
`testTagsAsResourceId`), `cls` (class-name suffix) and `clickable`; given inline or as
`"selector":{…}`.

An **intent** is `{"action","data","type","package","component","categories","flags","extras"}`.
`component` takes `pkg/cls`, `pkg/.Cls`, `.Cls` or a class name; `flags` an int or names
(`"NEW_TASK"`, `"CLEAR_TOP"`); an extra's JSON type picks its Java type, or
`{"type":"long","value":1}` names it (`string int long float double boolean uri string[] int[]`).

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
- **Process and task model.** One process, one task; `launch` while running pushes a new launcher
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
  window draws one device pixel per screen pixel, hence the `w360dp-h640dp-hdpi` default; it does
  not resize after a `qualifiers` change, though screenshots do.
- **Stack traces say `Unknown Source`** for app frames (dex2jar keeps line numbers, not the source
  file attribute).
- **SDK level.** One of Robolectric's supported levels (`--sdk`, default 35), fetched as
  `android-all-instrumented` on first use like any Robolectric run.
