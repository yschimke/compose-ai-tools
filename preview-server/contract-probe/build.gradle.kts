// The extracted preview server's dependency floor, compiled against published artifacts.
//
// `compose-preview serve` is a protocol client: it talks to a daemon over the versioned JSON-RPC
// protocol and reads the payload schemas the daemon returns. Issue #3824 measured that and listed
// the modules it should be allowed to see. This module turns that list from an assertion in an
// issue body into something the build can fail on:
//
//   * every dependency below is a `ee.schimke.composeai:*` COORDINATE, not a `project(...)` —
//     because this is a separate build, it could not be a project dependency even if someone
//     wanted it to be, which is the whole reason the build is separate (see `settings.gradle.kts`);
//   * `ContractSurface.kt` names one type per contract, so a module that stops being published, or
//     stops carrying a type serve needs, breaks here rather than on the day of the split;
//   * `checkContractSurface` walks the RESOLVED runtime classpath — transitives included — and
//     fails on any `ee.schimke.composeai` artifact that is not an allowed contract or a recorded,
//     shrinking leak.
//
// What this module is NOT: the server. Nothing here renders, serves, or knows about HTTP. It is a
// probe, and it stays a probe until the preparation items in
// `docs/design/PREVIEW_SERVER_SPLIT.md` make it possible for the server's own source to compile in
// this build.

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
}

ktfmt { googleStyle() }

kotlin { jvmToolchain(21) }

val probeVersion: String = rootProject.extra["contractVersion"] as String

/** This repo's own publishing group. Anything else on the graph is a third-party dependency. */
val INTERNAL_GROUP = "ee.schimke.composeai"

/**
 * The contracts an extracted preview server is allowed to depend on.
 *
 * `#3824` listed six. Compiling the server's real import set against them turned up four more —
 * `data-theme-core`, `data-render-core` and `data-remotecompose-core` (payload schemas the viewer
 * renders) and `daemon-core`'s `devices` package (device frames). They are payload schemas of the
 * same kind as the six, so they are added here rather than treated as leaks; the list is the
 * measured floor, not the guessed one.
 */
val contracts =
  listOf(
    "daemon-core",
    "daemon-protocol",
    "daemon-client",
    "preview-data-api",
    "render-session-api",
    "render-session-subprocess",
    "common-io",
    // Content-crop geometry, extracted from `ServeThumbCrop.kt` during the ServeCommand split.
    // Both halves need the same arithmetic — the server crops catalog thumbnails, `bundle split`
    // crops the same renders — so it is a contract rather than a thing to keep two copies of.
    "common-image-crop",
    // The agent-grant vocabulary. Both ends of `--agent-grants` speak it — the server mints, the
    // CLI's `auth` asks — so it is a contract rather than a thing for the client to reach into the
    // server for.
    "agent-grant-protocol",
    "data-layoutinspector-core",
    "data-preview-overrides-core",
    "data-remotecompose-core",
    "data-theme-core",
    "data-render-core",
    // The renderer's locale-direction rule. `serve` resolves a published capture gutter's
    // leading/trailing edges onto left/right exactly as the render that produced the pixels did,
    // and a second copy of that language table in the server would be a thing to drift.
    "data-pseudolocale-core",
    // The `.previewbundle` format (#3824 preparation item 5). `serve` reads bundles — the
    // manifest, the signatures, the classpath entries, the baked previews — and until this module
    // existed it did that by reaching into `:cli`. It was the one entry in the dependency-floor
    // table below that was not a module at all; naming it here is what stops it being a blocker.
    "bundle-format",
    // Turning those coordinates back into local jars. `serve` resolves a bundle's classpath before
    // it can hand the daemon a `-cp`, and while this lived in `:cli` that was the last thing
    // making an extracted server depend on the CLI. Preparation item 7.
    "bundle-coordinates",
  )

dependencies {
  contracts.forEach { implementation("ee.schimke.composeai:$it:$probeVersion") }
  implementation(libs.okio)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

/**
 * Artifacts that reach the classpath through a contract but do not belong to an extracted preview
 * server. Each is a preparation item in `docs/design/PREVIEW_SERVER_SPLIT.md`, and each must
 * eventually leave this list — the check fails when a recorded leak is GONE as well as when a new
 * one appears, so the list cannot rot into a permanent exemption.
 *
 * **Empty, as of #3824 preparation item 3.** The last entry was `mcp`, reached because
 * `:render-session-subprocess` built its transport on `:mcp`'s `DaemonClient` /
 * `SubprocessDaemonClientFactory` — so consuming the render-session library dragged an MCP server
 * onto the classpath. Those types now live in `:daemon-client`, which is a contract rather than a
 * leak: the server legitimately needs a daemon client, it just must not need an MCP server to get
 * one.
 *
 * Keep it empty. An addition here is a regression, not a TODO — the extracted server's dependency
 * floor is now exactly [contracts], and anything else on the graph should fail the build until it
 * is either promoted to a contract with a reason or removed.
 */
val contractLeaks = emptyMap<String, String>()

abstract class CheckContractSurface : DefaultTask() {
  /** Artifact names (no group, no version) the server is allowed to resolve. */
  @get:Input abstract val allowed: SetProperty<String>

  /** Artifact name -> why it is still here. Must shrink; see `contractLeaks` above. */
  @get:Input abstract val leaks: MapProperty<String, String>

  /** The group whose artifacts are this repo's own — everything else is third-party. */
  @get:Input abstract val internalGroup: Property<String>

  /** The version the contracts were published under, which allowed modules must resolve at. */
  @get:Input abstract val expectedVersion: Property<String>

  /**
   * The resolved runtime classpath as `name:version` pairs, transitives included.
   *
   * Identities, not file names. The first version of this task recognised an internal artifact by
   * its file name ending in `-<probeVersion>.jar`, which quietly assumed every internal artifact on
   * the graph resolves at the probe version. A contract POM that pulled an `ee.schimke.composeai`
   * module at some *other* version — a released coordinate, say — would fail that suffix test, drop
   * out of `seen`, and the dependency-floor check would stay green while the extracted server had
   * gained exactly the kind of artifact this task exists to catch. Group and module come off the
   * resolution result now, so the version cannot decide whether something is seen.
   *
   * The version is kept, not projected away. A contract POM can pin a sibling contract to a
   * *released* version above the probe version, and Gradle's conflict resolution will happily
   * select it — at which point the probe compiles against stale released bytecode while reporting
   * that it verified this PR's. Name-only matching could not see that, so the check now asserts
   * allowed modules resolve at [expectedVersion] while still reporting unexpected modules at any
   * version.
   *
   * Resolved into a plain `Set<String>` at configuration time so the task holds no Gradle model
   * objects and stays configuration-cache-safe.
   */
  @get:Input abstract val resolvedModules: SetProperty<String>

  @TaskAction
  fun check() {
    val resolved =
      resolvedModules.get().associate { it.substringBefore(":") to it.substringAfter(":") }
    val seen = resolved.keys
    val allowedNames = allowed.get()
    val recordedLeaks = leaks.get()
    val unexpected = (seen - allowedNames - recordedLeaks.keys).sorted()
    val healed = (recordedLeaks.keys - seen).sorted()
    val wrongVersion =
      (seen intersect allowedNames).filter { resolved[it] != expectedVersion.get() }.sorted()

    val problems = buildList {
      if (unexpected.isNotEmpty()) {
        add(
          buildString {
            appendLine(
              "The preview server's contract classpath carries ${unexpected.size} " +
                "ee.schimke.composeai artifact(s) that are neither a contract nor a recorded leak:"
            )
            unexpected.forEach { appendLine("    $it") }
            appendLine()
            append(
              "Either it is a contract the extracted server legitimately needs — add it to " +
                "`contracts` in preview-server/contract-probe/build.gradle.kts and say why in the " +
                "PR — or a contract module grew a dependency it should not have. Post-split, every " +
                "name on that list is a repository the server would have to depend on."
            )
          }
        )
      }
      if (wrongVersion.isNotEmpty()) {
        add(
          buildString {
            appendLine(
              "${wrongVersion.size} contract(s) did not resolve at the probe version " +
                "${expectedVersion.get()}, so the probe is not checking what this build published:"
            )
            wrongVersion.forEach { appendLine("    $it -> ${resolved[it]}") }
            appendLine()
            append(
              "A contract POM pinning a sibling to a released version lets conflict resolution " +
                "pick the release over the freshly published artifact, which would hide a removal " +
                "or an incompatible change in the contract this PR actually touches."
            )
          }
        )
      }
      healed.forEach { name ->
        add(
          "`$name` is recorded as a known leak but no longer resolves. That is the fix landing: " +
            "delete it from `contractLeaks` in preview-server/contract-probe/build.gradle.kts and " +
            "tick the matching preparation item in docs/design/PREVIEW_SERVER_SPLIT.md."
        )
      }
      if (seen.isEmpty()) {
        add(
          "No ${internalGroup.get()} artifacts resolved at all. Run this build through " +
            "scripts/check-preview-server-contracts.sh, which publishes them first."
        )
      }
    }

    if (problems.isNotEmpty()) {
      throw GradleException(
        problems.joinToString("\n\n", prefix = "checkContractSurface failed.\n\n")
      )
    }

    logger.lifecycle(
      "checkContractSurface: ${allowedNames.size} contracts resolved, " +
        "${recordedLeaks.size} known leak(s) still to remove — " +
        "see docs/design/PREVIEW_SERVER_SPLIT.md"
    )
  }
}

tasks.register<CheckContractSurface>("checkContractSurface") {
  description =
    "Fails if the extracted preview server's dependency floor grows, or a recorded leak rots."
  group = "verification"
  allowed.set(contracts)
  leaks.set(contractLeaks)
  internalGroup.set(INTERNAL_GROUP)
  expectedVersion.set(probeVersion)
  resolvedModules.set(
    configurations.named("runtimeClasspath").map { configuration ->
      configuration.incoming.resolutionResult.allComponents
        .mapNotNull { it.moduleVersion }
        .filter { it.group == INTERNAL_GROUP }
        .map { "${it.name}:${it.version}" }
        .toSet()
    }
  )
}

tasks.named("check") { dependsOn("checkContractSurface") }
