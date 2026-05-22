# Non-Gradle integration

How to drive `compose-preview` from a build system other than Gradle —
[Amper](https://amper.org), [Bazel](https://bazel.build), Buck2, Maven,
or a hand-rolled shell pipeline. The recipe is the same in every case:
**produce a `daemon-launch.json` descriptor pointing at compiled Kotlin
classes, then open a `RenderSession`**. The Gradle plugin is one producer
of that descriptor; this document is for everyone else.

The two contracts you implement against:

| Artifact | Schema | Wire-stable | Producer |
| --- | --- | --- | --- |
| `daemon-launch.json` | [`DaemonClasspathDescriptor.kt`](../gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/daemonlaunch/DaemonClasspathDescriptor.kt) (published as `ee.schimke.composeai:daemon-launch-builder`) | `schemaVersion = 1` | You — or [`DaemonLaunchBuilder`](../gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/daemonlaunch/DaemonLaunchBuilder.kt) / its `java -jar` CLI |
| `previews.json` | [`PreviewData.kt`](../gradle-plugin/preview-discovery/src/main/kotlin/ee/schimke/composeai/discovery/PreviewData.kt) (published as `ee.schimke.composeai:preview-discovery`) | `schema = "compose-previews/v1"` | You — or [`PreviewDiscovery`](../gradle-plugin/preview-discovery/src/main/kotlin/ee/schimke/composeai/discovery/PreviewDiscovery.kt) / its `java -jar` CLI |

The consumer of both is the daemon JVM (`daemon/desktop` or `daemon/android`),
driven by `RenderSessionFactory.open(...)` from the
[`render-session-api`](../render-session/api/) +
[`render-session-subprocess`](../render-session/subprocess/) Maven artifacts.

## Architecture in one diagram

```
┌─────────────────────────────┐
│ Your build system           │   Amper task, Bazel rule,
│ (Amper / Bazel / Buck2)     │   `mvn -P preview`, shell script
└───────────────┬─────────────┘
                │ produces
                ▼
┌─────────────────────────────┐    ┌────────────────────────────┐
│ <module>/                   │    │ Renderer + connector jars  │
│   build/...classes/         │    │ from Maven Central:        │
│   build/compose-previews/   │    │   - daemon-desktop or      │
│     daemon-launch.json   ◄──┼────┤     daemon-android         │
│     previews.json           │    │   - data-<ext>-connector   │
└───────────────┬─────────────┘    │   - data-<ext>-core        │
                │                  └────────────────────────────┘
                │ RenderSessionConfig.descriptorPath
                ▼
┌─────────────────────────────┐
│ render-session-subprocess   │   `SubprocessRenderSessions.open(config)`
│ (Maven Central)             │   forks the daemon JVM, handshakes over
└───────────────┬─────────────┘   JSON-RPC, returns a `RenderSession`.
                │
                ▼
┌─────────────────────────────┐
│ Daemon JVM                  │   Hosts renderer-desktop or renderer-android
│  (mainClass:                │   inside a sandbox; exposes `renderNow`,
│   ee.schimke.composeai      │   `data/fetch`, `extensions/enable`, ...
│   .daemon.DaemonMain)       │
└─────────────────────────────┘
```

The Gradle plugin is *one* implementation of "your build system" in that diagram.
The rest of this document is how to be a different one.

## Pick a backend

| Backend | Renderer | Reaches | Use when |
| --- | --- | --- | --- |
| **`renderer-desktop`** | ImageComposeScene + Skia | Compose Multiplatform Desktop / `compose.desktop.currentOs` | You're rendering CMP Desktop or JVM Compose previews. Lightest classpath; no Android machinery. |
| **`renderer-android`** | Robolectric sandbox + `captureRoboImage` | AndroidX Compose / `androidx.compose.ui` | You're rendering Jetpack Compose previews against the Android runtime. Heavier — needs Robolectric + `android.jar` on the classpath, and a working Android SDK. |

The CMP Desktop path is the simpler integration target for a first non-Gradle
implementation. Add Android later when the Desktop path is green.

## The `daemon-launch.json` contract

Wire shape (lifted from `DaemonClasspathDescriptor.kt`; field order matters
for the classpath but not the rest):

```json
{
  "schemaVersion": 1,
  "modulePath": ":app",
  "variant": "desktop",
  "enabled": true,
  "mainClass": "ee.schimke.composeai.daemon.DaemonMain",
  "javaLauncher": null,
  "classpath": [
    "/abs/path/to/daemon-desktop-<version>.jar",
    "/abs/path/to/data-render-connector-<version>.jar",
    "...other connector jars you want available...",
    "/abs/path/to/daemon-core-<version>.jar",
    "/abs/path/to/data-render-core-<version>.jar",
    "/abs/path/to/<your-module-runtime-deps...>.jar",
    "/abs/path/to/<your-module-classes/>"
  ],
  "jvmArgs": ["-Xmx1024m"],
  "systemProperties": {
    "composeai.daemon.protocolVersion": "1",
    "composeai.daemon.modulePath": ":app",
    "composeai.daemon.moduleId": ":app",
    "composeai.daemon.moduleProjectDir": "/abs/path/to/<module>",
    "composeai.daemon.workspaceRoot": "/abs/path/to/<workspace-root>",
    "composeai.daemon.previewsJsonPath": "/abs/path/to/previews.json",
    "composeai.harness.previewsManifest": "/abs/path/to/previews.json",
    "composeai.render.outputDir": "/abs/path/to/build/compose-previews/renders",
    "composeai.daemon.historyDir": "/abs/path/to/.compose-preview-history",
    "composeai.daemon.userClassDirs": "/abs/path/to/<your-module-classes/>",
    "composeai.daemon.idleTimeoutMs": "5000"
  },
  "workingDirectory": "/abs/path/to/<module>",
  "manifestPath": "/abs/path/to/previews.json"
}
```

### Field-by-field

| Field | Required | Meaning |
| --- | --- | --- |
| `schemaVersion` | yes | Pin to `1`. Bumped on breaking changes; the subprocess factory rejects anything else with a clear error. |
| `modulePath` | yes | Logical module id. The daemon uses it as a label; for non-Gradle callers, anything stable identifying the module is fine (`":app"`, `"app:debug"`, `"compose-desktop"`). |
| `variant` | yes | Build variant. `"desktop"` for CMP; `"debug"` / `"release"` for Android. Informational. |
| `enabled` | yes | Mirror of the user's `composePreview.daemon.enabled` switch. `true` for normal use. `RenderSessionConfig.forceEnabled` overrides on the consumer side. |
| `mainClass` | yes | `ee.schimke.composeai.daemon.DaemonMain` for both backends. |
| `javaLauncher` | no (`null` ok) | Absolute path to a `java` binary. `null` falls back to the JDK the consumer's JVM is using. |
| `classpath` | yes, ordered | See [Classpath layering](#classpath-layering). |
| `jvmArgs` | yes | At minimum `["-Xmx1024m"]`. Renderer-android needs more `--add-opens` flags — see the gradle plugin's `AndroidPreviewClasspath.buildJvmArgs` for the full set. |
| `systemProperties` | yes | See [System properties](#system-properties). |
| `workingDirectory` | yes | Daemon JVM `cwd`. Module project dir. |
| `manifestPath` | yes | Absolute path to `previews.json`. Same as `composeai.daemon.previewsJsonPath` in sysprops. |

### Classpath layering

Order is load-bearing — the renderer and its connectors must precede the
user's runtime so version collisions resolve in favour of the pinned
renderer-side classes:

1. **Renderer jar** — `daemon-desktop-<v>.jar` or `daemon-android-<v>.jar`.
   Bundles `DaemonMain` (the JSON-RPC server) plus its backend's renderer.
2. **Data extension connector jars** — `data-<extension>-connector-<v>.jar`.
   One per extension you want available (`a11y`, `theme`, `history`, …).
   Each connector pulls its `core` jar transitively.
3. **`daemon-core-<v>.jar`** — JSON-RPC protocol, shared types.
4. **`data-render-core-<v>.jar`**, plus the `*-core` of each connector you
   pulled. These are the producer-side data-product implementations.
5. **User Compose runtime** — `compose.desktop.currentOs`, AndroidX Compose,
   `material3`, etc. Pulled out of your build system's resolver.
6. **User compiled classes** — directory or jar containing your `@Preview`
   functions.

The Gradle plugin's order is:
1) daemon module's classes;
2) `AndroidPreviewClasspath.buildTestClasspath()` output;
3) AGP additions for Android.
Match that broad shape and you'll be fine.

### System properties

The minimum set the daemon reads at boot:

| Property | Purpose |
| --- | --- |
| `composeai.daemon.protocolVersion` | Always `"1"` for schema v1. |
| `composeai.daemon.modulePath` | Same as `modulePath` in the descriptor. |
| `composeai.daemon.moduleId` | Same as `modulePath`. Reported back via `initialize`. |
| `composeai.daemon.moduleProjectDir` | Absolute path to the module dir. |
| `composeai.daemon.workspaceRoot` | Repo root (for git-provenance metadata). |
| `composeai.daemon.previewsJsonPath` | Path to `previews.json`. The daemon seeds its preview index from this on startup. |
| `composeai.harness.previewsManifest` | Same path. Legacy alias still read by the harness path. |
| `composeai.render.outputDir` | Where rendered PNGs land. The daemon writes here; you read from here. |
| `composeai.daemon.historyDir` | Where per-render archives go. Pick any dir; defaults under `.compose-preview-history/`. |
| `composeai.daemon.userClassDirs` | `:`-separated list of directories holding user `.class` files. Used by classloader hot-swap on `fileChanged` notifications. |
| `composeai.daemon.idleTimeoutMs` | Idle exit timeout. `5000` is the gradle plugin's default. |

The full list lives in
[`docs/daemon/CONFIG.md`](daemon/CONFIG.md); the above is the minimum a
non-Gradle integration must populate.

## The `previews.json` contract

Discovery in the Gradle plugin is `DiscoverPreviewsTask` — it runs
[ClassGraph](https://github.com/classgraph/classgraph) over the compiled
class directories and dependency jars and emits a manifest:

```json
{
  "schema": "compose-previews/v1",
  "module": "<modulePath>",
  "variant": "<variant>",
  "previews": [
    {
      "id": "<fqClass>.<function>[_<variant-name>]",
      "functionName": "<function>",
      "className": "<fqClass>",
      "sourceFile": "<relative-path-or-null>",
      "params": { "...preview-annotation params..." },
      "captures": [ { "renderOutput": "renders/<id>.png" } ]
    }
  ]
}
```

For a non-Gradle integration the cheapest route is to scan the same way:
pull `io.github.classgraph:classgraph` into your build, scan the
user-classes dir, and emit the JSON. The fields you must populate are:

- `id`: a deterministic identifier the daemon and renderer pass through.
  `<className>.<functionName>` is the Gradle-plugin convention; the daemon
  doesn't enforce a format.
- `className`, `functionName`: enough to load and invoke the composable
  via reflection.
- `captures[].renderOutput`: relative path under
  `composeai.render.outputDir` where the PNG lands.

Multi-preview annotations (`@PreviewParameter`, `@Preview` arrays, custom
multipreviews) need fan-out at discovery time. The gradle plugin's
`PreviewData.kt` is the reference. Until a non-Gradle discovery library is
extracted, the practical path is to either
(a) drive `DiscoverPreviewsTask` standalone via the Tooling API in a
synthetic Gradle project, or
(b) author `previews.json` by hand for a known fixed set.

## Driving a `RenderSession`

Once you have `daemon-launch.json` and `previews.json` on disk:

```kotlin
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File

fun main() {
  val descriptor = File("<your-module>/build/compose-previews/daemon-launch.json")
  val config = RenderSessionConfig(
    descriptorPath = descriptor,
    workspaceRoot = File("/abs/path/to/workspace-root"),
  )

  SubprocessRenderSessions.open(config).use { session ->
    val result = session.renderNow(
      previewIds = listOf("com.example.MyPreviewsKt.Greeting"),
    )
    println("Rendered ${result.previews.size} previews:")
    for (p in result.previews) println("  ${p.id} → ${p.renderOutput}")
  }
}
```

Maven coordinates (pre-1.0):

```kotlin
implementation("ee.schimke.composeai:render-session-api:<version>")
implementation("ee.schimke.composeai:render-session-subprocess:<version>")
```

## Data extensions (a11y, hierarchy, etc.)

Each data extension ships as two jars on the daemon's classpath:
`data-<ext>-core-<v>.jar` and `data-<ext>-connector-<v>.jar`. Include the
connector pair for every extension you want available; the daemon will
advertise them through `listExtensions()`.

Enable on a per-session basis, then fetch:

```kotlin
session.enableExtensions(listOf("a11y"))
val a11y = session.fetchData(
  previewId = "com.example.MyPreviewsKt.Greeting",
  kind = "a11y/atf",
)
println(a11y.payload)
```

The renderer re-renders automatically when an extension is marked
`requiresRerender = true` (a11y is). See [`DATA-PRODUCTS.md`](daemon/DATA-PRODUCTS.md)
for the full kind catalogue.

## Worked examples

Build-system-specific recipes (JetBrains Amper, Bazel) live in
[`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib),
the dedicated repo for non-Gradle integrations. The recipes there
drive the published `ee.schimke.composeai:preview-discovery` +
`:daemon-launch-builder` + `:render-cli` artifacts through
`java -jar` from a build-system-native rule. This document is the
contract spec the recipes implement against.

## Limitations and follow-ups

- **Renderer-android requires Robolectric.** Driving the Android renderer
  from Bazel needs a working Robolectric + AGP classpath inside Bazel —
  doable but well off the beaten path. Start with renderer-desktop.
- **`previews.json` schema versioning.** The wire field is `"schema":
  "compose-previews/v1"`. The daemon tolerates unknown fields; clients
  that produce manifests should round-trip schema-stable fields.
- **Maven Central artifacts are pre-1.0.** Pin to a specific version;
  expect API changes across minor versions until 1.0.

## Reference

- Descriptor schema source: [`DaemonClasspathDescriptor.kt`](../gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/daemonlaunch/DaemonClasspathDescriptor.kt) (published as `ee.schimke.composeai:daemon-launch-builder`)
- Wire protocol: [`docs/daemon/PROTOCOL.md`](daemon/PROTOCOL.md)
- Data products catalogue: [`docs/daemon/DATA-PRODUCTS.md`](daemon/DATA-PRODUCTS.md)
- `RenderSession` API: [`render-session/api/.../RenderSession.kt`](../render-session/api/src/main/kotlin/ee/schimke/composeai/render/session/RenderSession.kt)
- Reference producer (the Gradle plugin's bootstrap task): [`DaemonBootstrapTask.kt`](../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/daemon/DaemonBootstrapTask.kt) — now a thin adapter over `DaemonLaunchBuilder`
- Standalone preview-discovery library + CLI: [`PreviewDiscovery.kt`](../gradle-plugin/preview-discovery/src/main/kotlin/ee/schimke/composeai/discovery/PreviewDiscovery.kt) + [`PreviewDiscoveryCli.kt`](../gradle-plugin/preview-discovery/src/main/kotlin/ee/schimke/composeai/discovery/PreviewDiscoveryCli.kt)
- Standalone daemon-launch-builder library + CLI: [`DaemonLaunchBuilder.kt`](../gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/daemonlaunch/DaemonLaunchBuilder.kt) + [`DaemonLaunchBuilderCli.kt`](../gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/daemonlaunch/DaemonLaunchBuilderCli.kt)
- Render CLI driving `SubprocessRenderSessions`: [`RenderCli.kt`](../render-cli/src/main/kotlin/ee/schimke/composeai/render/cli/RenderCli.kt)
- Contract test demonstrating the recipe end-to-end: [`render-session/subprocess/src/test/.../NonGradleContractTest.kt`](../render-session/subprocess/src/test/kotlin/ee/schimke/composeai/render/session/subprocess/NonGradleContractTest.kt)
- Build-system-specific fixtures + end-to-end tests: [`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib)
