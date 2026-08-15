package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rule from issue #3690: package-store libraries belong to package-store JVMs, and mixing the
 * two kills every preview in the module with a glibc-version error that reads like a skiko bug.
 */
class RenderNativeEnvTest {

  /** Stand-in for the real filesystem: a symlink farm that resolves into a Nix store. */
  private val links =
    mapOf(
      "/root/.cache/coo-ee/desktop-gl/lib" to "/nix/store/6ljs-cooee-desktop-gl/lib",
      "/root/.cache/coo-ee/jdk-gl/17/bin/java" to "/root/.cache/coo-ee/jdk-gl/17/bin/java",
      "/home/me/.nix-profile/bin/java" to "/nix/store/66sp-temurin-bin-17.0.19/bin/java",
    )

  private fun decide(
    renderJavaExecutable: String? = null,
    daemonJavaHome: String? = "/usr/lib/jvm/java-21-openjdk-amd64",
    ldLibraryPath: String?,
    osName: String = "Linux",
    mode: String? = null,
  ) =
    RenderNativeEnv.decide(
      renderJavaExecutable = renderJavaExecutable,
      daemonJavaHome = daemonJavaHome,
      ldLibraryPath = ldLibraryPath,
      osName = osName,
      mode = mode,
      canonicalize = { path -> links[path] ?: path },
    )

  @Test
  fun `store libs are dropped for a system render JVM`() {
    // The exact shape of the reported sandbox: an Ubuntu JDK 21 picked by `jvmToolchain(21)`, with
    // a Nix-built GL directory inherited from the session-start hook.
    val decision =
      decide(
        renderJavaExecutable = "/usr/lib/jvm/java-21-openjdk-amd64/bin/java",
        ldLibraryPath = "/root/.cache/coo-ee/desktop-gl/lib",
      )

    val sanitized = decision as RenderNativeEnv.Decision.Sanitized
    // Nothing survives, so the variable is removed rather than emptied — an empty LD_LIBRARY_PATH
    // is read by glibc as "the current directory", which is not what "no store libs" means.
    assertThat(sanitized.value).isNull()
    assertThat(sanitized.dropped).containsExactly("/root/.cache/coo-ee/desktop-gl/lib")
    assertThat(sanitized.explanation).contains("GLIBC_")
  }

  @Test
  fun `non-store entries are kept in order`() {
    val decision =
      decide(
        renderJavaExecutable = "/usr/lib/jvm/java-21-openjdk-amd64/bin/java",
        ldLibraryPath = "/opt/site/lib:/root/.cache/coo-ee/desktop-gl/lib:/usr/local/lib",
      )

    val sanitized = decision as RenderNativeEnv.Decision.Sanitized
    assertThat(sanitized.value).isEqualTo("/opt/site/lib:/usr/local/lib")
    assertThat(sanitized.kept).containsExactly("/opt/site/lib", "/usr/local/lib").inOrder()
  }

  @Test
  fun `an empty entry is a search location too, and survives pruning`() {
    // glibc reads an empty LD_LIBRARY_PATH element as the current directory. It is not a store
    // path, so "everything else exactly as inherited" has to include it — position and all.
    val decision =
      decide(
        renderJavaExecutable = "/usr/lib/jvm/java-21-openjdk-amd64/bin/java",
        ldLibraryPath = ":/root/.cache/coo-ee/desktop-gl/lib:/opt/lib",
      )

    val sanitized = decision as RenderNativeEnv.Decision.Sanitized
    assertThat(sanitized.value).isEqualTo(":/opt/lib")
    assertThat(sanitized.dropped).containsExactly("/root/.cache/coo-ee/desktop-gl/lib")
  }

  @Test
  fun `a store render JVM keeps everything it inherited`() {
    // LD_LIBRARY_PATH is the *only* channel a patchelf'd store loader reads, and the doctor's own
    // remediation is to point it at /usr/lib/x86_64-linux-gnu. Pruning here would break that.
    val decision =
      decide(
        renderJavaExecutable = "/home/me/.nix-profile/bin/java",
        ldLibraryPath = "/root/.cache/coo-ee/desktop-gl/lib:/usr/lib/x86_64-linux-gnu",
      )

    assertThat(decision).isEqualTo(RenderNativeEnv.Decision.Inherit)
  }

  @Test
  fun `the daemon JVM is the subject when no render executable is pinned`() {
    val storeDaemon =
      decide(
        daemonJavaHome = "/nix/store/66sp-temurin-bin-17.0.19",
        ldLibraryPath = "/root/.cache/coo-ee/desktop-gl/lib",
      )
    assertThat(storeDaemon).isEqualTo(RenderNativeEnv.Decision.Inherit)

    val systemDaemon = decide(ldLibraryPath = "/root/.cache/coo-ee/desktop-gl/lib")
    assertThat(systemDaemon).isInstanceOf(RenderNativeEnv.Decision.Sanitized::class.java)
  }

  @Test
  fun `nothing to do without store dirs, without the variable, or off Linux`() {
    assertThat(decide(ldLibraryPath = "/usr/lib/x86_64-linux-gnu:/opt/lib"))
      .isEqualTo(RenderNativeEnv.Decision.Inherit)
    assertThat(decide(ldLibraryPath = null)).isEqualTo(RenderNativeEnv.Decision.Inherit)
    assertThat(decide(ldLibraryPath = "")).isEqualTo(RenderNativeEnv.Decision.Inherit)
    assertThat(decide(ldLibraryPath = "/root/.cache/coo-ee/desktop-gl/lib", osName = "Mac OS X"))
      .isEqualTo(RenderNativeEnv.Decision.Inherit)
  }

  @Test
  fun `the inherit mode is an escape hatch`() {
    assertThat(
        decide(
          ldLibraryPath = "/root/.cache/coo-ee/desktop-gl/lib",
          mode = RenderNativeEnv.MODE_INHERIT,
        )
      )
      .isEqualTo(RenderNativeEnv.Decision.Inherit)
  }

  @Test
  fun `apply removes the variable when nothing survives and rewrites it otherwise`() {
    val removed = HashMap(mapOf("LD_LIBRARY_PATH" to "/nix/store/x/lib", "PATH" to "/usr/bin"))
    RenderNativeEnv.apply(decide(ldLibraryPath = "/root/.cache/coo-ee/desktop-gl/lib"), removed)
    assertThat(removed).containsExactly("PATH", "/usr/bin")

    val rewritten = HashMap(mapOf("LD_LIBRARY_PATH" to "unused"))
    RenderNativeEnv.apply(
      decide(ldLibraryPath = "/opt/site/lib:/root/.cache/coo-ee/desktop-gl/lib"),
      rewritten,
    )
    assertThat(rewritten["LD_LIBRARY_PATH"]).isEqualTo("/opt/site/lib")

    // Inherit must not touch the map at all — that is what "unchanged" means for every host that
    // isn't in this trap.
    val untouched = HashMap(mapOf("LD_LIBRARY_PATH" to "/usr/lib"))
    RenderNativeEnv.apply(RenderNativeEnv.Decision.Inherit, untouched)
    assertThat(untouched["LD_LIBRARY_PATH"]).isEqualTo("/usr/lib")
  }

  @Test
  fun `rewritten returns a fresh map, or null when there is nothing to change`() {
    // The javaexec lane sets `environment` wholesale rather than mutating it, so it needs the copy
    // — and needs "no change" to be distinguishable from "an empty environment".
    val inherited = mapOf<String, Any>("LD_LIBRARY_PATH" to "/nix/x", "PATH" to "/usr/bin")

    val rewritten =
      RenderNativeEnv.rewritten(
        decide(ldLibraryPath = "/opt/site/lib:/root/.cache/coo-ee/desktop-gl/lib"),
        inherited,
      )
    assertThat(rewritten).containsExactly("LD_LIBRARY_PATH", "/opt/site/lib", "PATH", "/usr/bin")
    // The caller's map is not touched.
    assertThat(inherited["LD_LIBRARY_PATH"]).isEqualTo("/nix/x")

    assertThat(RenderNativeEnv.rewritten(RenderNativeEnv.Decision.Inherit, inherited)).isNull()
  }
}
