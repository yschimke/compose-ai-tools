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
    // A transient *scope* takes cgroup properties only. Service exec-context settings would fail
    // unit creation outright, so a scope must never be handed one — and the profile must not claim
    // the isolation those settings would have provided.
    assertTrue(argv.none { it.startsWith("PrivateNetwork") }, argv.toString())
    assertTrue(argv.none { it.startsWith("ProtectSystem") }, argv.toString())
    assertTrue(argv.none { it.startsWith("NoNewPrivileges") }, argv.toString())
    assertFalse(PlaygroundSandbox.Profile.SYSTEMD.declaresEgressBlocked)
    assertFalse(PlaygroundSandbox.Profile.SYSTEMD.declaresFilesystemContained)
    assertTrue(PlaygroundSandbox.Profile.SYSTEMD.declaresResourceCaps)
  }

  @Test
  fun `strict is systemd caps wrapping the bwrap jail`() {
    val argv = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.STRICT).command(paths)

    assertEquals("systemd-run", argv.first())
    assertTrue(argv.indexOf("systemd-run") < argv.indexOf("bwrap"))
    assertTrue("--unshare-net" in argv)
    // The only built-in that provides every property the --public gate asks for.
    assertTrue(
      PlaygroundSandbox.Profile.entries.filter {
        it.declaresEgressBlocked && it.declaresFilesystemContained && it.declaresResourceCaps
      } == listOf(PlaygroundSandbox.Profile.STRICT)
    )
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
  fun `jailed robolectric launch resolves dependencies from the host maven repository`() {
    val sandbox = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP)
    val base = linkedMapOf("robolectric.graphicsMode" to "NATIVE")

    val fromHome =
      sandbox.robolectricSystemProperties(base = base, mavenRepoLocal = null, userHome = "/root")
    assertEquals("NATIVE", fromHome["robolectric.graphicsMode"])
    assertEquals("/root/.m2/repository", fromHome["maven.repo.local"])

    val overridden =
      sandbox.robolectricSystemProperties(
        base = base,
        mavenRepoLocal = "/cache/maven",
        userHome = "/ignored",
      )
    assertEquals("/cache/maven", overridden["maven.repo.local"])
  }

  @Test
  fun `unsandboxed robolectric launch stays unchanged`() {
    val base = linkedMapOf("robolectric.graphicsMode" to "NATIVE")

    assertTrue(
      PlaygroundSandbox.NONE.robolectricSystemProperties(
        base = base,
        mavenRepoLocal = "/cache/maven",
        userHome = "/root",
      ) === base
    )
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

  @Test
  fun `dropping the jail removes the argv and keeps every cap`() {
    // The recovery for a jail that cannot launch on this host. The argv is what fails to spawn, so
    // it is the only thing that goes; the caps are the half that actually protects the box.
    val dropped = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.UNSHARE).droppingJail()

    assertEquals(emptyList(), dropped.command(paths), "no jail argv survives the drop")
    assertTrue(dropped.isActive, "still active — otherwise the caps would go too")
    assertTrue("-Xmx1152m" in dropped.jvmArgs(paths.workDir))
    assertTrue(dropped.jvmArgs(paths.workDir).any { it.startsWith("-XX:ActiveProcessorCount=") })
    assertTrue("-XX:+ExitOnOutOfMemoryError" in dropped.jvmArgs(paths.workDir))
    assertEquals(
      PlaygroundSandbox.DEFAULT_TTL_SECONDS,
      dropped.ttlSeconds,
      "the hard kill still arms",
    )
  }

  @Test
  fun `a dropped jail says so, so a log or status is never mistaken for containment`() {
    val dropped = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP).droppingJail()

    assertTrue("jail dropped" in dropped.describe(), dropped.describe())
    // The profile id is retained: what the operator ASKED for stays visible next to what happened.
    assertTrue("bwrap" in dropped.describe(), dropped.describe())
  }

  @Test
  fun `the profiles excluded from the drop are exactly those whose caps live in the argv`() {
    // The discriminator ServeCommand refuses on. `systemd`/`strict` enforce MemoryMax, CPUQuota
    // and TasksMax through the systemd-run prefix that command() emits, so dropping that argv
    // drops the enforcement — heap and pool sizing are all that would remain. Pinning the set here
    // means adding a future cgroup-backed profile can't silently inherit the caps-only fallback.
    val capBacked =
      PlaygroundSandbox.Profile.entries.filter { it.declaresResourceCaps }.map { it.id }.toSet()

    assertEquals(setOf("systemd", "strict"), capBacked)

    val strict = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.STRICT)
    assertTrue(
      strict.command(paths).any { it.startsWith("MemoryMax=") },
      "the enforceable cap is in the argv, which is what makes dropping it unsafe",
    )
    assertTrue(
      strict.jvmArgs(paths.workDir).none { it.startsWith("MemoryMax") },
      "and jvmArgs cannot replace it",
    )
  }

  @Test
  fun `dropping is inert for every profile that had no argv anyway`() {
    val none = PlaygroundSandbox.NONE.droppingJail()
    assertEquals(emptyList(), none.command(paths))
    assertFalse(none.isActive, "none stays none — there was nothing to drop")
  }
}

/**
 * The `--public` admission decision (issues #3016, #3210). Two independent postures admit the lane:
 * **contained** — a sandbox that has *proved* it contains a snippet, where every uncertain state
 * (no profile, no probe, a probe that failed to launch, a probe with any failing check) stays a
 * refusal — and **repo-access-gated**, where GitHub auth limits the callers to repo collaborators
 * and the containment evidence is no longer the thing being asked for. Only anonymous *and*
 * uncontained is refused outright.
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
      PlaygroundPublicGate.decide(
        isPublic = false,
        repoAccessGated = false,
        sandbox = PlaygroundSandbox.NONE,
        probe = null,
      ) is PlaygroundPublicGate.Decision.Allow
    )
    assertTrue(
      PlaygroundPublicGate.decide(
        isPublic = false,
        repoAccessGated = false,
        sandbox = bwrap,
        probe = null,
      ) is PlaygroundPublicGate.Decision.Allow
    )
  }

  @Test
  fun `public with no sandbox is refused, as before Phase 4`() {
    val decision =
      PlaygroundPublicGate.decide(
        isPublic = true,
        repoAccessGated = false,
        sandbox = PlaygroundSandbox.NONE,
        probe = null,
      )

    val refusal = assertRefused(decision)
    assertTrue("--playground-sandbox" in refusal, refusal)
  }

  @Test
  fun `public with a sandbox but no probe result is refused`() {
    assertRefused(
      PlaygroundPublicGate.decide(
        isPublic = true,
        repoAccessGated = false,
        sandbox = bwrap,
        probe = null,
      )
    )
  }

  @Test
  fun `public is refused when the jail could not even launch`() {
    val refusal =
      assertRefused(
        PlaygroundPublicGate.decide(
          isPublic = true,
          repoAccessGated = false,
          sandbox = bwrap,
          probe = PlaygroundSandboxProbe.Report(ran = false, detail = "bwrap: command not found"),
        )
      )
    assertTrue("bwrap: command not found" in refusal, refusal)
  }

  @Test
  fun `public is refused when any single check fails`() {
    val leaks = cleanProbe.copy(egressBlocked = false)
    val refusal = assertRefused(PlaygroundPublicGate.decide(true, false, bwrap, leaks))
    assertTrue("outbound network reachable" in refusal, refusal)

    assertRefused(
      PlaygroundPublicGate.decide(true, false, bwrap, cleanProbe.copy(filesystemContained = false))
    )
    assertRefused(
      PlaygroundPublicGate.decide(true, false, bwrap, cleanProbe.copy(processIsolated = false))
    )
    // A jail so tight the render can't write its PNG is also a refusal — it would fail every run.
    assertRefused(
      PlaygroundPublicGate.decide(true, false, bwrap, cleanProbe.copy(workDirWritable = false))
    )
  }

  @Test
  fun `public is allowed on a verified, resource-capped sandbox`() {
    val strict = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.STRICT)

    val decision =
      PlaygroundPublicGate.decide(
        isPublic = true,
        repoAccessGated = false,
        sandbox = strict,
        probe = cleanProbe,
      )

    val allow = decision as? PlaygroundPublicGate.Decision.Allow
    requireNotNull(allow) { "expected Allow, got $decision" }
    assertTrue("verified" in allow.detail, allow.detail)
  }

  @Test
  fun `a sealed jail with no cpu or pid cap is still refused under public`() {
    // bwrap contains a snippet perfectly and caps nothing: -Xmx bounds heap, ActiveProcessorCount
    // only sizes JVM pools, and the probe cannot measure either. A snippet could spin CPU-bound
    // threads until the box starves, so containment alone is not admission.
    val refusal = assertRefused(PlaygroundPublicGate.decide(true, false, bwrap, cleanProbe))

    assertTrue("no CPU or process-count cap" in refusal, refusal)
    assertTrue("strict" in refusal, refusal)

    val unshare = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.UNSHARE)
    assertRefused(PlaygroundPublicGate.decide(true, false, unshare, cleanProbe))
  }

  @Test
  fun `a custom profile that proves itself is admitted, one that does not is not`() {
    val custom = PlaygroundSandbox.parseProfile("custom:my-jail --net=none").getOrThrow()

    assertTrue(
      PlaygroundPublicGate.decide(true, false, custom, cleanProbe)
        is PlaygroundPublicGate.Decision.Allow,
      "a custom jail is admitted on evidence, not on its name",
    )
    assertRefused(
      PlaygroundPublicGate.decide(true, false, custom, cleanProbe.copy(egressBlocked = false))
    )
  }

  @Test
  fun `a repo-access-gated public host is admitted with no sandbox at all`() {
    // Issue #3210: the routes already reject anyone without access to --github-auth-repo, so the
    // snippet is a collaborator's, not a stranger's — the same trust level as the token-gated
    // posture, which `decide` admits with no sandbox.
    val allow =
      assertAllowed(
        PlaygroundPublicGate.decide(
          isPublic = true,
          repoAccessGated = true,
          sandbox = PlaygroundSandbox.NONE,
          probe = null,
        )
      )

    assertTrue("repo-access-gated" in allow, allow)
    assertTrue("no sandbox" in allow, allow)
  }

  @Test
  fun `a repo-access-gated public host keeps a configured sandbox, as defence in depth`() {
    // bwrap is refused as an *admission basis* (no cpu/pid cap) but must still be applied when the
    // lane is admitted on repo access — and the log must not read as if containment let it in.
    val allow =
      assertAllowed(
        PlaygroundPublicGate.decide(
          isPublic = true,
          repoAccessGated = true,
          sandbox = bwrap,
          probe = cleanProbe,
        )
      )

    assertTrue("repo-access-gated" in allow, allow)
    assertTrue("defence in depth" in allow, allow)
  }

  @Test
  fun `repo-access gating does not rescue a jail that failed its probe from being reported`() {
    // The lane still serves — admission never rested on the jail here — so this is an Allow. The
    // ServeCommand caller is what warns; the gate's job is only not to refuse.
    assertAllowed(
      PlaygroundPublicGate.decide(
        isPublic = true,
        repoAccessGated = true,
        sandbox = bwrap,
        probe = cleanProbe.copy(egressBlocked = false),
      )
    )
  }

  @Test
  fun `anonymous and uncontained names both remedies`() {
    // Issue #3214: the refusal an operator hits on a container that cannot jail a snippet used to
    // point only at --playground-sandbox, which that box cannot satisfy. GitHub auth is the way
    // out, so the message has to say so.
    val refusal =
      assertRefused(
        PlaygroundPublicGate.decide(
          isPublic = true,
          repoAccessGated = false,
          sandbox = PlaygroundSandbox.NONE,
          probe = null,
        )
      )

    assertTrue("--github-auth-repo" in refusal, refusal)
    assertTrue("--playground-sandbox" in refusal, refusal)
  }

  @Test
  fun `the anonymous contained posture says it is anonymous`() {
    // Still admitted (PLAYGROUND.md §6's designed posture), but an operator must not read
    // "admitted" and assume sign-in is enforced.
    val strict = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.STRICT)

    val allow =
      assertAllowed(
        PlaygroundPublicGate.decide(
          isPublic = true,
          repoAccessGated = false,
          sandbox = strict,
          probe = cleanProbe,
        )
      )

    assertTrue("ANONYMOUS" in allow, allow)
  }

  @Test
  fun `a jail that cannot launch is still refused when containment is the admission basis`() {
    // The safety property the auto-fallback rests on. ServeCommand only drops a jail AFTER the
    // gate has admitted the lane, so this refusal is what keeps the fallback from ever handing an
    // anonymous --public host an uncontained playground: it never gets past `decide`.
    val refusal =
      assertRefused(
        PlaygroundPublicGate.decide(
          isPublic = true,
          repoAccessGated = false,
          sandbox = bwrap,
          probe = PlaygroundSandboxProbe.Report(ran = false, detail = "bwrap: not found"),
        )
      )

    assertTrue("bwrap: not found" in refusal, refusal)
  }

  @Test
  fun `a repo-access-gated host with an unlaunchable jail is admitted, so it can fall back`() {
    // The mirror of the case above: here the lane is admitted on WHO can reach it, so the dead
    // jail is a degradation to report rather than a reason to refuse — which is exactly the state
    // ServeCommand converts into a caps-only sandbox.
    assertAllowed(
      PlaygroundPublicGate.decide(
        isPublic = true,
        repoAccessGated = true,
        sandbox = bwrap,
        probe = PlaygroundSandboxProbe.Report(ran = false, detail = "bwrap: not found"),
      )
    )
  }

  private fun assertAllowed(decision: PlaygroundPublicGate.Decision): String {
    val allow = decision as? PlaygroundPublicGate.Decision.Allow
    requireNotNull(allow) { "expected Allow, got $decision" }
    return allow.detail
  }

  private fun assertRefused(decision: PlaygroundPublicGate.Decision): String {
    val refuse = decision as? PlaygroundPublicGate.Decision.Refuse
    requireNotNull(refuse) { "expected Refuse, got $decision" }
    return refuse.reason
  }
}
