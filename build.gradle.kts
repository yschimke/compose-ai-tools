plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  // ktfmt is applied by `ComposeAiBaseConventionsPlugin` (build-logic); declaring it here too would
  // load a second ktfmt on a different classloader.
  // Loaded into the root scope so :renderer-android and :daemon:android share the plugin's
  // ClassLoader; otherwise Gradle refuses to share its MavenCentralBuildService between them.
  alias(libs.plugins.maven.publish) apply false
}

// The root's aggregate and release tasks live in `root-tasks.gradle.kts`. This file is a shared
// build input to `.github/scripts/maven-publish-plan.sh` (a change here republishes every module),
// so release-wiring changes stay out of it.
apply(from = "root-tasks.gradle.kts")

// The TypeScript Remote Compose player's browser bundle (published by yschimke/rc-players),
// unpacked to a stable path. Callers (`rc-*` browser tests, the design-artifacts job) pass it by
// path — see `.github/workflows/ci.yml` and `design-artifacts-reusable.yml`. Its own resolvable
// configuration, since it's a static asset that belongs on no classpath.
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

// The released CMP/Wasm player distribution (the bytes the CLI's `rc-player-wasm/` sidecar ships),
// staged like the TypeScript bundle above; `design-artifacts-reusable.yml` passes it to
// `rc-compare --cmp-wasm`.
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
