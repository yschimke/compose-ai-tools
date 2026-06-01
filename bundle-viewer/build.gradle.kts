plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Version derivation mirrors `:cli/build.gradle.kts` — CI sets `PLUGIN_VERSION` from the git
// tag (e.g. `0.10.15`), local builds compute the next-patch SNAPSHOT from
// `.release-please-manifest.json`. Keeping the schemes aligned means the viewer's release
// artefact ships at the same version as the CLI / plugin / MCP server, so `compose-preview
// $X` and `compose-preview-viewer $X` always refer to mutually compatible builds.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

base { archivesName.set("compose-preview-viewer") }

// The viewer is a Compose Desktop application, configured through Compose Multiplatform's own
// `compose.desktop.application` DSL rather than the JVM `application` plugin. Two portable
// artefacts
// come out of it (portable-bundles.md Tier 2.2):
//   - `packageUberJarForCurrentOS` — a single self-contained
//     `compose-preview-viewer-<os>-<arch>-<ver>.jar` (the current OS's Compose Desktop + Skiko
//     runtime flattened in), so anyone with a JDK 17+ can `java -jar … foo.png` with nothing
//     unpacked. Bundles Skiko's native libs and merges Compose's service files correctly.
//   - `packageDistributionForCurrentOS` → a native installer (`.deb`/`.rpm` on Linux, `.dmg` on
//     macOS, `.msi` on Windows) built by `jpackage`, which embeds a JDK runtime image — so a
//     non-Java colleague installs and launches the viewer with nothing else on their machine.
// Both stay Isolated-Projects-clean — unlike the GradleUp Shadow plugin, whose optional-property
// lookup walks to the parent project and trips this repo's `isolated-projects=true` +
// `configuration-cache.problems=fail` gate.
//
// We drop the JVM `application` plugin entirely (its slim `distZip`/`distTar` is superseded by the
// drag-around uber jar, and keeping both registers two colliding `run` tasks).
compose.desktop {
  application {
    mainClass = "ee.schimke.composeai.viewer.MainKt"
    // Compose Multiplatform Desktop's Skiko loader uses `System.load` for native libs. JDK 24+
    // would otherwise print a 4-line warning on every launch; pre-declaring native access for the
    // unnamed module silences it (parity with the old applicationDefaultJvmArgs).
    jvmArgs += "--enable-native-access=ALL-UNNAMED"
    nativeDistributions {
      // Per-OS native installers. `TargetFormat.Deb` (Linux), `Dmg` (macOS), `Msi` (Windows) —
      // jpackage only builds the format(s) native to the runner's OS, so the release matrix runs
      // `packageDistributionForCurrentOS` on one runner per OS. Rpm is omitted: the Linux release
      // runner is Ubuntu (ships `dpkg-deb`, not `rpmbuild`), and `.deb` plus the universal uber jar
      // already cover Linux recipients.
      targetFormats(
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
      )
      packageName = "compose-preview-viewer"
      // jpackage rejects a `-SNAPSHOT`/non-numeric package version, so feed it the numeric core of
      // the project version. The full version (incl. any `-SNAPSHOT`) still names the uber jar;
      // this
      // only affects the installer's internal package metadata.
      val numericVersion = project.version.toString().substringBefore("-")
      packageVersion = numericVersion
      description = "Compose Preview Viewer — opens a packed preview bundle and renders it live."
      vendor = "compose-ai-tools"
      macOS {
        // macOS's CFBundleShortVersionString requires MAJOR >= 1; `.deb`/`.msi` accept a 0 major,
        // but the DMG build hard-fails on it. While the repo is pre-1.0 (`0.x.y`), coerce the
        // leading-zero major to 1 for the DMG's internal version only — the release asset filename
        // still carries the real `0.x.y`. Versions already at major >= 1 pass through unchanged.
        packageVersion = numericVersion.replaceFirst(Regex("^0\\."), "1.")
      }
    }
  }
}

dependencies {
  // Full Compose Desktop runtime — the viewer composes the bundle's `@Preview` composable LIVE
  // inside its own Window, so every Compose API the bundle's classes resolve against has to be
  // on the parent classloader. Bundle's classes load via a child URLClassLoader (see
  // `BundleLoader.kt`); parent-loader Compose wins on every shared symbol.
  implementation(compose.desktop.currentOs)
  implementation(compose.runtime)
  implementation(compose.ui)
  implementation(compose.foundation)
  implementation(compose.material3)
  // `androidx.compose.ui.tooling.preview.Preview` lives here. Bundles compiled against the
  // standard Compose `@Preview` annotation only resolve when this artifact is on the classpath.
  implementation(compose.components.uiToolingPreview)

  implementation(libs.kotlinx.serialization.json)

  // Ktor client (OkHttp engine) for opening a bundle from a URL. Explicit okhttp dep pins the
  // engine to OkHttp 5.x over ktor-client-okhttp's transitive 4.12.0.
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.okhttp)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
