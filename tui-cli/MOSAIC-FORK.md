# Using a local Mosaic fork

`:tui-cli` is wired to consume **`com.jakewharton.mosaic:mosaic-runtime:0.19.0-SNAPSHOT`**
(plus the matching `mosaic-terminal` / `mosaic-tty` / `mosaic-tty-terminal` siblings) from
the local Maven cache so we can develop against the fork at
[**`yschimke/mosaic@compose-ai-tools`**](https://github.com/yschimke/mosaic/tree/compose-ai-tools)
without waiting for upstream PRs to merge. That branch adds two composables we describe
in the RFCs alongside this doc:

- `RawText(text)` — verbatim escape-sequence passthrough with consumer-declared display
  width. Resolves layout-engine width-tracking desync (see
  [`MOSAIC-IMAGE-RFC.md`](MOSAIC-IMAGE-RFC.md) §2 Option A).
- `Image(painter, …)` — Kitty Graphics Protocol with a half-block fallback, picked at
  render time via the existing `Terminal.Capabilities.kittyGraphics` flag.

## Workflow

### One-time fork setup

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
com.jakewharton.mosaic:mosaic-runtime:0.19.0-SNAPSHOT`. Both halves are required.

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

Adds `mavenLocal()` to the project's `dependencyResolutionManagement.repositories`,
**scoped to the `com.jakewharton.mosaic` group** via `content { includeGroup(...) }`.
Scoping matters: an unscoped `mavenLocal()` would let any stale snapshot in `~/.m2`
shadow whatever's in Maven Central, and that's a well-known way to make CI builds
non-reproducible.

The earlier draft of this wiring used `includeBuild("../mosaic")` for composite-build
substitution instead. That doesn't work today because Mosaic's `build-support` Kotlin
module compiles `java.net.http.HttpClient.use { … }` which requires JDK 21+ source
compatibility, and the compose-ai-tools Gradle daemon is pinned to JDK 17 via
`gradle/gradle-daemon-jvm.properties`. The two builds can't share a daemon JVM, so we
isolate them via `publishToMavenLocal`.

### `gradle/libs.versions.toml`

`mosaic = "0.19.0-SNAPSHOT"` matches the fork's `gradle.properties` VERSION_NAME. When
the local publication is missing this resolves to whatever 0.19.0 line ships upstream
(currently nothing — so the build will fail cleanly with a "not found" message rather
than silently picking up stable 0.18.0). When stable 0.19.0 lands on Maven Central, this
file is what we bump to consume it.

## Sandbox vs. real-machine differences

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

## Runtime caveat

Skipping the JNI native compilation means the published `mosaic-tty-jvm-0.19.0-SNAPSHOT.jar`
has no platform `.so` / `.dylib` / `.dll` resources. If you actually run the TUI
(`compose-preview-tui`) against this artefact and it tries to engage raw-mode terminal
I/O, expect an `UnsatisfiedLinkError`. The build sandbox where these local patches were
authored is for **build verification only** — the e2e harness at
[`src/test/kotlin/.../e2e/KittyE2ETest.kt`](src/test/kotlin/ee/schimke/composeai/tui/e2e/KittyE2ETest.kt)
needs to run on a machine where Mosaic was published with its native libs intact.

In practice that means: build Mosaic on your dev machine with full network access; copy
the resulting `~/.m2/repository/com/jakewharton/mosaic/...` tree to the sandbox if you
need to exercise the TUI there; or simply do TUI runtime testing on the dev machine.

## Falling back to upstream

To revert to upstream stable Mosaic without removing this wiring:

1. Edit `gradle/libs.versions.toml` and set `mosaic = "0.18.0"` (or whatever upstream
   stable is current).
2. Leave the `mavenLocal()` block in `settings.gradle.kts` in place — it's already scoped
   to the mosaic group, so absence of a local publication just falls through to Maven
   Central.

No other code changes are required. `:tui-cli`'s current source doesn't yet adopt the
fork's `RawText` / `Image` composables; the upgrade only widens the dependency surface.
The fork is plumbed in so we can land the adoption commit next, not because anything
imports from the fork today.
