package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResourceXmlClassifierTest {

  @Test
  fun `vector tag classifies as VECTOR`() {
    val xml =
      """
      <vector xmlns:android="http://schemas.android.com/apk/res/android"
          android:width="48dp" android:height="48dp"
          android:viewportWidth="48" android:viewportHeight="48">
        <path android:pathData="M0,0 L48,0 L48,48 L0,48 Z" android:fillColor="#FF0000" />
      </vector>
      """
        .trimIndent()
    assertThat(ResourceXmlClassifier.classify(xml.byteInputStream())).isEqualTo(ResourceType.VECTOR)
  }

  @Test
  fun `animated-vector tag classifies as ANIMATED_VECTOR`() {
    val xml =
      """
      <animated-vector xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:aapt="http://schemas.android.com/aapt">
        <aapt:attr name="android:drawable">
          <vector android:width="24dp" android:height="24dp"
              android:viewportWidth="24" android:viewportHeight="24" />
        </aapt:attr>
      </animated-vector>
      """
        .trimIndent()
    assertThat(ResourceXmlClassifier.classify(xml.byteInputStream()))
      .isEqualTo(ResourceType.ANIMATED_VECTOR)
  }

  @Test
  fun `adaptive-icon tag classifies as ADAPTIVE_ICON`() {
    val xml =
      """
      <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        <background android:drawable="@drawable/ic_launcher_background" />
        <foreground android:drawable="@drawable/ic_launcher_foreground" />
      </adaptive-icon>
      """
        .trimIndent()
    assertThat(ResourceXmlClassifier.classify(xml.byteInputStream()))
      .isEqualTo(ResourceType.ADAPTIVE_ICON)
  }

  @Test
  fun `unknown root tag returns null`() {
    val xml = """<shape xmlns:android="http://schemas.android.com/apk/res/android" />"""
    assertThat(ResourceXmlClassifier.classify(xml.byteInputStream())).isNull()
  }

  @Test
  fun `selector returns null - out of scope for initial catalogue`() {
    val xml =
      """
      <selector xmlns:android="http://schemas.android.com/apk/res/android">
        <item android:state_pressed="true" android:drawable="@color/black" />
        <item android:drawable="@color/white" />
      </selector>
      """
        .trimIndent()
    assertThat(ResourceXmlClassifier.classify(xml.byteInputStream())).isNull()
  }

  @Test
  fun `comments and whitespace in the prolog are skipped`() {
    val xml = "<?xml version='1.0' encoding='utf-8'?>\n<!-- header -->\n\n<vector />"
    assertThat(ResourceXmlClassifier.classify(xml.byteInputStream())).isEqualTo(ResourceType.VECTOR)
  }

  @Test
  fun `empty input returns null`() {
    assertThat(ResourceXmlClassifier.classify("".byteInputStream())).isNull()
  }

  @Test
  fun `malformed xml returns null instead of throwing`() {
    val xml = "<vector this is not valid"
    assertThat(ResourceXmlClassifier.classify(xml.byteInputStream())).isNull()
  }
}
