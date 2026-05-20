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
contrib/bazel/
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
[`samples/android/src/main/res/`](../../samples/android/src/main/res/), chosen
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

## Companion: Compose APK sample

A second Bazel sample at [`contrib/bazel-apk/`](../bazel-apk/) builds a
real Compose APK (`//:bazel_sample_apk`) via `rules_kotlin` +
`rules_android` + `rules_jvm_external`. It lives in a separate module
so its Android SDK / Maven toolchain wiring doesn't leak into this
resources-only job. That target is opt-in and known-fragile — see its
README for the rules_kotlin#1388 backstory.
