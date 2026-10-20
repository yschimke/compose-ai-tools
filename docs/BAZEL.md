# Bazel and configurable build integration

`compose-preview bazel` builds an existing Compose Multiplatform **JVM/Desktop**
target, discovers its compiled `@Preview` functions and renders through the usual
desktop daemon. No Gradle preview plugin, IntelliJ installation, Bazel distribution
or Android SDK is added to the CLI. The pure JVM discovery library and descriptor
builder are reused; renderer sidecars are provisioned on first prepare/render.

## Normal commands and build selection

Normal `list`, `render`, and `show` now select the build system from the repository:

```sh
compose-preview list
compose-preview show --filter Greeting --json
compose-preview render --filter Greeting --output greeting.png
```

A Buck2 project (`.buckroot` / `.buckconfig`) uses the same driver through a BXL
script; see [BUCK.md](BUCK.md).

Gradle settings (`settings.gradle.kts` / `settings.gradle`) win within a workspace,
with the existing wrapper fallback. Otherwise `MODULE.bazel`, `WORKSPACE[.bazel]`,
`.blazerc`, or BUILD files select the Bazel-family driver. Nested workspaces and
repository boundaries prevent selecting an unrelated outer build. Use
`--build-system gradle|bazel|blaze` to override a mixed checkout and `--project`
to select a workspace from elsewhere. `list`/`show --json` use the same versioned
CLI response on both paths, including capture paths, hashes and changed state.

The explicit form remains available: `compose-preview bazel list|render|show`.
It uses the same normal result envelope; `prepare` additionally prints the prepared
descriptor path. The default aspect package is installed on the first
build/discovery command. It does not modify application BUILD files or MODULE.bazel,
and it refuses to overwrite customized tool files. `bazel init` remains useful
for selecting an internal JavaInfo provider before the first build.

### Target and executable selection

Targets are selected the way `bazel build` reads them. In order, the first that is set wins:

1. `--target`, repeatable. Each value is a label or a target pattern: `//app/...`, `//app:all`,
   `//app:*`, or a `-` exclusion such as `-//app/legacy/...`. A Bazel-label `--module` also counts.
2. `target` (one label) or `targets` (labels and patterns) in the shared
   `.compose-preview/bazel.json`.
3. The nearest package at or above the current directory, as `//pkg:all`.
4. Outside any package, such as a workspace root whose BUILD files are all below it, everything
   below the current directory, as `//dir/...`.

Patterns are expanded with `query`, filtered by `kind()` to the rules the aspect can read. With no
`aspect` configured, that means JVM library and binary rules and `android_library` /
`kt_android_library` together: each kind is then built by its own aspect (see
[Android targets](#android-targets-experimental)). A configured aspect reads only its own kinds.
Set `targetKinds` (a `kind()` regex) for other rules,
such as an internal macro's rule class. The filter matters: a `...` pattern also matches genrules
and tests, and the aspect fails on a rule without `JavaInfo`.

Every selected target builds in one invocation, so Bazel analyses the graph once. The selection
is then **one module**, named by what you asked for (`//app/...`, or `//pkg:all` for a package).
Each target's own previews are discovered, and one daemon renders them all on the union of the
targets' runtime classpaths. Bazel resolves a workspace's dependencies to one version per
configuration, so the union does not mix versions. `browse` therefore shows the whole set on one
page, and compose-preview-mcp runs one daemon for it and rebuilds it by selecting it again.

Several Android targets stay separate modules by default, each rendering exactly as it does alone,
on its own resource APK. With `"mergeAndroid": true` in `.compose-preview/bazel.json` they merge
like JVM targets. Robolectric loads one resource APK, so the CLI then links
one over the whole selection: rules_compose_preview's `link.sh`, the link the aspect ran for each
target, now run once over the union of their compiled resources. The overlay order keeps each
target's own (dependencies first), every target's R package gets R classes with the merged APK's
IDs, and the APK's own package is the first target's. Each target's own `r-final.jar` leaves the
classpath, since its IDs belong to its own APK. The link is cached under the module's output
directory until an input changes. Where two sibling libraries define the same resource, the later
one in the selection wins, as it would in an `android_binary` that depends on both.

The CLI leaves two kinds of runtime jar off the render classpath, for Bazel and Buck2 alike:
jars with no class or resource in them (KMP stub jars, and the wrapper of a resource-only AAR),
and a library's own R jar when the target's final-ID R jar defines every class in it. The final
R jar comes first on the classpath, so those classes would never load from the library's jar,
whose non-final IDs name nothing in the linked APK anyway. A Bazel Android target carries one of
each per AAR: on compose-ai-contrib's `bazel-apk`, 115 of 175 runtime jars. The project's own
jars, and any jar the CLI cannot read, always stay.

These cases stay separate modules, with a note: Android targets without `mergeAndroid`; Android
and JVM targets together, which render on different runtimes; Android targets from an aspect that
does not report `android.link` (a rules_compose_preview older than the merge); and targets built
in different configurations.
A target a pattern matched is a candidate: if the aspect reports nothing for it, it is skipped
with a note. A single label you name explicitly must build and hold
previews, as before. `list`, `render` and `show` report across all the targets. `--id`,
`--filter` and `--preview` select across them, and `--output` still names exactly one preview.
`bazel bundle` and `--out` take one module, so a JVM selection packs as one bundle.

A repo can commit the default once, so every developer runs the normal commands without
repeating labels:

```json
{
  "schemaVersion": 1,
  "target": "//app:ui",
  "buildCommand": ["blaze"],
  "buildArgs": ["--config=desktop"]
}
```

or, for every UI library under `//app` except the legacy tree:

```json
{
  "schemaVersion": 1,
  "targets": ["//app/...", "-//app/legacy/..."]
}
```

The executable is chosen in this order, and the first match wins:

1. `--build-command`;
2. `buildCommand` in `.compose-preview/bazel.json` — commit it to make the choice
   predictable for everyone;
3. `--build-system blaze`;
4. the `COMPOSE_PREVIEW_BUILD_COMMAND` environment variable;
5. `.blazerc` in the workspace root selects `blaze`;
6. `.bazelversion` with `bazelisk` on PATH selects `bazelisk`, which honours the pin;
7. the first of `bazel`, `bazelisk`, `blaze` on PATH.

A detected choice (steps 4–7) is reported on stderr with its reason, as is every
choice under `--verbose`. The driver never runs a candidate merely to detect it.

Ignore generated `.compose-preview/bazel/` and `.compose-preview/outputs/`, but
keep the shared JSON config trackable. The current adapter renders static Desktop
PNG captures; other formats/native target labels are described in the
[artifact and native-target design](design/BAZEL_PREVIEW_TARGETS.md).

The default provider load is `@rules_java//java/common:java_info.bzl`. It needs
`rules_java` visible to the main repository; a transitive bzlmod dependency is not
necessarily visible. If necessary add a direct `bazel_dep(name = "rules_java",
version = "<your compatible version>")`, or select an existing provider:

```sh
compose-preview bazel init --java-info-load //third_party/java:defs.bzl
```

This is one-time aspect deployment, not a promise that every unconfigured Bazel
workspace works without setup. Rules that do not expose compatible JavaInfo need
an adapted aspect or an external artifact producer. The JavaInfo shim separates
provider/tool differences from runtime extraction; it does not require Kotlin's
private providers or the IDE's dependency graph.

## Select the same configuration as the application build

```sh
compose-preview bazel render --target //app:ui \
  --build-command bazelisk --bazel-arg=--config=desktop
```

`--bazel-arg` repeats and takes one literal build argument each. Startup options,
wrapper arguments and internal deployments go in `.compose-preview/bazel.json`,
or a file selected explicitly with `--config <file>`:

```json
{
  "schemaVersion": 1,
  "buildCommand": ["bazelisk"],
  "startupArgs": ["--output_user_root=/path/to/cache"],
  "buildArgs": ["--config=desktop", "--remote_download_outputs=toplevel"],
  "aspect": "//.compose-preview/bazel:runtime.bzl%compose_preview_aspect",
  "installAspect": true,
  "metadataOutputGroup": "compose_preview_metadata",
  "runtimeOutputGroup": "compose_preview_runtime"
}
```

`buildCommand` is an argv array, never a shell command string. Relative executable
paths are resolved by the process runner against the selected workspace; use an
absolute path for a wrapper outside it. Config is consumed when a preview command selects the Bazel-family driver,
never merely by opening a project. `--project` selects the
workspace, and `--timeout` bounds each build command and render wait (300 seconds
by default). Failed builds, remote-only/missing artifacts and ambiguous configured
outputs fail with diagnostics rather than borrowing outputs from another build.

`buildArgs` is empty by default: no flag is passed that a Bazel-family tool,
version or wrapper might reject. Flags worth configuring:

| Flag | Why |
| --- | --- |
| `--remote_download_outputs=toplevel` (or `all`) | With Build without the Bytes, materializes the requested metadata/runtime output groups locally. Unmaterialized outputs fail with a diagnostic. |
| `--config=<name>` | Select the same configuration the application build uses. |
| `--norun_validations` | Validation actions say nothing about renderability; skipping them shortens the build. |
| `--tool_tag=compose-preview` | Attributes these invocations in build-event and remote-execution logs. |

BEP outputs reported with a non-`file` URI (for example `bytestream://` from a
remote cache) are resolved through their `pathPrefix` and `name` against
`info execution_root`; only an output that is absent there fails.

### Aspect deployment

With the default `aspect`, the driver copies a small aspect package into
`//.compose-preview/bazel` on first use. A configured non-default `aspect` label
is used as-is and nothing is written to the source tree. Where a new top-level
package is unwelcome (large monorepos, repository policy), either:

- depend on the rules module (`bazel_dep(name = "rules_compose_preview", ...)`,
  see [compose-preview-bazel](https://github.com/yschimke/compose-preview-bazel))
  and set `"aspect": "@rules_compose_preview//:runtime.bzl%compose_preview_aspect"`,
  which ships the same aspect and output groups; or
- point `aspect` at an approved internal aspect producing the same metadata.

Setting `"installAspect": false` makes this a hard rule: if the default package is
missing the driver fails instead of creating it.

For Blaze testing, change `buildCommand` to the approved executable/wrapper and
select the supported provider load/aspect. The direct adapter expects
`build`, `info execution_root`, JSON BEP with named sets/output groups, and
command-line aspects. **Public Bazel is tested; Blaze compatibility needs internal
validation.** An IDE installation is unnecessary. Historical IntelliJ
`render_jars` output is a candidate input, but it still needs a small wrapper to
produce the metadata below; changing the output-group name alone cannot recover
application ownership/configuration.

## Artifacts from an existing aspect or another build system

A wrapper can produce this JSON without any core CLI change:

```json
{
  "schemaVersion": 1,
  "target": "//app:ui",
  "configuration": "bazel-out/<configured-key>/bin",
  "projectJars": ["bazel-out/<configured-key>/bin/app/ui.jar"],
  "runtimeJars": [
    "bazel-out/<configured-key>/bin/app/ui.jar",
    "external/<repo>/runtime.jar"
  ],
  "sourceFiles": ["app/Greeting.kt"],
  "dataFiles": []
}
```

```sh
compose-preview bazel list --project /workspace \
  --artifacts /path/to/artifacts.json --execution-root /path/to/execroot --json
compose-preview bazel render --project /workspace \
  --artifacts /path/to/artifacts.json --execution-root /path/to/execroot
```

Import runs no build tool. Paths are absolute or relative to `--execution-root`;
all must already be materialized. `projectJars` contains only the selected target's
compiled application jars, **never ABI/interface jars**. `runtimeJars` is ordered,
includes every project jar and the complete runtime-only dependency closure.
`sourceFiles` maps source/generated compilation inputs. `dataFiles` records extra
runfiles, which the aspect also requests for materialization.

The shape is published as `ee.schimke.composeai:build-artifacts-protocol`
(compose-preview-contracts), so producers and the CLI agree on it without copying
types. Schema 1 is a JVM/Desktop target. Schema 2 adds an `android` block:

```json
{
  "schemaVersion": 2,
  "...": "the schema 1 fields",
  "android": {
    "packageName": "com.example.ui",
    "resourceApk": "bazel-out/<configured-key>/bin/app/_compose_preview/ui/resources.ap_",
    "manifest": "app/AndroidManifest.xml",
    "rJar": "bazel-out/<configured-key>/bin/app/_compose_preview/ui/r-final.jar",
    "compileSdk": 36
  }
}
```

`resourceApk` is a standalone linked resource APK, `manifest` the manifest it was
linked against, and `rJar` R classes carrying that APK's final IDs; the CLI puts
it ahead of every library's non-final R classes. A schema-1 file with an `android`
block, or schema 2 without one, is refused.

This is not the full executable/NDJSON driver
protocol proposed in [#5945](https://github.com/yschimke/compose-ai-tools/issues/5945).
That broader handshake/list-targets protocol remains separate work after the
internal API review. Unknown build systems can use artifact import now.

## Preparation and renderer behavior

`list` builds and discovers without downloading a renderer. `prepare` also writes
`daemon-launch.json`, whose path is printed to stdout, for existing RenderSession
consumers. `render` uses that descriptor. The manifest, components record, artifact
metadata and descriptor live under `.compose-preview/outputs/<target-config-hash>/`,
or `--out <dir>`. Prepared files use a `build/compose-previews/` subdirectory
so the existing result/state consumers work unchanged. Each run rebuilds/discovers; there is no independent stale cache.

### Host cache, and content-addressed output trees

Between runs the CLI keeps host-side memos under `${XDG_CACHE_HOME:-~/.cache}/composeai/bazel-host/`:
which runtime jars classpath pruning drops, each dependency jar's SHA-256, the level an
`android.jar` declares, and a target's discovery result. Each per-file value is keyed on a stamp of
the file (size, mtime, ctime and inode), and a value recorded within 3 s of the file's last change is
not trusted, as git does for its index. `COMPOSE_PREVIEW_BAZEL_HOST_CACHE=0` turns the cache off.
`--keep-daemon` (`COMPOSE_PREVIEW_KEEP_DAEMON=1`, `"keepDaemon": true`) additionally keeps the
render daemon warm for `daemonIdleTimeoutSeconds` (default 300).

A content-addressed output tree, such as a CAS-backed FUSE `bazel-out` that remounts
`bazel-out/<config>/bin` on every build, gives every file a fresh mtime, ctime and inode although its
bytes did not change, so every jar misses and is scanned and hashed again. If that filesystem
publishes each file's content digest as an extended attribute, name it:

```json
{ "digestXattr": "user.checksum.sha256" }
```

or `COMPOSE_PREVIEW_BAZEL_DIGEST_XATTR=user.checksum.sha256` (the leading `user.` is optional). The
variable wins over `bazel.json`, since the mount is a property of the machine rather than the
workspace; set it empty to turn a committed `digestXattr` off. A file whose attribute reads back is
then stamped by its size and the attribute's bytes, which are treated as opaque (ASCII or binary,
any hash) and never as the file's SHA-256, which is still computed once per stamp. A digest stamp is
trusted at once: it names the content, so the 3 s window, which exists for coarse timestamps, does
not apply. It also memoises where the unix stamp cannot (Windows). A file without the attribute
(workspace and external jars outside the mounted subtree) keeps the unix stamp. `--verbose` names
the attribute in use and, at exit, how many stamps were digest-keyed and how many memo lookups hit.

Name only an attribute the filesystem derives from the file's content, normally the one passed to
Bazel's `--unix_digest_hash_attribute_name`. An unrelated attribute that does not change with the
bytes would serve stale classpath and fingerprint results, which is why the CLI never probes for
common names on its own.

The aspect requests metadata and runtime output groups. JavaInfo supplies the
transitive closure, including `runtime_deps`, without traversing every rule and
emitting unrelated previews. BEP identifies/materializes the output; BEP alone
is not the runtime-classpath API. The application jars are discovery inputs;
dependencies resolve annotations/multi-previews without becoming application
previews. The renderer's pinned jars stay on the parent classpath and application
runtime jars on the daemon's user classpath, matching bundle replay isolation.

JAR-packaged JVM resources work. The adapter records/materializes loose runfiles
but does not synthesize arbitrary Bazel runfiles trees or configure custom JNI
library paths. Applications relying on those layouts may need an adapted launcher.
CMP iOS/Wasm, watch/rebuild subscriptions and automatic whole-workspace target
enumeration are not implemented here.
An already-building Compose JVM target is required. Bytecode discovery also needs
an annotation retained in compiled classes, such as modern
`androidx.compose.ui.tooling.preview.Preview`. The legacy
`androidx.compose.desktop.ui.tooling.preview.Preview` is source-retained and cannot
be reconstructed from compiled jars; migrate it or supply a prepared manifest
through the existing non-Gradle RenderSession API.

## Android targets (experimental)

Schema-2 artifacts render on the Robolectric daemon instead of the desktop one.
The linked APK, manifest and package reach Robolectric through a synthesized
`test_config.properties`, as in the bundle lanes, and previews run on
`android.app.Application` rather than the app's own.

No configuration is needed. With no `aspect` in `.compose-preview/bazel.json`, the CLI builds the
selected `android_library` / `kt_android_library` targets with the Android aspect from
[compose-preview-bazel](https://github.com/yschimke/compose-preview-bazel), which links an
`android_library`'s resources itself, and any JVM targets with the JVM aspect. It uses
`@rules_compose_preview` when `MODULE.bazel` declares it (under its `repo_name`, if set), and
otherwise the copy it bundles (below) when the `rules_android` and `rules_java` that copy loads are
visible under those names (declared in `MODULE.bazel`, or defined in `WORKSPACE`), or `providers`
points it elsewhere. A workspace with neither, such as a Blaze one, keeps the JVM-only selection:
set `aspect` (or `providers`) to render its Android targets.
A target an Android library `exports` belongs to that library and is not built on its own:
rules_kotlin's `kt_android_library` is an `android_library` exporting the `_kt` and `_base`
libraries it compiles to. Pass `--verbose` to see which aspect was chosen.

To pin the aspect instead, set it; the CLI then builds only the rules that aspect reads:

```json
{
  "schemaVersion": 1,
  "target": "//app:ui",
  "aspect": "@rules_compose_preview//:android.bzl%compose_preview_android_aspect"
}
```

### Without `@rules_compose_preview`

A workspace that does not fetch the rules module gets the copy the CLI bundles. Pinned, that is:

```json
{
  "schemaVersion": 1,
  "target": "//app:ui",
  "aspect": "//.compose-preview/bazel/android:android.bzl%compose_preview_android_aspect"
}
```

The CLI installs that package (`android.bzl`, `android_providers.bzl`, `java_info.bzl`,
`runtime.bzl`, `link.sh`) on first use, or up front with `compose-preview bazel init --android`.
The files are copied byte for byte from one rules release (`provenance.json` beside them names it;
`scripts/sync-bazel-android-aspect.py <tag>` refreshes them). It is a package of its own, so the JVM
aspect in `.compose-preview/bazel/` is unchanged. `installed.json` records what the CLI wrote: a
newer CLI upgrades those files, and a file edited by hand is kept (`bazel init --android` fails on
one instead).

Where the Java or Android rules live elsewhere, `providers` points the two shims at them, and the
CLI generates `java_info.bzl` and `android_providers.bzl` from it. A provider names the `.bzl` that
exports it, with `%Name` when its name there differs; `JDK_RUNTIME` and
`ANDROID_SDK_TOOLCHAIN_TYPE` name labels. Unset entries keep the rules' defaults:

```json
{
  "schemaVersion": 1,
  "aspect": "//.compose-preview/bazel/android:android.bzl%compose_preview_android_aspect",
  "providers": {
    "JavaInfo": "//tools/build_defs/java:providers.bzl%JavaInfo",
    "JavaRuntimeInfo": "//tools/build_defs/java:providers.bzl",
    "JDK_RUNTIME": "//tools/jdk:jdk17",
    "AndroidIdeInfo": "//tools/build_defs/android:providers.bzl",
    "StarlarkAndroidResourcesInfo": "//tools/build_defs/android:providers.bzl",
    "ANDROID_SDK_TOOLCHAIN_TYPE": "//tools/android:sdk_toolchain_type"
  }
}
```

The Android daemon (`lib-daemon-android`) and `android.jar` (from `ANDROID_HOME`,
`ANDROID_SDK_ROOT` or `local.properties`) are resolved as for `bundle daemon`.
Robolectric fetches `android-all-instrumented` from Maven Central on first render;
set `COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR` to a directory holding those jars to
run offline (`robolectric.offline`); `compose-preview provision` fills one (below). For a closed
network, `COMPOSE_PREVIEW_NO_NETWORK=1` makes every outbound path of the CLI refuse instead of
fetching; [OFFLINE.md](OFFLINE.md) lists what to pre-provision, including which `android-all` jars.

The Robolectric sandbox renders on the target's `compileSdk`, as a Gradle module
does, clamped to the bundled Robolectric's range and to 35 when the CLI runs on a
JDK older than 21 (SDK 36 needs JDK 21). Offline (`COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR`), it is also
clamped to the newest level that directory has an `android-all` for, since a compileSdk can be
newer than any published for the daemon's Robolectric (SDK 37 for Robolectric 4.17); the render
logs the level it settled on. The aspect writes `compileSdk` only when
the target's `android.jar` path has an `android-<N>` segment, since rules_android's
`AndroidSdkInfo` carries no API level; otherwise the CLI reads it from the SDK
platform that jar belongs to (`AndroidVersion.ApiLevel` in the `source.properties`
beside it, else `ro.build.version.sdk` in its `build.prop`, following a symlinked
jar to its target), and when that jar is a generated build output with nothing beside
it, from the jar itself: the highest `android.os.Build.VERSION_CODES` constant it
declares. `"androidCompileSdk": 36` in `.compose-preview/bazel.json` overrides all of
these. With none, it renders on the default SDK (35). Each render logs the SDK it
uses and where the level came from, so a default is never silent.

Renders pin the wall clock to `10:10` (the daemon's `composeai.render.fixedTime`), and that
includes a target's own `System.currentTimeMillis()` calls: classes under `appClockPackages` —
class-name prefixes matched with `startsWith`, defaulting to the target's `packageName` plus `.`
and `androidx.wear.compose.` (where Wear's `TimeText` reads the clock) —
read the pinned instant, as a Gradle module's do under `composePreview.pinAppClock`. Pinning has
Robolectric instrument the target's own classes; if a preview misbehaves under that, turn it off
in `.compose-preview/bazel.json`:

```json
{
  "schemaVersion": 1,
  "target": "//app:ui",
  "pinAppClock": false
}
```

To pin more than the target's own package, list the prefixes instead:
`"appClockPackages": ["com.example.app.", "com.example.shared."]`. It reaches the daemon as
`composeai.render.clockPackages`. It cannot pin `java.time.*.now()`, `Calendar` or `Date()`, which
read the clock inside the JDK, and the daemon's `fixedTime=off` still gives the host clock. With
`mergeAndroid`, the default prefix is the merged APK's package (the first target's), so list
sibling targets' packages in `appClockPackages`.

A first build on a cold cache compiles
rules_android's own tools (protobuf included) and can run well past the default
300-second `--timeout`; raise it for that run (the contrib proof uses
`--timeout 2400`).

`bazel bundle` packs an Android target as a `backend="android"` bundle: the linked
APK, manifest and final-ID R classes go under `android/`, where a Gradle Android
bundle carries them, so `bundle render` and `bundle daemon` replay both the same
way. The R jar travels only there, not on the bundle's user classpath.

`bundle render` replays an Android bundle with the one-shot Robolectric renderer, which the
Android daemon sidecar (`lib-daemon-android`) carries; it is resolved and provisioned as for
`bundle daemon` (`-Dcomposeai.cli.libRendererAndroidDir` still wins when set). Three flags carry
what a bundle does not record, so a build that knows them renders without reading the host:

| Flag | Meaning |
| --- | --- |
| `--android-sdk <n>` | The sandbox SDK, e.g. the target's `compileSdk`; clamped as above. Default: `-Dcomposeai.bundle.androidSdk`, else 35. |
| `--android-jar <file>` | The `android.jar` to put on the classpath, instead of the SDK `ANDROID_HOME` or `local.properties` names. |
| `--clock-packages <prefix,…>` | The app clock to pin, as `appClockPackages`; absent pins nothing, as before. |
| `--local-jars <list>` | A file of the build's own jar paths, one per line. A Maven coordinate the bundle's `dependency-resolution.json` records for one of them, by its content hash (`builtSha256`), resolves to that file instead of a repository. So a stamped `processed_` jar, rules_kotlin's unversioned `kotlin-stdlib.jar` or a jar newer than the lockfile's pin renders as the exact bytes the producer identified, offline. |
| `--app-compose auto\|app\|daemon` | Whose Compose runs, decided as the Android daemon decides it (`composeai.daemon.appCompose`, which also sets it): `auto`, the default, runs the app's Compose only when it is newer than the renderer's; `app` and `daemon` force one. The one-shot renderer has a single classpath, so the decision orders it: the renderer's jars before the app's dependencies unless the app's Compose runs. The render logs the decision. |

The producer (`compose-preview-producer --artifacts`) accepts schema-2 artifacts too and writes
the same `backend="android"` bundle, which is how rules_compose_preview's `compose_preview` rule
prepares, renders (with these flags) and bakes an Android library as ordinary Bazel actions.

The end-to-end proof is compose-ai-contrib's `bazel-android-render` job: it builds
the rules_android Compose sample in `bazel-apk/` through this driver and renders a
preview that reads string and colour resources on the Robolectric daemon.

### Pre-fetching for an offline render

`compose-preview provision` downloads, now, everything a render would otherwise
fetch on first use, through the same provisioners the render uses:

```sh
compose-preview provision                    # --desktop --android
compose-preview provision --android --android-sdk 35,36
compose-preview provision --all --json       # + preview server, MCP server, XR compositor
```

| Part | What | Where (under `${XDG_CACHE_HOME:-~/.cache}/composeai/`) |
| --- | --- | --- |
| `--desktop` | desktop daemon sidecars, this host's Skiko native | `preview-daemon/<v>/`, `skiko/` |
| `--android` | Android daemon sidecar, `android-all-instrumented` per `--android-sdk` level (default 35) | `preview-daemon/<v>/`, `robolectric-deps/` |
| `--server`, `--mcp` | the preview server, the MCP server | `preview-server/<v>/`, `preview-mcp/<v>/` |
| `--xr` | the XR compositor | `xr-composite/` |

It prints the cache root, each provisioned path, and the environment that
points a later run at them: `COMPOSE_PREVIEW_OFFLINE=1`,
`COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR`, the daemon/server versions it fetched,
and `XDG_CACHE_HOME` for a copy that lands somewhere else. It exits 1 when any
part could not be fetched.

Which `android-all-instrumented` jar a level needs is Robolectric's answer, not
a table in the CLI: `provision` loads the Robolectric on the Android daemon
sidecar in an isolated class loader and asks its `DefaultSdkProvider` for the
level's artifact (for Robolectric 4.17 and SDK 35,
`android-all-instrumented-15-robolectric-13954326-i7.jar`). It is downloaded
from Maven Central under exactly that Maven file name, which is what
Robolectric's offline resolver looks up, and verified against the `.sha512`,
`.sha256` or `.sha1` Central publishes. Pass every level your targets render
on: a Bazel target renders on its `compileSdk`, so a `compileSdk = 36` target
on JDK 21 needs `--android-sdk 36`.

## Browsing (`browse`, `serve --discover`)

`compose-preview browse` works in a Bazel workspace with no server change. The preview server
asks its build host Gradle-shaped questions, and in a workspace that routes to Bazel the host
answers them from this driver:

- discovery builds the selected targets (see [Target and executable selection](#target-and-executable-selection);
  `--target` patterns work here too), discovers and renders their previews, and reports the
  selection as one module, so it is one page. A label becomes a slash-free module name (`//app/ui:lib` → `app.ui:lib`, `//app/...` → `app.all`), because the
  server puts module names in URL segments;
- starting a module's daemon uses the `daemon-launch.json` the render wrote, preparing it again
  only if it is missing;
- recompiling a module after an edit (`:<module>:composePreviewCompile`) always runs `prepare` for
  its selection, into the same output paths, so the daemon's classloader swap loads the new jars.
  A failed build answers false, and the server keeps the last good classes;
- there is no Gradle root, so the server skips its Gradle-only Wasm app and git history discovery
  instead of walking `bazel-*` output symlinks.

The build host allows 1800 seconds per build by default (a first
build compiles the toolchain); pass `--timeout` to change it.


The dedicated Bazel CI test builds the contrib Desktop fixture with the bundled
aspect, discovers source and generated previews, excludes a dependency preview,
and renders actual UI that reflectively calls a runtime-only class and reads a
packaged resource. Missing runtime artifacts therefore fail the live render.
Measured against the existing CLI runtime graph, this adds six pure JVM JARs
(2,789,049 bytes, about 2.7 MiB uncompressed), plus the small adapter code and
aspect resources. It adds no Bazel/IntelliJ SDK, renderer, Android SDK or compiler
implementation to the CLI runtime.

Unit tests also cover BEP selection/forward references, configuration ambiguity,
remote metadata, missing artifacts, literal argv, build errors/timeouts and safe
aspect initialization.


## Testing the adapter from a source checkout

Build the CLI branch once in `compose-ai-tools`:

```sh
scripts/agent-gradle.sh :cli:installDist
cli/build/install/compose-preview/bin/compose-preview bazel init --project /path/to/workspace
cli/build/install/compose-preview/bin/compose-preview bazel render \
  --project /path/to/workspace --target //app:ui --build-command blaze
```

Run init with the approved `--java-info-load` when the default rules_java load is
not available. For an already-approved internal aspect, configure `--aspect` and
the output groups; its metadata must implement the artifact contract above. If
that interface is unavailable, an internal wrapper can produce the artifact JSON
and use `--artifacts` instead. Use a binary built from this branch until the
feature is included in a published CLI release.

## Native Bazel build/run targets and publication

Reusable rules live in [compose-preview-bazel](https://github.com/yschimke/compose-preview-bazel),
independently versioned from the CLI. The `rules_compose_preview` Bzlmod module
provides `compose_preview(name = "ui-preview", module = ":ui", producer = "@compose_preview_producer//:producer", cli = "@compose_preview_cli//:cli")`.
Consumers can pin a full Git commit or a release archive with SHA-256, and separately
pin the CLI archive using `compose_preview_cli_repository`.

```sh
bazel run //app:ui-preview -- --filter Greeting --output greeting.png
bazel build //app:ui-preview
```

The native rule uses a separate `producer` executable, built from `:bundle-producer`.
It prepares runnable bundles offline from declared JavaInfo inputs with normal Bazel
caching. `outputs = ["bundle"]` is the default; `png` and `rendered_bundle` are
optional products. `bazel run //app:ui-preview -- render --filter Greeting` calls
the existing bundle renderer against the cached prepared bundle. Rendering is
local, uncached and non-remote unless a render toolchain declares the
native/font/renderer inputs (for an Android `module`, the Android daemon and
`android-all` jars). Neither mode starts a nested build. See the rule repository for pinned
producer/CLI distribution provisioning and fresh-consumer verification.

## Portable runnable PNG/ZIP bundles

```sh
compose-preview render --bundle --maven-install maven_install.json
compose-preview bazel bundle --target //app:ui --maven-install maven_install.json --output bundle.png
```

The polyglot carries a real PNG cover, baked preview images, own bytecode/resources,
and the complete runtime classpath. The existing preview server can resolve and
re-render it. Published dependencies prefer Maven coordinates even when Bazel has
transformed their bytes: lockfile paths/names, Maven layout, pom.properties and
manifest version hints identify the published artifact. Available lockfile checksums
refer to the published artifact. `dependency-resolution.json` records inferred
coordinates and built/published hashes. Unidentified or unpublished project JARs
remain embedded; `--embed-deps` explicitly requests an offline bundle.

A `dependencyPolicy` block in `.compose-preview/bazel.json` changes that per group: verify
coordinates against the published checksum (`coordinate-verified`, with `allowTransformed` for
Bazel's `processed_` jars), substitute the nearest released version for an unreleased top-of-tree
build (`closest-released`, marking the bundle `approximate`), or embed. It also identifies androidx
jars by their `META-INF/<group>_<artifact>.version` stamp and by the `runtimeJarOwners` the aspect
reports. Without the block nothing changes. Format, defaults and examples:
[portable bundles → dependency policy](portable-bundles.md#dependency-policy-for-bazel-bundles-6016).

Use `--no-render` on `bazel bundle` to package without provisioning a renderer or
reading old captures. The PNG/ZIP uses a valid stub cover and remains runnable.

For native builds set `outputs = ["bundle"], maven_install = ":maven_install.json"` on
`compose_preview`, then build `//app:ui-preview --output_groups=bundle`. It produces
`bazel-bin/app/ui-preview.bundle.png`. CMP framework coordinates resolve through
Maven Central; some AndroidX dependencies use Google Maven, already supported by
the server. Bundle packaging preserves reflection/runtime-only resources instead
of pruning the runtime closure through bytecode-only reachability.

`--output_groups=rendered_bundle` instead produces a separate baked-image bundle.
The small offline producer module contains discovery, coordinate recovery and JAR
packaging; it has no CLI, server, build driver or renderer dependency. Both CLI and
native builds share it. Runtime CLI filters leave preparation untouched; build-time
rule filters are declared action arguments and invalidate the preparation cache.
