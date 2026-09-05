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
include(":preview-data-api")

project(":preview-data-api").projectDir = file("api/preview-data-api")

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

include(":preview-annotations")

project(":preview-annotations").projectDir = file("api/preview-annotations")

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

include(":lottie-preview-runtime")

project(":lottie-preview-runtime").projectDir = file("runtimes/lottie")

include(":svg-preview-runtime")

project(":svg-preview-runtime").projectDir = file("runtimes/svg")

include(":slot-preview-runtime")

project(":slot-preview-runtime").projectDir = file("runtimes/slots")

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

include(":renderer-desktop")

project(":renderer-desktop").projectDir = file("renderers/desktop")

include(":renderer-android")

project(":renderer-android").projectDir = file("renderers/android")

// JVM client for the native `xr-composite --serve` render server that the daemon fronts. The
// daemon's future XR RenderSession backend wraps this.
include(":renderer-xr-client")

project(":renderer-xr-client").projectDir = file("renderers/xr-client")

include(":daemon:core")



// Per-product data-product modules — each `data/<product>/` carries a `core` (published) and a
// `connector` (daemon glue, unpublished) module. See docs/daemon/DATA-PRODUCTS.md § "Module split
// (D2.2)". Project paths must stay FLAT (`:data-a11y-core`, not `:data:a11y:core`): a nested leaf
// named `core` collides with `:daemon:core` under Gradle's `<group>:<projectName>` resolution.
// docs/build-scripts/SETTINGS.md#flat-data-paths
include(":data-a11y-core")

project(":data-a11y-core").projectDir = file("data/a11y/core")

include(":data-a11y-hierarchy-android")

project(":data-a11y-hierarchy-android").projectDir = file("data/a11y/hierarchy-android")

include(":data-a11y-connector")

project(":data-a11y-connector").projectDir = file("data/a11y/connector")

include(":data-fonts-core")

project(":data-fonts-core").projectDir = file("data/fonts/core")

include(":data-fonts-connector")

project(":data-fonts-connector").projectDir = file("data/fonts/connector")

include(":data-navigation-core")

project(":data-navigation-core").projectDir = file("data/navigation/core")

include(":data-navigation-connector")

project(":data-navigation-connector").projectDir = file("data/navigation/connector")

// Google Fonts resolution + machine-local TTF cache. Shared by the Robolectric downloadable-font
// shadow and the Remote Compose typeface resolver so both lanes resolve a family to the same file.
include(":data-fonts-google")

project(":data-fonts-google").projectDir = file("data/fonts/google")

include(":data-render-compose")

project(":data-render-compose").projectDir = file("data/render/compose")

include(":data-render-connector")

project(":data-render-connector").projectDir = file("data/render/connector")

include(":data-motion-core")

project(":data-motion-core").projectDir = file("data/motion/core")

include(":data-scroll-core")

project(":data-scroll-core").projectDir = file("data/scroll/core")

include(":data-scroll-android")

project(":data-scroll-android").projectDir = file("data/scroll/android")

include(":data-scroll-connector")

project(":data-scroll-connector").projectDir = file("data/scroll/connector")

include(":data-history-core")

project(":data-history-core").projectDir = file("data/history/core")

include(":data-history-connector")

project(":data-history-connector").projectDir = file("data/history/connector")

include(":data-layoutinspector-connector")

project(":data-layoutinspector-connector").projectDir = file("data/layoutinspector/connector")

include(":data-resources-connector")

project(":data-resources-connector").projectDir = file("data/resources/connector")

include(":data-resources-core")

project(":data-resources-core").projectDir = file("data/resources/core")

include(":data-strings-connector")

project(":data-strings-connector").projectDir = file("data/strings/connector")

include(":data-strings-core")

project(":data-strings-core").projectDir = file("data/strings/core")

include(":data-theme-connector")

project(":data-theme-connector").projectDir = file("data/theme/connector")

include(":data-wallpaper-core")

project(":data-wallpaper-core").projectDir = file("data/wallpaper/core")

include(":data-wallpaper-connector")

project(":data-wallpaper-connector").projectDir = file("data/wallpaper/connector")

include(":data-ambient-core")

project(":data-ambient-core").projectDir = file("data/ambient/core")

include(":data-ambient-connector")

project(":data-ambient-connector").projectDir = file("data/ambient/connector")

include(":data-glimmer-environment-connector")

project(":data-glimmer-environment-connector").projectDir =
  file("data/glimmer-environment/connector")

include(":data-gestures-core")

project(":data-gestures-core").projectDir = file("data/gestures/core")

include(":data-gestures-connector")

project(":data-gestures-connector").projectDir = file("data/gestures/connector")

include(":data-gestures-robolectric-stubs")

project(":data-gestures-robolectric-stubs").projectDir = file("data/gestures/robolectric-stubs")

include(":data-shared-element-core")

project(":data-shared-element-core").projectDir = file("data/shared-element/core")

include(":data-focus-core")

project(":data-focus-core").projectDir = file("data/focus/core")

include(":data-focus-connector")

project(":data-focus-connector").projectDir = file("data/focus/connector")

include(":data-focus-connector-desktop")

project(":data-focus-connector-desktop").projectDir = file("data/focus/connector-desktop")

include(":data-keyboard-core")

project(":data-keyboard-core").projectDir = file("data/keyboard/core")

include(":data-keyboard-connector")

project(":data-keyboard-connector").projectDir = file("data/keyboard/connector")

include(":data-keyboard-connector-desktop")

project(":data-keyboard-connector-desktop").projectDir = file("data/keyboard/connector-desktop")

include(":data-touch-overlay-connector")

project(":data-touch-overlay-connector").projectDir = file("data/touch-overlay/connector")

include(":data-launcher-widget-connector")

project(":data-launcher-widget-connector").projectDir = file("data/launcher-widget/connector")

include(":data-pseudolocale-core")

project(":data-pseudolocale-core").projectDir = file("data/pseudolocale/core")

include(":data-pseudolocale-connector")

project(":data-pseudolocale-connector").projectDir = file("data/pseudolocale/connector")

include(":data-pseudolocale-connector-desktop")

project(":data-pseudolocale-connector-desktop").projectDir =
  file("data/pseudolocale/connector-desktop")

include(":data-recomposition-core")

project(":data-recomposition-core").projectDir = file("data/recomposition/core")

include(":data-recomposition-connector")

project(":data-recomposition-connector").projectDir = file("data/recomposition/connector")

include(":data-displayfilter-core")

project(":data-displayfilter-core").projectDir = file("data/displayfilter/core")

include(":data-displayfilter-connector")

project(":data-displayfilter-connector").projectDir = file("data/displayfilter/connector")

include(":data-deviceframe-core")

project(":data-deviceframe-core").projectDir = file("data/deviceframe/core")

include(":data-deviceframe-connector")

project(":data-deviceframe-connector").projectDir = file("data/deviceframe/connector")

include(":data-permissions-core")

project(":data-permissions-core").projectDir = file("data/permissions/core")

include(":data-permissions-connector")

project(":data-permissions-connector").projectDir = file("data/permissions/connector")

// Remote Compose connector — exposes the daemon's named-value store, host-action capture queue,
// and active profile to user code rendering a `RemotePreview { ... }` block. Android-only:
// `androidx.compose.remote.*` is an Android artifact requiring compileSdk 37 (see
// `:samples:remotecompose`); the connector ships its alpha-API deps as `compileOnly` so daemon
// modules at compileSdk 36 can still consume the AAR. The Compose API surface (composition local,
// data product, override planner) registers on `:daemon:android` only.
include(":data-remotecompose-core")

project(":data-remotecompose-core").projectDir = file("data/remotecompose/core")

include(":data-remotecompose-connector")

project(":data-remotecompose-connector").projectDir = file("data/remotecompose/connector")

include(":data-preview-overrides-runtime")

project(":data-preview-overrides-runtime").projectDir = file("data/preview-overrides/runtime")

include(":data-preview-overrides-connector")

project(":data-preview-overrides-connector").projectDir = file("data/preview-overrides/connector")

// UIAutomator-shaped query/action API for the Compose preview renderer. Carries the matcher,
// the Selector DSL, and the JSON wire format — consumed by `:daemon:android` for
// `record_preview`'s `uia.*` script events.
include(":data-uiautomator-core")

project(":data-uiautomator-core").projectDir = file("data/uiautomator/core")

include(":data-uiautomator-connector")

project(":data-uiautomator-connector").projectDir = file("data/uiautomator/connector")

include(":data-uiautomator-hierarchy-android")

project(":data-uiautomator-hierarchy-android").projectDir =
  file("data/uiautomator/hierarchy-android")

include(":daemon:android")

include(":daemon:desktop")

include(":daemon:harness")

// Standalone Kotlin Build Tools API parity/soak harness (#1332). Nothing in production depends on
// it — in-process compile shipped in `:daemon:core`'s `bta/` package — and it is retained only for
// its BTA-impl parity, IC and classloader-leak soak tests (`./gradlew :daemon:bta-host:test`).
include(":daemon:bta-host")

// Companion fixture for `:daemon:bta-host` — same Kotlin source compiled through Gradle's
// standard `compileKotlin`, so the BTA parity test has a reference artefact to diff against.
// Same lifecycle as `:daemon:bta-host`; remove together with it.
include(":daemon:bta-host-fixture")

// JSON-RPC client for the preview daemon, published so the render-session library can drive a
// daemon without dragging the MCP server onto the classpath. Lifted out of `:mcp` for exactly that
// reason — #3824 preparation item 3, the last leak the contract probe recorded.
include(":daemon-client")

project(":daemon-client").projectDir = file("daemon/client")

include(":mcp")

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
