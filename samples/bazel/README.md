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
└── app/
    └── res/
        ├── drawable/ic_demo.xml
        └── drawable-night/ic_demo.xml
```

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

- [x] `MODULE.bazel`, `.bazelrc`, sample XML drawables
- [x] `discover_resources` rule (placeholder shell action)
- [ ] `compose-preview discover-resources` CLI subcommand (next commit)
- [ ] Swap `_discover.sh` for the real CLI binary
- [ ] `render_resources` rule (blocked on render-CLI extraction)
- [ ] CI smoke job (opt-in workflow, not on the default `check` matrix)
