// A standalone build, deliberately not included by the root `settings.gradle.kts`: it verifies the
// host-natives binaries `host-natives/build.sh` produced, and runs only in `host-natives.yml`.
rootProject.name = "host-natives-verify"

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
  // Robolectric's native runtime is what the binaries run against, so verify at the simulator's
  // pin.
  versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
