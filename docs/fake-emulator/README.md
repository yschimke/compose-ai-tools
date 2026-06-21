# Fake Android emulator

A standalone module set that **impersonates a running Android emulator** so the
compose-preview render pipeline can be driven by the tools that drive a real
device — `adb`, Android Studio's device list, and Studio's embedded "Running
Devices" view — and a preview launched on it with the same intent Studio's
"Deploy Preview to Device" uses.

It is not an Android runtime. It answers the wire protocols a real emulator
exposes and routes the requests that matter (`am start … PreviewActivity`,
screenshot, screenshot stream) into the existing daemon / `serve` render path.
The emulator's "screen" is whatever the renderer last produced for the launched
`@Preview`.

See [DESIGN.md](DESIGN.md) for the architecture and the full scope /
deferred-work breakdown.

## Modules

- **`:fake-emulator-core`** — pure-Kotlin ADB device transport (adbd), emulator
  console, `screencap`, the `am start … PreviewActivity` preview-launch parser,
  the `FrameSource` / `PreviewLauncher` SPIs, and the Studio discovery-file
  writer. `dadb`-tested.
- **`:fake-emulator-grpc`** — the emulator `EmulatorController` gRPC subset +
  screenshot video stream, from a vendored `emulator_controller.proto`.
- **`:fake-emulator`** — runnable launcher wiring core + gRPC + a
  RenderSession-backed `FrameSource`.

## Run it

```bash
# Placeholder screen (no project), good for ADB / Studio bring-up:
./gradlew :fake-emulator:run --args="--adb-port 5555 --console-port 5554"

# Driven by a real render session (preview pixels become the screen):
./gradlew :fake-emulator:run --args="\
  --descriptor samples/android/build/compose-previews/daemon-launch.json \
  --adb-port 5555 --console-port 5554"
```

Then:

```bash
adb connect localhost:5555
adb -s emulator-5554 shell am start \
  -n com.example.app/androidx.compose.ui.tooling.PreviewActivity \
  --es composable com.example.PreviewsKt.MyPreview
adb -s emulator-5554 exec-out screencap -p > screen.png
```

## Test

```bash
./gradlew :fake-emulator-core:test
```

The ADB transport + preview-launch path is verified with `dadb`, which speaks
the device protocol directly — no real `adb` or device required.
