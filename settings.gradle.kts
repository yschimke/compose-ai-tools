pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

// Why this file is shaped the way it is — the lanes it can switch, the build cache policy, the
// module layout — is docs/build-scripts/SETTINGS.md. Comments here state the live constraint only.
// Per-project conventions (ktfmt, googleStyle, the history-gate system property) are applied by
// each module via `plugins { id("composeai.base-conventions") }`, never from the root build.
// docs/build-scripts/SETTINGS.md#base-conventions

// Snapshot probe for the SDK compatibility matrix's snapshot cells: lets `:samples:sdk-matrix`
// render at SDK 37 against a Robolectric snapshot.
// docs/build-scripts/SETTINGS.md#robolectric-snapshots
val matrixRobolectricVersion: String? =
  providers.gradleProperty("composeai.matrix.robolectricVersion").orNull

// Which line the three Remote Compose groups (`androidx.compose.remote`,
// `androidx.wear.compose.remote`, `androidx.glance.wear`) resolve from: `release` (default, the
// alpha coordinates pinned in `gradle/libs.versions.toml`) or `snapshot`
// (`-Pcomposeai.remoteCompose=snapshot`, androidx-main post-submit).
//
// CONSTRAINT: the whole trio moves together. They only work when built against the same
// `remote-creation*`, so the mode flips all three keys at once and one group must never straddle
// the two lines. docs/build-scripts/SETTINGS.md#remote-compose-lane
val remoteComposeLine =
  providers.gradleProperty("composeai.remoteCompose").orElse("release").get().trim().lowercase()

require(remoteComposeLine == "release" || remoteComposeLine == "snapshot") {
  "composeai.remoteCompose must be 'release' or 'snapshot', was '$remoteComposeLine'"
}

val useRemoteComposeSnapshot = remoteComposeLine == "snapshot"

// androidx-main post-submit build the Remote Compose / Glance Wear artifacts resolve from when
// `composeai.remoteCompose=snapshot`. Bump this one line to move all three groups to a newer
// snapshot; build ids age out of androidx.dev after a few weeks, so if the artifacts 404 pick a
// fresh one from https://androidx.dev/snapshots/builds.
val androidxSnapshotBuildId = "16155060"

dependencyResolutionManagement {
  // PREFER_PROJECT exists solely so the Kotlin wasmJs toolchain's plugin-owned Node.js
  // distribution repository stays usable; dependency repositories still belong here.
  // docs/build-scripts/SETTINGS.md#repositories-mode
  repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
  repositories {
    google()
    mavenCentral()
    maven("https://repo.gradle.org/gradle/libs-releases")
    // All three Remote Compose groups come from ONE build id, group-scoped and snapshots-only, so
    // the trio can't skew and nothing else can drift onto an unreviewed snapshot.
    // docs/build-scripts/SETTINGS.md#remote-compose-lane
    if (useRemoteComposeSnapshot) {
      maven("https://androidx.dev/snapshots/builds/$androidxSnapshotBuildId/artifacts/repository") {
        name = "androidxSnapshots"
        content {
          includeGroupByRegex("androidx\\.compose\\.remote.*")
          includeGroupByRegex("androidx\\.wear\\.compose\\.remote.*")
          includeGroupByRegex("androidx\\.glance\\.wear.*")
        }
        mavenContent { snapshotsOnly() }
      }
    }
    if (matrixRobolectricVersion?.endsWith("-SNAPSHOT") == true) {
      maven("https://central.sonatype.com/repository/maven-snapshots/") {
        name = "robolectric-snapshots-central"
        content { includeGroup("org.robolectric") }
      }
      maven("https://oss.sonatype.org/content/repositories/snapshots/") {
        name = "robolectric-snapshots-oss"
        content { includeGroup("org.robolectric") }
      }
    }
  }

  // Snapshot mode rewrites the three version refs in place, so the TOML keeps exactly one set of
  // coordinates — the released ones. docs/build-scripts/SETTINGS.md#catalog-override
  if (useRemoteComposeSnapshot) {
    versionCatalogs {
      // `create`, not `named` — `named` fails here, and `create("libs")` returns the builder with
      // the TOML already imported, so these three lines override three versions and nothing else.
      create("libs") {
        version("compose-remote", "1.0.0-SNAPSHOT")
        version("wear-compose-remote", "1.0.0-SNAPSHOT")
        version("glance-wear", "1.0.0-SNAPSHOT")
      }
    }
  }
}

// BuildFetch remote Gradle build cache, complementing the local one. Writes are restricted to
// trusted CI builds (ON_CI=true on main); PRs and developer machines are read-only, and the gate is
// value-based so an explicit ON_CI=false stays read-only. Token resolution order and the rest of
// the policy: docs/build-scripts/SETTINGS.md#build-cache
val onCi = providers.environmentVariable("ON_CI").orElse("false").get().toBoolean()

// Non-blank view of a single env var / gradle property: trims and drops empties so a present-but-
// empty source (an unset secret CI still exports) never shadows a later fallback and never enables
// the cache with an empty credential.
val nonBlank = { source: Provider<String> -> source.map { it.trim() }.filter { it.isNotEmpty() } }
val cacheToken =
  nonBlank(providers.environmentVariable("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN"))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.environmentVariable("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orNull

// TEMPORARY (issue #2824): kill switch for the BuildFetch remote cache — two entries are stored
// truncated at rest and Gradle treats the short read as FATAL, so any build resolving either key
// dies. Skip the remote until BuildFetch evicts them; the local cache stays on regardless.
// TO REVERT: delete this flag + the `composeai.remoteCache` line in gradle.properties.
// docs/build-scripts/SETTINGS.md#remote-cache-kill-switch
val remoteCacheDisabled =
  providers.gradleProperty("composeai.remoteCache").orElse("on").get().trim().lowercase() == "off"

buildCache {
  // The local cache stays ON everywhere, including on the trusted main runs that push — it
  // suppresses the redundant pushes, not the useful ones, so every trusted run can contribute.
  // Don't gate this on push again. docs/build-scripts/SETTINGS.md#local-cache-always-on
  local { isEnabled = true }
  remote<HttpBuildCache> {
    url = uri("https://cache.eu-central-a.buildfetch.com/8ESz2z/gradle/")

    credentials {
      username = "token-auth"
      password = cacheToken
    }

    isPush = onCi && !remoteCacheDisabled
    // TEMPORARY (#2824): `!remoteCacheDisabled` skips the cache holding the truncated entries.
    isEnabled = cacheToken != null && !remoteCacheDisabled
  }
}

rootProject.name = "compose-ai-tools"

includeBuild("gradle-plugin")

include(":cli")

// Modules that used to live here and where they went: docs/build-scripts/SETTINGS.md#extractions

// Compose/Wasm client for the preview server, staged into the CLI distribution as `preview-ui/`.
// It is a FORK of compose-preview-server's `wasm-ui`, gated byte-identical against a pinned
// upstream SHA by `.github/ci/check_serve_wasm_fork.py`: port a change to both, then bump the pin.
// docs/build-scripts/SETTINGS.md#serve-wasm-fork
include(":cli:serve-wasm")

// The preview-bundle *format* — split out of `:cli` for issue #3824. Everything a reader of a
// `.previewbundle` needs (well-known entry names, the manifest DTO, sidecar injectors,
// deterministic zip helpers, the detached signature scheme, classpath hydration, Android
// resource/launch support), with none of the argument parsing. `:cli` keeps the `bundle`
// subcommands and depends on this. Types keep the `ee.schimke.composeai.cli` package for
// source-compat, the same way `:gradle-preview-driver` did.
include(":bundle-format")

project(":bundle-format").projectDir = file("bundle/format")

// Resolving a bundle's recorded Maven coordinates into local jars — cache probes then an HTTP
// fetch. Split out of `:cli` for #3824 preparation item 7: `serve` needs it, and while it lived in
// `:cli` an extracted preview server could only have reached it through the CLI. Deliberately not
// part of `:bundle-format`, which stays offline and network-free.
include(":bundle-coordinates")

project(":bundle-coordinates").projectDir = file("bundle/coordinates")

// The wire contract between a preview server and a Gradle build host process — the seven build
// operations `ServeBuildHost` names, as messages rather than as a Kotlin interface. Published from
// here rather than from contracts because two of the operations carry `PreviewModule` —
// docs/design/BUILD_HOST_PROTOCOL_PREVIEWMODULE.md.
include(":build-host-protocol")

project(":build-host-protocol").projectDir = file("api/build-host-protocol")

// Published wire-format DTOs (`PreviewResult`, `PreviewManifest`, the v1 a11y mirror types, …).
// Lives outside `:cli` so external consumers can pull just the data shapes without dragging in
// `:cli`'s Gradle Tooling API + scripting closure.
// Content-crop geometry shared by the preview server (catalog thumbnails) and the CLI
// (`bundle split`). Extracted from `:cli:serve`'s `ServeThumbCrop.kt` so a CLI command does not
// depend on the server for arithmetic — #3824 preparation.
include(":common-image-crop")

project(":common-image-crop").projectDir = file("common/image-crop")

// HTML/JS/URL escaping and PNG header dimensions, shared by the server's pages and the bundle's
// web-embed gallery. Extracted from `:cli:serve` so `WebEmbed` could move to `:bundle-format`
// without dragging generic escaping into a format module — #3824 preparation.
include(":common-web-escaping")

project(":common-web-escaping").projectDir = file("common/web-escaping")

// Step B of the clean-API carve-out: the Gradle Tooling-API render pipeline that previously
// lived inside `:cli`'s `Command` base class. Exposes a `GradlePreviewDriver` library so
// external consumers (contrib scripting, third-party tooling) can render previews and read the
// result without taking a dependency on `:cli`. The CLI's own commands are refactored to drive
// this library, keeping a single source of truth.
include(":gradle-preview-driver")

project(":gradle-preview-driver").projectDir = file("api/gradle-preview-driver")

include(":bundle-viewer")

include(":notification-preview-runtime")

project(":notification-preview-runtime").projectDir = file("runtimes/notification")

include(":glance-preview-runtime")

project(":glance-preview-runtime").projectDir = file("runtimes/glance")

include(":appwidget-preview-runtime")

project(":appwidget-preview-runtime").projectDir = file("runtimes/appwidget")

include(":typography-preview-runtime")

project(":typography-preview-runtime").projectDir = file("runtimes/typography")

include(":color-preview-runtime")

project(":color-preview-runtime").projectDir = file("runtimes/color")

include(":splash-preview-runtime")

project(":splash-preview-runtime").projectDir = file("runtimes/splash")

// The composition document a UI builder assembles (a tree of component ids + per-instance knob
// values) and the Compose source it generates. Pure data + codegen, no Compose dependency, jvm +
// wasmJs so the browser builder and the JVM tests share one model. See docs/design/UI_BUILDER.md.
include(":screen-model")

project(":screen-model").projectDir = file("screen/model")

include(":wear-preview-runtime")

project(":wear-preview-runtime").projectDir = file("runtimes/wear-preview")

// The usage-snippet compile gate (see its build file). Empty unless `-PusageCorpus=` points it at
// a generated corpus, so it costs a normal build nothing.
include(":usage-source-psi")
include(":tools:usage-compile-check")
include(":samples:android")

// Compose Material 3 **design catalog** — one `@Preview` per component in its
// primary modes, authored so the renderer can export the module as an importable
// sticker sheet (see `docs/design/DESIGN_CATALOGS.md`). Now a Compose Multiplatform
// (desktop) module rendered by the desktop daemon (no Android SDK), so the public
// desktop preview server can also build + live re-render it (`--allow-render-trusted`).
include(":samples:design-catalog-m3")

// Single source of truth for the M3 catalog component set — shared `commonMain`
// composables consumed by both `:samples:design-catalog-m3` (desktop `@Preview`
// sticker sheet + live render) and `:samples:cmp-wasm-catalog` (in-browser wasm).
include(":samples:design-catalog-m3-shared")

// Android-only supplement to the (CMP) M3 catalog — the few previews that need
// androidx material3 APIs with no CMP equivalent (the material3 1.5.0-alpha inset
// focus ring). Rendered via Robolectric and folded into the compose-m3 catalog by
// the design-artifacts generator so those variants stay selectable.
include(":samples:design-catalog-m3-android")

include(":samples:android-alpha")

include(":samples:android-library")

include(":samples:android-screenshot-test")

include(":samples:android-daemon-bench")

// Fixture for the Android (Robolectric) serve-lane e2e: a tiny preview-only app whose merged
// manifest names an `Application` the render classpath doesn't carry — the #2669 shape. Packed into
// a bundle and live-rendered by `serve`. The lane that consumed it moved to
// yschimke/compose-preview-server with the server itself; the fixture stays here because it is
// a sample of THIS repository's render classpath, which is what makes it a useful fixture.
include(":samples:android-live-lane")

include(":samples:sdk-matrix")

include(":samples:wear")

// Wear widget/tile preview fixture for issue #2670 — a Wear module with
// `retargetWearPreviews = false` so its device-less widget previews crop to their intrinsic
// bounds (at wear density) for export as fixed-size drawable assets, rather than the 227dp
// watch-face canvas.
include(":samples:wear-widget")

// Wear Compose Material 3 **design catalog** — one `@Preview` per component in its
// primary (round size) modes, exported as a sticker sheet (see
// `docs/design/DESIGN_CATALOGS.md` and the M3 sibling `:samples:design-catalog-m3`).
include(":samples:design-catalog-wear-m3")

include(":samples:xr-glimmer")

include(":samples:cmp")

include(":samples:cmp-shared")

// In-browser CMP tier — a `wasmJs` Compose app rendering the M3 catalog in the
// browser sandbox (a `wasmJs` Compose app). wasmJs-only, no
// renderable `@Preview`, so it sits outside the desktop/Android render path.
include(":samples:cmp-wasm-catalog")

// Non-renderable KMP-Android library (no `jvm("desktop")` target) — regression fixture for
// #1852 / #1855. See its build.gradle.kts. Must coexist in the build without breaking CLI
// discovery of the other sample modules.
include(":samples:cmp-android-only")

include(":samples:desktop-daemon-bench")

include(":samples:remotecompose")

// The one `data/…` module that stayed when the extractors moved to compose-preview-daemon: the
// shared-element transition model is consumed by the render matrix here, not by a daemon. Flat
// path for the same reason the moved ones were. docs/build-scripts/SETTINGS.md#flat-data-paths
include(":data-shared-element-core")

project(":data-shared-element-core").projectDir = file("data/shared-element/core")

// Standalone Kotlin Build Tools API parity/soak harness (#1332). Nothing in production depends on
// it — the in-process compile ships in compose-preview-daemon's `daemon-core` `bta/` package — and
// it is retained only for its BTA-impl parity, IC and classloader-leak soak tests
// (`./gradlew :daemon:bta-host:test`).
include(":daemon:bta-host")

// Companion fixture for `:daemon:bta-host` — same Kotlin source compiled through Gradle's
// standard `compileKotlin`, so the BTA parity test has a reference artefact to diff against.
// Same lifecycle as `:daemon:bta-host`; remove together with it.
include(":daemon:bta-host-fixture")

// Render-matrix axes (`MatrixAxes`/`MatrixCell`) and the contact-sheet stitcher, shared by the
// CLI's offline `render-matrix` command and the MCP server's `render_matrix` tool. It was lifted
// out of `:mcp` for exactly that reason, and it is why `:mcp` could then move to
// compose-preview-server (#5176) without taking an offline CLI command with it: what layer 1 still
// calls stays in layer 1. The MCP server consumes it as a published coordinate now.
include(":render-matrix")

// The render host, the bundle daemon and the git-backed preview history — daemon-backed rendering,
// packed-bundle materialisation and manifest reads, with no web server underneath. Moved here from
// yschimke/compose-preview-server. docs/build-scripts/SETTINGS.md#render-host
include(":render-host")

// Public render-session library. `:render-session-api` is the pure-interface surface every
// consumer (CLI, MCP server, third-party tooling) compiles against; `:render-session-subprocess`
// is the daemon-subprocess-backed implementation.
include(":render-session-api")

project(":render-session-api").projectDir = file("render-session/api")

include(":render-session-subprocess")

project(":render-session-subprocess").projectDir = file("render-session/subprocess")

// In-process Compose Multiplatform Desktop backend for the render-session library. Hosts the
// daemon's `JsonRpcServer` + `DesktopHost` in the calling JVM via piped streams instead of forking
// a subprocess. Trades classpath footprint (the calling JVM picks up Skiko + Compose Desktop) for
// dramatically faster session startup. Embedders that don't want the runtime footprint stick with
// `:render-session-subprocess`.
include(":render-session-embedded-desktop")

project(":render-session-embedded-desktop").projectDir = file("render-session/embedded-desktop")

// Thin `java -cp` CLI over `:render-session-subprocess` for non-Gradle build systems
// (Bazel rules, Amper tasks in `yschimke/compose-ai-contrib`). See `contrib/README.md`.
include(":render-cli")

project(":render-cli").projectDir = file("render-session/cli")

// JDK 21+ samples, gated on the running JVM because this repo's build daemon is pinned to
// JDK 17 (`gradle/gradle-daemon-jvm.properties`). `.github/workflows/samples-sdk21.yml` runs the
// subtree on 21. docs/build-scripts/SETTINGS.md#jdk21-samples
if (JavaVersion.current() >= JavaVersion.VERSION_21) {
  include(":samples:sdk21:android-metro-viewmodel")
}

// Local iteration against a compose-preview-daemon checkout. The renderers, the daemon hosts, the
// preview annotations and data API and the data extractors are consumed at the published
// `composeai-preview-daemon` pin (#5336); `-Pcomposeai.previewDaemonDir=../compose-preview-daemon`
// substitutes every coordinate this build resolves from that line for the sibling checkout's
// project, so a daemon change can be tried here before it is released. The three daemon hosts need
// an explicit mapping because their project names (`:daemon:core`) are not their artifactIds; the
// flat modules substitute by `group:name` on their own, and are listed anyway so the set is stated.
providers.gradleProperty("composeai.previewDaemonDir").orNull?.let { dir ->
  includeBuild(dir) {
    dependencySubstitution {
      mapOf(
          "daemon-core" to ":daemon:core",
          "daemon-android" to ":daemon:android",
          "daemon-desktop" to ":daemon:desktop",
          "daemon-client" to ":daemon-client",
          "renderer-desktop" to ":renderer-desktop",
          "renderer-android" to ":renderer-android",
          "preview-data-api" to ":preview-data-api",
          "preview-annotations" to ":preview-annotations",
          "data-fonts-core" to ":data-fonts-core",
          "data-pseudolocale-core" to ":data-pseudolocale-core",
          "data-remotecompose-core" to ":data-remotecompose-core",
          "data-remotecompose-connector" to ":data-remotecompose-connector",
          "data-keyboard-connector" to ":data-keyboard-connector",
          "data-ambient-connector" to ":data-ambient-connector",
          "data-glimmer-environment-connector" to ":data-glimmer-environment-connector",
          "data-launcher-widget-connector" to ":data-launcher-widget-connector",
          "data-layoutinspector-connector" to ":data-layoutinspector-connector",
          "data-preview-overrides-runtime" to ":data-preview-overrides-runtime",
          "slot-preview-runtime" to ":slot-preview-runtime",
          "lottie-preview-runtime" to ":lottie-preview-runtime",
          "svg-preview-runtime" to ":svg-preview-runtime",
        )
        .forEach { (artifact, path) ->
          substitute(module("ee.schimke.composeai:$artifact")).using(project(path))
        }
    }
  }
}

// Project paths carrying ktfmt, handed to the root build's `ktfmtCheckAll` / `ktfmtFormatAll`
// aggregate tasks through a system property. The channel must stay closure-free under Isolated
// Projects. docs/build-scripts/SETTINGS.md#ktfmt-project-paths
val ktfmtProjectPaths = buildList {
  fun visit(descriptor: org.gradle.api.initialization.ProjectDescriptor) {
    // Only projects with a build script apply `composeai.base-conventions`, so container projects
    // like `:daemon` / `:samples` own no ktfmt task and are skipped.
    if (descriptor.buildFile.exists()) add(descriptor.path)
    descriptor.children.forEach(::visit)
  }
  // The root can't apply `composeai.base-conventions` (it would leak to every subproject), so it
  // carries no ktfmt and is left out.
  rootProject.children.forEach(::visit)
}
System.setProperty("composeai.ktfmtProjectPaths", ktfmtProjectPaths.joinToString(","))
