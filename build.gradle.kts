plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  // ktfmt is no longer declared here: `ComposeAiBaseConventionsPlugin` (build-logic) applies and
  // configures it on every project from settings.gradle.kts via `gradle.lifecycle.beforeProject` —
  // the Isolated Projects-safe replacement for the old `allprojects {}` block. ktfmt + the Kotlin
  // Gradle plugin it links against ride on the build-logic classpath, so declaring the alias here
  // too would put a second ktfmt on a different classloader.
  // Loaded into the root scope so :renderer-android and :daemon:android (and
  // any future sibling) share the plugin's ClassLoader. Without this, each
  // sibling instantiates its own MavenCentralBuildService class and Gradle
  // refuses to share the build service across them — fails configuration
  // with "Cannot set the value of task ':daemon:android:dropMavenCentral
  // Deployment' property 'buildService'".
  alias(libs.plugins.maven.publish) apply false
}

// The root's aggregate and release tasks (ktfmt, the functional-test entrypoints,
// `printPublishTasks`) live in `root-tasks.gradle.kts`, not here. This file is a shared build input
// to `.github/scripts/maven-publish-plan.sh` -- the plugins above reach every module, so a change to
// it publishes all of them -- while those tasks decide which tasks run and build nothing. Keeping
// them apart means a change to the release wiring stops re-uploading every unchanged coordinate
// (v2.21.1 did, for #5527's fix to `printPublishTasks`).
apply(from = "root-tasks.gradle.kts")

// The vendored TypeScript Remote Compose player's browser bundle, staged to a stable path.
//
// The `rc-*` browser tests and the design-artifacts job drive this player by *file path*
// (`--player <bundle.js>`). That path used to be `third_party/remote-compose-player/dist/bundle.js`
// in this checkout; the players are published by yschimke/rc-players now, so the bundle arrives as
// a zip and is unpacked here instead. The staged location is the contract those callers use — see
// `.github/workflows/ci.yml` and `design-artifacts-reusable.yml`.
//
// Its own resolvable configuration, not a `dependencies {}` entry: this is a static asset, and it
// has no business on any compile or runtime classpath.
val vendoredRcPlayerJs =
  configurations.create("vendoredRcPlayerJs") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

dependencies {
  add("vendoredRcPlayerJs", platform(libs.rcplayers.bom))
  // The versionless catalog alias needs map notation for the classified zip. `map` keeps it lazy.
  add(
    "vendoredRcPlayerJs",
    libs.rcplayer.js.dist.map {
      mapOf("group" to it.module.group, "name" to it.module.name, "classifier" to "dist", "ext" to "zip")
    },
  )
}

// The CMP/Wasm player distribution, staged to a stable path — the same arrangement as the
// TypeScript bundle above, one layer up the stack.
//
// `:rc-player-wasm:wasmPlayerDist` used to produce this directory in-tree. The player is published
// by yschimke/rc-players now, so what is staged here is the *released* bundle: exactly the bytes
// the CLI's `rc-player-wasm/` sidecar ships. `design-artifacts-reusable.yml`'s CMP/Wasm comparison
// lane passes it to the export driver's `rc-compare --cmp-wasm`. (The driver's browser guards that
// used to read it here run in design-parity's CI now, against the same published bundle.)
val vendoredRcPlayerWasm =
  configurations.create("vendoredRcPlayerWasm") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

dependencies {
  add("vendoredRcPlayerWasm", platform(libs.rcplayers.bom))
  // The versionless catalog alias needs map notation for the classified zip. `map` keeps it lazy.
  add(
    "vendoredRcPlayerWasm",
    libs.rcplayer.wasm.dist.map {
      mapOf("group" to it.module.group, "name" to it.module.name, "classifier" to "dist", "ext" to "zip")
    },
  )
}

tasks.register<Sync>("stageVendoredRcPlayerWasm") {
  group = "build"
  description =
    "Unpacks the published CMP/Wasm player distribution to build/vendored-rc-player-wasm/, the " +
      "path the browser guards read through RC_CMP_WASM_DIST."
  from(provider { zipTree(vendoredRcPlayerWasm.singleFile) })
  into(layout.buildDirectory.dir("vendored-rc-player-wasm"))
}

tasks.register<Sync>("stageVendoredRcPlayerJs") {
  group = "build"
  description =
    "Unpacks the vendored TypeScript Remote Compose player bundle to " +
      "build/vendored-rc-player-js/, the path the browser tests and design-artifacts pass to " +
      "--player."
  from(provider { zipTree(vendoredRcPlayerJs.singleFile) })
  into(layout.buildDirectory.dir("vendored-rc-player-js"))
}
