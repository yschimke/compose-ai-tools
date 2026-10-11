package ee.schimke.composeai.cli

/**
 * "Can the render JVM actually `dlopen` skiko?" — the check behind `env.desktop-natives`.
 *
 * CMP Desktop renders through `libskiko-linux-x64.so`, whose [REQUIRED_LIBS] don't ship with the
 * JDK; one missing fails every preview with:
 * ```
 * UnsatisfiedLinkError: …/libskiko-linux-x64.so: libGL.so.1: cannot open shared object file
 * ```
 *
 * A simple "is libGL installed?" isn't enough:
 * 1. A Nix/Guix store JDK uses the store's `ld-linux`, which ignores `/etc/ld.so.cache` and the
 *    system lib dirs, so `ldd` can succeed while the render fails ([loaderReadsSystemCache]).
 * 2. `LD_LIBRARY_PATH` must be exported all the way to the render subprocess (forked from the
 *    Gradle
 *    daemon), so the environment is read, and the remediation includes `./gradlew --stop`.
 *
 * The evaluation is pure over injected inputs for testing; [DoctorCommand] supplies live values.
 */
internal object DesktopNativesCheck {

  /**
   * Direct `DT_NEEDED` entries of `libskiko-linux-x64.so` (from `readelf -d`), minus `libm`, `libc`
   * and `ld-linux`. Re-check when bumping skiko. `libfreetype.so.6` is omitted: it arrives via
   * fontconfig, and probing it directly only adds false negatives.
   */
  val REQUIRED_LIBS =
    listOf(
      "libGL.so.1" to "OpenGL — skiko links it even for offscreen/software rendering",
      "libX11.so.6" to "X11 client library, pulled in by skiko's AWT integration",
      "libfontconfig.so.1" to "font enumeration; also brings freetype in transitively",
      "libstdc++.so.6" to "C++ runtime Skia itself is built against",
    )

  /**
   * Directories the system `ld.so` searches after `LD_LIBRARY_PATH`, in order; only used when
   * [loaderReadsSystemCache]. Covers Debian multiarch and `/usr/lib64` distros.
   */
  val SYSTEM_LIB_DIRS =
    listOf("/usr/lib/x86_64-linux-gnu", "/lib/x86_64-linux-gnu", "/usr/lib64", "/lib64", "/usr/lib")

  /**
   * Prefixes of JDKs patchelf'd to a private `ld-linux`. Matched on the resolved `java.home`, so
   * profile symlinks into the store count.
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
     * `LD_LIBRARY_PATH` entries that resolve into a Nix/Guix store; relevant only with a non-store
     * JVM ([glibcSkew]).
     */
    val storeDirsOnPath: List<String> = emptyList(),
  ) {
    val missing: List<LibStatus>
      get() = libs.filter { it.resolvedAt == null }

    /**
     * Store libraries on the search path of a JVM linked against system glibc: everything resolves,
     * but a store `libGL.so.1` pulls in the store's newer glibc and `dlopen` fails:
     * ```
     * /lib/x86_64-linux-gnu/libc.so.6: version `GLIBC_ABI_DT_X86_64_PLT' not found
     *   (required by /nix/store/…-glibc-2.42-67/lib/libpthread.so.0)
     * ```
     * The reverse (system dirs on a store JVM's path) is the documented remediation, not flagged.
     */
    val glibcSkew: Boolean
      get() = applicable && loaderReadsSystemCache && storeDirsOnPath.isNotEmpty()

    val ok: Boolean
      get() = !applicable || (missing.isEmpty() && !glibcSkew)
  }

  /**
   * Resolve each of [REQUIRED_LIBS] as the render JVM's dynamic loader would.
   *
   * @param osName `System.getProperty("os.name")`.
   * @param renderJavaHome `java.home` of the JVM that forks the render (the Gradle daemon's). Null
   *   assumes a system loader, erring toward under-reporting.
   * @param ldLibraryPath the inherited `LD_LIBRARY_PATH` environment variable, the same value the
   *   daemon and render subprocess inherit, so an unexported variable shows as unset.
   * @param exists existence predicate for an absolute path, injected for tests.
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
    // Resolved, since store lib dirs are often reached through symlink farms.
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
      // System dirs only count when the loader reads them; a store JDK's doesn't.
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
   * Whether [javaHome]'s loader consults `/etc/ld.so.cache` and the system dirs: false for Nix/Guix
   * store JDKs, true otherwise (including null, so failures aren't invented).
   */
  fun loaderReadsSystemCache(javaHome: String?): Boolean {
    val home = javaHome ?: return true
    return STORE_PREFIXES.none { home.startsWith(it) }
  }

  /**
   * Turn a [Result] into the `env.desktop-natives` check; split out so the wording is testable
   * (like [interpretDaemonSmoke]). [inClaudeCloud] only changes the remediation phrasing.
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

    // Only when nothing is missing: a skew is a warning (the plugin prunes store dirs for its
    // render JVM), a missing library is an error, and the error must not be hidden behind the
    // warning.
    if (result.glibcSkew && result.missing.isEmpty()) {
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
          // Both at once: the missing library owns the status, but name the skew too.
          if (result.glibcSkew) {
            append(
              ". Also on this box: LD_LIBRARY_PATH mixes ${result.storeDirsOnPath.size} " +
                "package-store director(ies) into a system render JVM " +
                "(${result.storeDirsOnPath.joinToString(", ")}), which is its own failure mode — " +
                "see docs/DESKTOP_NATIVE_DEPS.md."
            )
          }
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
