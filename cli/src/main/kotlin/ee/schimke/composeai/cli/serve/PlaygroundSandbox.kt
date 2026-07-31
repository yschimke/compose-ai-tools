package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The playground's **per-session sandbox** policy — Phase 4 of
 * [docs/design/PLAYGROUND.md](../../../../../../../../docs/design/PLAYGROUND.md) §6, the gate
 * between a token-gated internal tool and a playground open under `--public`.
 *
 * Every playground lane already runs a snippet in its **own child JVM** (the first-frame render,
 * the remote-compose capture, and the Stage-2 live session each spawn a fresh daemon subprocess
 * over that snippet's classes — never a hot-swap into a shared, long-lived daemon). What this type
 * adds is the containment around that child:
 * - **an argv prefix** ([command]) that launches the JVM inside an OS jail — no network namespace,
 *   a read-only view of the host, a scrubbed environment;
 * - **JVM-level caps** ([jvmArgs]) that bound heap, CPU parallelism and temp files even on a
 *   profile whose jail carries no cgroup;
 * - **a hard wall-clock TTL** ([ttlSeconds]) the spawner arms as a `destroyForcibly` watchdog, so a
 *   snippet that wedges its JVM is killed rather than lingering to its token's expiry.
 *
 * The type is pure: it computes argv and never spawns anything, so every profile's containment is
 * unit-testable without the tool being installed. Whether a configured sandbox *actually* contains
 * anything is a separate, empirical question answered by [PlaygroundSandboxProbe] — and under
 * `--public` a passing probe is mandatory ([PlaygroundPublicGate]), because a profile's advertised
 * properties are a claim and the probe is the evidence.
 */
data class PlaygroundSandbox(
  val profile: Profile,
  /** Heap + cgroup memory ceiling for one snippet JVM. */
  val memoryMb: Int = DEFAULT_MEMORY_MB,
  /** CPU budget for one snippet JVM: a cgroup `CPUQuota` and `-XX:ActiveProcessorCount`. */
  val cpus: Double = DEFAULT_CPUS,
  /** Task/pid ceiling, so a snippet can't fork-bomb the box. */
  val pids: Int = DEFAULT_PIDS,
  /** Hard wall-clock lifetime of one snippet JVM, enforced by kill — not by cooperation. */
  val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  /**
   * Extra host paths the jail binds **read-only**, on top of the snippet's classpath and the JDK.
   * The escape hatch for caches a render legitimately reads with no network to fetch them (the
   * Robolectric `android-all` cache, the downloadable-font cache).
   */
  val extraReadOnlyPaths: List<String> = emptyList(),
  /** Operator-supplied argv for [Profile.CUSTOM]; ignored by every other profile. */
  val customCommand: List<String> = emptyList(),
) {

  /**
   * How the child JVM is jailed. The `declares…` flags are the profile's **claim** about what its
   * jail provides — used for startup logging and for the "you asked for `--public` with no
   * containment at all" refusal. They are never a substitute for [PlaygroundSandboxProbe]; a
   * `--public` host must still prove the claim empirically.
   */
  enum class Profile(
    val id: String,
    val declaresEgressBlocked: Boolean,
    val declaresFilesystemContained: Boolean,
    val declaresResourceCaps: Boolean,
  ) {
    /**
     * No jail — the pre-Phase-4 behaviour. Fine for a token-gated dev host; never for `--public`.
     */
    NONE("none", false, false, false),

    /**
     * `unshare(1)`: a fresh user + network + pid namespace, child killed with the parent. Blocks
     * egress and process visibility with no privileges and no extra package, but leaves the host
     * filesystem visible — so it is a good local default and **not** enough for `--public`.
     */
    UNSHARE("unshare", true, false, false),

    /**
     * `bwrap(1)` (bubblewrap): network unshared, environment cleared, the host bound **read-only**
     * with a tmpfs `/tmp` and exactly one writable path (the snippet's work dir). Containment
     * without cgroups — heap/CPU come from [jvmArgs].
     */
    BWRAP("bwrap", true, true, false),

    /**
     * `systemd-run --scope` with `MemoryMax` / `MemorySwapMax` / `CPUQuota` / `TasksMax`. Real
     * cgroup caps — and **only** cgroup caps.
     *
     * A transient **scope** takes cgroup resource properties but *not* service execution settings
     * (`PrivateNetwork`, `PrivateTmp`, `ProtectSystem`, `NoNewPrivileges`): those live in a service
     * unit's exec context, and passing them to `--scope` fails unit creation outright. Rather than
     * move the daemon into a transient service — which would put systemd between us and the JVM's
     * stdio, the JSON-RPC transport — this profile owns resource control alone and delegates
     * isolation to [STRICT]'s `bwrap` half. Hence: no egress or filesystem claim here.
     */
    SYSTEMD("systemd", false, false, true),

    /**
     * `systemd-run --scope … bwrap …` — [SYSTEMD]'s cgroup caps around [BWRAP]'s namespace and
     * filesystem containment. The profile a `--public` host should run: it is the only built-in
     * that provides every property [PlaygroundPublicGate] requires.
     */
    STRICT("strict", true, true, true),

    /**
     * An operator-supplied argv prefix. Claims nothing — a `--public` host running `custom` is
     * admitted purely on its probe result.
     */
    CUSTOM("custom", false, false, false),
  }

  /** The host paths one snippet JVM needs: its writable work dir, its read-only inputs, the JDK. */
  data class Paths(val workDir: File, val readOnly: List<File>, val javaHome: File)

  /** True when this sandbox does anything at all — [Profile.NONE] is the only no-op. */
  val isActive: Boolean
    get() = profile != Profile.NONE

  /**
   * The argv prefix the snippet JVM launches behind, or empty for [Profile.NONE]. Paths are bound
   * with the `-try` variants where a host may legitimately lack them, so one missing `/lib64` can't
   * turn a containment profile into a failed spawn.
   */
  fun command(paths: Paths): List<String> =
    when (profile) {
      Profile.NONE -> emptyList()
      Profile.UNSHARE -> unshareCommand()
      Profile.BWRAP -> bwrapCommand(paths)
      Profile.SYSTEMD -> systemdCommand()
      Profile.STRICT -> systemdCommand() + bwrapCommand(paths)
      Profile.CUSTOM -> customCommand
    }

  /**
   * JVM-level caps applied to **every** active profile, so heap and CPU are bounded even where the
   * jail carries no cgroup (`unshare`, `bwrap`): a heap ceiling under the sandbox's memory budget,
   * a CPU-parallelism ceiling matching its CPU budget, OOM as a *process exit* rather than a
   * thrashing JVM, and a temp dir inside the one writable path. Empty for [Profile.NONE], which
   * keeps the pre-Phase-4 launch byte-identical.
   */
  fun jvmArgs(workDir: File): List<String> {
    if (!isActive) return emptyList()
    return listOf(
      "-Xmx${heapMb()}m",
      "-XX:ActiveProcessorCount=${activeProcessorCount()}",
      "-XX:+ExitOnOutOfMemoryError",
      // The session work dir is the one writable path in the jail — and it is deleted with the
      // snippet's token, so a snippet's temp files are ephemeral by construction.
      "-Djava.io.tmpdir=${workDir.absolutePath}",
    )
  }

  /**
   * Heap ceiling: three quarters of the memory budget, leaving room for the JVM's own non-heap
   * footprint (metaspace, code cache, Skiko/Robolectric native allocations) under a cgroup that
   * would otherwise OOM-kill the process before the heap limit ever bit.
   */
  internal fun heapMb(): Int = (memoryMb * 3 / 4).coerceAtLeast(MIN_HEAP_MB)

  internal fun activeProcessorCount(): Int = ceil(cpus).toInt().coerceAtLeast(1)

  /** One-line summary for the startup log — what this host will do to a stranger's snippet. */
  fun describe(): String =
    if (!isActive) "sandbox=none (playground refused under --public)"
    else
      "sandbox=${profile.id} mem=${memoryMb}MB heap=${heapMb()}MB cpus=$cpus pids=$pids " +
        "ttl=${ttlSeconds}s"

  private fun unshareCommand(): List<String> =
    listOf(
      "unshare",
      // A fresh user namespace is what lets an unprivileged serve host create the others.
      "--user",
      "--map-root-user",
      // The whole point: no route to anything. A snippet gets loopback in an empty netns.
      "--net",
      // Own pid namespace + /proc, so a snippet can neither see nor signal host processes.
      "--pid",
      "--fork",
      "--mount-proc",
      // The daemon dies with the serve host; no orphan JVM survives a crash.
      "--kill-child",
    )

  private fun bwrapCommand(paths: Paths): List<String> = buildList {
    add("bwrap")
    add("--die-with-parent")
    add("--unshare-all")
    // Redundant under --unshare-all, but egress is the property we most want to be explicit about.
    add("--unshare-net")
    add("--new-session")
    // The serve JVM's environment carries operator secrets (--admin-token, cloud credentials); a
    // snippet must not be able to read them out of /proc/self/environ.
    add("--clearenv")
    add("--setenv")
    add("HOME")
    add(paths.workDir.absolutePath)
    add("--setenv")
    add("PATH")
    add("/usr/bin:/bin")
    add("--setenv")
    add("LANG")
    add("C.UTF-8")
    add("--proc")
    add("/proc")
    add("--dev")
    add("/dev")
    add("--tmpfs")
    add("/tmp")
    SYSTEM_READ_ONLY_PATHS.forEach { roBindTry(it) }
    roBindTry(paths.javaHome.absolutePath)
    // The classpath (catalog jars + the compiled snippet) and any operator-declared cache. Bound
    // one entry at a time rather than by common ancestor: an ancestor bind would hand the snippet
    // every *other* jar in the Gradle/Maven cache too.
    (paths.readOnly.map { it.absolutePath } + extraReadOnlyPaths).distinct().forEach {
      roBindTry(it)
    }
    // Exactly one writable path — and it is deleted with the snippet's token.
    add("--bind")
    add(paths.workDir.absolutePath)
    add(paths.workDir.absolutePath)
    add("--chdir")
    add(paths.workDir.absolutePath)
    add("--")
  }

  private fun MutableList<String>.roBindTry(path: String) {
    add("--ro-bind-try")
    add(path)
    add(path)
  }

  private fun systemdCommand(): List<String> =
    listOf(
      "systemd-run",
      "--scope",
      "--quiet",
      "--collect",
      "-p",
      "MemoryMax=${memoryMb}M",
      "-p",
      "MemorySwapMax=0",
      "-p",
      "CPUQuota=${(cpus * 100).roundToInt()}%",
      "-p",
      "TasksMax=$pids",
      // Deliberately cgroup properties only. `PrivateNetwork` / `PrivateTmp` / `ProtectSystem` /
      // `NoNewPrivileges` are service exec-context settings that a transient *scope* cannot take —
      // passing them fails unit creation, so a profile that advertised them would fail preflight on
      // every host. Isolation is bwrap's job (STRICT); the wall-clock deadline is the spawner's
      // kill watchdog, which needs no systemd version floor (`RuntimeMaxSec` on a scope wants
      // systemd 244+).
    )

  companion object {
    const val DEFAULT_MEMORY_MB = 1536
    const val DEFAULT_CPUS = 1.0
    const val DEFAULT_PIDS = 256

    /**
     * 15 minutes: comfortably longer than [PlaygroundTokenStore.DEFAULT_TTL_SECONDS] (so an
     * ordinary session ends by its token expiring, not by being shot), short enough that a wedged
     * JVM is reclaimed the same hour.
     */
    const val DEFAULT_TTL_SECONDS = 900L

    /** Below this a JVM can't even boot Skiko/Robolectric; refuse to configure a useless heap. */
    const val MIN_HEAP_MB = 256

    private const val MIN_MEMORY_MB = 384

    /**
     * Host paths a JVM needs to exec at all; `-try` because layouts differ (usr-merge, musl…).
     *
     * `/nix/store` is on the list for a reason worth writing down: a Nix-provisioned JDK's
     * `bin/java` resolves its ELF interpreter and libc out of *other* store paths, so binding only
     * `java.home` produces `execvp … No such file or directory` inside the jail. The store is
     * immutable and world-readable, so binding it read-only costs nothing where it exists and is a
     * no-op everywhere else.
     */
    private val SYSTEM_READ_ONLY_PATHS =
      listOf(
        "/usr",
        "/lib",
        "/lib64",
        "/bin",
        "/etc/alternatives",
        "/etc/ssl/certs",
        "/nix/store",
        "/opt",
      )

    val NONE = PlaygroundSandbox(profile = Profile.NONE)

    /**
     * Parse a `--playground-sandbox` value: a profile id (`none`, `unshare`, `bwrap`, `systemd`,
     * `strict`) or `custom:<argv>` where `<argv>` is a whitespace-separated command prefix. Null or
     * blank ⇒ [NONE], the pre-Phase-4 default.
     */
    fun parseProfile(spec: String?): Result<PlaygroundSandbox> {
      val raw = spec?.trim().orEmpty()
      if (raw.isEmpty()) return Result.success(NONE)
      if (raw.startsWith("custom:")) {
        val argv =
          raw.removePrefix("custom:").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (argv.isEmpty()) {
          return Result.failure(
            IllegalArgumentException("--playground-sandbox custom:<argv> needs a command")
          )
        }
        return Result.success(PlaygroundSandbox(profile = Profile.CUSTOM, customCommand = argv))
      }
      val profile =
        Profile.entries.firstOrNull { it.id == raw.lowercase() && it != Profile.CUSTOM }
          ?: return Result.failure(
            IllegalArgumentException(
              "unknown --playground-sandbox profile '$raw' — expected one of " +
                Profile.entries.filter { it != Profile.CUSTOM }.joinToString(", ") { it.id } +
                ", or custom:<argv>"
            )
          )
      return Result.success(PlaygroundSandbox(profile = profile))
    }

    /**
     * Validate the resource knobs, so a typo (`--playground-sandbox-memory-mb 15`) fails at startup
     * rather than as an unexplained daemon that never comes up.
     */
    fun validate(sandbox: PlaygroundSandbox): Result<PlaygroundSandbox> {
      if (!sandbox.isActive) return Result.success(sandbox)
      if (sandbox.memoryMb < MIN_MEMORY_MB) {
        return Result.failure(
          IllegalArgumentException(
            "--playground-sandbox-memory-mb must be at least $MIN_MEMORY_MB (got ${sandbox.memoryMb})"
          )
        )
      }
      if (sandbox.cpus <= 0.0) {
        return Result.failure(
          IllegalArgumentException("--playground-sandbox-cpus must be > 0 (got ${sandbox.cpus})")
        )
      }
      if (sandbox.pids < 16) {
        return Result.failure(
          IllegalArgumentException(
            "--playground-sandbox-pids must be at least 16 (got ${sandbox.pids})"
          )
        )
      }
      if (sandbox.ttlSeconds !in 30..24 * 3600) {
        return Result.failure(
          IllegalArgumentException(
            "--playground-sandbox-ttl must be between 30s and 24h (got ${sandbox.ttlSeconds}s)"
          )
        )
      }
      return Result.success(sandbox)
    }
  }
}

/**
 * The `--public` admission decision for the playground lane — the literal "gate" of PLAYGROUND.md
 * §6 and issue #3016.
 *
 * Before Phase 4 this was a flat refusal: `--playground-bundle` under `--public` disabled the lane,
 * because the serve host's founding constraint is that it never runs untrusted code. The constraint
 * has not moved; what changed is that a snippet can now be run somewhere that *isn't* the host. So
 * the gate opens on evidence, never on configuration alone:
 * 1. a sandbox profile must be configured (`none` is still a flat refusal),
 * 2. the startup [probe][PlaygroundSandboxProbe] must have run **inside that jail** and come back
 *    with egress blocked, the host filesystem contained, and the process namespace isolated, and
 * 3. the jail must actually **cap CPU and process count**, which the probe cannot measure. A
 *    snippet inside a perfectly sealed `bwrap` can still spawn CPU-bound threads until it starves
 *    the box: `-Xmx` bounds heap and `-XX:ActiveProcessorCount` only sizes JVM pools. So a built-in
 *    profile with no cgroup behind it (`unshare`, `bwrap`) is refused under `--public` and pointed
 *    at `strict`; a `custom:` jail is taken at its word here (its caps are the operator's to
 *    supply) but still has to pass the probe.
 *
 * A profile that merely *claims* containment ([PlaygroundSandbox.Profile.declaresEgressBlocked] and
 * friends) is not enough: `bwrap` on a kernel with user namespaces disabled, or a `custom:` wrapper
 * with a typo, both claim everything and contain nothing. Fail-closed in every direction — an
 * absent probe report is a refusal, not a pass.
 */
object PlaygroundPublicGate {

  sealed interface Decision {
    /** The lane may serve; [detail] is the startup log line. */
    data class Allow(val detail: String) : Decision

    /** The lane stays disabled; [reason] is printed and is actionable. */
    data class Refuse(val reason: String) : Decision
  }

  /**
   * Decide whether the playground may serve. [isPublic] false ⇒ always allowed (the token-gated
   * posture Phases 1–3 shipped under), and the sandbox — configured or not — is still applied.
   */
  fun decide(
    isPublic: Boolean,
    sandbox: PlaygroundSandbox,
    probe: PlaygroundSandboxProbe.Report?,
  ): Decision {
    if (!isPublic) {
      return Decision.Allow(
        if (sandbox.isActive) "token-gated; ${sandbox.describe()}"
        else "token-gated; no sandbox (add --playground-sandbox to rehearse the public posture)"
      )
    }
    if (!sandbox.isActive) {
      return Decision.Refuse(
        "--playground-bundle / --playground-android-bundle under --public need a per-session " +
          "sandbox: pass --playground-sandbox <bwrap|strict|systemd|unshare|custom:…>. Without one " +
          "a stranger's snippet would run unconfined on the server (PLAYGROUND.md §6)."
      )
    }
    if (probe == null) {
      return Decision.Refuse(
        "playground sandbox preflight did not run — refusing to serve the playground under " +
          "--public on an unverified sandbox."
      )
    }
    if (!probe.ran) {
      return Decision.Refuse(
        "playground sandbox preflight could not launch the jail (${probe.detail}) — is " +
          "'${sandbox.profile.id}' installed and permitted on this host?"
      )
    }
    val failures = probe.failedChecks()
    if (failures.isNotEmpty()) {
      return Decision.Refuse(
        "playground sandbox preflight failed under profile '${sandbox.profile.id}': " +
          failures.joinToString("; ") +
          ". The playground stays disabled under --public until the jail contains a snippet."
      )
    }
    // Containment proven — but the probe cannot measure CPU or process-count caps, and a sealed
    // jail with none of those still lets a snippet burn the box down from the inside.
    if (
      !sandbox.profile.declaresResourceCaps && sandbox.profile != PlaygroundSandbox.Profile.CUSTOM
    ) {
      return Decision.Refuse(
        "profile '${sandbox.profile.id}' contains a snippet but applies no CPU or process-count " +
          "cap, so one snippet can still starve the box (-Xmx bounds heap only). Use " +
          "--playground-sandbox strict (cgroup caps around the same jail), or a custom: jail that " +
          "applies its own caps."
      )
    }
    val caveat =
      if (
        sandbox.profile == PlaygroundSandbox.Profile.CUSTOM && !sandbox.profile.declaresResourceCaps
      )
        " (resource caps are the custom jail's responsibility — verify MemoryMax/CPUQuota/TasksMax " +
          "or equivalent yourself)"
      else ""
    return Decision.Allow("public; verified ${sandbox.describe()}$caveat")
  }
}
