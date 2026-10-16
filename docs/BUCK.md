# Buck2

`compose-preview` builds an existing [Buck2](https://buck2.build) JVM or Android
library, discovers its compiled `@Preview` functions and renders them on the
desktop or Android daemon, the same way it does for [Bazel](BAZEL.md). No BUCK
file changes and no new rules: a [BXL](https://buck2.build/docs/bxl/explanation/basics/)
script reads the providers the existing `kotlin_library` / `java_library` /
`android_library` already produce.

```sh
compose-preview list
compose-preview render --filter Greeting --output greeting.png
compose-preview show --json
compose-preview buck list --target //app:app     # explicit form
```

Status: **Compose Multiplatform JVM/Desktop targets, and Jetpack Compose
`android_library` targets** on the prelude's system Android toolchain (see
[Android](#android)).

## How it works

Buck2 has no aspects, so the Bazel driver's aspect becomes a BXL script,
`//.compose-preview/buck/compose_preview.bxl:artifacts`, copied from the CLI
with `link_sh.bxl`, which carries the `link.sh` it runs for Android libraries as
a string (a source file outside any package cannot be an action input).
`compose-preview buck init` writes both explicitly. They are the only files the CLI adds. A copy the CLI
wrote is upgraded by a newer CLI (`installed.json` records them); one you edit
is kept, and `buck init` refuses to replace it.

For each selected target the script:

1. keeps it only if its rule matches `targetKinds` (default
   `^(kotlin_library|java_library|android_library)$`) and it has a compiled jar;
2. reads `JavaLibraryInfo.library_output` (the target's own jar) and the
   `JavaPackagingInfo.packaging_deps` closure (its runtime classpath, in order);
3. builds and materializes those jars (`ctx.output.ensure_multiple`);
4. for an Android library, links its resources (below);
5. prints the [build-artifacts document](BAZEL.md#artifacts-from-an-existing-aspect-or-another-build-system)
   the Bazel aspects write (schema 1, or schema 2 with an `android` block), with
   `runtimeJarOwners` naming the target behind each runtime jar.

Everything after that is the Bazel driver unchanged: discovery, the daemon, the
normal `list`/`render`/`show` output, `bundle`, `browse` and `build-host`. Paths
in the document are relative to the project root, which is Buck2's execution
root.

The script loads the prelude's Java and Android providers, so the project's
prelude cell must be named `prelude` (the `buck2 init` default). A project with a
renamed or forked prelude sets `bxl` to its own script.

## Detection

A directory holding `.buckroot` is the project root; otherwise the outermost
`.buckconfig` below the repository boundary (a cell such as a vendored prelude
has its own `.buckconfig`, so the nearest one is not the root). Gradle still
wins where it owns the same root (`settings.gradle(.kts)` or `gradlew` there).
`--build-system buck` forces it in a mixed checkout.

## Selecting targets

`--target` is repeatable and takes what `buck2 build` takes: `//app:app`, a
package's targets `//app:`, or a recursive `//app/...`. Without one,
`target`/`targets` in `.compose-preview/buck.json` win; otherwise the nearest
package at or above the current directory (`//pkg:`), or outside any package
everything below it (`//dir/...`). A pattern's matches are candidates: one that
is not a JVM or Android library, or holds no previews, is skipped. A label you
name must be one. Buck2 patterns have no `-` exclusions.

Several JVM targets are one module rendered by one daemon on the union of their
runtime classpaths, as on Bazel. Android targets each render on their own
resource APK: the BXL document carries no link inputs for a merge.

## Android

An `android_library` has no resource APK of its own: Buck2 links resources only
into an `android_binary`. So the script links one per library, as the
[Bazel Android aspect](BAZEL.md) does, with the same `link.sh` from
rules_compose_preview, run as a BXL action:

- **Resources:** every `aapt2 compile` archive in the library's
  `AndroidPackageableInfo` closure, dependencies first and prebuilt AARs' (low
  priority) before the rest, the order the prelude links an `android_binary` in;
  the AARs' assets too.
- **R classes:** final IDs for every resource package in the closure (the
  library's own and each AAR's, such as `androidx.lifecycle.runtime.R`), compiled
  into an R jar the daemon puts ahead of the runtime classpath.
- **Toolchain:** `aapt2` and `android.jar` from the library's own Android
  toolchain; the JDK that compiles the R classes is `[java] java_home`, or
  `--java-home` to the script.
- **Package:** the `package` of the library's `android_resource` deps, else its
  manifest's.
- **SDK level:** `[android] compile_sdk_version` (`android-<N>`), clamped to
  what Robolectric renders on the daemon's JDK.

Rendering then follows the Bazel Android path: the Android daemon, Robolectric's
`android-all` jars, and an `android.jar` from `ANDROID_HOME` /
`ANDROID_SDK_ROOT`, which must be set.

**Compose version.** The Android daemon carries its own Jetpack Compose (1.12.1
for daemon 3.18.5), and its classes win over the target's, while the R IDs come
from the target's resources. A target on an older Compose fails with
`NoSuchFieldError: androidx.compose.ui.R$id ...` at `onAttachedToWindow`: use at
least the daemon's Compose. This applies to the Bazel Android path too.

## Configuration

`.compose-preview/buck.json` takes the [Bazel config](BAZEL.md)'s shape; these
fields apply:

```json
{
  "schemaVersion": 1,
  "targets": ["//app/..."],
  "buildCommand": ["buck2"],
  "startupArgs": ["--isolation-dir", "preview"],
  "buildArgs": ["@mode/desktop"],
  "targetKinds": "^(kotlin_library|java_library|my_kotlin_library)$",
  "bxl": "//tools/preview:compose_preview.bxl:artifacts",
  "dependencyPolicy": null
}
```

- `buildCommand`: an argv array. Default `buck2`, or `$COMPOSE_PREVIEW_BUILD_COMMAND`.
  `--build-command` overrides both.
- `startupArgs` go before `bxl`, `buildArgs` after it (`--buck-arg` adds more):
  `buck2 <startup> bxl <build args> <bxl> -- --kinds <regex> --target <t>…`.
- `targetKinds`: a regex over rule names, for a macro's own rule.
- `bxl` / `installAspect: false`: use your own script and never write one.

## The samples

[`yschimke/compose-ai-contrib` `buck2-desktop/`](https://github.com/yschimke/compose-ai-contrib/tree/main/buck2-desktop)
is a Compose Desktop `kotlin_library` on the prelude's own Kotlin toolchain, with
the Compose compiler plugin and a generated third-party jar set.
[`buck2-android/`](https://github.com/yschimke/compose-ai-contrib/tree/main/buck2-android)
is the same for a Jetpack Compose `android_library` with an `android_resource`
and the AndroidX closure as `android_prebuilt_aar`s. Their CI renders both
previews through this driver.
