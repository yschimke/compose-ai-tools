# Classloader forensics diff: standalone-control vs daemon-subject

- A (control): `/home/yuri/workspace/compose-ai-tools/.claude/worktrees/agent-a1d57e023ed253ee7/renderer-android/build/reports/classloader-forensics/standalone.json`
- B (subject): `/home/yuri/workspace/compose-ai-tools/.claude/worktrees/agent-a1d57e023ed253ee7/renderer-android-daemon/build/reports/classloader-forensics/daemon.json`
- Survey size: A=32, B=38, both=31
- Unchanged: 13/31

## 1. Smoking gun — same FQN, different classloader / codeSource / bytecode

| FQN | A loader | B loader | A location | B location | classloader-id ≠ | codeSource ≠ | moduleHash ≠ |
|---|---|---|---|---|---|---|---|
| `androidx.compose.runtime.Composer` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `runtime-runtime.jar` | ⚠️ yes | ⚠️ yes | no |
| `androidx.compose.runtime.Composition` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `runtime-runtime.jar` | ⚠️ yes | ⚠️ yes | no |
| `androidx.compose.runtime.Recomposer` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `runtime-runtime.jar` | ⚠️ yes | ⚠️ yes | no |
| `androidx.compose.ui.test.junit4.AndroidComposeTestRule` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `ui-test-junit4-runtime.jar` | ⚠️ yes | ⚠️ yes | no |
| `androidx.compose.ui.test.junit4.ComposeTestRule` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `ui-test-junit4-runtime.jar` | ⚠️ yes | ⚠️ yes | no |
| `androidx.compose.runtime.reflect.ComposableMethod` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `runtime-runtime.jar` | ⚠️ yes | ⚠️ yes | no |
| `com.github.takahirom.roborazzi.RoborazziKt` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `roborazzi-1.59.0-runtime.jar` | ⚠️ yes | ⚠️ yes | no |
| `com.github.takahirom.roborazzi.RoborazziOptions` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `roborazzi-core-release-runtime.jar` | ⚠️ yes | ⚠️ yes | no |
| `org.robolectric.RuntimeEnvironment` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `shadows-framework-4.16.1.jar` | ⚠️ yes | ⚠️ yes | no |
| `org.robolectric.shadows.ShadowApplication` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `shadows-framework-4.16.1.jar` | ⚠️ yes | ⚠️ yes | no |
| `org.robolectric.shadows.ShadowResources` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `shadows-framework-4.16.1.jar` | ⚠️ yes | ⚠️ yes | no |
| `android.app.Activity` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `android.jar` | ⚠️ yes | ⚠️ yes | no |
| `android.content.res.Resources` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `android.jar` | ⚠️ yes | ⚠️ yes | no |
| `androidx.activity.ComponentActivity` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `activity-1.13.0-runtime.jar` | ⚠️ yes | ⚠️ yes | no |
| `android.os.Looper` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `android.jar` | ⚠️ yes | ⚠️ yes | no |
| `kotlinx.coroutines.CoroutineDispatcher` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `kotlinx-coroutines-core-jvm-1.9.0.jar` | ⚠️ yes | ⚠️ yes | no |
| `androidx.lifecycle.ViewModel` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `lifecycle-viewmodel-release-runtime.jar` | ⚠️ yes | ⚠️ yes | no |
| `androidx.compose.runtime.internal.ComposableLambdaImpl` | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` | _(none)_ | `runtime-runtime.jar` | ⚠️ yes | ⚠️ yes | no |

## 2. Robolectric instrumentation flag mismatches

| FQN | A instrumented? | B instrumented? | A loader | B loader |
|---|---|---|---|---|
| `androidx.compose.runtime.Composer` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `androidx.compose.runtime.Composition` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `androidx.compose.runtime.Recomposer` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `androidx.compose.ui.test.junit4.AndroidComposeTestRule` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `androidx.compose.ui.test.junit4.ComposeTestRule` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `androidx.compose.runtime.reflect.ComposableMethod` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `com.github.takahirom.roborazzi.RoborazziKt` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `com.github.takahirom.roborazzi.RoborazziOptions` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `org.robolectric.RuntimeEnvironment` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `org.robolectric.shadows.ShadowApplication` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `org.robolectric.shadows.ShadowResources` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `android.app.Activity` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `android.content.res.Resources` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `androidx.activity.ComponentActivity` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `android.os.Looper` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `kotlinx.coroutines.CoroutineDispatcher` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `androidx.lifecycle.ViewModel` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |
| `androidx.compose.runtime.internal.ComposableLambdaImpl` | true | false | `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader@0x65a15628` | `jdk.internal.loader.ClassLoaders$AppClassLoader@0x5ffd2b27` |

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

