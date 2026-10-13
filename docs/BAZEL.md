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

An explicit `--target` (or a Bazel-label `--module`) wins, then `target` in the shared
`.compose-preview/bazel.json`. Without either, the driver queries JVM targets in
the nearest package. One target is selected; multiple candidates produce a useful
selection error. It does not scan `//...` or guess which target a large monorepo
should build. A repo can commit the default once so every developer runs the
normal commands without repeating labels:

```json
{
  "schemaVersion": 1,
  "target": "//app:ui",
  "buildCommand": ["blaze"],
  "buildArgs": ["--config=desktop"]
}
```

For public Bazel use its build command/flags; the default materializes remote
outputs. Explicit `--build-command` wins over configured argv. Otherwise `.blazerc`
selects Blaze; the driver looks for Bazel, Bazelisk, then Blaze on PATH. It never
runs those executables merely to detect the build system.

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
  "buildArgs": ["--config=desktop", "--remote_download_outputs=all"],
  "aspect": "//.compose-preview/bazel:runtime.bzl%compose_preview_aspect",
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

For Blaze testing, change `buildCommand` to the approved executable/wrapper,
select the supported provider load/aspect, and replace `buildArgs` if the internal
system does not support `--remote_download_outputs=all`. The direct adapter expects
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

This is a small CLI-owned artifact contract, not the full executable/NDJSON driver
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
Android resource APKs/manifests/R classes, Robolectric configuration, CMP iOS/Wasm,
watch/rebuild subscriptions and automatic whole-workspace target enumeration are not implemented here.
An already-building Compose JVM target is required. Bytecode discovery also needs
an annotation retained in compiled classes, such as modern
`androidx.compose.ui.tooling.preview.Preview`. The legacy
`androidx.compose.desktop.ui.tooling.preview.Preview` is source-retained and cannot
be reconstructed from compiled jars; migrate it or supply a prepared manifest
through the existing non-Gradle RenderSession API.

## Verification

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
provides `compose_preview(name = "ui-preview", module = ":ui", cli = "@compose_preview_cli//:cli")`.
Consumers can pin a full Git commit or a release archive with SHA-256, and separately
pin the CLI archive using `compose_preview_cli_repository`.

```sh
bazel run //app:ui-preview-run -- --filter Greeting --output greeting.png
bazel build //app:ui-preview
```

Both consume JavaInfo directly and call the CLI artifact-import path without a
nested build. The build emits actual PNGs and manifests in a declared tree;
rendering is currently local, uncached and non-remote because daemon/native
provisioning uses host caches. This is experimental Desktop JVM support, not a
hermetic remote-render toolchain. Full installation and publication instructions,
a checksum-producing packager and a fresh-workspace integration test live in the
rule repository. CLI-first attachment still needs no application BUILD edits;
native labels require an explicit `compose_preview` declaration.

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

For native builds set `bundle = True, maven_install = ":maven_install.json"` on
`compose_preview`, then build `//app:ui-preview-bundle`. It produces
`bazel-bin/app/ui-preview-bundle.png`. CMP framework coordinates resolve through
Maven Central; some AndroidX dependencies use Google Maven, already supported by
the server. Bundle packaging preserves reflection/runtime-only resources instead
of pruning the runtime closure through bytecode-only reachability.
