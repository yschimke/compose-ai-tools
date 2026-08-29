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

// The version the contracts that MOVED OUT resolve at. They are published from
// yschimke/compose-preview-contracts, so this build cannot publish them under `contractVersion`
// the way it does the rest — they come from Maven Central at whatever
// `composeai-contracts` names in the root build's gradle/libs.versions.toml.
// `scripts/check-preview-server-contracts.sh` reads that file and passes it through, so the pin
// has one home rather than two that can disagree.
val externalContractsVersion: String =
  (findProperty("composeai.externalContractsVersion") as String?)
    ?: error(
      "composeai.externalContractsVersion is required. Run this build through " +
        "scripts/check-preview-server-contracts.sh, which reads the pin from " +
        "gradle/libs.versions.toml — building `-p preview-server` directly cannot see it."
    )

allprojects {
  group = "ee.schimke.composeai.previewserver"
  extra["contractVersion"] = contractVersion
  extra["externalContractsVersion"] = externalContractsVersion
}
