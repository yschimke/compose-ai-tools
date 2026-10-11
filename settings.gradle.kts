pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

// Rationale for this file's shape (lanes, build cache policy, module layout) is in
// docs/build-scripts/SETTINGS.md; comments here state the live constraint only. Per-project
// conventions are applied by each module via `plugins { id("composeai.base-conventions") }`.

// Snapshot probe for the SDK compatibility matrix's snapshot cells: lets `:samples:sdk-matrix`
// render at SDK 37 against a Robolectric snapshot.
// docs/build-scripts/SETTINGS.md#robolectric-snapshots
val matrixRobolectricVersion: String? =
  providers.gradleProperty("composeai.matrix.robolectricVersion").orNull

// Which line the three Remote Compose groups (`androidx.compose.remote`,
// `androidx.wear.compose.remote`, `androidx.glance.wear`) resolve from: `release` (default, pinned
// in `gradle/libs.versions.toml`) or `snapshot` (`-Pcomposeai.remoteCompose=snapshot`).
//
// CONSTRAINT: the trio must move together (they must share `remote-creation*`).
// docs/build-scripts/SETTINGS.md#remote-compose-lane
val remoteComposeLine =
  providers.gradleProperty("composeai.remoteCompose").orElse("release").get().trim().lowercase()

require(remoteComposeLine == "release" || remoteComposeLine == "snapshot") {
  "composeai.remoteCompose must be 'release' or 'snapshot', was '$remoteComposeLine'"
}

val useRemoteComposeSnapshot = remoteComposeLine == "snapshot"

// androidx-main build id for `composeai.remoteCompose=snapshot`. Build ids age out of androidx.dev
// after a few weeks; if artifacts 404, pick a fresh one from https://androidx.dev/snapshots/builds.
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
      // `create`, not `named` (which fails here); `create("libs")` returns the builder with the
      // TOML already imported, so this overrides just these three versions.
      create("libs") {
        version("compose-remote", "1.0.0-SNAPSHOT")
        version("wear-compose-remote", "1.0.0-SNAPSHOT")
        version("glance-wear", "1.0.0-SNAPSHOT")
      }
    }
  }
}

// BuildFetch remote build cache. Writes only from trusted CI (ON_CI=true on main); everything else
// is read-only, and an explicit ON_CI=false stays read-only.
// docs/build-scripts/SETTINGS.md#build-cache
val onCi = providers.environmentVariable("ON_CI").orElse("false").get().toBoolean()

// Trims and drops empty values so an unset-but-exported secret never shadows a later fallback or
// enables the cache with an empty credential.
val nonBlank = { source: Provider<String> -> source.map { it.trim() }.filter { it.isNotEmpty() } }
val cacheToken =
  nonBlank(providers.environmentVariable("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN"))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.environmentVariable("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orNull

// TEMPORARY (#2824): kill switch for the BuildFetch remote cache — two truncated entries make any
// build resolving them fail. TO REVERT: delete this flag + `composeai.remoteCache` in
// gradle.properties. docs/build-scripts/SETTINGS.md#remote-cache-kill-switch
val remoteCacheDisabled =
  providers.gradleProperty("composeai.remoteCache").orElse("on").get().trim().lowercase() == "off"

buildCache {
  // The local cache stays on everywhere, including trusted pushing runs; don't gate it on push.
  // docs/build-scripts/SETTINGS.md#local-cache-always-on
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

// Extracted modules and where they went: docs/build-scripts/SETTINGS.md#extractions

// Compose/Wasm client for the preview server, staged into the CLI distribution as `preview-ui/`.
// A FORK of compose-preview-server's `wasm-ui`, gated byte-identical against a pinned upstream SHA
// by `.github/ci/check_serve_wasm_fork.py`: port a change to both, then bump the pin.
include(":cli:serve-wasm")

// The preview-bundle format: everything a `.previewbundle` reader needs, without `:cli`'s argument
// parsing. Types keep the `ee.schimke.composeai.cli` package for source compatibility.
include(":bundle-format")

project(":bundle-format").projectDir = file("bundle/format")

// Resolves a bundle's recorded Maven coordinates into local jars (cache probes, then HTTP). Kept
// out of `:bundle-format`, which stays offline.
include(":bundle-coordinates")

project(":bundle-coordinates").projectDir = file("bundle/coordinates")

// The wire contract between a preview server and a Gradle build host process, as messages.
// Published from here because two operations carry `PreviewModule` —
// docs/design/BUILD_HOST_PROTOCOL_PREVIEWMODULE.md.
include(":build-host-protocol")

project(":build-host-protocol").projectDir = file("api/build-host-protocol")

// Content-crop geometry shared by the preview server (catalog thumbnails) and the CLI
// (`bundle split`).
include(":common-image-crop")

project(":common-image-crop").projectDir = file("common/image-crop")

// HTML/JS/URL escaping and PNG header dimensions, shared by server pages and the bundle's web-embed
// gallery.
include(":common-web-escaping")

project(":common-web-escaping").projectDir = file("common/web-escaping")

// The Remote Compose JSON codec (authoring JSON ↔ `.rc` bytes). Its own module so an offline render
// doesn't link the Remote Compose runtime. See `remotecompose/json/build.gradle.kts` and
// `docs/design/REMOTE_COMPOSE_JSON.md`.
include(":remotecompose-json")

project(":remotecompose-json").projectDir = file("remotecompose/json")

// The Gradle Tooling-API render pipeline as a library (`GradlePreviewDriver`), so external tooling
// can render previews without depending on `:cli`. The CLI drives it too.
include(":gradle-preview-driver")

project(":gradle-preview-driver").projectDir = file("api/gradle-preview-driver")

include(":bundle-viewer")

include(":notification-preview-runtime")

project(":notification-preview-runtime").projectDir = file("runtimes/notification")

include(":glance-preview-runtime")

project(":glance-preview-runtime").projectDir = file("runtimes/glance")

include(":appwidget-preview-runtime")

project(":appwidget-preview-runtime").projectDir = file("runtimes/appwidget")

include(":theme-pin-compiler-plugin")

project(":theme-pin-compiler-plugin").projectDir = file("compiler/theme-pin")

include(":theme-pin-runtime")

project(":theme-pin-runtime").projectDir = file("runtimes/theme-pin")

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

// Compose Material 3 design catalog — one `@Preview` per component in its primary modes, exported
// as an importable sticker sheet (`docs/design/DESIGN_CATALOGS.md`). A CMP desktop module, so it
// renders without an Android SDK and the public preview server can live re-render it.
include(":samples:design-catalog-m3")

// Single source of truth for the M3 catalog components, shared by `:samples:design-catalog-m3` and
// `:samples:cmp-wasm-catalog`.
include(":samples:design-catalog-m3-shared")

// Android-only supplement to the M3 catalog for androidx material3 APIs with no CMP equivalent.
// Rendered via Robolectric and folded into the compose-m3 catalog by the design-artifacts
// generator.
include(":samples:design-catalog-m3-android")

include(":samples:android-alpha")

include(":samples:android-library")

include(":samples:android-screenshot-test")

include(":samples:android-daemon-bench")

// Fixture for the Android (Robolectric) serve-lane e2e: its merged manifest names an `Application`
// the render classpath doesn't carry. The lane lives in compose-preview-server; the fixture stays
// here because it samples this repository's render classpath.
include(":samples:android-live-lane")

include(":samples:sdk-matrix")

include(":samples:wear")

// Wear widget/tile preview fixture: `retargetWearPreviews = false` so device-less widget previews
// crop to their intrinsic bounds (at wear density) rather than the 227dp watch-face canvas.
include(":samples:wear-widget")

// Wear Compose Material 3 design catalog, exported as a sticker sheet (see
// `docs/design/DESIGN_CATALOGS.md`).
include(":samples:design-catalog-wear-m3")

include(":samples:xr-glimmer")

include(":samples:cmp")

include(":samples:cmp-shared")
// Previews declared once and rendered on BOTH lanes by the two samples above/below it, through
// the `composePreviewSource` configuration. Applies no preview plugin itself.
include(":samples:preview-source-shared")

// In-browser CMP tier: a `wasmJs` Compose app rendering the M3 catalog. No renderable `@Preview`.
include(":samples:cmp-wasm-catalog")

// Non-renderable KMP-Android library (no `jvm("desktop")` target): regression fixture that must
// coexist without breaking CLI discovery of the other samples. See its build.gradle.kts.
include(":samples:cmp-android-only")
include(":samples:cmp-android-robolectric")

include(":samples:desktop-daemon-bench")

include(":samples:remotecompose")

// The one `data/…` module still here: the shared-element transition model is consumed by the render
// matrix, not a daemon. docs/build-scripts/SETTINGS.md#flat-data-paths
include(":data-shared-element-core")

project(":data-shared-element-core").projectDir = file("data/shared-element/core")

// Standalone Kotlin Build Tools API parity/soak harness. Nothing in production depends on it; kept
// for its BTA parity, IC and classloader-leak soak tests (`./gradlew :daemon:bta-host:test`).
include(":daemon:bta-host")

// Fixture for `:daemon:bta-host`: the same source compiled by Gradle's `compileKotlin`, as the
// parity reference. Remove together with `:daemon:bta-host`.
include(":daemon:bta-host-fixture")

// Render-matrix axes and the contact-sheet stitcher, shared by the CLI's offline `render-matrix`
// command and the MCP server's `render_matrix` tool (as a published coordinate).
include(":render-matrix")

// The design-guidelines engine: a catalog's `ui-builder.guidelines.json` asked about rendered
// previews through OpenRouter, with an evidence loop and a render-hash cache. Driven by the CLI's
// `guidelines` command; consumed by the MCP server and VS Code extension as a published coordinate.
include(":design-guidelines")
project(":design-guidelines").projectDir = file("guidelines/engine")

// The render host, the bundle daemon and the git-backed preview history, with no web server.
// docs/build-scripts/SETTINGS.md#render-host
include(":render-host")

// The `compose-preview serve` cmp-jvm render worker: draws a captured `.rc` document to PNG or
// layered SVG through `rc-player-compose`. Staged into the CLI install as `lib-rcjvm/` and spawned
// by `:render-host`; not published.
include(":rc-render-jvm")

// Public render-session library. `:render-session-api` is the pure-interface surface every
// consumer (CLI, MCP server, third-party tooling) compiles against; `:render-session-subprocess`
// is the daemon-subprocess-backed implementation.
include(":render-session-api")

project(":render-session-api").projectDir = file("render-session/api")

include(":render-session-subprocess")

project(":render-session-subprocess").projectDir = file("render-session/subprocess")

// In-process Compose Desktop backend for the render-session library: hosts the daemon in the
// calling JVM via piped streams. Much faster startup at the cost of Skiko + Compose Desktop on the
// caller's classpath; otherwise use `:render-session-subprocess`.
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

// Local iteration against a compose-preview-daemon checkout:
// `-Pcomposeai.previewDaemonDir=../compose-preview-daemon` substitutes every coordinate resolved
// from the `composeai-preview-daemon` pin with the sibling checkout's project. The daemon hosts
// need an explicit mapping because their project names (`:daemon:core`) aren't their artifactIds;
// the flat modules are listed anyway so the set is stated.
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

include(":bom")

// Project paths applying `composeai.maven-publishing`, handed to `:bom` through a system property
// so its constraints are derived from the build (closure-free, for Isolated Projects). Matched with
// the closing quote so `composeai.maven-publishing-platform` doesn't pull `:bom` in to constrain
// itself. The root build script is skipped: it mentions the plugin id without applying it.
val publishedProjectPaths = buildList {
  fun visit(descriptor: org.gradle.api.initialization.ProjectDescriptor) {
    if (
      descriptor.buildFile.exists() &&
        descriptor.buildFile.readText().contains("composeai.maven-publishing\")")
    ) {
      add(descriptor.path)
    }
    descriptor.children.forEach(::visit)
  }
  rootProject.children.forEach(::visit)
}
System.setProperty("composeai.publishedProjectPaths", publishedProjectPaths.joinToString(","))

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
