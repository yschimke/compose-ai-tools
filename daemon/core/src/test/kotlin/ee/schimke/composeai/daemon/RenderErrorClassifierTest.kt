package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RenderErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderErrorClassifierTest {

  @Test
  fun androidxComposeOnDesktopIsRuntimeWithAClasspathSuggestion() {
    val c =
      RenderErrorClassifier.classify(
        "java.lang.NoSuchMethodError: Implemented only in JetBrains fork"
      )
    assertEquals(RenderErrorKind.RUNTIME, c.kind)
    assertTrue(c.suggestion, c.suggestion!!.contains("org.jetbrains.compose"))
  }

  @Test
  fun newerSdkIsRuntimeWithAnSdkSuggestion() {
    val c =
      RenderErrorClassifier.classify("PackageParser: Requires newer sdk version #36 (current #35)")
    assertEquals(RenderErrorKind.RUNTIME, c.kind)
    assertTrue(c.suggestion, c.suggestion!!.contains("compileSdk"))
  }

  @Test
  fun capturePathFailureIsCapture() {
    val c =
      RenderErrorClassifier.classify(
        "java.lang.RuntimeException: captureRoboImage / PixelCopy failed"
      )
    assertEquals(RenderErrorKind.CAPTURE, c.kind)
    assertNotNull(c.suggestion)
  }

  @Test
  fun robolectricLockIsCaptureWithSandboxSuggestion() {
    val c =
      RenderErrorClassifier.classify(
        "java.io.IOException: /home/user/.robolectric-download-lock: permission denied"
      )
    assertEquals(RenderErrorKind.CAPTURE, c.kind)
    assertTrue(c.suggestion, c.suggestion!!.contains(".robolectric-download-lock"))
  }

  @Test
  fun missingComposableIsRuntimeWithASuggestion() {
    val c =
      RenderErrorClassifier.classify(
        "java.lang.NoSuchMethodException: getDeclaredComposableMethod failed"
      )
    assertEquals(RenderErrorKind.RUNTIME, c.kind)
    assertTrue(c.suggestion, c.suggestion!!.contains("@Composable"))
  }

  @Test
  fun timeoutIsTimeout() {
    val c = RenderErrorClassifier.classify("java.util.concurrent.TimeoutException: timed out")
    assertEquals(RenderErrorKind.TIMEOUT, c.kind)
  }

  @Test
  fun unknownCompositionErrorDefaultsToRuntimeWithoutASuggestion() {
    val c = RenderErrorClassifier.classify(IllegalStateException("boom"))
    assertEquals(RenderErrorKind.RUNTIME, c.kind)
    assertNull(c.suggestion)
  }

  @Test
  fun walksTheCauseChainForSignatures() {
    val cause =
      RuntimeException(
        "wrapper",
        IllegalStateException("PackageParser: Requires newer sdk version"),
      )
    assertEquals(RenderErrorKind.RUNTIME, RenderErrorClassifier.classify(cause).kind)
    assertNotNull(RenderErrorClassifier.classify(cause).suggestion)
  }
}
