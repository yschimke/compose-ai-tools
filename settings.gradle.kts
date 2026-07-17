pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

// The per-project conventions that used to live in the root build's `allprojects {}` block (ktfmt
// + googleStyle + the history-gate test system property) now live in `ComposeAiBaseConventionsPlugin`
// (build-logic), applied by each module via `plugins { id("composeai.base-conventions") }`. Isolated
// Projects forbids the root build configuring its siblings, and `gradle.lifecycle.beforeProject`
// can't apply an included-build plugin (its imperative `pluginManager.apply(id)` doesn't resolve
// against `pluginManagement`), so explicit per-module application is the IP-safe route — and it
// keeps `googleStyle()` typed (the convention plugin's classpath carries the ktfmt type).

// Snapshot probe for the SDK compatibility matrix's snapshot cells. Pulls Robolectric
// snapshots (which carry API 37 fixes ahead of the next stable release) from the new Sonatype
// Central Maven snapshots endpoint so `:samples:sdk-matrix` can render at SDK 37. The legacy
// `oss.sonatype.org` host is still reachable but stopped accepting new snapshots during
// Sonatype's 2024–2025 migration to `central.sonatype.com`. Both endpoints are added so a
// future-published snapshot that lands on the old host still resolves; in practice the new one
// wins. Scoped to `org.robolectric` so a stray snapshot artifact in some other group can't leak
// in. Repos are added only when the property is set; default builds aren't slowed by extra
// snapshot lookups.
val matrixRobolectricVersion: String? =
  providers.gradleProperty("composeai.matrix.robolectricVersion").orNull

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven("https://repo.gradle.org/gradle/libs-releases")
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
}

// BuildFetch remote Gradle build cache. Complements the local build cache (org.gradle.caching=true
// in gradle.properties) by sharing task outputs across CI runs and developer machines.
//
// Token: resolved from the first non-blank of, in order —
//          1. env  BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN  (project-specific)
//          2. prop BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN
//          3. env  BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN            (shared / general fallback)
//          4. prop BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN
//        The project-specific name lets a developer keep a separate token per BuildFetch project
//        (this repo and meshcore-mobile point at different caches) in one shared
//        ~/.gradle/gradle.properties; the general name lets a single token serve everything and is
//        what CI exports. Env wins over a gradle property of the same name so CI overrides a stray
//        local property. Each source is trimmed and empty-checked independently so a present-but-
//        empty value (e.g. an unset secret that CI still exports) never shadows a later source and
//        never enables the cache with an empty credential. When nothing resolves the cache disables
//        itself (isEnabled below) and the build falls back to the local cache with no error.
// Push:  writes are restricted to trusted CI builds (ON_CI=true on main); PRs and developer machines
//        are read-only. The gate is value-based so an explicit ON_CI=false is honoured as read-only.
val onCi = providers.environmentVariable("ON_CI").orElse("false").get().toBoolean()

// Non-blank view of a single env var / gradle property: trims and drops empties so a present-but-empty
// source doesn't shadow a later fallback (see the header comment).
val nonBlank = { source: Provider<String> -> source.map { it.trim() }.filter { it.isNotEmpty() } }
val cacheToken =
  nonBlank(providers.environmentVariable("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN"))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.environmentVariable("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orNull

// True only when this run will actually push to the remote: a trusted main-branch run (ON_CI) with a
// usable token. Anything else (PRs, dev machines, or a main run whose token is unprovisioned/blank)
// does not push, so it must keep the local cache.
val remotePushEnabled = onCi && cacheToken != null

buildCache {
  // On the trusted main-branch runs, CI is the sole writer of the BuildFetch remote cache — and the
  // only thing that populates it for every other consumer (PRs, developer machines). Gradle never
  // re-uploads a *local* build-cache hit to the remote; it pushes to the remote only when a task
  // actually executes. setup-gradle restores a warm local build cache (caches/build-cache-1) from the
  // GitHub Actions cache, so with it in place every task resolves as FROM-CACHE (local), nothing is
  // pushed, and the remote stays empty (dev machines then see 0 remote hits). Disabling the local
  // cache on the pushing runs forces tasks to execute-and-push, or to hit the remote directly, so
  // BuildFetch actually gets seeded.
  //
  // Gate this on remotePushEnabled, not just ON_CI: if the token is unprovisioned/blank the remote
  // below disables itself, and disabling the local cache too would make that main run execute every
  // cacheable task with *no* cache at all. Off-CI, on PRs, and on token-less main runs the local
  // cache stays on.
  local { isEnabled = !remotePushEnabled }
  remote<HttpBuildCache> {
    url = uri("https://cache.eu-central-a.buildfetch.com/8ESz2z/gradle/")

    credentials {
      username = "token-auth"
      password = cacheToken
    }

    isPush = onCi
    isEnabled = cacheToken != null
  }
}

rootProject.name = "compose-ai-tools"

includeBuild("gradle-plugin")

include(":cli")

// Mobile + Wear "session viewer" client apps and their shared engine. `:clients:core` is the
// pure-JVM streamed-frame client (connects to `compose-preview serve`'s `WS /ws/{previewId}` lane,
// decodes pushed frames, forwards pointer/key input, parses the tapped session link + the mDNS
// discovery contract). `:clients:mobile` / `:clients:wear` are the Android / Wear OS shells on top.
// Co-located with the service they consume (the `serve` server lives in `:cli`).
include(":clients:core")
include(":clients:mobile")
include(":clients:wear")


// Published wire-format DTOs (`PreviewResult`, `PreviewManifest`, the v1 a11y mirror types, …).
// Lives outside `:cli` so external consumers (contrib scripting, future MCP integrations,
// third-party tooling) can pull just the data shapes without dragging in `:cli`'s Gradle Tooling
// API + scripting closure. Step A of the clean-API carve-out — issue #1084 / docs/AGENTS.md
// "Built-in scripts" / clean-API discussion.
include(":preview-data-api")

project(":preview-data-api").projectDir = file("api/preview-data-api")

// Okio-based file/IO foundation. Every non-Gradle production module funnels file reads/writes
// through `:common-io`'s suspend helpers (Dispatchers.IO + Okio FileSystem) instead of
// `java.io.File`. Published because most consumers are themselves published.
include(":common-io")

project(":common-io").projectDir = file("common/io")

// Step B of the clean-API carve-out: the Gradle Tooling-API render pipeline that previously
// lived inside `:cli`'s `Command` base class. Exposes a `GradlePreviewDriver` library so
// external consumers (contrib scripting, third-party tooling) can render previews and read the
// result without taking a dependency on `:cli`. The CLI's own commands are refactored to drive
// this library, keeping a single source of truth.
include(":gradle-preview-driver")

project(":gradle-preview-driver").projectDir = file("api/gradle-preview-driver")

// `:cli-scripting` (the Kotlin-scripting host for `compose-preview script <path>`) was removed
// in the step C carve-out — see issue #1084 / the clean-API discussion. Scripting now lives in
// `yschimke/compose-ai-contrib` as a standalone consumer of `:preview-data-api` +
// `:gradle-preview-driver`. Its absence from this repo is the proof the published API is
// expressive enough to build features against, not just inside. The intermediate
// `:examples-scripting` reference (a build-tested template that contrib's copy job pulled into
// its own published module) has also been removed now that contrib hosts the canonical
// implementation.

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

include(":wear-preview-runtime")

project(":wear-preview-runtime").projectDir = file("runtimes/wear-preview")

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

include(":samples:sdk-matrix")

include(":samples:wear")

// Wear Compose Material 3 **design catalog** — one `@Preview` per component in its
// primary (round size) modes, exported as a sticker sheet (see
// `docs/design/DESIGN_CATALOGS.md` and the M3 sibling `:samples:design-catalog-m3`).
include(":samples:design-catalog-wear-m3")

include(":samples:xr-glimmer")

include(":samples:xr-spatial")

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

// Remote Compose **design catalog** — one `@Preview` per Remote Compose component
// (Wear Compose Remote Material 3 + remote-creation-compose primitives), exported
// as a sticker sheet like the M3 / Wear siblings. Each sticker is a real
// RemoteDocument built by `RemotePreview` and rasterised by the player, so this
// module carries the alpha Remote Compose runtime (compileSdk 37) rather than the
// stable Compose BOM (see `:samples:remotecompose` and `docs/design/DESIGN_CATALOGS.md`).
include(":samples:design-catalog-remote-m3")

include(":renderer-desktop")

project(":renderer-desktop").projectDir = file("renderers/desktop")

include(":renderer-android")

project(":renderer-android").projectDir = file("renderers/android")

include(":renderer-xr")

project(":renderer-xr").projectDir = file("renderers/xr")

// JVM client for the native `xr-composite --serve` render server that the daemon fronts. The
// daemon's future XR RenderSession backend wraps this.
include(":renderer-xr-client")

project(":renderer-xr-client").projectDir = file("renderers/xr-client")

include(":daemon:core")

// Per-product data-product modules — each `data/<product>/` carries a `core` (generic Android /
// Compose / AndroidX-test code, published) and a `connector` (daemon glue, unpublished) module.
// See docs/daemon/DATA-PRODUCTS.md § "Module split (D2.2)".
//
// Project paths use flat names rather than `:data:a11y:core` because Gradle resolves project
// dependencies by `<group>:<projectName>` and `:data:a11y:core`'s leaf name "core" collides
// with `:daemon:core`'s — same group, same name, "by conflict resolution" substitutes one for
// the other. Flat names avoid the collision; the directory layout on disk stays nested under
// `data/<product>/`. Published Maven coordinates are set explicitly in each module's
// `mavenPublishing { coordinates(...) }` block.
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

include(":data-render-core")

project(":data-render-core").projectDir = file("data/render/core")

include(":data-render-compose")

project(":data-render-compose").projectDir = file("data/render/compose")

include(":data-render-connector")

project(":data-render-connector").projectDir = file("data/render/connector")

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

include(":data-layoutinspector-core")

project(":data-layoutinspector-core").projectDir = file("data/layoutinspector/core")

include(":data-resources-connector")

project(":data-resources-connector").projectDir = file("data/resources/connector")

include(":data-resources-core")

project(":data-resources-core").projectDir = file("data/resources/core")

include(":data-strings-connector")

project(":data-strings-connector").projectDir = file("data/strings/connector")

include(":data-strings-core")

project(":data-strings-core").projectDir = file("data/strings/core")

include(":data-theme-core")

project(":data-theme-core").projectDir = file("data/theme/core")

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

include(":data-gestures-core")

project(":data-gestures-core").projectDir = file("data/gestures/core")

include(":data-gestures-connector")

project(":data-gestures-connector").projectDir = file("data/gestures/connector")

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

// Plain-Compose named overrides — opt-in author-declared editable knobs (`previewOverride*`). Unlike
// Remote Compose this needs no alpha runtime, so the runtime + connector are portable Compose
// Multiplatform JVM modules consumed by both daemon backends. `core` carries the wire-shape; `runtime`
// is the consumer-facing lookup API; `connector` seeds values + produces the `compose/overrides` data.
include(":data-preview-overrides-core")

project(":data-preview-overrides-core").projectDir = file("data/preview-overrides/core")

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

// Standalone Kotlin Build Tools API parity/soak harness (#1332). The stage-2 spike it began as
// has SHIPPED: in-process compile is wired into `:daemon:core` (`bta/BtaCompileSession`,
// `bta/DefaultBtaCompileService`, the `compileSources` JSON-RPC method) behind the experimental
// workspace flag `composePreview.daemon.compileInProcess`.
// Nothing in production depends on this module; it's retained only for its BTA-impl parity, IC,
// and classloader-leak soak tests (`./gradlew :daemon:bta-host:test`).
include(":daemon:bta-host")

// Companion fixture for `:daemon:bta-host` — same Kotlin source compiled through Gradle's
// standard `compileKotlin`, so the BTA parity test has a reference artefact to diff against.
// Same lifecycle as `:daemon:bta-host`; remove together with it.
include(":daemon:bta-host-fixture")

include(":mcp")

// Public render-session library. `:render-session-api` is the pure-interface surface every
// consumer (CLI, MCP server, third-party tooling) compiles against; `:render-session-subprocess`
// is the daemon-subprocess-backed implementation. Future `:render-session-embedded` will host the
// in-process Robolectric driver once that's viable.
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

// JDK 21+ samples. Each module here pulls in tooling whose own gradle plugin
// is compiled to Java 21 bytecode and therefore can't load on this repo's
// default JDK 17 build daemon (see `gradle/gradle-daemon-jvm.properties`,
// pinned to `toolchainVersion=17`). Rather than bump the daemon repo-wide
// and force every contributor onto JDK 21, we keep the pin at 17 and gate
// inclusion on `JavaVersion.current()`. The dedicated CI workflow
// `.github/workflows/samples-sdk21.yml` rewrites the daemon-jvm-properties
// file on the runner (never committed) so the daemon launches on 21 and
// the subtree gets exercised on every PR that touches it.
//
// Currently:
//  * `samples/sdk21/android-metro-viewmodel` — Metro 1.x DI; its Gradle
//    plugin jar targets Java 21.
if (JavaVersion.current() >= JavaVersion.VERSION_21) {
  include(":samples:sdk21:android-metro-viewmodel")
}

// Snapshot the project paths that carry ktfmt (every project except the root, which applies no
// convention plugin) for the root build's `ktfmtCheckAll` / `ktfmtFormatAll` aggregate tasks. Under
// Isolated Projects the root build can't iterate `allprojects` to discover its siblings, but it can
// depend on their tasks by path. We gather the paths here in settings — where every project is
// already known — and hand them to the root build through a system property, read back via a
// configuration-cache-tracked `providers.systemProperty(...)`. The channel must be closure-free: a
// `gradle.lifecycle.beforeProject` closure can't carry the list (IP isolates the action and can't
// serialize the captured settings-script reference).
val ktfmtProjectPaths = buildList {
  fun visit(descriptor: org.gradle.api.initialization.ProjectDescriptor) {
    // Only projects with a build script apply `composeai.base-conventions` (and therefore own a
    // `ktfmtCheck`/`ktfmtFormat` task). Container projects like `:daemon` / `:samples`, which exist
    // only because of nested includes and have no build file, are skipped.
    if (descriptor.buildFile.exists()) add(descriptor.path)
    descriptor.children.forEach(::visit)
  }
  // Start from the root's children: the root project can't apply `composeai.base-conventions`
  // (a build-logic plugin on the root classpath leaks to every subproject and collides with their
  // versioned plugin aliases), so the root carries no ktfmt and is left out.
  rootProject.children.forEach(::visit)
}
System.setProperty("composeai.ktfmtProjectPaths", ktfmtProjectPaths.joinToString(","))
