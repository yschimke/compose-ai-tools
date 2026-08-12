package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #3690: one broken `dlopen` produced 1064 failed previews, one of which carried the reason.
 * These pin that the reason is stated in words, and that the other 1063 say so rather than
 * repeating `Could not initialize class org.jetbrains.skia.Surface` a thousand times.
 */
class NativeLoadDiagnosisTest {

  /** The latch is JVM-wide by design, so each test starts from a clean one. */
  @Before
  fun reset() {
    NativeLoadDiagnosis.resetForTesting()
  }

  /**
   * The real shape, transcribed from a reproduction of issue #3690: the outer link error only names
   * the file, and the reason is two causes down.
   */
  private fun glibcSkewFailure(): Throwable {
    val reason =
      UnsatisfiedLinkError(
        "/root/.skiko/skiko-linux-x64-434f7633/libskiko-linux-x64.so: " +
          "/lib/x86_64-linux-gnu/libc.so.6: version `GLIBC_ABI_DT_X86_64_PLT' not found " +
          "(required by /nix/store/qqiqd3ah10x8-glibc-2.42-67/lib/libpthread.so.0)"
      )
    // Stand-in for org.jetbrains.skiko.LibraryLoadException, which the renderer deliberately does
    // not link: same position in the chain, same uninformative message.
    val skikoWrapper =
      UnsatisfiedLinkError(
          "Failed to loade library /root/.skiko/skiko-linux-x64-434f7633/libskiko-linux-x64.so"
        )
        .apply { initCause(reason) }
    return ExceptionInInitializerError(skikoWrapper)
  }

  @Test
  fun `an ordinary preview throw is not a native failure`() {
    assertNull(NativeLoadDiagnosis.diagnose(IllegalStateException("no LocalContext")))
  }

  @Test
  fun `the glibc mismatch is named, along with the JVM that hit it`() {
    val diagnosis = NativeLoadDiagnosis.diagnose(glibcSkewFailure())

    assertNotNull(diagnosis)
    assertFalse(diagnosis!!.cascade)
    // The three things a reader needs: which symbol version was missing, that a package store is
    // on one side of it, and how to stop it happening.
    assertTrue(diagnosis.text.contains("GLIBC_ABI_DT_X86_64_PLT"))
    assertTrue(diagnosis.text.contains("package store"))
    assertTrue(diagnosis.text.contains("LD_LIBRARY_PATH"))
  }

  @Test
  fun `a missing soname points at the package that provides it`() {
    val diagnosis =
      NativeLoadDiagnosis.diagnose(
        UnsatisfiedLinkError(
          "/root/.skiko/x/libskiko-linux-x64.so: libGL.so.1: cannot open shared object file: " +
            "No such file or directory"
        )
      )

    assertNotNull(diagnosis)
    assertTrue(diagnosis!!.text.contains("libGL.so.1"))
    assertTrue(diagnosis.text.contains("libgl1"))
    assertTrue(diagnosis.text.contains("env.desktop-natives"))
  }

  @Test
  fun `a preview's own JNI library is not blamed on skiko`() {
    // A preview (or one of its dependencies) calling System.loadLibrary on a host without that
    // library throws the same UnsatisfiedLinkError. Answering it with libskiko advice would
    // misattribute the failure — and latching it would dismiss a later real skiko failure as a
    // cascade of something unrelated.
    val ownLib =
      UnsatisfiedLinkError(
        "/opt/app/libtokenizer.so: libtokenizer.so: cannot open shared object file: " +
          "No such file or directory"
      )

    val diagnosis = NativeLoadDiagnosis.diagnose(ownLib)

    assertNotNull(diagnosis)
    assertFalse(diagnosis!!.cascade)
    assertTrue(diagnosis.text.contains("libtokenizer.so"))
    assertFalse(diagnosis.text.contains("skiko"))
    assertFalse(diagnosis.text.contains("libgl1"))

    // And it did not take the Skia latch: the next skiko failure is still reported as the first.
    val skiko = NativeLoadDiagnosis.diagnose(glibcSkewFailure())
    assertFalse(skiko!!.cascade)
    assertTrue(skiko.text.contains("skiko"))
  }

  @Test
  fun `the same missing app library is reported once per JVM, not once per preview`() {
    // Every preview touching the broken library raises the same error. Without its own
    // bookkeeping, a warm worker would print a thousand identical lines — the flood the Skia
    // latch exists to prevent, arriving through a different door.
    fun failure() =
      UnsatisfiedLinkError(
        "/opt/app/libtokenizer.so: libtokenizer.so: cannot open shared object file"
      )

    assertFalse(NativeLoadDiagnosis.diagnose(failure())!!.cascade)
    assertTrue(NativeLoadDiagnosis.diagnose(failure())!!.cascade)
    assertTrue(NativeLoadDiagnosis.diagnose(failure())!!.cascade)

    // A *different* library is news again, and still not a Skia cascade.
    val other =
      NativeLoadDiagnosis.diagnose(
        UnsatisfiedLinkError("/opt/app/libcodec.so: libcodec.so: cannot open shared object file")
      )
    assertFalse(other!!.cascade)
    assertTrue(other.text.contains("libcodec.so"))
  }

  @Test
  fun `later previews are reported as a cascade of the first failure`() {
    val first = NativeLoadDiagnosis.diagnose(glibcSkewFailure())
    val second =
      NativeLoadDiagnosis.diagnose(
        NoClassDefFoundError("Could not initialize class org.jetbrains.skia.Surface")
      )

    assertNotNull(second)
    assertTrue(second!!.cascade)
    // The cascade carries the real explanation with it: a panel shows one card at a time, so
    // "look at another preview's sidecar" would be a dead end there.
    assertTrue(second.text.contains("GLIBC_ABI_DT_X86_64_PLT"))
    assertTrue(second.text.startsWith("Cascade of the first native-load failure"))
    assertFalse(first!!.cascade)
  }

  @Test
  fun `a cascade in a JVM that never saw the first failure says where to look`() {
    // The per-capture fork lane: this JVM inherited a poisoned Skia class from nothing, because
    // the failing initialisation happened before this renderer's own bookkeeping.
    val diagnosis =
      NativeLoadDiagnosis.diagnose(
        NoClassDefFoundError("Could not initialize class org.jetbrains.skiko.Library")
      )

    assertNotNull(diagnosis)
    assertTrue(diagnosis!!.cascade)
    assertTrue(diagnosis.text.contains("first preview"))
  }

  @Test
  fun `a repeated first-class failure in the same JVM is a cascade too`() {
    NativeLoadDiagnosis.diagnose(glibcSkewFailure())
    val again = NativeLoadDiagnosis.diagnose(glibcSkewFailure())

    // Each fresh fork rediscovers the cause, but a pooled worker must not shout it once per
    // preview: only the first occurrence in a JVM is the news.
    assertTrue(again!!.cascade)
  }

  @Test
  fun `the cause chain is followed, and a cycle in it does not hang`() {
    // What the renderer actually catches is the wrapper: ExceptionInInitializerError ->
    // LibraryLoadException -> UnsatisfiedLinkError. Only the innermost frame carries the reason.
    val nested = RuntimeException("compose failed", glibcSkewFailure())
    assertTrue(NativeLoadDiagnosis.diagnose(nested)!!.text.contains("GLIBC_ABI_DT_X86_64_PLT"))

    NativeLoadDiagnosis.resetForTesting()
    val a = RuntimeException("a")
    val b = RuntimeException("b", a)
    a.initCause(b)
    assertNull(NativeLoadDiagnosis.diagnose(b))
  }

  @Test
  fun `the runtime snapshot records the JVM and its native search path`() {
    val snapshot = NativeLoadDiagnosis.runtimeSnapshot { name ->
      if (name == "LD_LIBRARY_PATH") "/root/.cache/coo-ee/desktop-gl/lib" else null
    }

    val byKey = snapshot.toMap()
    assertEquals(System.getProperty("java.home"), byKey["javaHome"])
    assertEquals("/root/.cache/coo-ee/desktop-gl/lib", byKey["ldLibraryPath"])
    // Every key present on every sidecar, so a consumer can read it without null-checking each.
    assertTrue(byKey.keys.containsAll(listOf("javaVersion", "javaVendor", "osArch")))
  }
}
