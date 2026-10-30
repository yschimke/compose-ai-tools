# Native preview targets and artifact exports

Status: normal Gradle/Bazel routing and the Desktop PNG adapter are implemented
in tools #5947. A local PNG `compose_preview` build/run macro now lives in
[compose-preview-bazel](https://github.com/yschimke/compose-preview-bazel).
Verification targets, installer commands, remote/cacheable render actions and
additional exports below remain proposals. The implemented macro requires a
separately declared CLI executable; it currently accepts only `formats = ["png"]`.

## Two complementary entry points

For editing a repository, use the normal CLI:

```sh
compose-preview list
compose-preview show --filter Greeting --json
compose-preview render --filter Greeting --output greeting.png
```

Gradle wins within the selected workspace when settings are present. Bazel
workspaces are bounded before looking at an unrelated outer Gradle build. Shared
configuration pins the target/build command; otherwise selection is limited to
the current package, requiring an explicit choice when multiple JVM targets exist.
The same versioned list/show response and PNG/hash/state logic is used on both paths.

For repeatable CI and developers who use the native build command, add a real
preview target. Bazel requires a verb:

```sh
bazel build //app:ui-preview
bazel run //app:ui-preview -- --filter Greeting
bazel test //app:ui-preview-test
```

The corresponding public commands use `bazel build|run|test`.

Proposed BUILD declaration, once the rules are implemented and deployed:

```starlark
load("//tools/compose_preview:defs.bzl", "compose_preview")

compose_preview(
    name = "ui-preview",
    module = ":ui",
    backend = "desktop",
    formats = ["png", "layout"],
    visibility = ["//visibility:public"],
)
```

The macro would create the build action plus a runnable local launcher and a
verification test. They must all consume the same runtime/source/configuration
provider and renderer contracts. The runnable target should consume prepared
artifacts, rather than invoking a nested build from inside a render action.

## What can actually be injected

[Aspects](https://bazel.build/extending/aspects) attach metadata/actions to existing
targets. They do not add a new `//app:ui-preview` label. The current CLI therefore
needs only a small tool package and an aspect invocation, with no application BUILD
edits. It is the least invasive first-use path.

[Macros](https://bazel.build/extending/macros) create named targets while BUILD files
are evaluated. That is the mechanism for native preview labels. Good choices are:

1. Add an explicit `compose_preview` call beside an existing library.
2. Have an organization's existing Compose library macro optionally create a
   sibling preview target. It can always declare the target; execution-time
   discovery determines whether there are previews.
3. Offer an explicit setup operation that inserts the call using
   [Buildozer](https://github.com/bazel-contrib/buildtools/blob/main/buildozer/README.md).
   Show the edit, preserve comments and existing macros, and make repeated setup
   idempotent. Buildozer operates on source BUILD syntax, not the macro-expanded
   graph, so adding a separate call is safer than editing a generated target.

A proposed command could be `compose-preview bazel init --add-target //app:ui-preview
--target //app:ui`. This flag is not implemented yet. A source annotation search can
suggest candidates, but cannot be authoritative: annotations can be aliased,
source-retained, generated or supplied through multi-preview annotations. Confirm
through configured build metadata and compiled discovery first.

A rule/aspect cannot wait for bytecode discovery and then create new named BUILD
targets. That crosses Bazel's loading/analysis/execution phases. An overlay can
host generated helper targets in another package/repository, but does not make a
label appear in the original `//app` package without changing its BUILD evaluation.
Normal list/render commands should therefore keep aspect attachment as their
first-use behavior; installing persistent native labels is an explicit operation.

## Outputs and capabilities

| Request | Proposed carriage | Actual support / prerequisite |
| --- | --- | --- |
| Static image | `<id>.png` | Current Bazel/Desktop adapter is tested. |
| Remote Compose | `<id>.rc` | Requires a preview producing a Remote Compose document, or a supported explicit conversion/capture path. This is not automatic for arbitrary Compose UI. The daemon's document product is `compose/remotecompose-doc`; its knob metadata product is separate. |
| Animated image | `<id>.apng` | Existing daemon animation/recording APIs support APNG. Exposing the recording/animated-capture operation through the Bazel adapter needs separate wiring and a live animation test. |
| Layout information | `<id>.layout.json` | Existing `layout/inspector` and `compose/semantics` data products provide trees, bounds and related metadata on Android and Desktop. Export should reuse those versioned schemas. |
| Accessibility | `<id>.a11y.json` | Existing ATF accessibility producer is Android/Robolectric-specific. Desktop needs a separate portable accessibility implementation; do not advertise ATF parity. |

Capability availability should come from the selected renderer/session, not from
filename extensions or the presence of BUILD files. An explicitly requested
unsupported export fails clearly. Optional outputs may be recorded as unavailable;
empty placeholder `.rc`/`.a11y` files would incorrectly imply a successful export.

Because preview identifiers are discovered after compilation, each rule should
declare a directory artifact per selected output kind plus a versioned artifact
index. For example `ui-preview/png/`, `ui-preview/layout/`, and
`ui-preview/artifacts.json`. The index should record preview id, configured target,
backend, kind/schema, path, MIME type and content hash; animations also need frame
and duration metadata. Proposed output groups such as `preview_png`, `preview_rc`,
`preview_apng`, `preview_layout`, and `preview_a11y` let callers materialize selected
results. Do not assume the rule can declare every per-preview file during analysis.

Use one prepared runtime and one render session for related exports where possible.
Separate image generation from baseline verification: `build` produces artifacts,
`test` compares declared baselines and enforces requested checks, and an explicit
record/update operation changes baselines. Producing images should not silently
rewrite version-controlled expected results.

## Patterns from other build tools

| Tool | Existing pattern | What to reuse here |
| --- | --- | --- |
| Gradle | [Lazy plugin task registration](https://docs.gradle.org/current/userguide/writing_tasks.html), variant-specific record/verify tasks in [Paparazzi](https://github.com/cashapp/paparazzi) and [Roborazzi](https://github.com/takahirom/roborazzi) | Register tasks through a plugin, retain the native lifecycle, and keep recording separate from verification. Gradle's init-script injection can apply that plugin without permanent build-script edits. |
| Bazel | Rules/macros declare target names; aspects augment configured targets. [Robolectric's Bazel integration](https://github.com/robolectric/robolectric-bazel) uses Android local-test targets. | Use an aspect for ad hoc discovery/preparation and a real macro/rule for native build/run/test labels. Android still requires its runtime/resource toolchain. |
| Buck2 | [BXL](https://buck2.build/docs/bxl/explanation/basics/) can query/analyze existing targets, construct actions and ensure artifacts are built/materialized without new permanent BUCK target calls. | A build-native external integration surface can provide the same prepared artifact contract without rewriting application definitions. BXL is a Buck2 capability, not an assumed Bazel API. |
| Maven | [Plugin goals](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html) can be invoked directly or bound to lifecycle phases. | Provide an explicit goal/profile for export and a separate verification goal instead of changing compilation semantics. |

These sources establish mechanisms and naming conventions, not compatibility with
our renderer. The provider import, action deployment, artifact materialization and
allowed execution environment must still be validated against each custom Bazel distribution.

## Next implementation gate

Start with a native Desktop PNG build/run target sharing the current provider and
prepared descriptor/manifest, and prove it with a declared, pinned toolchain.
Add layout JSON next, then animation. Evaluate Remote Compose document production
and Android accessibility as explicit capability tracks.

A cacheable/remote render action must declare its renderer, Java/native libraries,
fonts, resources, preview settings and baseline inputs. It must not download a
sidecar or font at action execution time, rely on host caches, or inherit hidden
network/locale state. The current CLI provisions on the developer host, which is
appropriate for an ad hoc local command but is not itself a hermetic Bazel action.
That distinction is why a thin alias around the installed CLI should be a local
run target first, not presented as a correct remotely cached build rule.
