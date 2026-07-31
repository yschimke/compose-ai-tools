package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The playground's per-session sandbox policy (PLAYGROUND.md §6, issue #3016): the argv each
 * profile jails a snippet JVM behind, the JVM-level caps that apply regardless of profile, and the
 * knob validation that turns a typo into a startup failure rather than a silently unconfined
 * playground.
 */
class PlaygroundSandboxTest {

  private val paths =
    PlaygroundSandbox.Paths(
      workDir = File("/tmp/pg/snippet-1"),
      readOnly = listOf(File("/cache/m3.jar"), File("/tmp/pg/snippet-1/classes")),
      javaHome = File("/opt/jdk17"),
    )

  @Test
  fun `none is inert`() {
    val sandbox = PlaygroundSandbox.NONE
    assertFalse(sandbox.isActive)
    assertEquals(emptyList(), sandbox.command(paths))
    assertEquals(emptyList(), sandbox.jvmArgs(paths.workDir))
  }

  @Test
  fun `bwrap unshares the network, clears the env, and leaves exactly one writable path`() {
    val argv = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP).command(paths)

    assertEquals("bwrap", argv.first())
    assertTrue("--unshare-net" in argv, "egress must be unshared: $argv")
    assertTrue("--die-with-parent" in argv)
    // The serve JVM's env carries operator secrets (--admin-token); a snippet must not read them.
    assertTrue("--clearenv" in argv)
    // The JDK and every classpath entry are read-only…
    assertTrue(argv.windowed(3).any { it == listOf("--ro-bind-try", "/opt/jdk17", "/opt/jdk17") })
    assertTrue(
      argv.windowed(3).any { it == listOf("--ro-bind-try", "/cache/m3.jar", "/cache/m3.jar") }
    )
    // …and the work dir is the ONLY --bind (read-write) in the whole argv.
    val writableBinds = argv.windowed(3).filter { it[0] == "--bind" }
    assertEquals(
      listOf(listOf("--bind", "/tmp/pg/snippet-1", "/tmp/pg/snippet-1")),
      writableBinds,
      "exactly one writable path, the session work dir",
    )
    assertEquals("--", argv.last(), "the jail argv must terminate before the JVM command")
  }

  @Test
  fun `unshare blocks egress and pids without needing bubblewrap`() {
    val argv = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.UNSHARE).command(paths)

    assertEquals("unshare", argv.first())
    assertTrue("--net" in argv)
    assertTrue("--pid" in argv)
    assertTrue("--kill-child" in argv, "the jailed JVM must die with the serve host")
    // It sees the host filesystem, which is exactly why it does not claim containment — and so
    // cannot pass the --public gate on its declared properties alone.
    assertFalse(PlaygroundSandbox.Profile.UNSHARE.declaresFilesystemContained)
  }

  @Test
  fun `systemd carries the cgroup caps and its own runtime deadline`() {
    val argv =
      PlaygroundSandbox(
          profile = PlaygroundSandbox.Profile.SYSTEMD,
          memoryMb = 2048,
          cpus = 1.5,
          pids = 64,
          ttlSeconds = 300,
        )
        .command(paths)

    assertTrue("MemoryMax=2048M" in argv)
    assertTrue("CPUQuota=150%" in argv)
    assertTrue("TasksMax=64" in argv)
    assertTrue("RuntimeMaxSec=300" in argv)
    assertTrue("PrivateNetwork=yes" in argv)
  }

  @Test
  fun `strict is systemd caps wrapping the bwrap jail`() {
    val argv = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.STRICT).command(paths)

    assertEquals("systemd-run", argv.first())
    assertTrue(argv.indexOf("systemd-run") < argv.indexOf("bwrap"))
    assertTrue("--unshare-net" in argv)
  }

  @Test
  fun `jvm caps bound heap and cpu on every active profile`() {
    val sandbox =
      PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP, memoryMb = 2048, cpus = 1.2)

    val jvmArgs = sandbox.jvmArgs(paths.workDir)

    // Three quarters of the budget: the rest is metaspace / code cache / Skiko native, which a
    // cgroup counts too — a heap sized at the full budget gets OOM-killed before -Xmx ever bites.
    assertEquals(1536, sandbox.heapMb())
    assertTrue("-Xmx1536m" in jvmArgs)
    assertTrue("-XX:ActiveProcessorCount=2" in jvmArgs)
    assertTrue("-XX:+ExitOnOutOfMemoryError" in jvmArgs)
    assertTrue(jvmArgs.any { it.startsWith("-Djava.io.tmpdir=") && it.endsWith("snippet-1") })
  }

  @Test
  fun `custom carries the operator argv verbatim and claims nothing`() {
    val sandbox = PlaygroundSandbox.parseProfile("custom:firejail --net=none").getOrThrow()

    assertEquals(PlaygroundSandbox.Profile.CUSTOM, sandbox.profile)
    assertEquals(listOf("firejail", "--net=none"), sandbox.command(paths))
    assertFalse(PlaygroundSandbox.Profile.CUSTOM.declaresEgressBlocked)
  }

  @Test
  fun `profile parsing is fail-closed on nonsense`() {
    assertEquals(PlaygroundSandbox.NONE, PlaygroundSandbox.parseProfile(null).getOrThrow())
    assertEquals(PlaygroundSandbox.NONE, PlaygroundSandbox.parseProfile("  ").getOrThrow())
    assertTrue(PlaygroundSandbox.parseProfile("docker").isFailure)
    assertTrue(PlaygroundSandbox.parseProfile("custom:").isFailure)
    // `custom` without an argv is not a profile name.
    assertTrue(PlaygroundSandbox.parseProfile("custom").isFailure)
  }

  @Test
  fun `resource knobs are validated so a typo fails at startup`() {
    val base = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP)

    assertTrue(PlaygroundSandbox.validate(base).isSuccess)
    assertTrue(PlaygroundSandbox.validate(base.copy(memoryMb = 15)).isFailure)
    assertTrue(PlaygroundSandbox.validate(base.copy(cpus = 0.0)).isFailure)
    assertTrue(PlaygroundSandbox.validate(base.copy(pids = 1)).isFailure)
    assertTrue(PlaygroundSandbox.validate(base.copy(ttlSeconds = 5)).isFailure)
    // …but an inert sandbox is never rejected: `none` has no caps to get wrong.
    assertTrue(PlaygroundSandbox.validate(PlaygroundSandbox.NONE.copy(memoryMb = 15)).isSuccess)
  }

  @Test
  fun `the default ttl outlives a preview token so sessions end by expiry, not by the axe`() {
    assertTrue(PlaygroundSandbox.DEFAULT_TTL_SECONDS > PlaygroundTokenStore.DEFAULT_TTL_SECONDS)
  }
}

/**
 * The `--public` admission decision (issue #3016). The pre-Phase-4 rule was a flat refusal; the
 * rule now is "a sandbox that has *proved* it contains a snippet", and every uncertain state — no
 * profile, no probe, a probe that failed to launch, a probe with any failing check — stays a
 * refusal.
 */
class PlaygroundPublicGateTest {

  private val bwrap = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP)

  private val cleanProbe =
    PlaygroundSandboxProbe.Report(
      ran = true,
      egressBlocked = true,
      filesystemContained = true,
      processIsolated = true,
      workDirWritable = true,
    )

  @Test
  fun `a token-gated host serves with or without a sandbox`() {
    assertTrue(
      PlaygroundPublicGate.decide(isPublic = false, sandbox = PlaygroundSandbox.NONE, probe = null)
        is PlaygroundPublicGate.Decision.Allow
    )
    assertTrue(
      PlaygroundPublicGate.decide(isPublic = false, sandbox = bwrap, probe = null)
        is PlaygroundPublicGate.Decision.Allow
    )
  }

  @Test
  fun `public with no sandbox is refused, as before Phase 4`() {
    val decision =
      PlaygroundPublicGate.decide(isPublic = true, sandbox = PlaygroundSandbox.NONE, probe = null)

    val refusal = assertRefused(decision)
    assertTrue("--playground-sandbox" in refusal, refusal)
  }

  @Test
  fun `public with a sandbox but no probe result is refused`() {
    assertRefused(PlaygroundPublicGate.decide(isPublic = true, sandbox = bwrap, probe = null))
  }

  @Test
  fun `public is refused when the jail could not even launch`() {
    val refusal =
      assertRefused(
        PlaygroundPublicGate.decide(
          isPublic = true,
          sandbox = bwrap,
          probe = PlaygroundSandboxProbe.Report(ran = false, detail = "bwrap: command not found"),
        )
      )
    assertTrue("bwrap: command not found" in refusal, refusal)
  }

  @Test
  fun `public is refused when any single check fails`() {
    val leaks = cleanProbe.copy(egressBlocked = false)
    val refusal = assertRefused(PlaygroundPublicGate.decide(true, bwrap, leaks))
    assertTrue("outbound network reachable" in refusal, refusal)

    assertRefused(
      PlaygroundPublicGate.decide(true, bwrap, cleanProbe.copy(filesystemContained = false))
    )
    assertRefused(
      PlaygroundPublicGate.decide(true, bwrap, cleanProbe.copy(processIsolated = false))
    )
    // A jail so tight the render can't write its PNG is also a refusal — it would fail every run.
    assertRefused(
      PlaygroundPublicGate.decide(true, bwrap, cleanProbe.copy(workDirWritable = false))
    )
  }

  @Test
  fun `public is allowed on a verified sandbox`() {
    val decision = PlaygroundPublicGate.decide(isPublic = true, sandbox = bwrap, probe = cleanProbe)

    val allow = decision as? PlaygroundPublicGate.Decision.Allow
    requireNotNull(allow) { "expected Allow, got $decision" }
    assertTrue("verified" in allow.detail, allow.detail)
  }

  @Test
  fun `a custom profile that proves itself is admitted, one that does not is not`() {
    val custom = PlaygroundSandbox.parseProfile("custom:my-jail --net=none").getOrThrow()

    assertTrue(
      PlaygroundPublicGate.decide(true, custom, cleanProbe) is PlaygroundPublicGate.Decision.Allow,
      "a custom jail is admitted on evidence, not on its name",
    )
    assertRefused(PlaygroundPublicGate.decide(true, custom, cleanProbe.copy(egressBlocked = false)))
  }

  private fun assertRefused(decision: PlaygroundPublicGate.Decision): String {
    val refuse = decision as? PlaygroundPublicGate.Decision.Refuse
    requireNotNull(refuse) { "expected Refuse, got $decision" }
    return refuse.reason
  }
}
