// `compose-preview serve` — the separate build.
//
// Issue #3824 asks for the preview server to be prepared for extraction into its own repository
// without moving it yet, because the coupling gate is red (run
// `python3 scripts/measure-serve-coupling.py` for today's numbers). "Prepared" means the seam is
// real *before* the move: a separate Gradle build, not a subproject.
//
// The difference matters. A subproject of the outer build can take `project(":daemon:core")`
// dependencies, so its dependency floor is whatever the last PR happened to reach for, and the
// day of the split is the day everyone finds out what that was. A separate build cannot: Gradle
// does not offer the *root* build's projects for substitution into an included build, so
// everything here resolves the way it will resolve after the split — as a published artifact, by
// coordinate, from a repository.
//
// That is why this build is NOT included in the root `settings.gradle.kts`. Wiring it in with
// `includeBuild` would be the comfortable choice and would defeat the point: the contracts would
// resolve from the workspace and the missing ones would never be missed. It is built on its own:
//
//     scripts/check-preview-server-contracts.sh
//
// which publishes the contract modules to Maven Local under a fixed probe version and then builds
// this build against them. CI runs it on every PR (`preview-server-contracts` in ci.yml).
//
// What lives here today is the contract probe — the compile-time proof that the server's
// dependency floor is publishable and self-contained. The server's own source follows once the
// preparation items in #3824 land (bundle format extracted, `ServeCommand` thinned, the serve
// Playwright harness relocated); `docs/design/PREVIEW_SERVER_SPLIT.md` has the order.

pluginManagement {
  includeBuild("../build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // The contract exchange. Post-split this is Maven Central (plus a snapshot feed from `main`,
    // per #3824's "If we split anyway"); in-repo it is whatever
    // `scripts/check-preview-server-contracts.sh` last published. Group-filtered so a stale
    // third-party artifact in the developer's local repo can't shadow Central.
    mavenLocal { content { includeGroup("ee.schimke.composeai") } }
    google()
    mavenCentral()
  }

  // The version catalog is shared with the outer build by *file*, not by project wiring — one more
  // thing that becomes a copy on the day of the split, and stays honest until then.
  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "preview-server"

include(":contract-probe")
