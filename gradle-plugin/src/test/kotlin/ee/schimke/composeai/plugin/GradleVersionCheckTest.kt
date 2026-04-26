package ee.schimke.composeai.plugin

import org.gradle.util.GradleVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleVersionCheckTest {

  @Test
  fun `current floor passes`() {
    assertNull(GradleVersionCheck.problem(GradleVersion.version("8.13")))
  }

  @Test
  fun `newer than floor passes`() {
    assertNull(GradleVersionCheck.problem(GradleVersion.version("9.4.1")))
  }

  @Test
  fun `signal-style 8_13_2 passes`() {
    // Spot-check: real-world AGP-8 consumer (signalapp/Signal-Android-class) at the time of
    // writing. The `agp8-min` integration fixture is pinned slightly tighter (Gradle 8.13).
    assertNull(GradleVersionCheck.problem(GradleVersion.version("8.13.2")))
  }

  @Test
  fun `androidify-style 9_3 passes`() {
    // Spot-check: real-world AGP-9 consumer (android/androidify) at the time of writing.
    assertNull(GradleVersionCheck.problem(GradleVersion.version("9.3.0")))
  }

  @Test
  fun `release candidate of floor passes`() {
    // GradleVersion.baseVersion strips the `-rc-N` suffix, so 8.13-rc-1 still satisfies 8.13.
    assertNull(GradleVersionCheck.problem(GradleVersion.version("8.13-rc-1")))
  }

  @Test
  fun `older than floor fails with remediation`() {
    val msg = GradleVersionCheck.problem(GradleVersion.version("8.11"))
    assertNotNull(msg)
    assertTrue(msg!!.contains("8.11"))
    assertTrue(msg.contains("8.13"))
    assertTrue(msg.contains("./gradlew wrapper --gradle-version 8.13"))
  }

  @Test
  fun `floor constant matches CompatRules`() {
    // Drift guard — both floors should move together when we widen / narrow consumer support.
    assertEquals("8.13", GradleVersionCheck.MIN.version)
  }
}
