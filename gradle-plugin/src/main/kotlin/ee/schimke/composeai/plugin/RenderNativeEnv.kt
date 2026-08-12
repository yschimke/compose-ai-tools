package ee.schimke.composeai.plugin

import java.io.File

/**
 * What `LD_LIBRARY_PATH` the desktop render JVM should be started with — the fix for "every preview
 * fails with `UnsatisfiedLinkError … version GLIBC_ABI_DT_X86_64_PLT not found`" in hybrid
 * Nix-over-Ubuntu sandboxes (issue #3690).
 *
 * ## The failure
 *
 * A Nix/Guix store ships its own glibc, and a store JDK is patchelf'd to the store's `ld-linux`, so
 * the documented way to give skiko its `DT_NEEDED` libs there is to put a store-built lib directory
 * on `LD_LIBRARY_PATH` (see [DesktopNativesCheck][ee.schimke.composeai.cli] and
 * `docs/DESKTOP_NATIVE_DEPS.md`). That is correct **for a store JVM**. But the variable is
 * inherited by every process the Gradle daemon forks, including a render JVM that is *not* from the
 * store — a `jvmToolchain(21)` that resolves to `/usr/lib/jvm/java-21-openjdk-amd64`, say. That
 * process starts with the system `libc.so.6` (Ubuntu 24.04: 2.39) and then, when Skia loads,
 * `LD_LIBRARY_PATH` pulls store `libGL.so.1` whose `RUNPATH` drags the store's glibc 2.42
 * `libpthread.so.0` into the same image. The loader refuses:
 * ```
 * java.lang.UnsatisfiedLinkError: …/libskiko-linux-x64.so:
 *   /lib/x86_64-linux-gnu/libc.so.6: version `GLIBC_ABI_DT_X86_64_PLT' not found
 *   (required by /nix/store/…-glibc-2.42-67/lib/libpthread.so.0)
 * ```
 *
 * and *every* preview in the module dies — the first with `ExceptionInInitializerError`, the rest
 * with the cascading `NoClassDefFoundError: Could not initialize class org.jetbrains.skia.Surface`.
 *
 * ## The rule
 *
 * Store libraries belong to store JVMs. So: when the render JVM does **not** live in a store, drop
 * the store directories from its `LD_LIBRARY_PATH` and leave everything else exactly as inherited.
 * A non-store JVM's loader reads `/etc/ld.so.cache` and `/usr/lib/<triple>`, so it finds the
 * system's own `libGL`/`libX11`/`fontconfig`/`libstdc++` without help — and if the host genuinely
 * lacks them, it now fails with the honest `libGL.so.1: cannot open shared object file` that
 * `compose-preview doctor` already diagnoses, instead of a glibc-version cascade that reads like a
 * skiko bug.
 *
 * Deliberately one-directional. A store JVM keeps whatever it inherited: `LD_LIBRARY_PATH` is the
 * *only* channel that reaches its loader, and the doctor's own remediation tells people to point it
 * at `/usr/lib/x86_64-linux-gnu` — so pruning system dirs there would break the documented fix for
 * the case this variable exists to serve.
 *
 * Pure and injectable so it is unit-testable without a Nix store; [RenderPreviewsTask] supplies the
 * live values and applies the result to both render lanes (the pooled worker and the per-capture
 * fork), which is what keeps the two lanes from disagreeing about the environment.
 */
internal object RenderNativeEnv {

  /**
   * The variable this reasons about. Linux-only; macOS/Windows have no equivalent worth pruning.
   */
  const val VAR = "LD_LIBRARY_PATH"

  /**
   * Escape hatch: `-Dcomposeai.render.nativeEnv=inherit` passes the environment through untouched,
   * for a host where the store libraries are deliberately the right answer for a non-store JVM.
   */
  const val SYS_PROP_MODE = "composeai.render.nativeEnv"

  const val MODE_INHERIT = "inherit"

  /** Package-store roots whose libraries carry their own glibc. Matched on the *resolved* path. */
  private val STORE_PREFIXES = listOf("/nix/store/", "/gnu/store/")

  sealed interface Decision {
    /** Start the render JVM with the environment exactly as inherited. */
    object Inherit : Decision

    /**
     * Start it with [VAR] replaced by [value], or with the variable removed entirely when [value]
     * is null (every entry was dropped — an empty `LD_LIBRARY_PATH` is not the same thing, since
     * glibc reads an empty path element as the current directory).
     */
    data class Sanitized(
      val value: String?,
      val kept: List<String>,
      val dropped: List<String>,
      /** One line for the build log explaining what was removed and why. */
      val explanation: String,
    ) : Decision
  }

  /**
   * @param renderJavaExecutable absolute path of the `java` the render forks into, or null when the
   *   render runs on the Gradle daemon's own JVM (then [daemonJavaHome] decides).
   * @param daemonJavaHome `java.home` of the current (daemon) JVM — the fallback subject.
   * @param ldLibraryPath the inherited value, verbatim.
   * @param osName `os.name`; anything but Linux is left alone.
   * @param mode the [SYS_PROP_MODE] value, if set.
   * @param canonicalize resolves a path through symlinks — injected so tests need no real store. A
   *   `~/.nix-profile/bin/java` symlink and a `~/.cache/…/desktop-gl/lib` link both only reveal
   *   their store origin once resolved.
   */
  fun decide(
    renderJavaExecutable: String?,
    daemonJavaHome: String?,
    ldLibraryPath: String?,
    osName: String = System.getProperty("os.name").orEmpty(),
    mode: String? = System.getProperty(SYS_PROP_MODE),
    canonicalize: (String) -> String = ::canonicalPathOf,
  ): Decision {
    if (!osName.lowercase().contains("linux")) return Decision.Inherit
    if (mode.equals(MODE_INHERIT, ignoreCase = true)) return Decision.Inherit

    val entries = ldLibraryPath.orEmpty().split(':').filter { it.isNotBlank() }
    if (entries.isEmpty()) return Decision.Inherit

    // The subject is the JVM that will actually run the render: the pinned executable when the
    // plugin raised the render JDK, otherwise the daemon's own home (the historical default).
    val subject = renderJavaExecutable ?: daemonJavaHome ?: return Decision.Inherit
    if (isStorePath(canonicalize(subject))) return Decision.Inherit

    val (dropped, kept) = entries.partition { isStorePath(canonicalize(it)) }
    if (dropped.isEmpty()) return Decision.Inherit

    return Decision.Sanitized(
      value = kept.joinToString(":").ifEmpty { null },
      kept = kept,
      dropped = dropped,
      explanation =
        "dropped ${dropped.size} package-store director${if (dropped.size == 1) "y" else "ies"} " +
          "from $VAR for the render JVM ($subject): ${dropped.joinToString(", ")}. " +
          "Store libraries carry the store's own glibc, and loading them into a JVM linked " +
          "against the system glibc fails every preview with `UnsatisfiedLinkError: … version " +
          "GLIBC_… not found`. The render JVM's loader reads /etc/ld.so.cache, so it finds the " +
          "system libGL/libX11/fontconfig/libstdc++ itself. Pass " +
          "-D$SYS_PROP_MODE=$MODE_INHERIT to keep the inherited value.",
    )
  }

  /** Apply [decision] in place, e.g. to `ProcessBuilder.environment()`. */
  fun apply(decision: Decision, env: MutableMap<String, String>) {
    if (decision !is Decision.Sanitized) return
    val value = decision.value
    if (value == null) env.remove(VAR) else env[VAR] = value
  }

  /**
   * [decision] applied to a copy of [env], or null when there is nothing to change — for
   * `JavaExecSpec.environment`, which is a whole-map property rather than a mutable map.
   */
  fun rewritten(decision: Decision, env: Map<String, Any>): Map<String, Any>? {
    if (decision !is Decision.Sanitized) return null
    val copy = LinkedHashMap(env)
    val value = decision.value
    if (value == null) copy.remove(VAR) else copy[VAR] = value
    return copy
  }

  private fun isStorePath(path: String): Boolean = STORE_PREFIXES.any { path.startsWith(it) }

  /** Best effort: an unresolvable path is judged as written rather than assumed store-free. */
  private fun canonicalPathOf(path: String): String =
    runCatching { File(path).canonicalPath }.getOrDefault(path)
}
