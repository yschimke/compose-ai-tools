package ee.schimke.composeai.daemon

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canary for the platform `PathMeasure()` actuals [PlatformPathMeasure] resolves by name.
 *
 * The bug this pins was invisible from inside this module. `ModifierTokenResolver` and
 * `DrawCaptureExtractor` both called Compose's common `PathMeasure()` factory, which — because this
 * module compiles against the desktop/skiko `compose.ui` — links to
 * `SkiaBackedPathMeasure_skikoKt.PathMeasure()`. On Android that class is absent, the call threw
 * `NoClassDefFoundError` inside the callers' `runCatching`, and every generic-outline shape
 * (`MaterialShapes`, morphs, squircles) quietly exported as a plain `<rect>` over its correctly
 * shaped pixels. A JVM-only test could never see it: on this classpath the skiko actual is present
 * and the old code worked.
 *
 * So the assertion is about the *names*, not the arithmetic: at least one facade must resolve here
 * (proving the reflection works at all), and **both** names must still be the ones Compose ships,
 * because the Android one is the half this JVM test cannot exercise. Neither is public API —
 * androidx can rename either in a patch release without breaking our compile, and the failure would
 * again be silent.
 *
 * **When this fails after a Compose bump, that is the test working.** Find the new file-facade name
 * and update [PlatformPathMeasure]; do not delete the case.
 */
class PlatformPathMeasureTest {

  /** The class names in [PlatformPathMeasure], restated so a rename has to be deliberate. */
  private val expected =
    listOf(
      "androidx.compose.ui.graphics.AndroidPathMeasure_androidKt",
      "androidx.compose.ui.graphics.SkiaBackedPathMeasure_skikoKt",
    )

  @Test
  fun oneFacadeResolvesOnThisRuntime() {
    val resolved = expected.filter { name -> runCatching { Class.forName(name) }.isSuccess }
    assertTrue(
      "Neither Compose PathMeasure file facade resolves on the test classpath. " +
        "PlatformPathMeasure.create() now returns null everywhere and every sampled outline " +
        "silently degrades to a rectangle. Probed: $expected",
      resolved.isNotEmpty(),
    )
  }

  @Test
  fun theResolvedFacadeExposesTheFactoryMethod() {
    val method = expected.firstNotNullOfOrNull { name ->
      runCatching { Class.forName(name).getMethod("PathMeasure") }.getOrNull()
    }
    assertNotNull(
      "A Compose PathMeasure facade resolved but exposes no no-arg `PathMeasure()` method — " +
        "PlatformPathMeasure would return null and outlines would degrade to rectangles.",
      method,
    )
  }

  /**
   * The factory must hand back a usable measure on a runtime that also carries the platform's
   * native graphics. This JVM classpath resolves the skiko facade but ships no `libskiko` native,
   * so construction legitimately fails here — assert only that it fails *softly*, since a null is
   * what keeps a missing native from throwing out of an export. The Android half, where the bug
   * lived, is covered end-to-end by `FigmaSvgGenericOutlineShapeTest` in `:daemon:android`.
   */
  @Test
  fun createDegradesToNullRatherThanThrowing() {
    PlatformPathMeasure.create()
  }
}
