package ee.schimke.composeai.cli

import ee.schimke.composeai.mcp.DaemonClientFactory
import ee.schimke.composeai.mcp.DaemonLaunchDescriptor
import ee.schimke.composeai.mcp.RegisteredProject
import ee.schimke.composeai.mcp.SubprocessDaemonClientFactory
import ee.schimke.composeai.mcp.WorkspaceId
import java.io.File

/**
 * Opt-in liveness probe for the per-module preview daemon. The main `doctor` command runs this when
 * the user passes `--daemon`: it parses the on-disk launch descriptor, forks the daemon JVM,
 * completes the `initialize` round-trip, then asks it to shut down.
 *
 * The probe is gated behind a flag because spawning a real daemon is expensive — cold-start is
 * ~600ms on Compose Desktop and 3-10s on the Android (Robolectric) backend, multiplied by the
 * number of modules that apply the plugin. Plain `doctor` stays cheap; agents and humans who want
 * an end-to-end "the daemon actually works" check ask for it explicitly.
 *
 * Implemented at the `DaemonClientFactory` seam rather than going through `DaemonSupervisor` so
 * tests can substitute an in-memory factory without spinning up a subprocess.
 */
internal sealed interface DaemonSmokeOutcome {
  /** Descriptor at `build/compose-previews/daemon-launch.json` was missing. */
  data class DescriptorMissing(val expectedPath: File) : DaemonSmokeOutcome

  /** Descriptor was present but JSON-malformed or missing required fields. */
  data class DescriptorUnreadable(val descriptorPath: File, val reason: String) : DaemonSmokeOutcome

  /** Descriptor parsed fine but its `enabled` flag is `false`. */
  data class DescriptorDisabled(val descriptorPath: File) : DaemonSmokeOutcome

  /** Daemon JVM never reached `initialize` (spawn or wire failure). */
  data class SpawnFailed(val reason: String) : DaemonSmokeOutcome

  /** Daemon reached `initialize` but the round-trip failed (timeout, error response). */
  data class InitializeFailed(val elapsedMs: Long, val reason: String) : DaemonSmokeOutcome

  /**
   * Daemon initialised cleanly. [elapsedMs] is the wall-clock for the initialize round-trip alone
   * (excludes JVM fork overhead); [daemonVersion] / [pid] / [protocolVersion] are echoed from the
   * `InitializeResult` and shown in the doctor detail line.
   */
  data class Ok(
    val elapsedMs: Long,
    val daemonVersion: String,
    val pid: Long,
    val protocolVersion: Int,
  ) : DaemonSmokeOutcome
}

/**
 * Path to the daemon launch descriptor for [modulePath] under [projectDir]. Mirrors
 * `DescriptorProvider.readingFromDisk()`'s convention: `:foo:bar` → `<projectDir>/foo/bar/...`.
 * Module paths are gradle-style, with or without the leading colon.
 */
internal fun daemonDescriptorFile(projectDir: File, modulePath: String): File {
  val trimmed = modulePath.trimStart(':')
  val moduleDir =
    if (trimmed.isEmpty()) projectDir
    else File(projectDir, trimmed.replace(':', File.separatorChar))
  return File(moduleDir, "build/compose-previews/daemon-launch.json")
}

/**
 * Run the smoke test for one module. Returns a [DaemonSmokeOutcome] describing the result.
 *
 * [factory] defaults to the production subprocess factory; tests inject an in-memory factory.
 * [workspaceName] is the project's root name (used for the synthesised [WorkspaceId] passed to the
 * factory — production daemons don't care, but the [RegisteredProject] type wants something).
 */
internal fun runDaemonSmokeTest(
  projectDir: File,
  modulePath: String,
  workspaceName: String = projectDir.name.ifBlank { "workspace" },
  factory: DaemonClientFactory = SubprocessDaemonClientFactory(),
): DaemonSmokeOutcome {
  val descriptorFile = daemonDescriptorFile(projectDir, modulePath)
  if (!descriptorFile.isFile) return DaemonSmokeOutcome.DescriptorMissing(descriptorFile)

  val descriptor =
    try {
      DaemonLaunchDescriptor.parse(descriptorFile.readText())
    } catch (e: Exception) {
      return DaemonSmokeOutcome.DescriptorUnreadable(
        descriptorPath = descriptorFile,
        reason = e.message ?: e.javaClass.simpleName,
      )
    }

  if (!descriptor.enabled) return DaemonSmokeOutcome.DescriptorDisabled(descriptorFile)

  val canonicalRoot = runCatching { projectDir.canonicalFile }.getOrDefault(projectDir.absoluteFile)
  val project =
    RegisteredProject(
      workspaceId = WorkspaceId.derive(workspaceName, canonicalRoot),
      rootProjectName = workspaceName,
      path = canonicalRoot,
      knownModules = mutableListOf(),
    )

  val spawn =
    try {
      factory.spawn(project, descriptor)
    } catch (e: Exception) {
      return DaemonSmokeOutcome.SpawnFailed(reason = e.message ?: e.javaClass.simpleName)
    }

  val client = spawn.client(onNotification = { _, _ -> }, onClose = {})
  val startNs = System.nanoTime()
  return try {
    val result =
      client.initialize(
        workspaceRoot = project.path.absolutePath,
        moduleId = descriptor.modulePath,
        moduleProjectDir = descriptor.workingDirectory,
      )
    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
    DaemonSmokeOutcome.Ok(
      elapsedMs = elapsedMs,
      daemonVersion = result.daemonVersion,
      pid = result.pid,
      protocolVersion = result.protocolVersion,
    )
  } catch (e: Exception) {
    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
    DaemonSmokeOutcome.InitializeFailed(
      elapsedMs = elapsedMs,
      reason = e.message ?: e.javaClass.simpleName,
    )
  } finally {
    runCatching { spawn.shutdown() }
  }
}

/**
 * Pure mapping from a smoke-test outcome to a [DoctorCheck]. Lifted out of [DoctorCommand] so the
 * mapping can be unit-tested without spawning a daemon.
 */
internal fun interpretDaemonSmoke(modulePath: String, outcome: DaemonSmokeOutcome): DoctorCheck {
  val id = "project.${modulePath.trimStart(':').ifEmpty { "root" }}.daemon-smoke"
  val installRemediation =
    DoctorRemediation(
      summary = "Bootstrap the daemon descriptor first.",
      commands = listOf("compose-preview mcp install"),
    )
  return when (outcome) {
    is DaemonSmokeOutcome.DescriptorMissing ->
      DoctorCheck(
        id = id,
        category = "project",
        status = "error",
        message = "$modulePath — daemon descriptor missing",
        detail = "expected at ${outcome.expectedPath}",
        remediation = installRemediation,
      )
    is DaemonSmokeOutcome.DescriptorUnreadable ->
      DoctorCheck(
        id = id,
        category = "project",
        status = "error",
        message = "$modulePath — daemon descriptor unreadable",
        detail = "${outcome.descriptorPath}: ${outcome.reason}",
        remediation = installRemediation,
      )
    is DaemonSmokeOutcome.DescriptorDisabled ->
      DoctorCheck(
        id = id,
        category = "project",
        status = "error",
        message = "$modulePath — daemon descriptor has enabled=false",
        detail = "${outcome.descriptorPath} reports enabled=false; `mcp install` flips it",
        remediation = installRemediation,
      )
    is DaemonSmokeOutcome.SpawnFailed ->
      DoctorCheck(
        id = id,
        category = "project",
        status = "error",
        message = "$modulePath — daemon JVM failed to spawn",
        detail = outcome.reason,
        remediation =
          DoctorRemediation(
            summary =
              "Check that the descriptor's javaLauncher is reachable and the project builds.",
            commands = listOf("./gradlew :$modulePath:composePreviewDaemonStart"),
          ),
      )
    is DaemonSmokeOutcome.InitializeFailed ->
      DoctorCheck(
        id = id,
        category = "project",
        status = "error",
        message = "$modulePath — daemon initialize failed after ${outcome.elapsedMs}ms",
        detail = outcome.reason,
        remediation =
          DoctorRemediation(
            summary =
              "Re-run discovery and the daemon bootstrap so the descriptor and classpath agree.",
            commands =
              listOf(
                "./gradlew :$modulePath:discoverPreviews :$modulePath:composePreviewDaemonStart",
                "compose-preview mcp install",
              ),
          ),
      )
    is DaemonSmokeOutcome.Ok ->
      DoctorCheck(
        id = id,
        category = "project",
        status = "ok",
        message =
          "$modulePath — daemon initialise OK in ${outcome.elapsedMs}ms" +
            " (v${outcome.daemonVersion}, pid ${outcome.pid})",
        detail = "protocol v${outcome.protocolVersion}",
      )
  }
}
