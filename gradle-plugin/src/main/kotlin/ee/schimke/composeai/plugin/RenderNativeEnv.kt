package ee.schimke.composeai.plugin

import java.io.File

/**
 * Decides the desktop render JVM's `LD_LIBRARY_PATH`, fixing `UnsatisfiedLinkError …
 * GLIBC_ABI_DT_X86_64_PLT not found` on hybrid Nix-over-Ubuntu sandboxes (#3690).
 *
 * ## The failure
 *
 * On Nix/Guix, store libs are put on `LD_LIBRARY_PATH` for a store JDK (see
 * `docs/DESKTOP_NATIVE_DEPS.md`). That's inherited by every process Gradle forks, including a
 * non-store render JVM, which starts with the system glibc; loading Skia then pulls store `libGL`
 * whose `RUNPATH` brings the store glibc into the same image:
 * ```
 * java.lang.UnsatisfiedLinkError: …/libskiko-linux-x64.so:
 *   /lib/x86_64-linux-gnu/libc.so.6: version `GLIBC_ABI_DT_X86_64_PLT' not found
 *   (required by /nix/store/…-glibc-2.42-67/lib/libpthread.so.0)
 * ```
 * and every preview in the module fails.
 *
 * ## The rule
 *
 * Store libraries belong to store JVMs: for a non-store render JVM, drop store directories from
 * `LD_LIBRARY_PATH` and keep everything else. Its loader finds system libs itself, and if they're
 * missing it fails with the honest `cannot open shared object file` that `compose-preview doctor`
 * diagnoses.
 *
 * One-directional: a store JVM keeps everything, since `LD_LIBRARY_PATH` is its only channel and
 * doctor's remediation points it at system dirs.
 *
 * Pure and injectable for testing; [RenderPreviewsTask] applies the result to both pooled and
 * forked lanes so they agree.
 */
internal object RenderNativeEnv {

  /** Linux-only; other platforms have nothing worth pruning. */
  const val VAR = "LD_LIBRARY_PATH"

  /** `-Dcomposeai.render.nativeEnv=inherit` passes the environment through untouched. */
  const val SYS_PROP_MODE = "composeai.render.nativeEnv"

  const val MODE_INHERIT = "inherit"

  /** Package-store roots whose libraries carry their own glibc. Matched on the *resolved* path. */
  private val STORE_PREFIXES = listOf("/nix/store/", "/gnu/store/")

  sealed interface Decision {
    /** Start the render JVM with the environment exactly as inherited. */
    object Inherit : Decision

    /**
     * Start with [VAR] = [value], or removed entirely when null (an empty value isn't equivalent:
     * glibc reads an empty element as the current directory).
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
   * @param renderJavaExecutable the `java` the render forks into, or null when it runs on the
   *   daemon JVM ([daemonJavaHome] decides).
   * @param daemonJavaHome `java.home` of the daemon JVM, the fallback subject.
   * @param ldLibraryPath the inherited value, verbatim.
   * @param osName `os.name`; non-Linux is left alone.
   * @param mode the [SYS_PROP_MODE] value, if set.
   * @param canonicalize resolves symlinks (injected for tests); profile symlinks only reveal their
   *   store origin once resolved.
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

    val entries = ldLibraryPath.orEmpty().split(':')
    if (entries.none { it.isNotBlank() }) return Decision.Inherit

    // The JVM that actually runs the render: the pinned executable, else the daemon's home.
    val subject = renderJavaExecutable ?: daemonJavaHome ?: return Decision.Inherit
    if (isStorePath(canonicalize(subject))) return Decision.Inherit

    // Blank entries are kept: glibc reads them as the current directory, an unrelated search
    // location.
    val (dropped, kept) = entries.partition { it.isNotBlank() && isStorePath(canonicalize(it)) }
    if (dropped.isEmpty()) return Decision.Inherit

    return Decision.Sanitized(
      // Removed only when nothing survives; an empty string differs from an absent variable.
      value = if (kept.isEmpty()) null else kept.joinToString(":"),
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
   * [decision] applied to a copy of [env], or null when unchanged; `JavaExecSpec.environment` is a
   * whole-map property.
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
  private fun canonicalPathOf(path: String): String = runCatching {
    File(path).canonicalPath
  }
    .getOrDefault(path)
}
