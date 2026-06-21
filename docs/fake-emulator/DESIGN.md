# Fake Android emulator — design

A standalone module that **impersonates a running Android emulator** so that
the compose-preview render pipeline can be driven by the same tools that drive
a real device: `adb`, Android Studio's device list, and (later) Studio's
embedded "Running Devices" panel.

It is *not* an Android runtime. There is no ART, no system server, no real
`am`. Instead the fake emulator answers the wire protocols a real emulator
exposes and routes the handful of requests that matter — "launch this preview",
"give me the screen" — into the existing daemon / `serve` render path. The
emulator's "screen" is whatever the renderer last produced for the launched
`@Preview`.

```
        ┌─────────────────┐   adb (transport)         ┌──────────────────────────┐
        │ adb server /    │◀────TCP, CNXN/OPEN/…──────▶│ FakeEmulator              │
        │ Android Studio  │                            │                          │
        │ dadb / Adam     │   emulator console (5554)  │  ┌────────────────────┐  │
        └─────────────────┘◀────TCP, text protocol────▶│  │ AdbTransportServer │  │
                  │                                     │  │  shell,v2 / sync / │  │
                  │         emulator gRPC               │  │  screencap         │  │
                  └─────────gRPC, EmulatorController────▶│  └─────────┬──────────┘  │
                            getScreenshot / stream      │            │ am start    │
                                                        │            ▼             │
                                                        │   PreviewLauncher ───────┼──┐
                                                        │   FrameSource ◀──frames──┼─┐│
                                                        └──────────────────────────┘ ││
                                                                                      ││
                                          ┌───────────────────────────────────────┐  ││
                                          │ RenderSession (:render-session-api)    │◀─┘│
                                          │  streamStart + streamFrame  →  PNG     │◀──┘
                                          │  (daemon subprocess, serve)            │
                                          └───────────────────────────────────────┘
```

## Why build on the remote-daemon / `serve` work

`compose-preview serve` already turns a `RenderSession` into a stream of PNG /
WebP frames over a WebSocket (`/ws/{previewId}`), with a held interactive
composition behind it (`stream/start` + `streamFrame`, see
`docs/daemon/STREAMING.md`). The fake emulator reuses exactly that frame source.
The only new surface is the *device-shaped* front end (ADB + console + gRPC)
that re-publishes those frames as an emulator display and maps `am start
… PreviewActivity --es composable <fqn>` onto a preview switch.

A direct consequence (and an explicit future goal): because the frame source is
the same `RenderSession` abstraction `serve` consumes, the fake emulator can be
fronted by the `serve` HTTP streaming lane too — start an emulator locally, then
**share a URL** that streams the same display. See "Sharing a URL" below.

## Modules

| Gradle path            | Directory             | What it is |
|------------------------|-----------------------|------------|
| `:fake-emulator-core`  | `fake-emulator/core`  | Pure-Kotlin ADB transport (adbd), emulator console, screencap, the `am start` preview-launch intent parser, the `FrameSource` / `PreviewLauncher` SPIs, and the Studio discovery-file writer. No render, no gRPC, no Android deps — unit-testable with `dadb`. |
| `:fake-emulator-grpc`  | `fake-emulator/grpc`  | The emulator `EmulatorController` gRPC service (subset) + screenshot video stream, generated from a vendored `emulator_controller.proto`, backed by a `FrameSource`. Isolated so the protobuf/grpc toolchain stays out of the core. |
| `:fake-emulator`       | `fake-emulator/app`   | Runnable launcher. Wires core + gRPC + a `RenderSessionFrameSource` (real frames from a daemon/`serve` session) and writes the discovery files so the process shows up as a device. |

The split keeps the **verifiable ADB core** free of the heavyweight
protobuf/gRPC toolchain and the render classpath, so its `dadb` tests run fast
and hermetically.

## ADB device transport (`:fake-emulator-core`)

We implement the *device* side of the adb transport protocol (what adbd
speaks), so an adb client connects to us directly (`adb connect host:port`, or
dadb / Adam pointed at our TCP port). 24-byte message header
(`command,arg0,arg1,data_length,data_crc32,magic`), commands `CNXN`, `OPEN`,
`OKAY`, `WRTE`, `CLSE`, (`AUTH`/`STLS` negotiated away — we connect un-authed,
the simplest interop path that dadb and `adb` both accept).

Per-OPEN we allocate a remote stream id and dispatch on the destination string:

| Destination            | Handling |
|------------------------|----------|
| `shell,v2:<cmd>`       | Shell-protocol framed (id+len+payload; stdout/stderr/exit). The modern path dadb/`adb` prefer. |
| `shell:<cmd>`          | Legacy raw shell (no framing). |
| `sync:`                | Minimal file-sync (STAT/SEND/RECV/QUIT) so install-shaped flows don't hang. |
| `framebuffer:`         | Reserved; `screencap` is the supported screenshot path. |

The shell command interpreter is deliberately small — a real device runs
thousands of commands, we answer the few that matter for *device detection* and
*preview launch*:

- `getprop [name]` — a fixed property map (`ro.product.*`, `ro.build.version.*`,
  `ro.kernel.qemu=1`, …) so clients classify us as an emulator.
- `am start -n <app>/androidx.compose.ui.tooling.PreviewActivity --es composable
  <fqn> [--es parameterProviderClassName <fqn>]` — parsed into a
  `PreviewLaunchRequest` and handed to the injected `PreviewLauncher`. This is
  the exact argv Android Studio's "Deploy Preview to Device" and the VS Code
  extension already emit (`vscode-extension/src/launchOnDevice.ts`).
- `screencap [-p]` — returns the current `FrameSource` frame as PNG bytes.
- Common detection no-ops (`wm size`, `echo`, `id`, …) answered plausibly;
  everything else returns empty output + exit 0.

## Emulator console (`:fake-emulator-core`)

A small text-protocol server on the console port (default 5554). Emits the
emulator banner + `OK`, accepts un-authed sessions, and answers `help`, `avd
name`, `avd path`, `redir list`, `kill`, `quit`. This is what classic
`adb`-emulator auto-detection probes; pairing the console port (5554) with the
adb port (5555) is what makes a real emulator appear as `emulator-5554`.

## Discovery (`:fake-emulator`)

Android Studio finds running emulators by reading registration files the
emulator drops in its discovery directory (`$XDG_RUNTIME_DIR/avd/running/` →
`$TMPDIR/avd/running/` → `~/.android/avd/running/`), named `pid_<pid>.ini`,
carrying `port.serial`, `port.adb`, `avd.name`, `grpc.port`, and `grpc.token`.
We write the same file (and delete it on shutdown) so the embedded-emulator
catalog lists us and knows where our gRPC lives.

## Emulator gRPC (`:fake-emulator-grpc`)

A subset of `android.emulation.control.EmulatorController` sufficient for
Studio's embedded view + a screenshot video stream:

- `getStatus`, `getVmConfiguration` — identity / liveness.
- `getDisplayConfigurations` — our display size (from the `FrameSource`).
- `getScreenshot` — one frame (PNG) from the `FrameSource`.
- `streamScreenshot` — the **video** stream: a server-streaming RPC that
  forwards every `FrameSource` frame as an `Image` message. This is the same
  frame feed the ADB `screencap` path serves, just pushed continuously.
- `sendKey` / `sendTouch` — mapped onto `RenderSession` interactive input where
  available (future: full pointer routing).

The proto is vendored (not a runtime dep on the SDK). Square **Wire** generates
the message classes (pure-Kotlin codegen, no `protoc`); the gRPC service is bound
by hand into grpc-netty via grpc-java's `ServerCalls` + a small Wire
`MethodDescriptor.Marshaller` (Wire dropped its own server-side gRPC artifact
after 4.9.11). Generated sources land under `build/` so ktfmt never sees them.

The vendored proto is a *subset* of the canonical `emulator_controller.proto`,
but the field **numbers and types** of the messages it defines are kept
wire-compatible with the real schema (cross-checked against `google-deepmind/
android_env`, AOSP `external/qemu`, and `yschimke/emulator-tools`) so a real
Studio client decodes our responses — e.g. `EmulatorStatus.booted = 3`,
`Image.image = 4`. Fields we don't model are omitted, not renumbered.

## Frame source

```kotlin
interface FrameSource {
  val display: DisplaySize
  fun latest(): EmulatorFrame?               // pull (screencap / getScreenshot)
  fun subscribe(sink: (EmulatorFrame) -> Unit): AutoCloseable  // push (video)
}
```

- `:fake-emulator-core` ships an in-memory `MutableFrameSource` (tests, and the
  "no preview launched yet" placeholder).
- `:fake-emulator` ships `RenderSessionFrameSource`, which opens a
  `RenderSession`, calls `streamStart` on preview launch, and republishes
  `streamFrame` notifications as `EmulatorFrame`s — i.e. the real preview
  pixels. This is the "wire to serve/daemon" path.

## Preview launch flow

1. `adb shell am start -n …/PreviewActivity --es composable com.x.FooKt.Bar`
2. ADB shell interpreter parses it → `PreviewLaunchRequest("com.x.FooKt.Bar")`.
3. `RenderSessionFrameSource` maps the FQN to a preview id and calls
   `session.streamStart(previewId)`.
4. `streamFrame` notifications flow → `EmulatorFrame`s → become the emulator
   display (ADB `screencap`, gRPC `getScreenshot`/`streamScreenshot`).

## Sharing a URL (future)

Because the display is a `FrameSource` and `serve` already streams a
`RenderSession` over HTTP/WS, a follow-up wires the *same* `FrameSource` into a
`serve` lane: run `compose-preview fake-emulator --serve`, get a
`composeai://session` / `http://…/p/{previewId}` URL, and the emulator screen is
viewable remotely with the existing mobile/wear clients. No new streaming code —
just point `serve`'s frame producer at the emulator's `FrameSource`.

## What this PR delivers vs. defers

**Delivered:** the module layout + build wiring; the ADB transport core
(CNXN/OPEN/OKAY/WRTE/CLSE, shell v2 + v1, minimal sync, screencap), the `am
start` preview-launch parser, the console, the discovery writer, the
`FrameSource`/`PreviewLauncher` SPIs, the `RenderSessionFrameSource` bridge, the
gRPC `EmulatorController` subset + screenshot stream, a runnable launcher, and
`dadb`-driven tests for the ADB + preview-launch path.

**Deferred:** full sync (large APK install), authenticated adb (RSA/TLS),
complete `EmulatorController` surface, pointer/key fidelity, and the `serve`
URL-sharing wire-up above.

## Testing

The ADB core is verified with **`dadb`** (`dev.mobile:dadb`), which speaks the
device transport directly — no real `adb` binary or device required. Tests:

- connect handshake → `dadb` reports the device online + banner props;
- `dadb.shell("getprop ro.kernel.qemu")` → `1`;
- `dadb.shell("am start -n app/androidx.compose.ui.tooling.PreviewActivity --es
  composable com.x.FooKt.Bar")` → the injected `PreviewLauncher` receives
  `com.x.FooKt.Bar`;
- `screencap -p` → the bytes of the current `FrameSource` frame.

Integration against a real `RenderSession` (full daemon + Robolectric) and a
real Android Studio is manual / CI-gated; it is not exercised by the unit
suite.
