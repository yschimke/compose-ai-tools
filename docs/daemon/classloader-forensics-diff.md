# Classloader forensics diff: standalone-control vs daemon-subject

- A (control): `/home/yuri/workspace/compose-ai-tools/renderer-android/build/reports/classloader-forensics/standalone.json`
- B (subject): `/home/yuri/workspace/compose-ai-tools/renderer-android-daemon/build/reports/classloader-forensics/daemon.json`
- Survey size: A=32, B=38, both=31
- Unchanged: 13/31

## 1. Smoking gun — same FQN, different classloader / codeSource / bytecode

| FQN | A loader | B loader | A location | B location | classloader-id ≠ | codeSource ≠ | moduleHash ≠ |
|---|---|---|---|---|---|---|---|
| `androidx.compose.runtime.Composer` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `androidx.compose.runtime.Composition` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `androidx.compose.runtime.Recomposer` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `androidx.compose.ui.test.junit4.AndroidComposeTestRule` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `androidx.compose.ui.test.junit4.ComposeTestRule` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `androidx.compose.runtime.reflect.ComposableMethod` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `com.github.takahirom.roborazzi.RoborazziKt` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `com.github.takahirom.roborazzi.RoborazziOptions` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `org.robolectric.RuntimeEnvironment` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `org.robolectric.shadows.ShadowApplication` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `org.robolectric.shadows.ShadowResources` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `android.app.Activity` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `android.content.res.Resources` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `androidx.activity.ComponentActivity` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `android.os.Looper` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `kotlinx.coroutines.CoroutineDispatcher` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `androidx.lifecycle.ViewModel` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |
| `androidx.compose.runtime.internal.ComposableLambdaImpl` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x301eda63` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x4f388b95` | _(none)_ | _(none)_ | ⚠️ yes | no | no |

## 2. Robolectric instrumentation flag mismatches

_None._ Every shared class has the same `robolectricInstrumented` flag in A and B.

## 3. Classes only in A

- `ee.schimke.composeai.renderer.ClassloaderForensicsFixturesKt`

## 4. Classes only in B

- `ee.schimke.composeai.daemon.RedFixturePreviewsKt`
- `ee.schimke.composeai.daemon.RenderEngine`
- `ee.schimke.composeai.daemon.RobolectricHost`
- `ee.schimke.composeai.daemon.SandboxHoldingRunner`
- `ee.schimke.composeai.daemon.UserClassLoaderHolder`
- `ee.schimke.composeai.daemon.bridge.DaemonHostBridge`
- `java.net.URLClassLoader`

## 5. Robolectric runtime-config diffs

_None._ Both runs report the same Robolectric config snapshot.

## 6. Other changes (loader-type only / package-version drift)

_None._

