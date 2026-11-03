# Host natives test app

An Android app with one check per native library that
[`host-natives/index.json`](../../host-natives/index.json) serves, plus Conscrypt. It shows each check's result on
screen and logs it under the `HostNatives` tag:

```
I/HostNatives: PASS sqliteJni SQLite 3.50.1
I/HostNatives: PASS jnidispatch strlen = 12, JNA 5.17.0
I/HostNatives: DONE 7/7 passed
```

| Library | Dependency | Check |
| --- | --- | --- |
| `androidx.graphics.path` | Compose UI (pins graphics-path 1.0.1) | `Path.iterator()` |
| `sqliteJni` | `androidx.sqlite:sqlite-bundled` | `SELECT sqlite_version()` |
| `ink` | `androidx.ink:ink-strokes` | build a `Stroke` |
| `zstd-jni-<version>` | `com.github.luben:zstd-jni` | compress and decompress |
| `jnidispatch` | `net.java.dev.jna:jna` | `strlen` from libc |
| `conscrypt_jni` | `org.conscrypt:conscrypt-android` | SHA-256 through its provider |
| `signal_jni` | `org.signal:libsignal-android` | generate a Curve25519 key pair |

Run it in the simulator, headless, and read the results:

```
./gradlew :samples:host-natives:assembleDebug
compose-preview launch samples/host-natives/build/outputs/apk/debug/host-natives-debug.apk \
  --headless --control stdio --sdk 35 | grep HostNatives
```

`--sdk 34` or higher: graphics-path's host build serves API 34 and up. Without `--control stdio`
a headless launch has nothing to drive it; with it, keep stdin open (`sleep 600 | …`) until `DONE`.

To try an index other than the one on `main` (a build of your own, say), set
`COMPOSE_PREVIEW_HOST_NATIVES_INDEX` to its path or URL.

Conscrypt has no entry in the index, and needs none: the simulator never loads the APK's
`org.conscrypt` classes (`SimulatorSandbox` leaves them to the runtime classpath), and Robolectric's
own desktop Conscrypt (`conscrypt-openjdk-uber`) brings its own library. Its check is kept so a
change there shows up here.
