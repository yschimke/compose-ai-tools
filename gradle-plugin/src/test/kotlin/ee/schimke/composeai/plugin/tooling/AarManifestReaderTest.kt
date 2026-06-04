package ee.schimke.composeai.plugin.tooling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AarManifestReaderTest {

  @Test
  fun `reads package and integer minSdk`() {
    val parsed =
      AarManifestReader.parse(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="ai.koog.agents">
            <uses-sdk android:minSdkVersion="35" />
        </manifest>
        """
          .trimIndent()
      )
    assertEquals("ai.koog.agents", parsed.packageName)
    assertEquals(35, parsed.minSdk)
  }

  @Test
  fun `missing uses-sdk yields null minSdk`() {
    val parsed =
      AarManifestReader.parse(
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example" />"""
      )
    assertEquals("com.example", parsed.packageName)
    assertNull(parsed.minSdk)
  }

  @Test
  fun `codename minSdk is treated as unknown`() {
    val parsed =
      AarManifestReader.parse(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
            <uses-sdk android:minSdkVersion="VanillaIceCream" />
        </manifest>
        """
          .trimIndent()
      )
    assertNull(parsed.minSdk)
  }

  @Test
  fun `malformed xml does not throw`() {
    val parsed = AarManifestReader.parse("not xml <<<")
    assertNull(parsed.packageName)
    assertNull(parsed.minSdk)
  }

  @Test
  fun `blank package is reported as null`() {
    val parsed =
      AarManifestReader.parse(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-sdk android:minSdkVersion="21" />
        </manifest>
        """
          .trimIndent()
      )
    assertNull(parsed.packageName)
    assertEquals(21, parsed.minSdk)
  }
}
