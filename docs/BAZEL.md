# Bazel and configurable build integration

`compose-preview bazel` builds an existing Compose Multiplatform **JVM/Desktop**
target, discovers its compiled `@Preview` functions and renders through the usual
desktop daemon. No Gradle preview plugin, IntelliJ installation, Bazel distribution
or Android SDK is added to the CLI. The pure JVM discovery library and descriptor
builder are reused; renderer sidecars are provisioned on first prepare/render.

## Quick start

From a workspace that already compiles Compose (including its compiler plugin):

```sh
compose-preview bazel init
compose-preview bazel list --target //app:ui
compose-preview bazel render --target //app:ui --filter Greeting --output greeting.png
```

`init` writes three small files under `.compose-preview/bazel/`. Commit them if
useful. It does not change application BUILD files or MODULE.bazel. Repeating init
is safe; it refuses to overwrite a customized aspect/provider. Ignore generated
`.compose-preview/outputs/` in your repository.

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
absolute path for a wrapper outside it. Config is consumed only by an explicit
`bazel` operation, never merely by opening a project. `--project` selects the
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
or `--out <dir>`. Each run rebuilds/discovers; there is no independent stale cache.

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
watch/rebuild subscriptions and target auto-selection are not implemented here.
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
