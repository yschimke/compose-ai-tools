package ee.schimke.composeai.fakeemulator

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApkInspectorTest {
  @Test
  fun `reads the package name from the binary manifest`() {
    val info = ApkInspector.inspect(ApkFixtures.apk("com.example.app"))
    assertThat(info.packageName).isEqualTo("com.example.app")
    assertThat(info.dexEntryCount).isEqualTo(1)
    assertThat(info.looksLikeApk).isTrue()
  }

  @Test
  fun `flags an APK that carries the Compose Preview annotation`() {
    assertThat(
        ApkInspector.inspect(ApkFixtures.apk("com.x", withPreview = true)).declaresComposePreviews
      )
      .isTrue()
    assertThat(
        ApkInspector.inspect(ApkFixtures.apk("com.x", withPreview = false)).declaresComposePreviews
      )
      .isFalse()
  }

  @Test
  fun `parses the exact package attribute, not any string in the pool`() {
    // The pool also holds "manifest" and "package"; the parser must return the attribute value.
    val info = ApkInspector.inspect(ApkFixtures.apk("io.schimke.previews.sample"))
    assertThat(info.packageName).isEqualTo("io.schimke.previews.sample")
  }

  @Test
  fun `non-APK bytes inspect cleanly to nulls`() {
    val info = ApkInspector.inspect("not a zip".toByteArray())
    assertThat(info.packageName).isNull()
    assertThat(info.dexEntryCount).isEqualTo(0)
    assertThat(info.looksLikeApk).isFalse()
  }

  @Test
  fun `parses a binary manifest directly`() {
    val attrs = BinaryXml.parseManifest(ApkFixtures.binaryManifest("com.demo"))
    assertThat(attrs).isNotNull()
    assertThat(attrs!!.packageName).isEqualTo("com.demo")
  }
}
