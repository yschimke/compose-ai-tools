package ee.schimke.composeai.cli.serve

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sandbox preflight (issue #3016): the throwaway JVM that runs *inside* the configured jail and
 * reports what it can still reach. Everything here is about fail-closed reading of that report — a
 * jail that never launched, or one that printed nothing, must never read as "contained".
 */
class PlaygroundSandboxProbeTest {

  private val tempDir: File =
    java.nio.file.Files.createTempDirectory("playground-sandbox-probe-test").toFile()

  @AfterTest
  fun cleanUp() {
    tempDir.deleteRecursively()
  }

  @Test
  fun `a clean report is parsed and marked as having run`() {
    val launch =
      PlaygroundSandboxProbe.Launch(
        exitCode = 0,
        stdout =
          "Picked up JAVA_TOOL_OPTIONS: -Dfoo\n" +
            PlaygroundSandboxProbe.REPORT_PREFIX +
            """{"egressBlocked":true,"filesystemContained":true,"processIsolated":true,""" +
            """"workDirWritable":true,"detail":"probed from pid 7"}""",
        stderr = "",
      )

    val report = PlaygroundSandboxProbe.parse(launch)

    assertTrue(report.ran)
    assertEquals(emptyList(), report.failedChecks())
  }

  @Test
  fun `a missing tool reads as did-not-run, not as contained`() {
    val launch =
      PlaygroundSandboxProbe.Launch(
        exitCode = 127,
        stdout = "",
        stderr = "bwrap: command not found\n",
      )

    val report = PlaygroundSandboxProbe.parse(launch)

    assertFalse(report.ran)
    assertEquals("bwrap: command not found", report.detail)
    assertTrue("the probe JVM never reported" in report.failedChecks())
    // The individual properties stay false: nothing was measured, so nothing is proven.
    assertFalse(report.egressBlocked)
    assertFalse(report.filesystemContained)
  }

  @Test
  fun `garbled output is a refusal rather than a parse crash`() {
    val launch =
      PlaygroundSandboxProbe.Launch(
        exitCode = 0,
        stdout = PlaygroundSandboxProbe.REPORT_PREFIX + "{not json",
        stderr = "",
      )

    val report = PlaygroundSandboxProbe.parse(launch)

    assertFalse(report.ran)
    assertTrue("unreadable probe report" in report.detail, report.detail)
  }

  @Test
  fun `an inactive sandbox is never probed`() {
    var launched = false

    val report =
      PlaygroundSandboxProbe.run(
        sandbox = PlaygroundSandbox.NONE,
        javaHome = File(System.getProperty("java.home")),
        classpath = listOf("/cli.jar"),
        workRoot = tempDir,
      ) { _, _ ->
        launched = true
        PlaygroundSandboxProbe.Launch(0, "", "")
      }

    assertFalse(launched, "there is no jail to probe")
    assertFalse(report.ran)
  }

  @Test
  fun `the probe launches the jail argv, the real java binary, and the probe main`() {
    var argv: List<String> = emptyList()

    PlaygroundSandboxProbe.run(
      sandbox = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP),
      javaHome = File("/opt/jdk17"),
      classpath = listOf("/cli.jar", "/dep.jar"),
      workRoot = tempDir,
    ) { command, _ ->
      argv = command
      PlaygroundSandboxProbe.Launch(0, "", "")
    }

    assertEquals("bwrap", argv.first())
    assertTrue("/opt/jdk17/bin/java" in argv, argv.toString())
    assertTrue(PlaygroundSandboxProbe.PROBE_MAIN_CLASS in argv)
    // The canary the child must NOT be able to read lives outside every bound path.
    val canaryArg = argv.last { it.endsWith("canary.txt") }
    assertFalse(
      argv.windowed(3).any { it[0] == "--ro-bind-try" && it[1] == canaryArg },
      "the canary must not be bound into the jail",
    )
  }

  @Test
  fun `the in-jail probe measures the properties it claims to`() {
    val workDir = File(tempDir, "work").apply { mkdirs() }
    val canary = File(tempDir, "canary.txt").apply { writeText("secret") }

    // Unjailed, in this very JVM: the canary is readable and this process's pid is visible, so the
    // probe must report NOT contained — the same reading that keeps a broken jail out of --public.
    val unjailed =
      PlaygroundSandboxProbeMain.probe(
        workDir = workDir,
        canary = canary,
        hostPid = ProcessHandle.current().pid(),
      )

    assertTrue(unjailed.ran)
    assertTrue(unjailed.workDirWritable)
    assertFalse(unjailed.filesystemContained, "the canary is readable from an unjailed JVM")
    if (File("/proc/${ProcessHandle.current().pid()}").exists()) {
      assertFalse(unjailed.processIsolated, "this JVM can see its own pid in /proc")
    }

    // A canary that isn't there at all — what a real jail's child sees — reads as contained.
    val contained =
      PlaygroundSandboxProbeMain.probe(
        workDir = workDir,
        canary = File(tempDir, "absent-canary.txt"),
        hostPid = 999_999_999L,
      )
    assertTrue(contained.filesystemContained)
    assertTrue(contained.processIsolated)
  }

  /**
   * End-to-end: spawn the probe JVM behind a real `unshare(1)` jail and assert it comes back with
   * egress blocked. Skipped where unprivileged user namespaces are unavailable (some hardened
   * kernels, most macOS/Windows dev boxes) — the point is to exercise the real launch path wherever
   * the kernel allows it, not to make the suite kernel-dependent.
   */
  @Test
  fun `a real unshare jail reports egress blocked`() {
    if (!unshareAvailable()) return

    val report =
      PlaygroundSandboxProbe.run(
        sandbox = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.UNSHARE),
        javaHome = File(System.getProperty("java.home")),
        classpath =
          System.getProperty("java.class.path").split(File.pathSeparator).filter {
            it.isNotBlank()
          },
        workRoot = tempDir,
      )

    assertTrue(report.ran, "probe did not run: ${report.detail}")
    assertTrue(report.egressBlocked, "an empty network namespace must have no route out")
    assertTrue(report.workDirWritable)
    assertTrue(report.processIsolated, "a fresh pid namespace hides the serve host's pid")
    // …and `unshare` alone still leaves the host filesystem visible, which is exactly why it does
    // not pass the --public gate: PlaygroundPublicGate refuses on filesystemContained = false.
    assertFalse(report.filesystemContained)
  }

  /**
   * `bwrap` is the containment half of the `--public`-grade `strict` profile, so it must satisfy
   * **all four** probe checks — the whole of what the probe can measure. (Admission additionally
   * requires cgroup caps, which is why `strict` and not bare `bwrap` is what a public host runs;
   * that half is `systemd-run`'s and is not probe-measurable.) Skipped where bubblewrap isn't
   * installed or the kernel refuses its namespaces.
   */
  @Test
  fun `a real bwrap jail passes every check the probe can measure`() {
    if (!toolAvailable(listOf("bwrap", "--version"))) return

    val sandbox = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP)
    val report =
      PlaygroundSandboxProbe.run(
        sandbox = sandbox,
        javaHome = File(System.getProperty("java.home")),
        classpath =
          System.getProperty("java.class.path").split(File.pathSeparator).filter {
            it.isNotBlank()
          },
        workRoot = tempDir,
      )

    assertTrue(report.ran, "probe did not run: ${report.detail}")
    assertEquals(emptyList(), report.failedChecks(), report.summary())
    // The same report under `strict` — bwrap's containment plus systemd's cgroup caps — is what the
    // gate admits.
    assertTrue(
      PlaygroundPublicGate.decide(
        isPublic = true,
        // On containment alone — no GitHub auth in the picture.
        repoAccessGated = false,
        sandbox = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.STRICT),
        probe = report,
      ) is PlaygroundPublicGate.Decision.Allow
    )
  }

  private fun unshareAvailable(): Boolean =
    toolAvailable(listOf("unshare", "--user", "--map-root-user", "--net", "true"))

  /**
   * True when [argv] runs and exits 0 — the tool exists and the kernel permits what it asks for.
   */
  private fun toolAvailable(argv: List<String>): Boolean = runCatching {
    val process =
      ProcessBuilder(argv)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
    process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0
  }
    .getOrDefault(false)
}
