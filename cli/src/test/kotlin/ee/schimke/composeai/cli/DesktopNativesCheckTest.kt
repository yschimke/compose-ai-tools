package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the two failure modes that motivated `env.desktop-natives` — a store JDK that can't see
 * system libs, and an `LD_LIBRARY_PATH` that was set but never exported — plus the happy paths, so
 * a refactor can't quietly turn the check into a no-op.
 */
class DesktopNativesCheckTest {

  private val systemLibs =
    DesktopNativesCheck.REQUIRED_LIBS.map { (soname, _) -> "/usr/lib/x86_64-linux-gnu/$soname" }
      .toSet()

  private val storeLibs =
    DesktopNativesCheck.REQUIRED_LIBS.map { (soname, _) -> "/opt/gl/lib/$soname" }.toSet()

  private fun evaluate(
    javaHome: String?,
    ldLibraryPath: String?,
    present: Set<String> = systemLibs,
    osName: String = "Linux",
  ) =
    DesktopNativesCheck.evaluateDesktopNatives(
      osName = osName,
      renderJavaHome = javaHome,
      ldLibraryPath = ldLibraryPath,
      exists = { it in present },
    )

  @Test
  fun `system jdk resolves libs from the system search path`() {
    val result = evaluate(javaHome = "/usr/lib/jvm/temurin-21", ldLibraryPath = null)

    assertTrue(result.ok)
    assertTrue(result.loaderReadsSystemCache)
    assertEquals(emptyList(), result.missing)
    assertTrue(result.libs.none { it.viaLdLibraryPath })
  }

  @Test
  fun `nix store jdk cannot see system libs`() {
    // The regression this check exists for: the libs are installed and `ldd` resolves them, but the
    // render JVM's loader never looks in /usr/lib, so every preview dies on UnsatisfiedLinkError.
    val result = evaluate(javaHome = "/nix/store/abc123-temurin-bin-17.0.19", ldLibraryPath = null)

    assertFalse(result.ok)
    assertFalse(result.loaderReadsSystemCache)
    assertEquals(DesktopNativesCheck.REQUIRED_LIBS.size, result.missing.size)
  }

  @Test
  fun `nix store jdk resolves libs handed to it via LD_LIBRARY_PATH`() {
    val result =
      evaluate(
        javaHome = "/nix/store/abc123-temurin-bin-17.0.19",
        ldLibraryPath = "/opt/gl/lib",
        present = storeLibs,
      )

    assertTrue(result.ok)
    assertTrue(result.libs.all { it.viaLdLibraryPath })
  }

  @Test
  fun `unexported LD_LIBRARY_PATH reads as unset and fails a store jdk`() {
    // A shell that assigns without exporting leaves System.getenv() null — the same thing the
    // Gradle daemon and the render subprocess see.
    val result =
      evaluate(
        javaHome = "/nix/store/abc123-temurin-bin-17.0.19",
        ldLibraryPath = null,
        present = storeLibs,
      )

    assertFalse(result.ok)
    assertEquals(emptyList(), result.ldLibraryPath)
  }

  @Test
  fun `a single missing lib is enough to fail`() {
    val result =
      evaluate(
        javaHome = "/usr/lib/jvm/temurin-21",
        ldLibraryPath = null,
        present = systemLibs.filterNot { it.endsWith("libGL.so.1") }.toSet(),
      )

    assertFalse(result.ok)
    assertEquals(listOf("libGL.so.1"), result.missing.map { it.soname })
  }

  @Test
  fun `non-linux hosts are skipped`() {
    val result =
      evaluate(
        javaHome = "/Library/Java/JavaVirtualMachines/21",
        ldLibraryPath = null,
        osName = "Mac OS X",
      )

    assertFalse(result.applicable)
    assertTrue(result.ok)
    assertEquals("skipped", DesktopNativesCheck.interpret(result, inClaudeCloud = false).status)
  }

  @Test
  fun `guix store jdks get the same treatment as nix`() {
    assertFalse(DesktopNativesCheck.loaderReadsSystemCache("/gnu/store/xyz-openjdk-21"))
    assertTrue(DesktopNativesCheck.loaderReadsSystemCache(null))
    assertTrue(DesktopNativesCheck.loaderReadsSystemCache("/opt/java/openjdk"))
  }

  @Test
  fun `interpret names the missing lib and offers the store-specific fix`() {
    val result = evaluate(javaHome = "/nix/store/abc123-jdk", ldLibraryPath = null)

    val check = DesktopNativesCheck.interpret(result, inClaudeCloud = true)

    assertEquals("env.desktop-natives", check.id)
    assertEquals("error", check.status)
    assertTrue(check.message.contains("libGL.so.1"))
    assertTrue(check.detail!!.contains("UnsatisfiedLinkError"))
    // The daemon-restart and --rerun steps are the two that people miss after fixing the libs.
    val commands = check.remediation!!.commands
    assertTrue(commands.any { it.contains("--stop") })
    assertTrue(commands.any { it.contains("--rerun") })
    assertTrue(commands.any { it.contains("LD_LIBRARY_PATH") })
  }

  @Test
  fun `store libs on a system JVM's path are reported even though every lib resolves`() {
    // Issue #3690: nothing is *missing* — the store dir supplies all four libs — but the render
    // JVM is an Ubuntu JDK, so loading them drags the store's glibc into a system-glibc process
    // and every preview dies. The old check called this `ok`.
    val result =
      DesktopNativesCheck.evaluateDesktopNatives(
        osName = "Linux",
        renderJavaHome = "/usr/lib/jvm/java-21-openjdk-amd64",
        ldLibraryPath = "/root/.cache/coo-ee/desktop-gl/lib",
        exists = { it.startsWith("/root/.cache/coo-ee/desktop-gl/lib/") },
        // The path is a symlink farm; only the resolved target admits it is a store.
        canonicalize = { "/nix/store/6ljs-cooee-desktop-gl/lib" },
      )

    assertTrue(result.missing.isEmpty())
    assertTrue(result.glibcSkew)
    assertFalse(result.ok)

    val check = DesktopNativesCheck.interpret(result, inClaudeCloud = true)
    assertEquals("warning", check.status)
    assertTrue(check.detail!!.contains("GLIBC_"))
    assertTrue(check.remediation!!.commands.any { it.contains("--stop") })
  }

  @Test
  fun `a missing lib outranks a glibc skew, so doctor still reports an error`() {
    // Both conditions on one box. The skew is survivable — the plugin prunes the store dirs for
    // its own render JVM — while a library the loader cannot find at all means nothing renders by
    // any route. Reporting the warning would exit 0 on a project whose previews cannot render.
    val result =
      DesktopNativesCheck.evaluateDesktopNatives(
        osName = "Linux",
        renderJavaHome = "/usr/lib/jvm/java-21-openjdk-amd64",
        ldLibraryPath = "/root/.cache/coo-ee/desktop-gl/lib",
        // The store dir supplies everything except libGL, so the skew *and* a missing lib.
        exists = {
          it.startsWith("/root/.cache/coo-ee/desktop-gl/lib/") && !it.endsWith("libGL.so.1")
        },
        canonicalize = { "/nix/store/6ljs-cooee-desktop-gl/lib" },
      )

    assertTrue(result.glibcSkew)
    assertEquals(listOf("libGL.so.1"), result.missing.map { it.soname })

    val check = DesktopNativesCheck.interpret(result, inClaudeCloud = true)
    assertEquals("error", check.status)
    assertTrue(check.message.contains("libGL.so.1"))
    // The skew is not dropped on the floor — fixing the missing lib would otherwise leave a
    // second, differently-shaped failure waiting behind it.
    assertTrue(check.detail!!.contains("package-store"))
  }

  @Test
  fun `store libs on a store JVM's path are the documented fix, not a warning`() {
    val result =
      DesktopNativesCheck.evaluateDesktopNatives(
        osName = "Linux",
        renderJavaHome = "/nix/store/abc123-temurin-bin-17.0.19",
        ldLibraryPath = "/root/.cache/coo-ee/desktop-gl/lib",
        exists = { it.startsWith("/root/.cache/coo-ee/desktop-gl/lib/") },
        canonicalize = { "/nix/store/6ljs-cooee-desktop-gl/lib" },
      )

    assertFalse(result.glibcSkew)
    assertTrue(result.ok)
    assertEquals("ok", DesktopNativesCheck.interpret(result, inClaudeCloud = true).status)
  }

  @Test
  fun `interpret reports where each lib resolved when healthy`() {
    val result = evaluate(javaHome = "/usr/lib/jvm/temurin-21", ldLibraryPath = null)

    val check = DesktopNativesCheck.interpret(result, inClaudeCloud = false)

    assertEquals("ok", check.status)
    assertTrue(check.detail!!.contains("/usr/lib/x86_64-linux-gnu/libGL.so.1"))
    assertEquals(null, check.remediation)
  }
}
