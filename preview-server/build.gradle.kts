// Root of the `preview-server` build. Deliberately thin: this build owns no conventions of its
// own, because everything it does must keep working when it is lifted into its own repository.

// Declared (not applied) so both plugins land on this build's shared classloader in a consistent
// order — ktfmt's Gradle plugin reaches for the Kotlin plugin's `KotlinSourceSet` when it is
// applied, and fails to decorate if it isn't there.
plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.ktfmt) apply false
}

// The version of `ee.schimke.composeai:*` the contracts resolve at. Set by
// `scripts/check-preview-server-contracts.sh` to the same fixed value it publishes under, so the
// exchange never depends on where release-please happens to have left the repo version. Post-split
// this becomes a real released version (and, per #3824, a snapshot feed from `main` so a deep-tier
// change is a same-day two-PR flow rather than a wait for a release).
val contractVersion: String =
  (findProperty("composeai.contractVersion") as String?) ?: "0.0.0-contract-probe-SNAPSHOT"

allprojects {
  group = "ee.schimke.composeai.previewserver"
  extra["contractVersion"] = contractVersion
}
