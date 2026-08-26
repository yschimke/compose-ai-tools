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
    "preview-data-api",
    "render-session-api",
    "render-session-subprocess",
    "common-io",
    "data-layoutinspector-core",
    "data-preview-overrides-core",
    "data-remotecompose-core",
    "data-theme-core",
    "data-render-core",
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
 */
val contractLeaks =
  mapOf(
    "renderer-xr-client" to
      "`:daemon:core` api-exposes the XR render-server client because `JsonRpcServer`'s " +
        "constructor takes an `XrRenderServerFactory?`. #3824 preparation item 4: invert it " +
        "into a port the daemon injects, so the protocol contract stops shipping a renderer " +
        "client to every consumer.",
    "mcp" to
      "`:render-session-subprocess` implements its transport on `:mcp`'s `DaemonClient` / " +
        "`SubprocessDaemonClientFactory`, so consuming the render-session library drags an MCP " +
        "server onto the classpath. #3824 preparation item 3: lift the daemon client out of " +
        "`:mcp` into its own published module. (`DaemonLaunchDescriptor`, the one MCP type " +
        "`serve` imported directly, has already moved to `:daemon:core`.)",
  )

abstract class CheckContractSurface : DefaultTask() {
  /** Artifact names (no group, no version) the server is allowed to resolve. */
  @get:Input abstract val allowed: SetProperty<String>

  /** Artifact name -> why it is still here. Must shrink; see `contractLeaks` above. */
  @get:Input abstract val leaks: MapProperty<String, String>

  /** The probe version the contracts were published under — how they are told apart on disk. */
  @get:Input abstract val contractVersion: Property<String>

  /**
   * The resolved runtime classpath, transitives included. Files rather than a resolution result so
   * the task stays configuration-cache-safe: a `ee.schimke.composeai` artifact is exactly one
   * published at [contractVersion], and a Maven artifact's file name is `<name>-<version>.jar`.
   *
   * `NAME_ONLY`, deliberately not `@Classpath`. Classpath normalization hashes jar *contents* and
   * ignores file names — and the file name is precisely what this task reads. Under `@Classpath` a
   * contract renamed (or a leak appearing under a new coordinate at identical content) would
   * normalize to an unchanged input and the check would report UP-TO-DATE without ever looking.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val classpath: ConfigurableFileCollection

  @TaskAction
  fun check() {
    val suffix = "-${contractVersion.get()}.jar"
    val seen =
      classpath.files
        .map { it.name }
        .filter { it.endsWith(suffix) }
        .map { it.removeSuffix(suffix) }
        .toSet()

    val allowedNames = allowed.get()
    val recordedLeaks = leaks.get()
    val unexpected = (seen - allowedNames - recordedLeaks.keys).sorted()
    val healed = (recordedLeaks.keys - seen).sorted()

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
      healed.forEach { name ->
        add(
          "`$name` is recorded as a known leak but no longer resolves. That is the fix landing: " +
            "delete it from `contractLeaks` in preview-server/contract-probe/build.gradle.kts and " +
            "tick the matching preparation item in docs/design/PREVIEW_SERVER_SPLIT.md."
        )
      }
      if (seen.isEmpty()) {
        add(
          "No contract artifacts resolved at all. Run this build through " +
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
  contractVersion.set(probeVersion)
  classpath.from(configurations.named("runtimeClasspath"))
}

tasks.named("check") { dependsOn("checkContractSurface") }
