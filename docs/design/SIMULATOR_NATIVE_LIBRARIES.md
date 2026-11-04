# Native libraries in the simulator

**Survey and status.** The survey was measured 2026-11-03 against Google Maven and Maven Central; the
status is as of 2026-11-04. Tracks [#6342](https://github.com/yschimke/compose-ai-tools/issues/6342)
and [#6411](https://github.com/yschimke/compose-ai-tools/issues/6411).

`compose-preview launch` runs an APK's code on the host JVM, so it cannot load the APK's
`lib/<abi>/*.so` files. Those files are Android ELF binaries linked against bionic. This page records
which common libraries ship native code, what each one does when its library fails to load, and which
of four fixes applies to it. Then it describes the mechanism that now serves host builds to
`launch`, the libraries it covers, and what remains.

## Summary

- **Few libraries are affected, and most degrade on their own.** Of 1,909 AndroidX artifacts, 20 ship
  `.so` files. Among the common third-party libraries surveyed below, the four NDK crash reporters
  (Sentry, Crashlytics, Bugsnag, Datadog) all catch the load failure and carry on without native
  crash capture. DataStore falls back to an in-process counter.
- **One library breaks nearly every Compose app: graphics-path 1.0.1.** Every stable Compose UI up
  to 1.12.1 pins it, and it loads its library unconditionally. A host build published from this
  repository fixes it (see [graphics-path](#the-graphics-path-host-build)).
- **`launch` now serves host builds** from an index read at run time
  ([how it works](#how-launch-serves-host-builds)). It covers graphics-path, AndroidX sqlite-bundled
  and Ink, zstd-jni, JNA and libsignal. `samples/host-natives` exercises all of them: 7 of 7 checks
  pass in the simulator (see [the test app](#the-test-app)).
- **Several more libraries have an upstream host build at the same version**: Realm Kotlin,
  ObjectBox and ONNX Runtime. Each has an open issue for what stands in the way.
- **Conscrypt needs nothing.** The simulator never loads the APK's `org.conscrypt` classes;
  Robolectric's runtime carries `conscrypt-openjdk-uber`, which brings its own library.
- **The libraries that fail hard with no host build** are MMKV, SQLCipher, ML Kit's bundled models,
  LiteRT/TensorFlow Lite and MediaPipe. So are GPU, camera and media libraries (graphics-core, WebGPU,
  Filament, MapLibre, WebRTC), but those need surfaces the simulator doesn't have anyway, so a
  library wouldn't help ([GPU and engine apps](#gpu-and-engine-apps)).

## What the simulator does today

Recorded in #6342 and handled by `simulator/host/.../NativeCode.java`:

- **Where the lookup happens:** an app's `System.loadLibrary("x")` searches the simulator JVM's real
  `java.library.path`. The APK's classes are converted from dex but not rewritten around library
  loading.
- **What is on that path:** the host builds `launch` provisions for the APK, ahead of the JVM's
  default ([how](#how-launch-serves-host-builds)).
- **When it fails:** `UnsatisfiedLinkError`. The simulator names the library on stderr. On the
  control channel it sends an `error` event with `kind` `native` rather than exiting silently.
- **What the app sees of its environment:**
  - `Build.SUPPORTED_ABIS` leads with the host ABI.
  - `applicationInfo.nativeLibraryDir` is a real (empty) directory, so SoLoader and ReLinker don't
    fail on a null path.
- **Class-loader binding:** a JNI library binds to the class loader of the class that loads it. The
  load happens in the app's own class, inside the simulator's sandbox class loader, so a library
  placed on `java.library.path` binds correctly with no rewriting. Each simulator is its own JVM, so
  there's no clash between apps.

## Four ways to handle a native library

| | Strategy | When it applies | Cost per library |
| --- | --- | --- | --- |
| A | **Nothing.** | The library gates its load on Dalvik, or catches the error and degrades. | None |
| B | **Upstream host build.** Fetch the host artifact at the APK's exact version and put its library on `java.library.path`, renaming it if the Android name differs. | A JVM or desktop artifact exists with the same JNI surface. | A table entry |
| C | **Build from source here**, on the [host-natives line](../RELEASING.md#the-host-natives-binaries). | The source is open, there's no host build, and the native code doesn't depend on Android-only APIs it actually reaches. | A build recipe plus a verification suite |
| D | **JVM stand-in.** Shadow the library's Java API in the simulator. | The native code is an engine behind a small Java API. | Code in `:simulator-host` |

AppSearch already uses D: `AppSearchSessions.java` stands in for its native search library, Icing.
graphics-path uses C. Everything else below is A, B or out of scope.

Versions must match exactly for B and C. JNI symbol sets change between releases, and a library
registering natives in `JNI_OnLoad` fails on the first missing class or method. For AndroidX, the
version comes from the APK's `META-INF/androidx.<group>_<artifact>.version` file. Third-party
libraries have no such convention; see [open questions](#open-questions).

## AndroidX

These are all 20 AndroidX artifacts that ship `.so` files, using the latest version of each as of the
survey. Two of them, `sqlite-bundled-jvm` and `ink-nativeloader-jvm`, are host builds and appear in
the "Host build" column. "Load site" is the method that calls `System.loadLibrary`, read from the bytecode. "Handler"
means that method, or a caller I checked, catches `UnsatisfiedLinkError` or a supertype.

| Artifact (stable / newest checked) | Load site | Off Android | Host build | Strategy |
| --- | --- | --- | --- | --- |
| `graphics:graphics-path` 1.0.1 | `PathIteratorImpl.<clinit>`, no handler | **Fails at first `Path.iterator()`**; every stable Compose UI ≤ 1.12.1 pins 1.0.1 | Built here | **C: served** (`host-natives-graphics-path-1.0.1-r2`) |
| `graphics:graphics-path` 1.1.0 | Same class, gated on `java.vm.name == dalvik` | Works; Kotlin fallback | Not needed | A |
| `datastore:datastore-core-android` 1.3.0 | `SharedCounter.Factory.<clinit>`, Dalvik check, catches `Throwable` | Works; in-process counter | None | A |
| `appsearch:appsearch-local-storage` 1.1.0 / 1.2.0-alpha02 | `IcingLibraryLoader.loadLibrary`, no handler | Would fail | None | **D: done** (`AppSearchSessions`) |
| `sqlite:sqlite-bundled-android` 2.7.1 / 2.8.0-alpha03 | `NativeLibraryLoader.loadLibrary`, no handler | **Fails at first connection** | `sqlite-bundled-jvm`: linux x64/arm64, macOS arm64, windows x64 | **B: served** (2.5.0–2.7.1) |
| `ink:ink-nativeloader-android` 1.0.0 / 1.1.0-alpha10 | `NativeLoader.loadSynchronous`, no handler | **Fails at first Ink use** | `ink-nativeloader-jvm`: linux x86_64 at 1.0.0; plus macOS arm64 at 1.1.0-alpha10 | **B: served** (1.0.0) |
| `tracing:tracing-perfetto-binary` 1.0.1 | `PerfettoNative.loadLib`, no handler | Fails only if Perfetto tracing is switched on | None | A in practice |
| `camera:camera-core` 1.6.2 / 1.7.0-rc01 | `ImageProcessingUtil`, `SurfaceUtil` static blocks, no handler | Fails when those classes load | None | Out of scope (no camera) |
| `graphics:graphics-core` 1.0.4 / 1.1.0-beta01 | Four binding classes (`JniBindings`, `EGLBindings`, `SyncFence*`), no handler | Fails at first front-buffer/EGL use | None | Out of scope (GPU) |
| `media3:media3-effect` 1.12.0-alpha02 | `HardwareBufferJni`; none in 1.11.1 stable | Fails in the hardware-buffer effect path | None | Out of scope (GL effects) |
| `webgpu:webgpu` 1.0.0-alpha06 | `Util.<clinit>`, `WebGpuUtils.initLibrary`, no handler | Fails | None | Out of scope (GPU) |
| `xr.runtime:runtime-openxr`, `xr.arcore:arcore-openxr` 1.0.0 | `OpenXrInstanceManager.initialize` catches `UnsatisfiedLinkError` | Degrades | None | A |
| `benchmark:benchmark-common` 1.5.0 | `BlackHole`, `CpuCounterJni` static blocks | Test-only | None | Out of scope |
| `games:games-*` (5 artifacts) | Prefab only, for the app's own C++ | Only the app's own native code uses them | None | Out of scope |

Notes:

- **sqlite-bundled:** the 2.7.1 Android loader calls `System.load(<path>/<name>)` when the system
  properties `androidx.sqlite.driver.bundled.path` and `androidx.sqlite.driver.bundled.name` are
  both set. Otherwise it calls `System.loadLibrary("sqliteJni")`. So either a property pair or
  `java.library.path` works.
  - #6342 found that the Android and JVM builds register their natives against the same classes.
  - There's no macOS x86_64 host build.
- **Ink:** #6342 found that the Android arm64 library and both 1.1.0-alpha10 host libraries export
  the same 343 `Java_androidx_ink_*` symbols. The stable 1.0.0 host jar is Linux-only, and no
  version has macOS x86_64.
- **DataStore:** the fallback only helps if R8 left it in. Its source comment says the Dalvik check
  exists so R8 can strip the fallback from release builds. A minified APK may therefore load the
  library unconditionally. This is inferred and not checked against a real minified APK. Debug
  APKs, which the simulator usually runs, aren't minified.

## Third-party libraries

These are the common open and commercial Android libraries that ship natives, plus a few frequently
assumed to and checked not to. The artifacts and their contents were read from Maven at the latest
stable version. "Host build" means an artifact at the **same version** that contains desktop
libraries.

### Storage

| Library (artifact, version) | Natives (ABIs) | Load site and handler | Host build | Strategy |
| --- | --- | --- | --- | --- |
| SQLCipher (`net.zetetic:sqlcipher-android` 4.20.0; legacy `android-database-sqlcipher` 4.5.4) | `libsqlcipher.so` (4 ABIs) | No load in the library's classes; the app loads it | None | C candidate ([#6421](https://github.com/yschimke/compose-ai-tools/issues/6421)) |
| MMKV (`com.tencent:mmkv` / `mmkv-static` 2.4.3) | `libmmkv.so` (**arm64-v8a, x86_64 only**) | `MMKV.tryLoadNativeLib`, no handler | None | C candidate ([#6420](https://github.com/yschimke/compose-ai-tools/issues/6420)) |
| Realm Java (`io.realm:realm-android-library` 10.19.0) | `librealm-jni.so` | `RealmCore.loadCorrectLibrary` catches `Throwable`, tries variants | None | Out of scope (SDK deprecated) |
| Realm Kotlin (`io.realm.kotlin:cinterop-android` 3.0.0) | `librealmc.so` | `loadAndroidNativeLibs`, no handler | `cinterop-jvm` 3.0.0: linux, macOS, windows, same name `realmc` | B (SDK deprecated; [#6419](https://github.com/yschimke/compose-ai-tools/issues/6419)) |
| ObjectBox (`io.objectbox:objectbox-android` → `objectbox-android-db` 5.4.2) | `libobjectbox-jni.so` | `NativeLibraryLoader.<clinit>`, checks `os.name`/`java.vendor`, catches `UnsatisfiedLinkError` | `objectbox-linux`, `-linux-arm64`, `-macos`, `-windows` 5.4.2 | B ([#6418](https://github.com/yschimke/compose-ai-tools/issues/6418)) |
| SQLDelight (`app.cash.sqldelight:android-driver` 2.4.1), Room with framework SQLite | None | | | Not affected |

ObjectBox's loader checks `os.name` and `java.vendor` for Android. On the host JVM the check fails,
so it looks for its desktop library name (`objectbox-jni-linux-x64`, `-macos`) rather than the
Android one. That's inferred from the loader's string constants and not run. If it holds, the host
artifact's library works under its own name.

### Security and crypto

| Library | Natives | Load site and handler | Host build | Strategy |
| --- | --- | --- | --- | --- |
| Conscrypt (`org.conscrypt:conscrypt-android` 2.8.0) | `libconscrypt_jni.so` | `NativeCryptoJni.init` from `NativeCrypto.<clinit>`, which catches `UnsatisfiedLinkError` | `conscrypt-openjdk-uber` 2.8.0: linux x86_64/aarch_64, osx x86_64/aarch_64, windows; library named `conscrypt_openjdk_jni-<os>-<arch>` | **Not needed**: the simulator uses Robolectric's own Conscrypt |
| libsignal (`org.signal:libsignal-android` 0.86.5) | `libsignal_jni.so` | In the shared client classes | `libsignal-client` 0.86.5: linux amd64, macOS amd64/aarch64, windows; arch-suffixed names | **B: served**, saved under the Android name (0.86.1–0.86.5) |
| lazysodium (`com.goterl:lazysodium-android` 5.2.0) | `libsodium.so`, loaded through JNA | Through JNA | `lazysodium-java` 5.2.0 bundles libsodium for linux, macOS, windows | B (with JNA) |
| RootBeer (`com.scottyab:rootbeer-lib` 0.1.2) | `libtoolChecker.so` | `RootBeerNative.<clinit>` catches `UnsatisfiedLinkError` | None | A (native check skipped) |
| Tink (`tink-android` 1.24.0), Play Integrity 1.6.0, SafetyNet 18.1.0 | None | | | Not affected |

On Conscrypt: `NativeCrypto` declares the same 369 natives in the Android and OpenJDK artifacts at
2.8.0, but the simulator never uses the APK's copy. `SimulatorSandbox` does not acquire
`org.conscrypt`, so those classes come from the runtime classpath, where Robolectric carries
`conscrypt-openjdk-uber` (2.6.2 today), which loads its own desktop library. `samples/host-natives`
installs the provider and digests with it, and passes with no index entry.

### Crash reporting and observability

All four crash reporters degrade: the app runs, and only native crash capture is lost.

| Library | Natives | Handler (verified in bytecode) |
| --- | --- | --- |
| Sentry (`io.sentry:sentry-android` 8.62.0 → `sentry-android-ndk` → `sentry-native-ndk` 0.17.x) | `libsentry.so`, `libsentry-android.so` | `io.sentry.android.ndk.SentryNdk`'s static loader lambda catches `Throwable` |
| Firebase Crashlytics NDK (`firebase-crashlytics-ndk` 21.0.1) | `libcrashlytics*.so` (4 libraries) | `JniNativeApi.<clinit>` catches `UnsatisfiedLinkError` |
| Bugsnag NDK (`bugsnag-plugin-android-ndk` 6.28.0) | `libbugsnag-ndk.so` | `bugsnag-android-core`'s `LibraryLoader.loadLibInternal` catches `UnsatisfiedLinkError` |
| Datadog NDK (`dd-sdk-android-ndk` 3.16.0) | `libdatadog-ndk.so` | `NdkCrashReportsFeature.loadNativeLibrary` catches `UnsatisfiedLinkError` |

`firebase-crashlytics` (without NDK) and `firebase-perf` ship no natives.

### Images, media and rendering

| Library | Natives | Load site and handler | Strategy |
| --- | --- | --- | --- |
| Fresco (`imagepipeline-native`, `animated-gif`, `nativeimagetranscoder` 3.9.0) | `libimagepipeline.so` and others | Through SoLoader, whose 0.12.1 `loadLibraryOnNonAndroid` calls `System.loadLibrary` | Not checked whether Fresco's default pipeline needs them |
| Rive (`app.rive:rive-android` 11.16.0) | `librive-android.so`, `libc++_shared.so` | `Rive.NativeLoader` catches `UnsatisfiedLinkError`, but nothing renders without it | Out of scope (GPU renderer) |
| Filament (`filament-android`, `gltfio-android` 1.77.3), SceneView 4.65.0 | `libfilament-jni.so`, `libgltfio-jni.so` | `Filament.<clinit>`, no handler | Out of scope (GPU) |
| MapLibre (`org.maplibre.gl:android-sdk` 2026.11.2-globe) | `libmaplibre.so` | `SystemLibraryLoader.load`, no handler | Out of scope (GPU) |
| WebRTC (`io.getstream:stream-webrtc-android` 1.3.10) | `libjingle_peerconnection_so.so` | `NativeLibrary.DefaultLoader.load`, no handler | Out of scope (camera, audio) |
| OpenCV (`org.opencv:opencv` 5.0.0) | `libopencv_java5.so`, `libc++_shared.so` | `StaticHelper.loadLibrary` catches `UnsatisfiedLinkError`; `OpenCVLoader.initLocal()` returns false | No same-version host build (`org.openpnp:opencv` is 4.9.0 and unofficial) |
| PDFium (`io.legere:pdfiumandroid` 2.0.3 → `pdfiumandroid-core`) | `libpdfium.so`, `libpdfiumandroid.so` | Not checked | No host build |
| Coil 3.6.3, Glide 5.0.9, Lottie 6.7.1 | None | | Not affected |

### Machine learning

| Library | Natives | Load site and handler | Host build |
| --- | --- | --- | --- |
| ML Kit barcode, bundled (`com.google.mlkit:barcode-scanning` 17.3.0) | `libbarhopper_v3.so` | `BarhopperV3` constructor, no handler | None |
| ML Kit text, bundled (`text-recognition` 16.0.1 → `text-recognition-bundled-common` 17.0.0) | `libmlkit_google_ocr_pipeline.so` | Not checked | None |
| ML Kit, unbundled (`play-services-mlkit-barcode-scanning` 18.3.1) | None; the model runs in Play services | | Not applicable: the simulator reports Play services APIs as unavailable |
| LiteRT (`com.google.ai.edge.litert:litert` 2.3.2, Google Maven) | `libLiteRt.so` (arm64-v8a, armeabi-v7a, x86_64) | Not found as a direct call | None on Maven |
| TensorFlow Lite (`org.tensorflow:tensorflow-lite` 2.17.0) | Not read: the survey couldn't open the archive | | None on Maven |
| MediaPipe (`tasks-genai` 0.10.35; `tasks-vision` 1.2.2 has none itself) | `libllm_inference_engine_jni.so` | Not checked | None |
| ONNX Runtime (`onnxruntime-android` 1.31.1) | `libonnxruntime.so`, `libonnxruntime4j_jni.so` | `OnnxRuntime` checks `java.vendor` for Android and honours `onnxruntime.native.*` properties | `onnxruntime` 1.31.1: linux x64/aarch64, macOS aarch64, windows x64 (**no macOS x86_64**); its Android classes declare 9 telemetry natives the host build lacks ([#6417](https://github.com/yschimke/compose-ai-tools/issues/6417)) |

Running inference in the simulator is unlikely to be worth doing. ONNX Runtime is the exception,
where B is cheap.

### Networking and general-purpose

| Library | Natives | Notes | Strategy |
| --- | --- | --- | --- |
| Cronet, embedded (`org.chromium.net:cronet-embedded` 500.1.0 → `cronet-bundled`) | `libcronet.<chromium-version>.so` | `cronet-fallback` (no natives) is the Java provider | Out of scope; apps that also ship the fallback provider should keep working (not run) |
| zstd-jni (`com.github.luben:zstd-jni` 1.5.7-25) | AAR: `libzstd-jni-1.5.7-25.so`; JAR: 18 desktop builds incl. darwin x86_64/aarch64, linux amd64/aarch64 | One coordinate, both packagings; the version is in the library name | **B: served**, matched by name (1.5.7-18 and later) |
| JNA (`net.java.dev.jna:jna` 5.19.1) | AAR: `libjnidispatch.so`; JAR: 25 desktop builds | `Native.loadNativeDispatchLibrary` has a Dalvik check and honours `jna.boot.library.path` | **B: served**, with `jna.boot.library.path` set (5.14.0 and later) |
| WireGuard (`com.wireguard.android:tunnel` 1.0.20260102) | `libwg-go.so`, `libwg.so`, `libwg-quick.so` | A VPN | Out of scope |
| OkHttp 5.5.1 | None | | Not affected |

### Out of scope: frameworks that are the app

React Native, Flutter and Unity apps draw their UI from a native engine: Hermes plus React Native's
core over SoLoader and fbjni (`com.facebook.fbjni:fbjni` 0.8.1 ships `libfbjni.so`), Flutter's
`libflutter.so`, or Unity's player. The simulator's model of an app as JVM classes drawing through
the Android framework doesn't apply to them.

## Platform gaps in upstream host builds

None of the upstream host builds covers every host the simulator runs on:

| Host | Gaps |
| --- | --- |
| linux x86_64 | None among the B candidates |
| linux arm64 | Ink, Realm Kotlin (single `jni/linux` build, not checked which architecture), libsignal; also Robolectric's own native runtime |
| macOS arm64 | Ink before 1.1.0-alpha10 |
| macOS x86_64 | sqlite-bundled, Ink, ONNX Runtime |
| windows x86_64 | Ink; the simulator isn't supported on Windows anyway |

A missing platform is the same as not applying B: the simulator keeps its current clear
`UnsatisfiedLinkError` message.

## How `launch` serves host builds

Shipped in [#6412](https://github.com/yschimke/compose-ai-tools/pull/6412),
[#6415](https://github.com/yschimke/compose-ai-tools/pull/6415) and
[#6422](https://github.com/yschimke/compose-ai-tools/pull/6422). The CLI bundles no binaries and no
versions.

- **The index.** [`host-natives/index.json`](../../host-natives/index.json) maps each library's load
  name to its builds: per platform, an archive URL, its sha256, and the library's path inside it. The
  archive is either one of this repository's `host-natives-<recipe>-<version>` releases or the
  library's own JVM artifact on Maven. `launch` fetches the index from `main` and caches it for a
  day, keyed by its URL; `COMPOSE_PREVIEW_HOST_NATIVES_INDEX` points at another.
- **Choosing a build** (`HostNativesProvision.plan`), for each library the APK carries:
  1. the build whose Android libraries share a **GNU build ID** with the APK's copy. The ID survives
     stripping, so it names the exact binary; the watcher records `androidBuildIds` for every ABI;
  2. the build at the APK's version: its AndroidX `META-INF/*.version` marker, or the version in the
     library's own name (`libraryPattern`; zstd-jni loads `zstd-jni-<version>`);
  3. the nearest version, or the newest when the version is unknown, with a warning.

  `minSdk` and `fixedIn` exclude a library where it would not help (graphics-path below API 34, or
  at 1.1.0 and later).
- **Loading it.** The build is downloaded once, its sha256 checked, and saved under the name the
  APK's code loads, so a desktop build named differently (libsignal's `signal_jni_<arch>`) still
  loads. Each library's directory goes at the front of the simulator's `java.library.path`, and an
  entry's `properties` become system properties (JNA's `jna.boot.library.path`). Every failure logs
  one line and the launch carries on, so the app sees the same `UnsatisfiedLinkError` as before.
- **Offline.** `compose-preview provision --host-natives [--apk <app.apk>]…` fetches the index and
  what each APK needs, or each library's newest build.
- **Keeping it current.** `host-natives/index.py` validates the index (`check --online` downloads and
  verifies every archive), adds new upstream versions and built-here versions whose native API is
  unchanged (`watch`), records new releases (`add-release`), and regenerates
  [`host-natives/ANDROIDX.md`](../../host-natives/ANDROIDX.md), the menu of AndroidX artifacts that
  ship native libraries (`survey`). `host-natives-watch.yml` runs it weekly; `host-natives.yml`
  publishes a recipe when its tag is new. Both open their index changes as PRs.
- **Compatibility.** Every released CLI reads the same file, so a new field must be optional and
  every entry keeps a plain `library`. `HostNativesProvisionTest` parses the index with the first
  released CLI's model.

The [release line](../RELEASING.md#the-host-natives-binaries) has the operational detail.

## The test app

[`samples/host-natives`](../../samples/host-natives) runs one check per served library (plus
Conscrypt) and logs `HostNatives: PASS|FAIL …`. Its first headless launch passed 5 of 7, which found
two bugs, both fixed in #6422:

- **graphics-path needed `GLIBC_2.43`.** The published build came from Ubuntu 26.04 runners, so it
  loaded on no older Linux; CI's verification ran on the same runners and never noticed. Linux
  builds now run in PyPA's `manylinux_2_28` image, and `build.sh` fails any library needing glibc
  newer than 2.28. The rebuild is `host-natives-graphics-path-1.0.1-r2`.
- **JNA's classes were never loaded.** Robolectric never acquires `com.sun.*`, so the sandbox handed
  `com.sun.jna` to its parent class loader, which has none of the APK's classes:
  `NoClassDefFoundError`. `SimulatorSandbox.AppLibraryConfiguration` makes an exception for
  `com.sun.jna.`.

With both, 7 of 7 pass.

## The graphics-path host build

This section records the one library built here.

### Why graphics-path, and only 1.0.1

- **The version Compose pins:** every stable Compose UI from 1.7.0-alpha08 through 1.12.1 pins
  `androidx.graphics:graphics-path:1.0.1`. Its `PathIteratorImpl` calls
  `System.loadLibrary("androidx.graphics.path")` unconditionally, so the simulator fails even at
  API 35.
- **The fixed version:** 1.1.0, which Compose UI 1.13.0-alpha02 and later and 1.13.0-beta02 pull
  in, loads the library only on Dalvik and falls back to Kotlin.
- **Why API 34+ is enough:** at API 34 and above, graphics-path walks paths with the framework
  `PathIterator`, which Robolectric 4.17's `ShadowNativePathIterator` supports. The library then
  only does `JNI_OnLoad` registration and conic-to-quadratic conversion. The code tied to Skia's
  `SkPathRef` layout (`PathIteratorPreApi34Impl`) is never reached.

### How it is built and released

- **The recipe.** [`host-natives/graphics-path/`](../../host-natives/graphics-path) pins AndroidX's
  sources by commit (`recipe.properties`) and checksum (`sources.sha256`), and compiles them
  unchanged with two stand-in headers: `host/android/api-level.h` (reports API 34) and a
  force-included `host_compat.h`. Linux gets static libstdc++ against glibc 2.28; macOS gets one
  universal arm64 + x86_64 dylib (macOS 11+). The build asserts that `JNI_OnLoad` is the only
  export.
- **The verification.** `host-natives/verify` is a standalone Robolectric build. At API 34, 35 and
  36 it checks three cases: 1.0.1 with the library matches the framework iterator's segments, 1.0.1
  without it throws `UnsatisfiedLinkError`, and 1.1.0 needs none.
- **The release line.** Releases follow the library's version, not this repository's:
  `host-natives-graphics-path-1.0.1`, then `-r2` for the glibc rebuild. `host-natives.yml` builds
  and verifies every recipe on each change and publishes only tags that don't exist yet. See
  [`RELEASING.md`](../RELEASING.md#the-host-natives-binaries).

### Gotchas met along the way

- **glibc floors.** A Linux library needs a glibc at least as new as every symbol version it links;
  built on `ubuntu-latest` it needed `sqrtf@GLIBC_2.43`. Build in `manylinux_2_28`. Debian 11
  doesn't work as a build image any more: its security archive is retired, and `apt-get` 404s.
- **Robolectric's never-acquired packages.** `com.sun.`, `java.`, `javax.`, `jdk.internal.` and
  `sun.` are always left to the parent class loader. An app library under one of them (JNA) needs an
  explicit exception in `SimulatorSandbox`.
- **Libraries Robolectric already has.** The simulator does not acquire `org.conscrypt` or
  `org.bouncycastle`, so their host builds are never used; check `SimulatorSandbox` before adding an
  index entry.
- **Bot-opened PRs.** The index PRs are opened with `GITHUB_TOKEN`, so GitHub holds their own
  `pull_request` runs for approval (`action_required`); the workflows dispatch the required checks
  instead. Auto-merge has not yet completed on such a PR.
- **Commit identity:** this repository's hooks and its `No Agent Attribution` gate reject an agent
  git identity. Cloud sandboxes often set one globally; commit as the human who owns the work.
- **Formatting the verify build:** `host-natives/verify` is outside the root build, so
  `ktfmtCheckAll` doesn't format it. Run the ktfmt CLI with `--google-style`.
- **actionlint:** it flags the `macos-15-intel` runner label because its built-in list is stale.
  GitHub accepts the label.

## Strategy C candidates beyond graphics-path

| Library | Why it might work | Why it might not |
| --- | --- | --- |
| MMKV ([#6420](https://github.com/yschimke/compose-ai-tools/issues/6420)) | Hard failure at startup in apps that use it; open-source C++ with a POSIX core | The JNI layer may use Android-only APIs (ashmem, `android/log`); not inspected |
| SQLCipher ([#6421](https://github.com/yschimke/compose-ai-tools/issues/6421)) | Open-source C; Android-only binaries | The JNI glue derives from AOSP's SQLite bindings and their helpers; the app loads the library itself, so the name has to match |
| DataStore shared counter | About four POSIX calls; sqlite-bundled is an AndroidX precedent for host targets | Not needed while the Kotlin fallback survives R8; better upstreamed into AndroidX |

Each needs the same discipline as graphics-path: a recipe beside `host-natives/graphics-path/`, with
sources pinned by commit and checksum, a verification suite that fails without the library, and an
index entry whose `release.recipe` names it.

## GPU and engine apps

A host library loads, but these apps then need something the simulator does not have.

- **Surfaces and GL.** Robolectric only shapes the API: `ShadowSurfaceView`'s holder returns `null`
  from `getSurface()` and `lockCanvas()`, `ShadowTextureView` returns a stub texture layer,
  `ShadowGLSurfaceView` starts no GL thread, and there is no EGL or GLES. The simulator now gives a
  `SurfaceView` a real surface (`SimulatorShadowSurfaceView`): an `ImageReader` behind
  `getSurface()`, read back once per frame it posts, and composited under or over its window.
  Filament renders into that surface through [`host-natives/filament`](../../host-natives/filament)
  ([#6428](https://github.com/yschimke/compose-ai-tools/issues/6428)): `filament-jni` built from
  Filament's sources with the OpenGL backend on headless EGL (Mesa's software drivers, or the host
  GPU's). `Engine.createSwapChain(surface)` gets a readable headless swap chain at the surface's size;
  each frame is read back as it ends and drawn onto the `Surface` with `lockHardwareCanvas()`, so it
  reaches the screen the way any `SurfaceView` frame does. An sRGB swap chain
  (`CONFIG_SRGB_COLORSPACE`, which apps ask for at feature level 0) gets a linear pbuffer, since Mesa
  refuses an sRGB one, and the read-back frame is sRGB-encoded instead. The GPU (or llvmpipe) renders; the one
  readback per frame is the cost. On macOS, which has no OpenGL ES (and an Android app's materials
  are OpenGL ES only, the shader model being fixed when Filament is built), the same layer runs on
  ANGLE, OpenGL ES over Metal: built from source at a pinned commit, and shipped beside the
  library as the index entry's `files` (`libEGL.dylib`, `libGLESv2.dylib`), which a CLI extracts
  into the same directory.
- **Unity.** The game's own code is ARM machine code (`libil2cpp.so`); no host build can stand in
  for it.
- **Godot.** Nobody publishes a desktop `libgodot_android.so`: its JNI layer depends on Android
  window, EGL, asset and audio APIs. [LibGodot](https://github.com/migeran/libgodot) builds the
  engine as a desktop shared library, but drives it through GDExtension, not the `GodotLib` JNI the
  APK's Java calls. A GDScript-only game's `.pck` could run in a desktop Godot of the same version,
  beside the simulator rather than in it.

## Open questions

- **Libraries without a build ID.** Most do carry one (Ink, JNA, Conscrypt, libsignal); sqlite-bundled
  and zstd-jni do not, and match by version marker and file name instead. A third-party library with
  neither falls back to the newest build. A hash of the APK's `.so` would name it, if packaging
  leaves the file byte-identical.
- **R8 and fallbacks.** Whether DataStore's Kotlin fallback survives minification. If it doesn't,
  minified APKs that use multi-process DataStore fail at their first counter use.
- **Fresco's default pipeline.** Whether it needs `imagepipeline-native` when nothing configures
  native code.

## How this was measured

- **Artifacts:** each artifact's archive index was read over HTTP range requests from
  `dl.google.com/dl/android/maven2` and `repo1.maven.org/maven2`, at the newest stable version in
  `maven-metadata.xml` unless a version is given. Native libraries were listed by extension (`.so`,
  `.dylib`, `.dll`, `.jnilib`) and directory.
- **Load sites:** found with `javap -c -p` over `classes.jar`, for methods that call
  `System.loadLibrary`, `System.load` or `Runtime.loadLibrary`.
  - "Handler" means an exception-table entry in that method for `UnsatisfiedLinkError`,
    `LinkageError`, `Error` or `Throwable`. For Sentry, Bugsnag, Datadog and Conscrypt, the callers
    were checked as well.
  - "Not checked" means the load wasn't a direct call in the artifact's own classes.
- **The AndroidX list:** taken from all 165 groups in Google Maven's index, at the latest version
  of each artifact.

All of it is a snapshot. Re-check the version and the load site before acting on a row.
