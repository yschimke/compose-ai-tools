# Bazel Compose APK sample (opt-in, known-fragile)

A second Bazel sample, sibling to [`samples/bazel/`](../bazel/). Builds a
tiny Android APK with a `@Composable @Preview` and a
`@NotificationPreview` function through `rules_kotlin` +
`rules_android` + `rules_jvm_external`.

## Status

**This target is known-fragile and intentionally not blocking CI.** Two
upstream issues stack here:

1. [bazelbuild/rules_kotlin#1388](https://github.com/bazelbuild/rules_kotlin/issues/1388) —
   the Compose compiler plugin can't be wired through `rules_kotlin` on
   Kotlin 2.x, so we stay on Kotlin 1.9.x + standalone Compose Compiler
   1.5.x in [`MODULE.bazel`](MODULE.bazel).
2. `rules_kotlin` 1.9.x predates Bazel 8's removal of native
   `JavaPluginInfo` (now in `@rules_java`), so loading
   `kotlin/internal/jvm/jvm.bzl` blows up on Bazel 8 with
   `name 'JavaPluginInfo' is not defined`. This module pins Bazel 7 via
   [`.bazelversion`](.bazelversion) to avoid that.

Both pins are deliberate toolchain divergences from the rest of
compose-ai-tools (which runs Kotlin 2.3.x on Bazel 8).

The CI job that builds the APK lives in
[`.github/workflows/bazel.yml`](../../.github/workflows/bazel.yml) under
`jobs.bazel-build-apk` with `continue-on-error: true`. Watch it; when
it goes green organically (upstream fix, version bump, or a working
fork lands) the `continue-on-error` flag is the next thing to drop.

Until then, treat the target as scaffolding — proof that the layout
compiles in principle, useful as a starting point when the toolchain
unblocks, but not a guarantee that `bazel build //:bazel_sample_apk`
succeeds on `main` today.

## Build

From this directory:

```
bazel build //:bazel_sample_apk
```

## Layout

```
samples/bazel-apk/
├── MODULE.bazel       # bzlmod entrypoint with Android/Kotlin/Compose deps
├── .bazelrc           # bzlmod-only, no WORKSPACE fallback
├── BUILD.bazel        # kt_android_library + android_binary wiring
└── app/src/main/
    ├── AndroidManifest.xml
    └── kotlin/com/example/bazelsample/
        ├── Greeting.kt        # @Preview composable
        ├── MainActivity.kt    # ComponentActivity host
        └── Notifications.kt   # @NotificationPreview function
```

## Why a separate module from `samples/bazel/`

The resources-only sample at [`samples/bazel/`](../bazel/) keeps its
`MODULE.bazel` clean of Android/Kotlin/Maven toolchain wiring so its
`//:app_resources` job doesn't need an Android SDK on the runner. Mixing
both targets into one module forced the SDK and Maven extensions to
evaluate even when only the resources target was being built — see
PR #1276 for the regression that motivated the split.

## Why `@NotificationPreview` is redeclared locally

[`Notifications.kt`](app/src/main/kotlin/com/example/bazelsample/Notifications.kt)
re-declares the annotation as a file-private class rather than
depending on the in-tree `:preview-annotations` Gradle module. The
fixture's whole point is to demonstrate a project layout that doesn't
know about Gradle; the canonical annotation lives at
[`preview-annotations/src/main/kotlin/ee/schimke/composeai/preview/NotificationPreview.kt`](../../preview-annotations/src/main/kotlin/ee/schimke/composeai/preview/NotificationPreview.kt)
and a real downstream consumer would resolve the published artifact
from Maven (FQN match is what the discovery side keys off).
