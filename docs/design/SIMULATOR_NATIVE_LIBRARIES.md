# Native libraries in the simulator

**Survey + plan + handover**, measured 2026-11-03 against Google Maven and Maven Central. Tracks
[#6342](https://github.com/yschimke/compose-ai-tools/issues/6342).

`compose-preview launch` runs an APK's code on the host JVM, so it cannot load the APK's
`lib/<abi>/*.so` files. Those files are Android ELF binaries linked against bionic. This page records
which common libraries ship native code, what each one does when its library fails to load, and which
of four fixes applies to it. It ends with the state of the one fix already built: a host build of
AndroidX graphics-path.

## Summary

- **Few libraries are affected, and most degrade on their own.** Of 1,909 AndroidX artifacts, 20 ship
  `.so` files. Among the common third-party libraries surveyed below, the four NDK crash reporters
  (Sentry, Crashlytics, Bugsnag, Datadog) all catch the load failure and carry on without native
  crash capture. DataStore falls back to an in-process counter.
- **One library breaks nearly every Compose app: graphics-path 1.0.1.** Every stable Compose UI up
  to 1.12.1 pins it, and it loads its library unconditionally. That one is fixed by a host build
  published from this repository (see [graphics-path](#the-graphics-path-host-build)); wiring it
  into the simulator is the next PR.
- **Several libraries already have an upstream host build at the same version**:
  - AndroidX sqlite-bundled and Ink.
  - Realm Kotlin, ObjectBox and ONNX Runtime.
  - zstd-jni and JNA.
  - Conscrypt and libsignal, but under a different library name.

  For these, the fix is to fetch the host artifact at exactly the APK's version and put its library
  on the simulator's `java.library.path`. No source build is needed.
- **The libraries that fail hard with no host build** are MMKV, SQLCipher, ML Kit's bundled models,
  LiteRT/TensorFlow Lite and MediaPipe. So are GPU, camera and media libraries (graphics-core, WebGPU,
  Filament, MapLibre, WebRTC), but those need surfaces the simulator doesn't have anyway, so a
  library wouldn't help.

## What the simulator does today

Recorded in #6342 and handled by `simulator/host/.../NativeCode.java`:

- **Where the lookup happens:** an app's `System.loadLibrary("x")` searches the simulator JVM's real
  `java.library.path`. The APK's classes are converted from dex but not rewritten around library
  loading.
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
| `graphics:graphics-path` 1.0.1 | `PathIteratorImpl.<clinit>`, no handler | **Fails at first `Path.iterator()`**; every stable Compose UI ≤ 1.12.1 pins 1.0.1 | Built here | **C: done**, wiring next |
| `graphics:graphics-path` 1.1.0 | Same class, gated on `java.vm.name == dalvik` | Works; Kotlin fallback | Not needed | A |
| `datastore:datastore-core-android` 1.3.0 | `SharedCounter.Factory.<clinit>`, Dalvik check, catches `Throwable` | Works; in-process counter | None | A |
| `appsearch:appsearch-local-storage` 1.1.0 / 1.2.0-alpha02 | `IcingLibraryLoader.loadLibrary`, no handler | Would fail | None | **D: done** (`AppSearchSessions`) |
| `sqlite:sqlite-bundled-android` 2.7.1 / 2.8.0-alpha03 | `NativeLibraryLoader.loadLibrary`, no handler | **Fails at first connection** | `sqlite-bundled-jvm`: linux x64/arm64, macOS arm64, windows x64 | **B** |
| `ink:ink-nativeloader-android` 1.0.0 / 1.1.0-alpha10 | `NativeLoader.loadSynchronous`, no handler | **Fails at first Ink use** | `ink-nativeloader-jvm`: linux x86_64 at 1.0.0; plus macOS arm64 at 1.1.0-alpha10 | **B** |
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
| SQLCipher (`net.zetetic:sqlcipher-android` 4.20.0; legacy `android-database-sqlcipher` 4.5.4) | `libsqlcipher.so` (4 ABIs) | No load in the library's classes; the app loads it | None | C candidate |
| MMKV (`com.tencent:mmkv` / `mmkv-static` 2.4.3) | `libmmkv.so` (**arm64-v8a, x86_64 only**) | `MMKV.tryLoadNativeLib`, no handler | None | C candidate |
| Realm Java (`io.realm:realm-android-library` 10.19.0) | `librealm-jni.so` | `RealmCore.loadCorrectLibrary` catches `Throwable`, tries variants | None | Out of scope (SDK deprecated) |
| Realm Kotlin (`io.realm.kotlin:cinterop-android` 3.0.0) | `librealmc.so` | `loadAndroidNativeLibs`, no handler | `cinterop-jvm` 3.0.0: linux, macOS, windows, same name `realmc` | B (SDK deprecated) |
| ObjectBox (`io.objectbox:objectbox-android` → `objectbox-android-db` 5.4.2) | `libobjectbox-jni.so` | `NativeLibraryLoader.<clinit>`, checks `os.name`/`java.vendor`, catches `UnsatisfiedLinkError` | `objectbox-linux`, `-linux-arm64`, `-macos`, `-windows` 5.4.2 | B |
| SQLDelight (`app.cash.sqldelight:android-driver` 2.4.1), Room with framework SQLite | None | | | Not affected |

ObjectBox's loader checks `os.name` and `java.vendor` for Android. On the host JVM the check fails,
so it looks for its desktop library name (`objectbox-jni-linux-x64`, `-macos`) rather than the
Android one. That's inferred from the loader's string constants and not run. If it holds, the host
artifact's library works under its own name.

### Security and crypto

| Library | Natives | Load site and handler | Host build | Strategy |
| --- | --- | --- | --- | --- |
| Conscrypt (`org.conscrypt:conscrypt-android` 2.8.0) | `libconscrypt_jni.so` | `NativeCryptoJni.init` from `NativeCrypto.<clinit>`, which catches `UnsatisfiedLinkError` | `conscrypt-openjdk-uber` 2.8.0: linux x86_64/aarch_64, osx x86_64/aarch_64, windows; library named `conscrypt_openjdk_jni-<os>-<arch>` | B with rename |
| libsignal (`org.signal:libsignal-android` 0.86.5) | `libsignal_jni.so` | In the shared client classes | `libsignal-client` 0.86.5: linux amd64, macOS amd64/aarch64, windows; arch-suffixed names | B with rename |
| lazysodium (`com.goterl:lazysodium-android` 5.2.0) | `libsodium.so`, loaded through JNA | Through JNA | `lazysodium-java` 5.2.0 bundles libsodium for linux, macOS, windows | B (with JNA) |
| RootBeer (`com.scottyab:rootbeer-lib` 0.1.2) | `libtoolChecker.so` | `RootBeerNative.<clinit>` catches `UnsatisfiedLinkError` | None | A (native check skipped) |
| Tink (`tink-android` 1.24.0), Play Integrity 1.6.0, SafetyNet 18.1.0 | None | | | Not affected |

On Conscrypt: an earlier session of this work checked that `NativeCrypto` declares the same 369
natives in the Android and OpenJDK artifacts at 2.8.0. Because the static block catches the error,
`Conscrypt.isAvailable()` should return false without a library. That's inferred from the handler
and not run. If so, app code that checks it keeps working, and code that installs the provider
unconditionally fails there.

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
| ONNX Runtime (`onnxruntime-android` 1.31.1) | `libonnxruntime.so`, `libonnxruntime4j_jni.so` | `OnnxRuntime` checks `java.vendor` for Android and honours `onnxruntime.native.*` properties | `onnxruntime` 1.31.1: linux x64/aarch64, macOS aarch64, windows x64 (**no macOS x86_64**) |

Running inference in the simulator is unlikely to be worth doing. ONNX Runtime is the exception,
where B is cheap.

### Networking and general-purpose

| Library | Natives | Notes | Strategy |
| --- | --- | --- | --- |
| Cronet, embedded (`org.chromium.net:cronet-embedded` 500.1.0 → `cronet-bundled`) | `libcronet.<chromium-version>.so` | `cronet-fallback` (no natives) is the Java provider | Out of scope; apps that also ship the fallback provider should keep working (not run) |
| zstd-jni (`com.github.luben:zstd-jni` 1.5.7-25) | AAR: `libzstd-jni-1.5.7-25.so`; JAR: 18 desktop builds incl. darwin x86_64/aarch64, linux amd64/aarch64 | One coordinate, both packagings; the version is in the library name | B |
| JNA (`net.java.dev.jna:jna` 5.19.1) | AAR: `libjnidispatch.so`; JAR: 25 desktop builds | `Native.loadNativeDispatchLibrary` has a Dalvik check and honours `jna.boot.library.path` | B |
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

## Shape of the general mechanism (strategy B)

One table, keyed on what the APK contains, gives the host artifact to fetch:

```text
android library name + library version -> host coordinate + version, per-platform path in the archive, file name to load as
```

At launch the CLI would:

1. List the APK's `lib/<abi>/*.so` names, which are already known from the APK.
2. Read the library versions: `META-INF/*.version` for AndroidX; for others, see
   [open questions](#open-questions).
3. Look each pair up in the table.
4. Fetch and extract each match into a cache directory per library and version. Under
   `COMPOSE_PREVIEW_NO_NETWORK`, refuse as the other provisioners do.
5. Rename where the table says so. `System.loadLibrary("conscrypt_jni")` maps to
   `libconscrypt_jni.so` on Linux and `libconscrypt_jni.dylib` on macOS.
6. Put the directories on the simulator's `java.library.path`.

Two cautions:

- **Load order:** a host build may depend on a second library loaded first; ONNX Runtime's JNI
  library needs `libonnxruntime`. Each table entry has to carry the whole set.
- **Version guessing:** a wrong-version library is worse than none. It loads, then fails at a random
  JNI call. Prefer no entry to a guessed version.

## The graphics-path host build

This section is the handover for the work done so far.

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

### What exists (merged in [#6405](https://github.com/yschimke/compose-ai-tools/pull/6405))

- **`host-natives/build.sh`:**
  - Fetches AndroidX's graphics-path C++ at the commit pinned in
    `host-natives/graphics-path/upstream.properties` and checks it against `sources.sha256`.
  - Compiles it unchanged, with two stand-in headers: `host/android/api-level.h` (reports API 34)
    and a force-included `host_compat.h`.
  - Produces a Linux x86_64 library (static libstdc++, glibc 2.4 floor) and a universal arm64 +
    x86_64 macOS dylib (macOS 11+).
  - Asserts that `JNI_OnLoad` is the only export.
- **`host-natives/verify`:** a standalone Robolectric build, not in the root settings. At API 34,
  35 and 36 it checks three cases:
  - graphics-path 1.0.1 with the library matches the framework iterator's segments in both conic
    modes.
  - 1.0.1 without the library throws `UnsatisfiedLinkError`.
  - 1.1.0 with no library matches as well.
- **`.github/workflows/host-natives.yml`** ("Host Natives"):
  - Builds on `ubuntu-latest` and `macos-latest`.
  - Verifies the packed tarballs on Linux x86_64, macOS arm64 and `macos-15-intel`.
  - Publishes only on a manual dispatch from `main` with `publish` ticked. That creates a
    `host-natives-v<VERSION>` prerelease, never marked latest, which the plugin release train, the
    install action and the installers ignore.
- **The release-line rules** are in [`RELEASING.md`](../RELEASING.md#the-host-natives-binaries).

### State at handover

- The workflow passed on all three verify runners in the PR.
- **Nothing is published yet.** Someone with write access has to run "Host Natives" from `main`
  with `publish` ticked. That creates `host-natives-v1.0.0` with
  `host-natives-linux-x86_64-1.0.0.tar.gz`, `host-natives-macos-universal-1.0.0.tar.gz` and
  `SHA256SUMS`. Each tarball holds the library, `host-natives.properties` and `NOTICE`.
- The branch `agent/host-natives-simulator-a489` was cut from `main` for the wiring and carries only
  this document.

### Next: wiring it into the simulator

1. **Pin.** Add `host-natives = "1.0.0"` to `gradle/libs.versions.toml` beside `xr-composite`. Bake
   it into the CLI's version properties the way `xrCompositeVersion` is in `cli/build.gradle.kts`,
   and expose it as `HOST_NATIVES_VERSION` in `Version.kt`.
2. **`HostNativesProvision.kt`**, modelled on `XrCompositeProvision.kt`:
   - **Platforms:** Linux on amd64 or x86_64 maps to `linux-x86_64`; macOS on any architecture maps
     to `macos-universal`; anything else gets nothing.
   - **URL:** `https://github.com/yschimke/compose-ai-tools/releases/download/host-natives-v<ver>/host-natives-<platform>-<ver>.tar.gz`.
     The tag prefix is `host-natives-v`, not `v`.
   - **Cache:** `${XDG_CACHE_HOME:-~/.cache}/composeai/host-natives/<ver>/<platform>`.
   - **Complete when** `host-natives.properties` and the library file are present.
   - **Same as the XR version:** `NetworkPolicy.require`, staging then an atomic move,
     failure that logs and skips, and the fetch hook for tests.
3. **Launch.** In `LaunchCommand.simulatorCommand`, add `-Djava.library.path=<cache><sep><existing>`
   before `options.jvmOptions`, so a user's own `-Djava.library.path` wins.
   - Add it **only when `options.sdk >= 34`**. Below API 34, graphics-path would use the
     Skia-layout code, and the host build's stub reports API 34.
   - The simulator's minimum SDK is lower (#6342 reproduced at API 33). Those launches keep today's
     clear error.
   - The function is pure, so test it in `LaunchCommandTest`, and add a `HostNativesProvisionTest`.
4. **Offline.** Add a `--host-natives` part to `ProvisionCommand`, beside `--xr`.
5. **CI.** Add a pin check like the `xr-composite-pin` job in `ci.yml`: the pinned release exists
   and carries both tarballs.
6. **Docs.** Update the "Native code" bullet in [`SIMULATOR.md`](../SIMULATOR.md).
7. **Evidence.** `samples/android` bundles graphics-path 1.0.1. Launch it at `--sdk 34` or higher on
   a screen that iterates a `Path`, before and after.

The render lanes (`BundleRenderer`, `BundleDaemonCommand`) share `AndroidBundleLaunch().jvmArgs()`.
Whether previews hit graphics-path 1.0.1 too is not checked.

### Gotchas met along the way

- **Commit identity:** this repository's hooks and its `No Agent Attribution` gate reject an agent
  git identity. Cloud sandboxes often set one globally; commit as the human who owns the work.
- **JDK 17 for the hook:** the pre-commit hook runs `ktfmtCheckAll`, which needs a JDK 17 toolchain.
  In a sandbox, `CLOUD_JDK_MAJORS=17 scripts/setup-cloud-jdk.sh`, then `./gradlew --stop`.
- **Formatting the verify build:** `host-natives/verify` is outside the root build, so
  `ktfmtCheckAll` doesn't format it. Run the ktfmt CLI with `--google-style`.
- **actionlint:** it flags the `macos-15-intel` runner label because its built-in list is stale.
  GitHub accepts the label.

## Strategy C candidates beyond graphics-path

| Library | Why it might work | Why it might not |
| --- | --- | --- |
| MMKV | Hard failure at startup in apps that use it; open-source C++ with a POSIX core | The JNI layer may use Android-only APIs (ashmem, `android/log`); not inspected |
| SQLCipher | Open-source C; Android-only binaries | The JNI glue derives from AOSP's SQLite bindings and their helpers; the app loads the library itself, so the name has to match |
| DataStore shared counter | About four POSIX calls; sqlite-bundled is an AndroidX precedent for host targets | Not needed while the Kotlin fallback survives R8; better upstreamed into AndroidX |

Each needs the same discipline as graphics-path: sources pinned by commit and checksum, a
verification suite that fails without the library, and its own entry on the host-natives line.

## Open questions

- **Third-party version detection.** AndroidX writes `META-INF/*.version`; most third-party
  libraries don't. Candidates per library:
  - A version string in the library's classes, such as a `BuildConfig` or `VERSION` constant.
  - The library file name: zstd-jni and Cronet put the version there.
  - A hash of the APK's `.so`, mapped back to a release.

  The last is exact but needs a table of hashes per release.
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
