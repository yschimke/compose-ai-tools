# Buck2

`compose-preview` builds an existing [Buck2](https://buck2.build) JVM library,
discovers its compiled `@Preview` functions and renders them on the desktop
daemon, the same way it does for [Bazel](BAZEL.md). No BUCK file changes and no
new rules: a [BXL](https://buck2.build/docs/bxl/explanation/basics/) script reads
the providers the existing `kotlin_library` / `java_library` already produce.

```sh
compose-preview list
compose-preview render --filter Greeting --output greeting.png
compose-preview show --json
compose-preview buck list --target //app:app     # explicit form
```

Status: **Compose Multiplatform JVM/Desktop targets.** Android libraries
(`android_library`) need a linked resource APK the BXL script does not produce
yet; they are skipped.

## How it works

Buck2 has no aspects, so the Bazel driver's aspect becomes a BXL script,
`//.compose-preview/buck/compose_preview.bxl:artifacts`, copied from the CLI on
first use (`compose-preview buck init` writes it explicitly). It is the only
file the CLI adds, and a copy you edit is kept, never overwritten.

For each selected target the script:

1. keeps it only if its rule matches `targetKinds` (default
   `^(kotlin_library|java_library)$`) and it has a compiled jar;
2. reads `JavaLibraryInfo.library_output` (the target's own jar) and the
   `JavaPackagingInfo.packaging_deps` closure (its runtime classpath, in order);
3. builds and materializes those jars (`ctx.output.ensure_multiple`);
4. prints the [build-artifacts document](BAZEL.md#artifacts-from-an-existing-aspect-or-another-build-system)
   (schema 1) the Bazel aspect writes, with `runtimeJarOwners` naming the
   target behind each runtime jar.

Everything after that is the Bazel driver unchanged: discovery, the daemon, the
normal `list`/`render`/`show` output, `bundle`, `browse` and `build-host`. Paths
in the document are relative to the project root, which is Buck2's execution
root.

The script loads `@prelude//java:java_providers.bzl`, so the project's prelude
cell must be named `prelude` (the `buck2 init` default). A project with a
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
is not a JVM library, or holds no previews, is skipped. A label you name must be
a JVM library. Buck2 patterns have no `-` exclusions.

Several targets are one module rendered by one daemon on the union of their
runtime classpaths, as on Bazel.

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

## The sample

[`yschimke/compose-ai-contrib` `buck2-desktop/`](https://github.com/yschimke/compose-ai-contrib/tree/main/buck2-desktop)
is a Compose Desktop `kotlin_library` on the prelude's own Kotlin toolchain, with
the Compose compiler plugin and a generated third-party jar set. Its CI renders
the preview through this driver.
