package ee.schimke.composeai.renderer

import java.util.concurrent.atomic.AtomicReference

/**
 * Turns a native-loading failure into one sentence a human can act on, and remembers the first one
 * so the cascade behind it stops pretending to be 1000 unrelated bugs (issue #3690).
 *
 * ## What it is for
 *
 * Skia arrives through `libskiko-linux-x64.so`. When that `.so` cannot be loaded, the *first*
 * preview to touch Skia throws `ExceptionInInitializerError` carrying the real reason, and every
 * later preview in the same JVM throws `NoClassDefFoundError: Could not initialize class
 * org.jetbrains.skia.Surface` carrying nothing at all. On a 1073-preview catalog that is one usable
 * error and 1072 red herrings — the reported experience in issue #3690, where the real cause (a
 * package-store `libpthread` pulled into a system-glibc JVM) was buried under the cascade.
 *
 * So: [diagnose] both *explains* the real failure and *latches* it, and any later "could not
 * initialize class org.jetbrains.skia…" is reported as a cascade of that same cause. The latch is
 * per-JVM, which is exactly the scope of the problem — a fresh render fork rediscovers its own
 * first failure, and a pooled worker carries the one it already found.
 *
 * Pure string work over an already-thrown `Throwable`, so it costs nothing on the happy path and is
 * unit-testable without a broken loader.
 */
internal object NativeLoadDiagnosis {

  /** The first *skiko* native-load explanation seen in this JVM, if any. */
  private val firstFailure = AtomicReference<String?>(null)

  /**
   * Non-skiko native failures already reported in this JVM, so a broken application JNI library
   * gets one log line rather than one per preview. Deliberately separate from [firstFailure]: those
   * failures are not what a `Could not initialize class org.jetbrains.skia…` cascades from, so they
   * must never be mistaken for its cause.
   */
  private val reportedNonSkiko: MutableSet<String> =
    java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

  private const val MAX_REMEMBERED_NON_SKIKO = 64

  /** Roots whose libraries carry the store's own glibc rather than the host's. */
  private val STORE_PREFIXES = listOf("/nix/store/", "/gnu/store/")

  /** `version \`GLIBC_2.38' not found (required by /nix/store/…/libpthread.so.0)`. */
  private val GLIBC_VERSION = Regex("""version `(GLIBC_[^']+)' not found""")

  private val REQUIRED_BY = Regex("""required by ([^\s)]+)""")

  /** `libGL.so.1: cannot open shared object file` — the soname that could not be resolved. */
  private val MISSING_SONAME = Regex("""([\w.+-]+\.so[\w.]*): cannot open shared object file""")

  /**
   * @property text the sentence written to the sidecar and (for the first failure) the build log.
   * @property cascade true when this failure has already been accounted for in this JVM — either an
   *   earlier preview broke Skia and this one inherited the wreckage, or the same native library
   *   already failed to load for a previous preview. Reported on the sidecar all the same — the
   *   panel shows one card at a time, so a card that just says "could not initialize class" is
   *   useless — but logged only once, or the build output becomes the very flood this exists to
   *   collapse.
   */
  data class Diagnosis(val text: String, val cascade: Boolean)

  /**
   * An actionable explanation for [e], or null when it is not a native-loading failure at all (the
   * overwhelmingly common case: a preview that threw on its own merits).
   */
  fun diagnose(e: Throwable): Diagnosis? {
    val chain = causeChain(e)
    // The *informative* link, not the outermost one. skiko wraps the loader's error in its own
    // `LibraryLoadException: Failed to loade library …/libskiko-linux-x64.so`, which names the file
    // and nothing about why — the reason (`version GLIBC_… not found`, `cannot open shared object
    // file`) is on the `UnsatisfiedLinkError` underneath it.
    val natives = chain.filter(::isNativeLoadFailure)
    val informative =
      natives.firstOrNull { namesTheReason(it.message.orEmpty()) } ?: natives.lastOrNull()
    informative?.let { native ->
      // Is this skiko's library, or a preview's own JNI dependency? A preview that calls
      // System.loadLibrary("foo") on a host without libfoo throws the same UnsatisfiedLinkError,
      // and answering it with skiko advice would be wrong twice over — misattributing the failure,
      // and latching it so a later *real* skiko failure is dismissed as a cascade of it.
      val skiko = chain.any(::mentionsSkiko)
      val explanation = explain(native, skiko)
      if (!skiko) {
        // Kept out of the Skia latch — a missing application JNI library says nothing about
        // skiko — but still reported once per JVM. Every preview that touches the same broken
        // library raises the same error, and a warm worker would otherwise print a thousand
        // identical lines: the exact log flood the latch exists to prevent, arriving by a
        // different door.
        return Diagnosis(explanation, cascade = !firstReportOf(explanation))
      }
      // First one wins: later previews in this JVM see the cascade, not another copy of the cause.
      val alreadySeen = !firstFailure.compareAndSet(null, explanation)
      return Diagnosis(explanation, cascade = alreadySeen)
    }
    if (chain.any(::isSkiaInitCascade)) {
      val first = firstFailure.get()
      val text =
        if (first != null) "Cascade of the first native-load failure in this render JVM. $first"
        else
          "Skia failed to initialise earlier in this render JVM, so every preview after it fails " +
            "with `Could not initialize class org.jetbrains.skia…`. The real error is on the " +
            "first preview this JVM tried to draw — look at its `.error.json` sidecar, not this " +
            "one."
      return Diagnosis(text, cascade = true)
    }
    return null
  }

  /**
   * The render JVM's own identity and native-search environment, recorded on every error sidecar.
   *
   * Issue #3690 could not be diagnosed from the sidecars alone: the reporter had four JDKs on the
   * box and no way to tell which one Gradle's toolchain resolution had actually forked, nor whether
   * the worker inherited the `LD_LIBRARY_PATH` they had tried to change. Those two facts are one
   * line each and answer the question without guessing.
   */
  fun runtimeSnapshot(env: (String) -> String? = System::getenv): List<Pair<String, String>> =
    listOf(
      "javaHome" to System.getProperty("java.home").orEmpty(),
      "javaVersion" to System.getProperty("java.version").orEmpty(),
      "javaVendor" to System.getProperty("java.vendor").orEmpty(),
      "osArch" to System.getProperty("os.arch").orEmpty(),
      // Empty string means "inherited nothing", which is itself the answer to "did my export reach
      // the worker?". Recorded verbatim: the entries are what decide which libskiko gets loaded.
      "ldLibraryPath" to env("LD_LIBRARY_PATH").orEmpty(),
    )

  /**
   * Whether [explanation] is being reported for the first time in this JVM. Bounded, because an
   * unbounded set here would be a slow leak in a long-lived worker.
   */
  private fun firstReportOf(explanation: String): Boolean {
    // Clearing rather than refusing, when the bound is reached. Short-circuiting on size would
    // report *every* later failure as already-seen — including one never encountered before — so a
    // genuinely new broken library would be silently dropped from the log forever. Dropping the
    // history instead costs at most one repeated line per distinct failure, which is the direction
    // to err in for something whose whole job is making a failure visible once.
    if (reportedNonSkiko.size >= MAX_REMEMBERED_NON_SKIKO) reportedNonSkiko.clear()
    return reportedNonSkiko.add(explanation)
  }

  /** Test seam — both latches are JVM-global state, so tests must be able to clear them. */
  internal fun resetForTesting() {
    firstFailure.set(null)
    reportedNonSkiko.clear()
  }

  /** Whether a message says *why* the load failed, rather than only which file failed to load. */
  private fun namesTheReason(message: String): Boolean =
    GLIBC_VERSION.containsMatchIn(message) || MISSING_SONAME.containsMatchIn(message)

  private fun isNativeLoadFailure(t: Throwable): Boolean =
    t is UnsatisfiedLinkError ||
      // skiko wraps the loader's error in its own type, and the renderer deliberately does not
      // depend on skiko's API surface for this — match by name.
      t.javaClass.name == "org.jetbrains.skiko.LibraryLoadException"

  /**
   * Whether [t] identifies Skia/skiko — by exception type, message (the `libskiko-…so` path the
   * loader reports), or a frame in skiko's own loader. Checked across the whole cause chain, since
   * the innermost `UnsatisfiedLinkError` names the file while the frames naming skiko sit above it.
   */
  private fun mentionsSkiko(t: Throwable): Boolean {
    val message = t.message.orEmpty()
    if (message.contains("libskiko") || message.contains("skiko-linux")) return true
    if (t.javaClass.name.startsWith("org.jetbrains.skiko")) return true
    return t.stackTrace.any {
      it.className.startsWith("org.jetbrains.skiko") ||
        it.className.startsWith("org.jetbrains.skia")
    }
  }

  private fun isSkiaInitCascade(t: Throwable): Boolean {
    if (t !is NoClassDefFoundError && t !is ExceptionInInitializerError) return false
    val message = t.message.orEmpty()
    return message.contains("org.jetbrains.skia") || message.contains("org.jetbrains.skiko")
  }

  /**
   * @param skiko whether the failure is skiko's own library load. When false this is a preview's
   *   (or a dependency's) JNI library, so the explanation stays about *that* library — the glibc
   *   and missing-soname reasoning is identical, the attribution is not.
   */
  private fun explain(native: Throwable, skiko: Boolean): String {
    val subject = if (skiko) "skiko's native library" else "A native library this preview loads"
    val message = native.message.orEmpty()
    val javaHome = System.getProperty("java.home").orEmpty()
    val ldPath = System.getenv("LD_LIBRARY_PATH").orEmpty()

    val glibcVersion = GLIBC_VERSION.find(message)?.groupValues?.get(1)
    if (glibcVersion != null) {
      val requiredBy = REQUIRED_BY.find(message)?.groupValues?.get(1)
      val fromStore = requiredBy != null && STORE_PREFIXES.any { requiredBy.startsWith(it) }
      return buildString {
        append(subject)
        append(" could not be loaded because this process mixed two glibc ")
        append("builds: the loader wanted `")
        append(glibcVersion)
        append("`")
        requiredBy?.let { append(" for ").append(it) }
        append(", and the C library already in the process does not provide it. ")
        if (fromStore) {
          append("The library that needs it comes from a package store (Nix/Guix), which ships ")
          append("its own glibc, while this render JVM (")
          append(javaHome.ifEmpty { "unknown java.home" })
          append(") is linked against the system one. Store libraries reach a JVM through ")
          append("LD_LIBRARY_PATH — here it was `")
          append(ldPath.ifEmpty { "(unset)" })
          append("`. Either render on a JDK from the same store, or keep the store directories ")
          append("off the render JVM's LD_LIBRARY_PATH; the compose-preview Gradle plugin prunes ")
          append("them automatically for a non-store render JVM (opt out with ")
          append("-Dcomposeai.render.nativeEnv=inherit).")
        } else {
          append("The render JVM (")
          append(javaHome.ifEmpty { "unknown java.home" })
          append(") and the libraries on LD_LIBRARY_PATH (`")
          append(ldPath.ifEmpty { "(unset)" })
          append("`) were built against different glibc versions. Point the render JVM at ")
          append("libraries built for this host, or run it on a JDK matching those libraries.")
        }
      }
    }

    val missing = MISSING_SONAME.find(message)?.groupValues?.get(1)
    if (missing != null) {
      val where =
        "was not found by the render JVM's loader (java.home=${javaHome.ifEmpty { "unknown" }}, " +
          "LD_LIBRARY_PATH=`${ldPath.ifEmpty { "(unset)" }}`)"
      return if (skiko) {
        "$subject could not be loaded: `$missing` $where. It is a direct DT_NEEDED dependency of " +
          "libskiko — install it (Debian/Ubuntu: libgl1, libx11-6, libfontconfig1, libstdc++6) or " +
          "put its directory on the render JVM's LD_LIBRARY_PATH. `compose-preview doctor` " +
          "reports this as env.desktop-natives."
      } else {
        "$subject could not be loaded: `$missing` $where. Install it, or put its directory on the " +
          "render JVM's LD_LIBRARY_PATH."
      }
    }

    return "$subject could not be loaded (java.home=" +
      "${javaHome.ifEmpty { "unknown" }}, LD_LIBRARY_PATH=`${ldPath.ifEmpty { "(unset)" }}`): " +
      message.ifEmpty { native.javaClass.name }
  }

  /** [e] and its causes, cycle-safe and bounded — a malformed chain must not hang a render. */
  private fun causeChain(e: Throwable): List<Throwable> {
    val seen = ArrayList<Throwable>(4)
    var current: Throwable? = e
    while (current != null && seen.size < 16 && seen.none { it === current }) {
      seen += current
      current = current.cause
    }
    return seen
  }
}
