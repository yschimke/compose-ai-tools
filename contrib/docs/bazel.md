# Driving `compose-preview` from Bazel

Bazel rules for Compose live in third-party space (`rules_kotlin`'s
`kt_compiler_plugin`). The recipe below uses the three published
`compose-preview` artifacts (extracted in Phase A — see
[`ee.schimke.composeai:preview-discovery`](https://central.sonatype.com/artifact/ee.schimke.composeai/preview-discovery),
[`:daemon-launch-builder`](https://central.sonatype.com/artifact/ee.schimke.composeai/daemon-launch-builder),
[`:render-cli`](https://central.sonatype.com/artifact/ee.schimke.composeai/render-cli))
through `rules_jvm_external` and a small set of `genrule`s that chain
the three `java -jar` invocations.

## Two fixtures shipped here

- [`bazel/`](../bazel/) — resources-only sample. Demonstrates the
  `discover_resources` rule shape against an Android `res/` tree
  cribbed from `compose-ai-tools/samples/android/`. No SDK toolchain
  needed; runs on every Bazel CI runner.
- [`bazel-apk/`](../bazel-apk/) — Compose APK target via `rules_kotlin`
  + `rules_android` + `rules_jvm_external`. Known-fragile while
  [`bazelbuild/rules_kotlin#1388`](https://github.com/bazelbuild/rules_kotlin/issues/1388)
  is open; treat as scaffolding for when the toolchain unblocks.

## Building the Compose target

The structural shape that compiles cleanly today (Kotlin 2.x via
`rules_kotlin` 2.x + the bundled `org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable`):

```bazel
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")
load("@rules_kotlin//kotlin:core.bzl", "kt_compiler_plugin")

kt_compiler_plugin(
    name = "compose_compiler_plugin",
    id = "androidx.compose.compiler",
    target_embedded_compiler = True,
    deps = ["@maven//:org_jetbrains_kotlin_kotlin_compose_compiler_plugin_embeddable"],
)

kt_jvm_library(
    name = "app",
    srcs = glob(["src/**/*.kt"]),
    plugins = [":compose_compiler_plugin"],
    deps = [
        "@maven//:org_jetbrains_compose_desktop_desktop_jvm_linux_x64",
        "@maven//:org_jetbrains_compose_components_components_ui_tooling_preview_desktop",
    ],
)
```

`bazel-apk/` lays this out for the Android target; the Compose Desktop
target is structurally identical minus the AGP machinery.

## Three-step rendering pipeline

```
bazel build //:app                              ← rules_kotlin produces .jar
  ↓
genrule + java -jar preview-discovery.jar       ← Phase A artifact #1
  → previews.json
  ↓
genrule + java -jar daemon-launch-builder.jar   ← Phase A artifact #2
  → daemon-launch.json
  ↓
sh_test + java -jar render-cli.jar              ← Phase A artifact #3
  → PNGs on disk
```

### 1. `MODULE.bazel` deps

```bazel
bazel_dep(name = "rules_jvm_external", version = "<latest>")
bazel_dep(name = "rules_kotlin", version = "<latest>")

maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
        "ee.schimke.composeai:preview-discovery:<v>",
        "ee.schimke.composeai:daemon-launch-builder:<v>",
        "ee.schimke.composeai:render-cli:<v>",

        # Renderer-side closure for the daemon descriptor's classpath:
        "ee.schimke.composeai:daemon-desktop:<v>",
        "ee.schimke.composeai:daemon-core:<v>",
        "ee.schimke.composeai:data-render-connector:<v>",
        "ee.schimke.composeai:data-render-core:<v>",
        # ...other data extensions you want available
    ],
    repositories = ["https://repo1.maven.org/maven2"],
)
use_repo(maven, "maven")
```

### 2. `discover_previews` rule

A `genrule` that invokes `PreviewDiscoveryCli` against `:app`'s
compiled output:

```bazel
genrule(
    name = "discover_previews",
    srcs = [":app"],
    outs = ["previews.json"],
    tools = ["@maven//:ee_schimke_composeai_preview_discovery"],
    cmd = """
        java -cp $(rootpath @maven//:ee_schimke_composeai_preview_discovery):$$(echo $(rlocationpaths @maven//:ee_schimke_composeai_preview_discovery) | tr ' ' ':') \\
            ee.schimke.composeai.discovery.PreviewDiscoveryCli \\
            --classes $(rootpath :app) \\
            --module //$(package_name):app \\
            --variant desktop \\
            --project-directory $(BINDIR) \\
            --out $@
    """,
)
```

The exact classpath construction depends on how `rules_jvm_external`
exposes transitives in your bazel version — the `@maven//:artifact`
label resolves to all transitives by default; older versions need
`exclude_transitive` flipping.

### 3. `build_daemon_descriptor` rule

```bazel
genrule(
    name = "daemon_descriptor",
    srcs = [
        ":app",
        ":discover_previews",
    ],
    outs = ["daemon-launch.json"],
    tools = ["@maven//:ee_schimke_composeai_daemon_launch_builder"],
    cmd = """
        # Renderer-side classpath: daemon-desktop + connectors + data-* cores,
        # then the Compose Desktop runtime jars rules_kotlin pulled in for :app.
        RENDERER_CP="$$(echo $(rlocationpaths @maven//:ee_schimke_composeai_daemon_desktop \\
            @maven//:ee_schimke_composeai_daemon_core \\
            @maven//:ee_schimke_composeai_data_render_connector \\
            @maven//:ee_schimke_composeai_data_render_core) | tr ' ' ':')"
        USER_CP="$(rootpath :app)"

        java -cp $(rootpath @maven//:ee_schimke_composeai_daemon_launch_builder) \\
            ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilderCli \\
            --module-path //$(package_name):app \\
            --variant desktop \\
            --main-class ee.schimke.composeai.daemon.DaemonMain \\
            --classpath $$RENDERER_CP:$$USER_CP \\
            --jvm-arg -Xmx1024m \\
            --system-property composeai.daemon.protocolVersion=1 \\
            --system-property composeai.daemon.modulePath=//$(package_name):app \\
            --system-property composeai.daemon.previewsJsonPath=$(rootpath :discover_previews) \\
            --system-property composeai.render.outputDir=$(@D)/renders \\
            --working-directory $$(pwd) \\
            --manifest-path $(rootpath :discover_previews) \\
            --out $@
    """,
)
```

### 4. `render_previews` test

A `sh_test` (or a custom rule) that invokes `RenderCli` and asserts
each requested PNG exists:

```bazel
sh_test(
    name = "render_previews_test",
    srcs = ["render_previews_test.sh"],
    data = [
        ":daemon_descriptor",
        ":discover_previews",
        "@maven//:ee_schimke_composeai_render_cli",
        # ... renderer-side closure that daemon-launch.json's classpath refers to
    ],
    args = ["$(rootpath :daemon_descriptor)"],
)
```

Where `render_previews_test.sh` does:

```bash
java -cp $(<the classpath from your rules_jvm_external manifest>) \
    ee.schimke.composeai.render.cli.RenderCli \
    --descriptor "$1" \
    --workspace-root "$PWD" \
    --previews $PREVIEWS \
    --timeout-seconds 60
```

## Limitations

- **`rules_kotlin` + Compose compiler.** Kotlin 2.x via the bundled
  `kotlin-compose-compiler-plugin-embeddable` works; the standalone
  `androidx.compose.compiler:compiler` jar is broken on K2
  ([`rules_kotlin#1388`](https://github.com/bazelbuild/rules_kotlin/issues/1388)).
- **Android rendering.** The Android backend (`daemon-android`)
  requires Robolectric + a working AGP-equivalent classpath. Driving
  that from Bazel is doable but well off the beaten path. Start with
  Compose Desktop above.
- **Reference projects.** `Bencodes/bazel_jetpack_compose_example`
  was the only public reference (now stale — Compose 1.2.0-beta02,
  Kotlin 1.6.21, Bazel 5.1.1). Use it as a structural template;
  expect to upgrade deps before the renderer can load classes
  compiled against it.

## See also

- The Bazel resources sample: [`bazel/`](../bazel/)
- The Bazel Compose APK sample: [`bazel-apk/`](../bazel-apk/)
- Contract spec (`daemon-launch.json` schema, classpath layering,
  sysprops): [`yschimke/compose-ai-tools/docs/NON_GRADLE_INTEGRATION.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/NON_GRADLE_INTEGRATION.md)
