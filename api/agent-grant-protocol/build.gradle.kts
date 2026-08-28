// The vocabulary a `serve` box and an agent-access client must agree on.
//
// `--agent-grants` is a two-party protocol: the CLI's `auth` command asks for a grant and polls for
// it, the preview server mints and revokes. Both ends therefore need the same scope and capability
// names, the same duration grammar, and the same token fingerprint — and while those lived in
// `:cli:serve`, the client half was reaching into the server to parse its own `--ttl`.
//
// Deliberately small. It carries what the two ends must share and nothing about how either behaves:
// no store, no routes, no HTTP. `ServeAgentGrantStore` — minting, expiry, persistence, rate limits
// —
// stays in the server, which is where the policy belongs.
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "agent-grant-protocol",
    displayName = "Compose Preview — Agent Grant Protocol",
    description =
      "Scopes, capabilities, duration grammar and token fingerprinting shared by the " +
        "compose-preview server that mints agent access grants and the client that asks for them.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // `explicitApi()` — this is a published contract both halves of the #3824 split compile against
  // across a repo boundary, so an implicitly-public declaration is an API decision nobody made.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
