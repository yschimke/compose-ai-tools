package ee.schimke.composeai.cli

/**
 * "Can the render JVM actually `dlopen` skiko?" — the check behind `env.desktop-natives`.
 *
 * The CMP Desktop renderer runs Skia through `libskiko-linux-x64.so`, which the JVM extracts to
 * `~/.skiko/<hash>/` on first use. That `.so` carries four direct `DT_NEEDED` entries — see
 * [REQUIRED_LIBS] — and none of them ship with the JDK. When one can't be resolved, *every* preview
 * in the module fails with the same line:
 * ```
 * UnsatisfiedLinkError: …/libskiko-linux-x64.so: libGL.so.1: cannot open shared object file
 * ```
 *
 * which reads like a missing package and usually isn't. Two things make this trap worse than it
 * looks, and both are why this check exists rather than a one-line "is libGL installed?":
 *
 * 1. **`ldconfig -p` and `ldd` can both say yes while the render still fails.** A JDK installed
 *    from a Nix (or Guix) store is patchelf'd to that store's `ld-linux`, which does **not** read
 *    `/etc/ld.so.cache` and does **not** search `/usr/lib/<triple>`. So a container can ship a
 *    perfectly good `/usr/lib/x86_64-linux-gnu/libGL.so.1`, have `ldd` resolve it happily (ldd runs
 *    the *system* loader), and still have the render JVM fail — the two processes don't use the
 *    same loader. [loaderReadsSystemCache] is what encodes that: when the render JVM lives in a
 *    store, only `LD_LIBRARY_PATH` counts.
 * 2. **`LD_LIBRARY_PATH` has to be *exported*, all the way down to the render subprocess.** The
 *    render forks from the Gradle daemon, which inherits from the Gradle client — a shell that sets
 *    the variable without exporting it (or a daemon started *before* the variable was set) leaves
 *    the render JVM with nothing. Hence [evaluateDesktopNatives] reads the env, not a shell
 *    snapshot, and the remediation says `./gradlew --stop`.
 *
 * The evaluation is a pure function over injected inputs so it can be unit-tested without a Nix
 * store, an `ldconfig`, or a real filesystem; [DoctorCommand] supplies the live values.
 */
internal object DesktopNativesCheck {

  /**
   * Direct `DT_NEEDED` entries of `libskiko-linux-x64.so`, minus the three every JVM already has
   * (`libm`, `libc`, `ld-linux`). Read off the shipped binary with `readelf -d`; re-check when
   * bumping the CMP / skiko version, since a new skiko can grow a dependency.
   *
   * `libfreetype.so.6` is deliberately *not* listed: skiko doesn't link it directly, it arrives
   * transitively through fontconfig, so probing for it adds a false-negative mode (a distro that
   * statically links freetype into fontconfig) without catching anything the fontconfig probe
   * misses.
   */
  val REQUIRED_LIBS =
    listOf(
      "libGL.so.1" to "OpenGL — skiko links it even for offscreen/software rendering",
      "libX11.so.6" to "X11 client library, pulled in by skiko's AWT integration",
      "libfontconfig.so.1" to "font enumeration; also brings freetype in transitively",
      "libstdc++.so.6" to "C++ runtime Skia itself is built against",
    )

  /**
   * Directories the *system* `ld.so` searches after `LD_LIBRARY_PATH`, in its own order. Only
   * consulted when [loaderReadsSystemCache] is true. `x86_64-linux-gnu` covers Debian/Ubuntu's
   * multiarch layout; `/usr/lib64` covers Fedora/RHEL/SUSE.
   */
  val SYSTEM_LIB_DIRS =
    listOf("/usr/lib/x86_64-linux-gnu", "/lib/x86_64-linux-gnu", "/usr/lib64", "/lib64", "/usr/lib")

  /**
   * Path prefixes whose JDKs are patchelf'd to a private `ld-linux` that ignores
   * `/etc/ld.so.cache`. Matching is on the *resolved* `java.home`, so a `~/.nix-profile/bin/java`
   * symlink into the store is caught too.
   */
  private val STORE_PREFIXES = listOf("/nix/store/", "/gnu/store/")

  /** One resolved-or-not native dependency. */
  data class LibStatus(
    val soname: String,
    val purpose: String,
    /** Absolute path the render JVM's loader would find, or `null` when nothing resolves. */
    val resolvedAt: String?,
    /** True when [resolvedAt] came from `LD_LIBRARY_PATH` rather than the system search path. */
    val viaLdLibraryPath: Boolean,
  )

  data class Result(
    /** False on non-Linux hosts, where skiko bundles / links what it needs. */
    val applicable: Boolean,
    val libs: List<LibStatus>,
    /** Entries of `LD_LIBRARY_PATH` as the render JVM would see it (empty when unset). */
    val ldLibraryPath: List<String>,
    /** See the class doc — false for store-provided JDKs, which ignore `/etc/ld.so.cache`. */
    val loaderReadsSystemCache: Boolean,
    /** `java.home` of the JVM the render forks into, as reported by Gradle. */
    val renderJavaHome: String?,
    /**
     * `LD_LIBRARY_PATH` entries that resolve into a Nix/Guix store. Only interesting alongside a
     * *non*-store render JVM — see [glibcSkew].
     */
    val storeDirsOnPath: List<String> = emptyList(),
  ) {
    val missing: List<LibStatus>
      get() = libs.filter { it.resolvedAt == null }

    /**
     * The hybrid-userland trap of issue #3690: store libraries on the search path of a render JVM
     * that is linked against the *system* glibc.
     *
     * Every lib below resolves, so [missing] is empty and this check used to report `ok` — while
     * every preview in the module died at `dlopen`, because a store `libGL.so.1` drags the store's
     * own (newer) glibc into a process that already holds the system one:
     * ```
     * /lib/x86_64-linux-gnu/libc.so.6: version `GLIBC_ABI_DT_X86_64_PLT' not found
     *   (required by /nix/store/…-glibc-2.42-67/lib/libpthread.so.0)
     * ```
     *
     * The reverse pairing (system dirs on a store JVM's path) is not flagged: that is the
     * documented remediation below, and the store loader needs `LD_LIBRARY_PATH` to find anything
     * at all.
     */
    val glibcSkew: Boolean
      get() = applicable && loaderReadsSystemCache && storeDirsOnPath.isNotEmpty()

    val ok: Boolean
      get() = !applicable || (missing.isEmpty() && !glibcSkew)
  }

  /**
   * Resolve each of [REQUIRED_LIBS] the way the render JVM's dynamic loader would.
   *
   * @param osName `System.getProperty("os.name")`.
   * @param renderJavaHome `java.home` of the JVM that forks the render — the Gradle daemon's JVM in
   *   practice (doctor already fetches it via `BuildEnvironment`). Null falls back to assuming a
   *   system loader, which is the lenient direction: we'd rather under-report than cry wolf.
   * @param ldLibraryPath the raw `LD_LIBRARY_PATH` *environment variable* (not a shell variable) as
   *   inherited by this process. This is the same value the Gradle daemon and the render subprocess
   *   inherit, which is exactly what makes an unexported variable detectable here.
   * @param exists existence predicate for an absolute path — injected so tests needn't touch disk.
   */
  fun evaluateDesktopNatives(
    osName: String,
    renderJavaHome: String?,
    ldLibraryPath: String?,
    exists: (String) -> Boolean,
    canonicalize: (String) -> String = { it },
  ): Result {
    val linux = osName.lowercase().contains("linux")
    val searchDirs = ldLibraryPath.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }
    val readsCache = loaderReadsSystemCache(renderJavaHome)
    // Resolved, not as written: a store lib dir is routinely reached through a symlink farm
    // (`~/.cache/coo-ee/desktop-gl/lib`, `~/.nix-profile/lib`), and only the resolved path admits
    // where it came from.
    val storeDirs = searchDirs.filter { dir ->
      STORE_PREFIXES.any { canonicalize(dir).startsWith(it) }
    }
    if (!linux) {
      return Result(
        applicable = false,
        libs = emptyList(),
        ldLibraryPath = searchDirs,
        loaderReadsSystemCache = readsCache,
        renderJavaHome = renderJavaHome,
        storeDirsOnPath = storeDirs,
      )
    }

    val libs = REQUIRED_LIBS.map { (soname, purpose) ->
      val fromLdPath = searchDirs.firstNotNullOfOrNull { dir -> "$dir/$soname".takeIf(exists) }
      // System dirs only count when the loader would actually look there. A store JDK won't,
      // which is the whole point of the distinction — see the class doc.
      val fromSystem =
        if (fromLdPath != null || !readsCache) null
        else SYSTEM_LIB_DIRS.firstNotNullOfOrNull { dir -> "$dir/$soname".takeIf(exists) }
      LibStatus(
        soname = soname,
        purpose = purpose,
        resolvedAt = fromLdPath ?: fromSystem,
        viaLdLibraryPath = fromLdPath != null,
      )
    }

    return Result(
      applicable = true,
      libs = libs,
      ldLibraryPath = searchDirs,
      loaderReadsSystemCache = readsCache,
      renderJavaHome = renderJavaHome,
      storeDirsOnPath = storeDirs,
    )
  }

  /**
   * Whether the loader backing [javaHome] consults `/etc/ld.so.cache` and the system library dirs.
   * False for Nix/Guix store JDKs (see class doc); true for everything else, including a null
   * [javaHome] — an unknown JVM is assumed conventional so we don't invent failures.
   */
  fun loaderReadsSystemCache(javaHome: String?): Boolean {
    val home = javaHome ?: return true
    return STORE_PREFIXES.none { home.startsWith(it) }
  }

  /**
   * Turn a [Result] into the `env.desktop-natives` check. Split out from [DoctorCommand] so the
   * message/remediation wording is unit-testable, matching how [interpretDaemonSmoke] is
   * structured.
   *
   * [inClaudeCloud] only changes the phrasing of the fix — cloud sandboxes reach this through a
   * session-start script, local machines through their package manager.
   */
  fun interpret(result: Result, inClaudeCloud: Boolean): DoctorCheck {
    val id = "env.desktop-natives"
    if (!result.applicable) {
      return DoctorCheck(
        id = id,
        category = "env",
        status = "skipped",
        message = "skiko native deps not checked (non-Linux host)",
      )
    }

    val storeNote =
      if (!result.loaderReadsSystemCache) {
        "The render JVM (${result.renderJavaHome}) comes from a Nix/Guix store, so its loader " +
          "ignores /etc/ld.so.cache and /usr/lib — only LD_LIBRARY_PATH counts. `ldd` and " +
          "`ldconfig -p` use the *system* loader and will look fine even when the render fails."
      } else null

    if (result.glibcSkew) {
      // Deliberately a warning rather than an error: the Gradle plugin prunes these directories
      // from the render JVM's own environment (`RenderNativeEnv`), so a render driven through it
      // works on this box as it stands. Anything else that starts a JVM from this environment — a
      // bare `java -jar`, an IDE, another Gradle plugin — still gets the mixed process image, so
      // the state is worth reporting rather than hiding.
      return DoctorCheck(
        id = id,
        category = "env",
        status = "warning",
        message =
          "LD_LIBRARY_PATH mixes package-store libraries into a system render JVM " +
            "(${result.storeDirsOnPath.size} store dir(s))",
        detail =
          buildString {
            append("Store dirs on LD_LIBRARY_PATH: ")
            append(result.storeDirsOnPath.joinToString(", "))
            append(". The render JVM (")
            append(result.renderJavaHome ?: "unknown")
            append(") is not from the store, so it starts with the system glibc; a store libGL ")
            append("pulls the store's own (newer) glibc into the same process and the loader ")
            append("refuses it: `libc.so.6: version GLIBC_… not found (required by ")
            append("/nix/store/…/libpthread.so.0)`. Every preview in the module then fails — the ")
            append("first with ExceptionInInitializerError, the rest with `Could not initialize ")
            append("class org.jetbrains.skia.Surface`. The compose-preview Gradle plugin drops ")
            append("these directories for its own render JVM, so renders driven through it are ")
            append("unaffected.")
          },
        remediation =
          DoctorRemediation(
            summary =
              "Give the store libraries to a store JDK, or keep them off a system JDK's " +
                "LD_LIBRARY_PATH — don't mix the two in one process.",
            commands =
              buildList {
                add("# Either: render on the JDK that matches the libraries (a store JDK),")
                add("#   e.g. pin it for Gradle:")
                add("#   org.gradle.java.home=/nix/store/…-temurin-bin-17…")
                add("# Or: keep the store dirs off the render JVM's environment and let the")
                add("#   system loader supply the libs:")
                add("apt-get install -y libgl1 libx11-6 libfontconfig1 libstdc++6")
                add("unset LD_LIBRARY_PATH")
                add("# The Gradle daemon caches its environment — restart it after changing it:")
                add("./gradlew --stop")
                add("# Failed renders are up-to-date task outputs; force the retry:")
                add("./gradlew :<module>:composePreviewRender --rerun")
              },
            docs = "https://github.com/$REPO/blob/main/docs/DESKTOP_NATIVE_DEPS.md",
          ),
      )
    }

    if (result.ok) {
      val viaEnv = result.libs.count { it.viaLdLibraryPath }
      return DoctorCheck(
        id = id,
        category = "env",
        status = "ok",
        message = "skiko native deps resolvable (${result.libs.size} libs)",
        detail =
          buildString {
            append(result.libs.joinToString("; ") { "${it.soname} → ${it.resolvedAt}" })
            if (viaEnv > 0) append(". $viaEnv via LD_LIBRARY_PATH=${result.ldLibraryPath}")
            storeNote?.let { append(". $it") }
          },
      )
    }

    val missing = result.missing
    return DoctorCheck(
      id = id,
      category = "env",
      status = "error",
      message =
        "CMP Desktop renders will fail — ${missing.size} skiko native dep(s) unresolvable: " +
          missing.joinToString(", ") { it.soname },
      detail =
        buildString {
          append(missing.joinToString("; ") { "${it.soname} (${it.purpose})" })
          append(". Symptom: every preview in the module fails with `UnsatisfiedLinkError: ")
          append(
            "…/libskiko-linux-x64.so: ${missing.first().soname}: cannot open shared object file`"
          )
          append(". LD_LIBRARY_PATH as inherited by this process: ")
          append(if (result.ldLibraryPath.isEmpty()) "(unset)" else result.ldLibraryPath.toString())
          storeNote?.let { append(". $it") }
        },
      remediation =
        DoctorRemediation(
          summary =
            if (inClaudeCloud)
              "Provision the Compose Desktop native libs in the session-start script and export " +
                "LD_LIBRARY_PATH so the Gradle daemon and the render subprocess inherit it."
            else
              "Install the libs, and make sure LD_LIBRARY_PATH is exported if they live off the " +
                "system search path.",
          commands =
            buildList {
              add("# Debian/Ubuntu:")
              add("apt-get install -y libgl1 libx11-6 libfontconfig1 libstdc++6")
              if (!result.loaderReadsSystemCache) {
                add("# The render JVM ignores system lib dirs — point it at them explicitly:")
                add(
                  "export LD_LIBRARY_PATH=/usr/lib/x86_64-linux-gnu\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
                )
                add("# …or render on a JDK outside the store (e.g. /usr/lib/jvm/…).")
              }
              add("# The Gradle daemon caches its environment — restart it after changing either:")
              add("./gradlew --stop")
              add("# Failed renders are up-to-date task outputs; force the retry:")
              add("./gradlew :<module>:composePreviewRender --rerun")
            },
          docs = "https://github.com/$REPO/blob/main/docs/DESKTOP_NATIVE_DEPS.md",
        ),
    )
  }
}
