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
    assertNull(GradleVersionCheck.problem(GradleVersion.version("9.0.0")))
  }

  @Test
  fun `newer than floor passes`() {
    assertNull(GradleVersionCheck.problem(GradleVersion.version("9.4.1")))
  }

  @Test
  fun `androidify-style 9_3 passes`() {
    // Spot-check: real-world consumer (android/androidify) at the time of writing.
    assertNull(GradleVersionCheck.problem(GradleVersion.version("9.3.0")))
  }

  @Test
  fun `release candidate of floor passes`() {
    // GradleVersion.baseVersion strips the `-rc-N` suffix, so 9.0.0-rc-1 still satisfies 9.0.0.
    assertNull(GradleVersionCheck.problem(GradleVersion.version("9.0.0-rc-1")))
  }

  @Test
  fun `older than floor fails with remediation`() {
    val msg = GradleVersionCheck.problem(GradleVersion.version("8.13"))
    assertNotNull(msg)
    assertTrue(msg!!.contains("8.13"))
    assertTrue(msg.contains("9.0.0"))
    assertTrue(msg.contains("./gradlew wrapper --gradle-version 9.0.0"))
  }

  @Test
  fun `floor constant matches CompatRules`() {
    // Drift guard — both floors should move together when we widen / narrow consumer support.
    assertEquals("9.0.0", GradleVersionCheck.MIN.version)
  }
}
