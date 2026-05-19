# Bazel sample

Stepping-stone demo for compose-ai-tools under Bazel, scoped to **resources
discovery** only (vectors and adaptive icons → `resources.json`). The
full Compose `@Preview` pipeline is tracked separately in
[#1037](https://github.com/yschimke/compose-ai-tools/issues/1037); the
seam this sample exercises is [#1253](https://github.com/yschimke/compose-ai-tools/issues/1253).

## What this proves

- The Gradle plugin's `resources.json` wire format is producible
  outside Gradle.
- A Bazel `discover_resources` rule can drive the discovery step as a
  hermetic action with declared inputs (XML files) and a declared
  output (the manifest).
- The same CLI surface (eventually `compose-preview discover-resources`)
  works for both Bazel and Amper, validating the spec.

## What's stubbed

The `_discover` action today is a shell script
([`_discover.sh`](_discover.sh)) that hand-rolls a subset of the
`ResourceDiscovery` logic from the Gradle plugin. It produces a valid
`resources.json` for `<vector>` / `<adaptive-icon>` / `<animated-vector>`
root tags only. The script is a placeholder — once
`compose-preview discover-resources` exists, the rule swaps the
executable and the script goes away. The **rule interface** (`srcs`,
`module`, `variant`, output `<name>.json`) is the part this sample is
committing to.

The **render** half (`renders/resources/<id>/<qualifiers>.png`) is
deliberately out of scope here. Rendering currently runs inside a
Robolectric `Test` task launched by the Gradle plugin, and unblocking
it under Bazel needs its own design pass.

## Layout

```
samples/bazel/
├── MODULE.bazel          # bzlmod entrypoint, no external deps
├── .bazelrc              # bzlmod-only, no WORKSPACE fallback
├── BUILD.bazel           # discover_resources target wiring
├── compose_preview.bzl   # rule definitions
├── _discover.sh          # placeholder discover action
└── app/res/              # mirrors samples/android/src/main/res/
    ├── drawable/         # vectors + an animated-vector
    ├── drawable-night/   # qualifier variant of ic_compose_logo
    ├── mipmap-anydpi-v26/  # two adaptive-icons
    └── values/           # non-drawable XML; discover skips these
```

The `app/res/` tree is a verbatim copy of
[`samples/android/src/main/res/`](../android/src/main/res/), chosen
because it covers every shape the resources pipeline cares about
(`<vector>`, `<animated-vector>`, `<adaptive-icon>`, a qualifier
variant under `drawable-night/`, and `<resources>` files under
`values/` that the discover action correctly skips). Mirroring an
existing Android sample also means we can byte-diff the eventual
Bazel-produced PNGs against the Gradle-produced ones once the render
half lands.

## Build

From this directory:

```
bazel build //:app_resources
```

Outputs `bazel-bin/app_resources.json` matching the schema in the
Gradle plugin's `resources.json`. Inspect with:

```
cat bazel-bin/app_resources.json
```

Expected: one entry per `(base, name)` pair, each listing per-qualifier
source files under `sourceFiles`. The `captures` array is empty until
the render half lands.

## Status

- [x] `MODULE.bazel`, `.bazelrc`, full `res/` tree mirroring `samples/android`
- [x] `discover_resources` rule (placeholder shell action)
- [x] Opt-in CI workflow (`.github/workflows/bazel.yml`)
- [ ] `compose-preview discover-resources` CLI subcommand
- [ ] Swap `_discover.sh` for the real CLI binary
- [ ] `render_resources` rule (blocked on render-CLI extraction)

## Compose APK target (opt-in, known-fragile)

A second target — `//:bazel_sample_apk` — builds a tiny Android APK
with a `@Composable @Preview` and a `@NotificationPreview` function
through `rules_kotlin` + `rules_android` + `rules_jvm_external`. Sources
under [`app/src/main/kotlin/com/example/bazelsample/`](app/src/main/kotlin/com/example/bazelsample/).
Manifest at [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml).

```
bazel build //:bazel_sample_apk
```

**This target is known-fragile and intentionally not blocking CI.** The
core problem is upstream:
[bazelbuild/rules_kotlin#1388](https://github.com/bazelbuild/rules_kotlin/issues/1388) —
the Compose compiler plugin can't be wired through `rules_kotlin` on
Kotlin 2.x. The pins in [`MODULE.bazel`](MODULE.bazel) stay on Kotlin
1.9.x + standalone Compose Compiler 1.5.x as a workaround; this is a
deliberate toolchain divergence from the rest of compose-ai-tools (which
runs Kotlin 2.3.x).

The CI job that builds the APK lives in
[`.github/workflows/bazel.yml`](../../.github/workflows/bazel.yml) under
`jobs.bazel-build-apk` with `continue-on-error: true`. Watch it; when
it goes green organically (upstream fix, version bump, or a working
fork lands) the `continue-on-error` flag is the next thing to drop.

Until then, treat the target as scaffolding — proof that the layout
compiles in principle, useful as a starting point when the toolchain
unblocks, but not a guarantee that `bazel build //:bazel_sample_apk`
succeeds on `main` today.

### Why `@NotificationPreview` is redeclared locally

[`Notifications.kt`](app/src/main/kotlin/com/example/bazelsample/Notifications.kt)
re-declares the annotation as a file-private class rather than
depending on the in-tree `:preview-annotations` Gradle module. The
fixture's whole point is to demonstrate a project layout that doesn't
know about Gradle; the canonical annotation lives at
[`preview-annotations/src/main/kotlin/ee/schimke/composeai/preview/NotificationPreview.kt`](../../preview-annotations/src/main/kotlin/ee/schimke/composeai/preview/NotificationPreview.kt)
and a real downstream consumer would resolve the published artifact
from Maven (FQN match is what the discovery side keys off).
