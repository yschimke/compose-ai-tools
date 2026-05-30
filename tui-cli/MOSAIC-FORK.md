# Using the Mosaic fork

`:tui-cli` consumes **`ee.schimke.composeai.mosaic:mosaic-runtime:0.19.0-SNAPSHOT`**
(plus the matching `mosaic-terminal` / `mosaic-tty` / `mosaic-tty-terminal` siblings) from
the fork at
[**`yschimke/mosaic@compose-ai-tools`**](https://github.com/yschimke/mosaic/tree/compose-ai-tools)
so we can develop against it without waiting for upstream PRs to merge. The fork republishes
Mosaic under the `ee.schimke.composeai.mosaic` group (the Kotlin package names are unchanged —
imports stay `com.jakewharton.mosaic.*`) and its `snapshot.yaml` workflow publishes a
`-SNAPSHOT` build to the Sonatype Central snapshots repo on every push to the branch.

## Opt-in flag

**The TUI build is off by default.** It is only wired into the build when `local.properties`
contains:

```properties
tui.enabled=true
```

`settings.gradle.kts` reads that flag at configuration time. When it is unset:

- the Sonatype Central snapshots repository is **not** added,
- the `:tui-cli` module is **not** included, and
- nothing references the Mosaic snapshot dependency.

So a default `./gradlew` checkout (and CI) never resolves the unstable snapshot. To work on
the TUI, set the flag, then build `:tui-cli` as usual. The version catalog still *declares*
`libs.mosaic.runtime` either way — the declaration is inert until `:tui-cli` is included.

That branch adds two composables we describe in the RFCs alongside this doc:

- `RawText(text)` — verbatim escape-sequence passthrough with consumer-declared display
  width. Resolves layout-engine width-tracking desync (see
  [`MOSAIC-IMAGE-RFC.md`](MOSAIC-IMAGE-RFC.md) §2 Option A).
- `Image(painter, …)` — Kitty Graphics Protocol with a half-block fallback, picked at
  render time via the existing `Terminal.Capabilities.kittyGraphics` flag.

## Workflow

**You normally don't need any of this.** The fork's `snapshot.yaml` workflow publishes the
artefacts (native libs included — see [Runtime](#runtime)) to Sonatype Central snapshots on
every push to `compose-ai-tools`, so a `tui.enabled=true` build just resolves them. The
publish-to-mavenLocal dance below is **only** for iterating on the fork's own source locally
before a snapshot is published.

### One-time fork setup (only when modifying the fork)

```bash
# Sibling to compose-ai-tools/
git clone --branch compose-ai-tools https://github.com/yschimke/mosaic.git ../mosaic

# Mosaic's build-support uses java.net.http.HttpClient.use { } (JDK 21+). Build Mosaic on
# JDK 21 even though compose-ai-tools' own daemon is pinned to JDK 17 — the two builds are
# independent and only their published artefact format needs to match.
cd ../mosaic
JAVA_HOME=/path/to/jdk-21 ./gradlew \
  :mosaic-runtime:publishJvmPublicationToMavenLocal \
  :mosaic-runtime:publishKotlinMultiplatformPublicationToMavenLocal \
  :mosaic-terminal:publishJvmPublicationToMavenLocal \
  :mosaic-terminal:publishKotlinMultiplatformPublicationToMavenLocal \
  :mosaic-tty:publishJvmPublicationToMavenLocal \
  :mosaic-tty:publishKotlinMultiplatformPublicationToMavenLocal \
  :mosaic-tty-terminal:publishJvmPublicationToMavenLocal \
  :mosaic-tty-terminal:publishKotlinMultiplatformPublicationToMavenLocal \
  -x test
```

The `publishKotlinMultiplatformPublicationToMavenLocal` half writes the root metadata
module that links the per-target jars; without it Gradle resolves the JVM jar but can't
find a `*.module` to read transitive deps from and the build fails with `Could not find
ee.schimke.composeai.mosaic:mosaic-runtime:0.19.0-SNAPSHOT`. Both halves are required.

### Iterating on Mosaic-side changes

```bash
# After editing the fork, republish the affected module only.
cd ../mosaic
JAVA_HOME=/path/to/jdk-21 ./gradlew \
  :mosaic-runtime:publishJvmPublicationToMavenLocal \
  :mosaic-runtime:publishKotlinMultiplatformPublicationToMavenLocal

# Then rebuild compose-ai-tools as usual.
cd ../compose-ai-tools
./gradlew :tui-cli:installDist
```

Gradle's snapshot resolution checks the local Maven cache's mtime against the cached
metadata — most edits are picked up within seconds; if you hit a stale-resolution issue,
`./gradlew --refresh-dependencies :tui-cli:compileKotlin` forces a recheck.

## Wiring details

### `settings.gradle.kts`

Reads `tui.enabled` from `local.properties` into a `tuiEnabled` flag. When the flag is set,
it adds **both** the Sonatype Central snapshots repo
(`https://central.sonatype.com/repository/maven-snapshots/`) and `mavenLocal()` to
`dependencyResolutionManagement.repositories`, each **scoped to the `ee.schimke.composeai.mosaic`
group** via `content { includeGroup(...) }`. The snapshot repo is the normal source; `mavenLocal()`
is kept for iterating on the fork locally (`publishToMavenLocal`) and wins when a local
publication is present. Scoping matters: an unscoped repo would let a stale snapshot in `~/.m2`
or on Sonatype shadow whatever's in Maven Central, a well-known way to make builds
non-reproducible. The same flag also guards `include(":tui-cli")`, so when it's unset the module
isn't part of the build and nothing resolves the snapshot.

The earlier draft of this wiring used `includeBuild("../mosaic")` for composite-build
substitution instead. That doesn't work today because Mosaic's `build-support` Kotlin
module compiles `java.net.http.HttpClient.use { … }` which requires JDK 21+ source
compatibility, and the compose-ai-tools Gradle daemon is pinned to JDK 17 via
`gradle/gradle-daemon-jvm.properties`. The two builds can't share a daemon JVM, so we
isolate them by consuming the published snapshot artefact instead.

### `gradle/libs.versions.toml`

`mosaic = "0.19.0-SNAPSHOT"` matches the fork's `gradle.properties` VERSION_NAME, and
`mosaic-runtime` points at the `ee.schimke.composeai.mosaic` group the fork publishes under.
The catalog entry is always declared but inert unless `:tui-cli` is included (i.e. unless
`tui.enabled=true`). When stable 0.19.0 lands on Maven Central, this file is what we bump to
consume it.

## Sandbox vs. real-machine differences (local publish only)

This section applies **only** to the local `publishToMavenLocal` path above — the published
Sonatype snapshot is built on a CI `macos-15` runner with full network access and needs none
of these workarounds.

Mosaic's `publishToMavenLocal` reaches three hosts during build setup:

| Host | Used for |
| --- | --- |
| `download.jetbrains.com` | Kotlin/Native LLVM toolchain (cklib plugin, applied by `mosaic-tty`) |
| `ziglang.org` | Zig compiler for mosaic-tty's JNI native libs |
| `download.java.net` | OpenJDK jextract for the `jdk22` FFM multi-release compilation |

The compose-ai-tools Code-on-the-web environment's startup config now includes these
three hosts in its allowlist, so **fresh sessions in this env (and any normal dev
machine) complete the publish end-to-end without patching the Mosaic checkout.** The
network policy is fixed at session-create time though, so any session created before the
allowlist update still sees 403s on those URLs — `curl -I` against any of the three is
a quick way to tell which kind of session you're in.

### Fork-side patches still needed (Gradle 9.5 / jextract 22)

On top of the network allowlist, two small patches to the Mosaic fork's
`mosaic-tty/build.gradle` are required for the `jdk22` FFM compile to succeed:

1. **`generateSourceFiles = true`** on the `jextract.libraries.register('mosaic')`
   block. The plugin defaults to emitting `.class` files; the build script's
   `defaultSourceSet.kotlin.srcDir(mosaicJextractGenerateBindings)` wiring needs Java
   sources, not bytecode, on the Kotlin compile classpath.
2. **Pin jextract to JDK 22**:
   `jextract.installation.javaLanguageVersion = JavaLanguageVersion.of(22)` (plugin
   v1.2.0 API). The Tty.kt bindings call the simplified `MosaicIoResult.error(seg)` /
   `count(seg)` accessors that jextract started emitting in JDK 22; pinning to JDK 21
   emits the older `error$get` / `count$get` forms that won't link.

Plugin v1.0.0 (originally pinned in `libs.versions.toml`) has a constructor-injection
bug on Gradle 9.5 that breaks the `download {}` / `local {}` configuration blocks. Bump
to v1.2.0 — it exposes `jextract.installation` directly and works around the issue.
Newer versions (v1.4.0) apply the `java` plugin which conflicts with KMP, so don't go
higher than v1.2.0 until upstream fixes the KMP interop.

These patches belong upstream in the fork; until then they live as uncommitted local
edits in `../mosaic`. `git diff` against `compose-ai-tools` shows them clearly.

For sessions that *are* stuck on the old policy, the workaround is patching the Mosaic
clone:

- `../mosaic/addAllTargets.gradle` — comment out the native targets so cklib's LLVM
  fetch never fires.
- `../mosaic/mosaic-tty/build.gradle` — comment out `apply plugin: 'co.touchlab.cklib'`,
  the `cklib { }` config block, the `${target.name}JniZigBuild` task wiring, the
  `apply plugin: 'de.infolektuell.jextract'` apply + its `jextract.libraries.register`
  block, and the `jdk22Compilation` create / `from(jdk22Compilation.output)` jar block.

Both patches are sandbox-only and **must be reverted before pushing any further work
upstream** — they're not part of yschimke/mosaic#1. `git diff` against the fork's
`compose-ai-tools` branch will show them.

## Runtime

The **published Sonatype snapshot ships the JNI native libraries**, so running the TUI
(`compose-preview-tui`) against it engages raw-mode terminal I/O without an
`UnsatisfiedLinkError`. Verified contents of `mosaic-tty-jvm-0.19.0-SNAPSHOT.jar`:

| Platform | Resource |
| --- | --- |
| Linux amd64 / aarch64 / riscv64 | `com/jakewharton/mosaic/tty/jni/{amd64,aarch64,riscv64}/libmosaic.so` |
| macOS x86_64 / arm64 | `…/{x86_64,aarch64}/libmosaic.dylib` |
| Windows | `…/{amd64,aarch64}/mosaic.dll` |

So the e2e harness at
[`src/test/kotlin/.../e2e/KittyE2ETest.kt`](src/test/kotlin/ee/schimke/composeai/tui/e2e/KittyE2ETest.kt)
and any real TUI run resolve the native lib straight from the snapshot — no manual
`~/.m2` copy needed.

### Caveat: the local `publishToMavenLocal` path

If you instead publish the fork yourself with the **sandbox patches** above (which comment
out the cklib / Zig / jextract native steps), the resulting local
`mosaic-tty-jvm-0.19.0-SNAPSHOT.jar` has **no** `.so` / `.dylib` / `.dll` resources, and a
TUI run against it fails with `UnsatisfiedLinkError`. That path is for **build verification
only**. To exercise the TUI from a sandboxed local publish, either run on the published
snapshot (the default) or build Mosaic unpatched on a machine with full network access.

## Falling back to upstream

To consume upstream stable Mosaic instead of the fork snapshot:

1. Edit `gradle/libs.versions.toml`, set `mosaic` to the stable release, and repoint
   `mosaic-runtime` at the `com.jakewharton.mosaic` group.
2. Swap the Sonatype snapshots repo in `settings.gradle.kts` for `mavenCentral()` scoping
   (or drop the scoped repos entirely once the artefact is on Maven Central).

Note that with `tui.enabled` unset none of this is on the build path at all, so a default
checkout already builds without ever touching the fork.
