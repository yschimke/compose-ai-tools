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
    assertNull(GradleVersionCheck.problem(GradleVersion.version("9.3.1")))
  }

  @Test
  fun `newer than floor passes`() {
    assertNull(GradleVersionCheck.problem(GradleVersion.version("9.4.1")))
  }

  @Test
  fun `release candidate of floor passes`() {
    // GradleVersion.baseVersion strips the `-rc-N` suffix, so 9.3.1-rc-1 still satisfies 9.3.1.
    assertNull(GradleVersionCheck.problem(GradleVersion.version("9.3.1-rc-1")))
  }

  @Test
  fun `older than floor fails with remediation`() {
    val msg = GradleVersionCheck.problem(GradleVersion.version("9.3.0"))
    assertNotNull(msg)
    assertTrue(msg!!.contains("9.3.0"))
    assertTrue(msg.contains("9.3.1"))
    assertTrue(msg.contains("./gradlew wrapper --gradle-version 9.3.1"))
  }

  @Test
  fun `floor constant matches CompatRules`() {
    // Drift guard — both floors are driven by AGP's documented minimum and should move together.
    assertEquals("9.3.1", GradleVersionCheck.MIN.version)
  }
}
