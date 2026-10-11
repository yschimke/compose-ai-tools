package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The playground's per-session sandbox policy
 * ([docs/design/PLAYGROUND.md](../../../../../../../../docs/design/PLAYGROUND.md) §6).
 *
 * Each playground lane already runs a snippet in its own child JVM; this adds containment around
 * it:
 * - an argv prefix ([command]) launching the JVM in an OS jail with a minimal environment;
 * - JVM-level caps ([jvmArgs]) bounding heap, CPU parallelism and temp files even without cgroups;
 * - a hard wall-clock TTL ([ttlSeconds]) enforced by a `destroyForcibly` watchdog.
 *
 * Pure: computes argv, never spawns. Whether a sandbox actually contains anything is checked by
 * [PlaygroundSandboxProbe], which is mandatory under `--public` ([PlaygroundPublicGate]).
 */
public data class PlaygroundSandbox(
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
   * Extra host paths bound read-only, for caches a render reads with no network (Robolectric
   * `android-all`, downloadable fonts).
   */
  val extraReadOnlyPaths: List<String> = emptyList(),
  /** Operator-supplied argv for [Profile.CUSTOM]; ignored by every other profile. */
  val customCommand: List<String> = emptyList(),
  /**
   * Set by [droppingJail] when the configured jail can't launch on this host; [command] then emits
   * no jail argv while every other cap stays on.
   */
  val jailDropped: Boolean = false,
) {

  /**
   * How the child JVM is jailed. The `declares…` flags are claims, used for logging and the
   * "`--public` with no containment" refusal; never a substitute for [PlaygroundSandboxProbe].
   */
  public enum class Profile(
    public val id: String,
    public val declaresEgressBlocked: Boolean,
    public val declaresFilesystemContained: Boolean,
    public val declaresResourceCaps: Boolean,
  ) {
    /** No jail. Fine for a token-gated dev host; never for `--public`. */
    NONE("none", false, false, false),

    /**
     * `unshare(1)`: fresh user + network + pid namespaces, no privileges needed. The host
     * filesystem stays visible, so not enough for `--public`.
     */
    UNSHARE("unshare", true, false, false),

    /**
     * `bwrap(1)`: network unshared, environment cleared, host bound read-only with a tmpfs `/tmp`
     * and only the work dir writable. No cgroups; heap/CPU come from [jvmArgs].
     */
    BWRAP("bwrap", true, true, false),

    /**
     * `systemd-run --scope` with `MemoryMax` / `MemorySwapMax` / `CPUQuota` / `TasksMax`: cgroup
     * caps only. A scope can't take exec-context settings (`PrivateNetwork` etc.), and a transient
     * service would sit between us and the JVM's stdio (the JSON-RPC transport), so isolation is
     * left to [STRICT]'s bwrap half.
     */
    SYSTEMD("systemd", false, false, true),

    /**
     * [SYSTEMD]'s cgroup caps around [BWRAP]'s containment — the only built-in that satisfies every
     * [PlaygroundPublicGate] requirement.
     */
    STRICT("strict", true, true, true),

    /**
     * An operator-supplied argv prefix. Claims nothing; under `--public` it is admitted on its
     * probe alone.
     */
    CUSTOM("custom", false, false, false),
  }

  /** The host paths one snippet JVM needs: its writable work dir, its read-only inputs, the JDK. */
  public data class Paths(val workDir: File, val readOnly: List<File>, val javaHome: File)

  /** True when this sandbox does anything at all — [Profile.NONE] is the only no-op. */
  val isActive: Boolean
    get() = profile != Profile.NONE

  /**
   * Drop the jail argv but keep every other cap, for a configured jail that can't launch on this
   * host (user namespaces forbidden, `bwrap` missing). Otherwise every snippet spawn fails with
   * EPERM and nobody is told. Better than [Profile.NONE], which would also drop `-Xmx`, CPU caps
   * and the TTL.
   *
   * Not for [Profile.SYSTEMD] / [Profile.STRICT]: their caps live in the dropped prefix, so
   * `ServeCommand` refuses those lanes instead. A [Profile.CUSTOM] argv is dropped anyway (with a
   * warning). Never reached when containment is what admitted the lane.
   */
  public fun droppingJail(): PlaygroundSandbox = copy(jailDropped = true)

  /**
   * The argv prefix the snippet JVM launches behind. `-try` binds tolerate paths a host may lack.
   *
   * Every prefix starts the JVM with a minimal environment — `bwrap --clearenv`, or a trailing `env
   * -i` keeping [retainChildEnvironment]'s allowlist — because the spawner passes the serve JVM's
   * full environment, including host secrets. Non-Unix hosts (no `env(1)`) get the jail argv alone.
   */
  public fun command(paths: Paths): List<String> = command(paths, System.getenv())

  /** [command] with the parent environment injected, so a test can pin the `env -i` suffix. */
  internal fun command(paths: Paths, parentEnvironment: Map<String, String>): List<String> {
    val jail =
      if (jailDropped) emptyList()
      else
        when (profile) {
          Profile.NONE -> emptyList()
          Profile.UNSHARE -> unshareCommand()
          Profile.BWRAP -> bwrapCommand(paths)
          Profile.SYSTEMD -> systemdCommand()
          Profile.STRICT -> systemdCommand() + bwrapCommand(paths)
          Profile.CUSTOM -> customCommand
        }
    return if (jailClearsEnvironment()) jail else jail + environmentCommand(parentEnvironment)
  }

  /** True when the jail argv itself clears the environment (`bwrap --clearenv`). */
  private fun jailClearsEnvironment(): Boolean =
    !jailDropped && (profile == Profile.BWRAP || profile == Profile.STRICT)

  /**
   * JVM-level caps for every active profile: heap under the memory budget, CPU parallelism matching
   * the CPU budget, OOM as a process exit, and the temp dir inside the one writable path. Empty for
   * [Profile.NONE].
   */
  public fun jvmArgs(workDir: File): List<String> {
    if (!isActive) return emptyList()
    return listOf(
      "-Xmx${heapMb()}m",
      "-XX:ActiveProcessorCount=${activeProcessorCount()}",
      "-XX:+ExitOnOutOfMemoryError",
      // The work dir is the only writable path and is deleted with the token, so temp files are
      // ephemeral.
      "-Djava.io.tmpdir=${workDir.absolutePath}",
    )
  }

  /**
   * Point a jailed Robolectric at the host Maven repository via `maven.repo.local`, since bwrap
   * replaces `HOME` and the default `~/.m2` lookup would hit an empty dir. Relative overrides are
   * made absolute. Inactive sandboxes return [base] unchanged.
   */
  public fun robolectricSystemProperties(
    base: Map<String, String>,
    mavenRepoLocal: String? = System.getProperty("maven.repo.local"),
    userHome: String? = System.getProperty("user.home"),
  ): Map<String, String> {
    if (!isActive) return base
    val repository =
      mavenRepoLocal?.takeIf { it.isNotBlank() }?.let(::File)
        ?: userHome?.takeIf { it.isNotBlank() }?.let { File(it, ".m2/repository") }
        ?: return base
    return base + ("maven.repo.local" to repository.absolutePath)
  }

  /**
   * Heap ceiling: three quarters of the memory budget, leaving room for non-heap and native memory
   * under a cgroup limit.
   */
  internal fun heapMb(): Int = (memoryMb * 3 / 4).coerceAtLeast(MIN_HEAP_MB)

  internal fun activeProcessorCount(): Int = ceil(cpus).toInt().coerceAtLeast(1)

  /** One-line summary for the startup log — what this host will do to a stranger's snippet. */
  public fun describe(): String =
    if (!isActive) "sandbox=none (playground refused under --public)"
    else
      "sandbox=${profile.id}${if (jailDropped) " (jail dropped — caps only)" else ""} " +
        "mem=${memoryMb}MB heap=${heapMb()}MB cpus=$cpus pids=$pids ttl=${ttlSeconds}s"

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
    // The serve JVM's environment holds operator secrets; keep them out of `/proc/self/environ`.
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
    // Bind each classpath entry individually; an ancestor bind would expose the whole Gradle/Maven
    // cache.
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
      // Cgroup properties only: a scope can't take exec-context settings. Isolation is bwrap's job
      // (STRICT), and the deadline is the spawner's watchdog (no systemd version floor needed).
    )

  public companion object {
    public const val DEFAULT_MEMORY_MB: Int = 1536
    public const val DEFAULT_CPUS: Double = 1.0
    public const val DEFAULT_PIDS: Int = 256

    /**
     * 15 minutes: longer than [PlaygroundTokenStore.DEFAULT_TTL_SECONDS] so sessions normally end
     * by token expiry, short enough to reclaim a wedged JVM within the hour.
     */
    public const val DEFAULT_TTL_SECONDS: Long = 900L

    /** Below this a JVM can't even boot Skiko/Robolectric; refuse to configure a useless heap. */
    public const val MIN_HEAP_MB: Int = 256

    private const val MIN_MEMORY_MB = 384

    /**
     * Host paths a JVM needs to exec; `-try` because layouts differ. `/nix/store` is included
     * because a Nix JDK resolves its ELF interpreter and libc from other store paths.
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

    public val NONE: PlaygroundSandbox = PlaygroundSandbox(profile = Profile.NONE)

    /**
     * Environment variables a snippet JVM keeps (plus every `LC_*`); everything else arrives via
     * argv.
     */
    internal val CHILD_ENVIRONMENT: Set<String> =
      setOf("PATH", "HOME", "LANG", "TZ", "TMPDIR", "JAVA_HOME")

    private fun isChildEnvironmentName(name: String): Boolean =
      name in CHILD_ENVIRONMENT || name.startsWith("LC_")

    /**
     * Narrow [environment] (e.g. `ProcessBuilder.environment()`) to the variables a snippet JVM or
     * its compile step may see.
     */
    public fun retainChildEnvironment(environment: MutableMap<String, String>) {
      environment.keys.retainAll(::isChildEnvironmentName)
    }

    /**
     * `env -i NAME=value… ` for the allowlisted subset of [parentEnvironment], stably ordered;
     * empty without `env(1)`.
     */
    internal fun environmentCommand(
      parentEnvironment: Map<String, String>,
      unix: Boolean = File.separatorChar == '/',
    ): List<String> {
      if (!unix) return emptyList()
      val kept = parentEnvironment.toMutableMap().also(::retainChildEnvironment).toSortedMap()
      return listOf("env", "-i") + kept.map { (name, value) -> "$name=$value" }
    }

    /**
     * Parse `--playground-sandbox`: a profile id (`none`, `unshare`, `bwrap`, `systemd`, `strict`)
     * or `custom:<argv>` (whitespace-separated prefix). Null or blank ⇒ [NONE].
     */
    public fun parseProfile(spec: String?): Result<PlaygroundSandbox> {
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
     * Validate the resource knobs so a typo fails at startup rather than as a daemon that never
     * starts.
     */
    public fun validate(sandbox: PlaygroundSandbox): Result<PlaygroundSandbox> {
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
 * The `--public` admission decision for the playground lane (PLAYGROUND.md §6).
 *
 * The host never runs untrusted code itself, so under `--public` the lane opens only on evidence:
 * 1. a sandbox profile is configured (`none` is refused),
 * 2. the startup [probe][PlaygroundSandboxProbe] ran inside that jail and found egress blocked, the
 *    host filesystem contained and the process namespace isolated, and
 * 3. the jail caps CPU and process count, which the probe can't measure; `unshare` / `bwrap` alone
 *    are refused and pointed at `strict`, while a `custom:` jail is taken at its word on caps.
 *
 * Claims are never enough, and an absent probe report is a refusal.
 *
 * Second posture: when GitHub auth is configured, all playground routes already require write
 * access to `--github-auth-repo`, the same trust level as the token-gated posture. [decide] then
 * admits the lane via [repoAccessGated], still applying any configured sandbox. Anonymous and
 * uncontained is never admitted; [Decision.Allow.detail] names the posture that admitted it.
 */
public object PlaygroundPublicGate {

  public sealed interface Decision {
    /** The lane may serve; [detail] is the startup log line. */
    public data class Allow(val detail: String) : Decision

    /** The lane stays disabled; [reason] is printed and is actionable. */
    public data class Refuse(val reason: String) : Decision
  }

  /**
   * Decide whether the playground may serve. Not [isPublic] ⇒ always allowed (sandbox still
   * applied). [repoAccessGated] (GitHub auth configured) admits a public box without containment;
   * see the class docs.
   */
  public fun decide(
    isPublic: Boolean,
    repoAccessGated: Boolean,
    sandbox: PlaygroundSandbox,
    probe: PlaygroundSandboxProbe.Report?,
  ): Decision {
    if (!isPublic) {
      return Decision.Allow(
        if (sandbox.isActive) "token-gated; ${sandbox.describe()}"
        else "token-gated; no sandbox (add --playground-sandbox to rehearse the public posture)"
      )
    }
    // Repo collaborators only: containment becomes defence in depth, not a precondition.
    if (repoAccessGated) {
      return Decision.Allow(
        "public host, repo-access-gated (GitHub sign-in with write access to --github-auth-repo); " +
          if (sandbox.isActive) "${sandbox.describe()} — defence in depth, not the admission basis"
          else
            "no sandbox — a collaborator's snippet runs unconfined on this host " +
              "(add --playground-sandbox for defence in depth)"
      )
    }
    if (!sandbox.isActive) {
      // Anonymous and uncontained: refuse, naming both remedies.
      return Decision.Refuse(
        "--playground-bundle / --playground-android-bundle under --public need EITHER GitHub " +
          "repo-access gating (--github-auth-client-id / --github-auth-client-secret / " +
          "--github-auth-cookie-secret / --github-auth-repo), which limits the lane to repo " +
          "collaborators, OR a per-session sandbox: --playground-sandbox " +
          "<bwrap|strict|systemd|unshare|custom:…>. With neither, an anonymous stranger's snippet " +
          "would run unconfined on the server (PLAYGROUND.md §6)."
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
    // Containment proven, but the probe can't measure CPU or process caps.
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
    return Decision.Allow(
      "public and ANONYMOUS (no GitHub auth configured); verified ${sandbox.describe()}$caveat. " +
        "Admitted on containment alone — configure --github-auth-repo to also bound who can " +
        "reach the lane."
    )
  }
}
