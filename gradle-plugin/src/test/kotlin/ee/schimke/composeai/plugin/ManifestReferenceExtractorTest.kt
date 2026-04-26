package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManifestReferenceExtractorTest {

  private fun extract(xml: String, packageName: String? = null): List<ManifestReference> =
    ManifestReferenceExtractor.extract(
      input = xml.byteInputStream(),
      source = "src/main/AndroidManifest.xml",
      packageName = packageName,
    )

  @Test
  fun `application icon and roundIcon both surface as references`() {
    val xml =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="com.example.app">
        <application
            android:icon="@mipmap/ic_launcher"
            android:roundIcon="@mipmap/ic_launcher_round" />
      </manifest>
      """
        .trimIndent()
    val refs = extract(xml)
    assertThat(refs).hasSize(2)
    assertThat(refs.map { it.attributeName }).containsExactly("android:icon", "android:roundIcon")
    assertThat(refs.map { it.resourceName }).containsExactly("ic_launcher", "ic_launcher_round")
    assertThat(refs.map { it.componentKind }).containsExactly("application", "application")
    assertThat(refs.map { it.componentName }).containsExactly(null, null)
  }

  @Test
  fun `activity icon override resolves relative names against package`() {
    val xml =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="com.example.app">
        <application>
          <activity
              android:name=".MainActivity"
              android:icon="@drawable/ic_settings" />
        </application>
      </manifest>
      """
        .trimIndent()
    val refs = extract(xml)
    assertThat(refs).hasSize(1)
    assertThat(refs[0].componentKind).isEqualTo("activity")
    assertThat(refs[0].componentName).isEqualTo("com.example.app.MainActivity")
    assertThat(refs[0].resourceType).isEqualTo("drawable")
    assertThat(refs[0].resourceName).isEqualTo("ic_settings")
  }

  @Test
  fun `fully qualified activity name is preserved`() {
    val xml =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
        <application>
          <activity android:name="com.other.pkg.Foo" android:icon="@drawable/foo" />
        </application>
      </manifest>
      """
        .trimIndent()
    assertThat(extract(xml).single().componentName).isEqualTo("com.other.pkg.Foo")
  }

  @Test
  fun `relative name without leading dot is also resolved`() {
    val xml =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
        <application>
          <activity android:name="MainActivity" android:icon="@drawable/foo" />
        </application>
      </manifest>
      """
        .trimIndent()
    assertThat(extract(xml).single().componentName).isEqualTo("com.example.app.MainActivity")
  }

  @Test
  fun `service receiver provider components are recognised`() {
    val xml =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
        <application>
          <service android:name=".SyncService" android:icon="@drawable/sync_icon" />
          <receiver android:name=".BootReceiver" android:icon="@drawable/boot_icon" />
          <provider
              android:name=".DocsProvider"
              android:authorities="com.example.app.docs"
              android:icon="@drawable/docs_icon" />
        </application>
      </manifest>
      """
        .trimIndent()
    val refs = extract(xml)
    assertThat(refs.map { it.componentKind }).containsExactly("service", "receiver", "provider")
    assertThat(refs.map { it.componentName })
      .containsExactly(
        "com.example.app.SyncService",
        "com.example.app.BootReceiver",
        "com.example.app.DocsProvider",
      )
  }

  @Test
  fun `activity-alias surfaces with its own icon`() {
    val xml =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
        <application>
          <activity-alias
              android:name=".MainAlias"
              android:targetActivity=".MainActivity"
              android:icon="@drawable/alias_icon" />
        </application>
      </manifest>
      """
        .trimIndent()
    val refs = extract(xml)
    assertThat(refs).hasSize(1)
    assertThat(refs[0].componentKind).isEqualTo("activity-alias")
    assertThat(refs[0].componentName).isEqualTo("com.example.app.MainAlias")
  }

  @Test
  fun `framework drawable references are skipped`() {
    val xml =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
        <application android:icon="@android:drawable/sym_def_app_icon" />
      </manifest>
      """
        .trimIndent()
    assertThat(extract(xml)).isEmpty()
  }

  @Test
  fun `theme attribute references are skipped`() {
    val xml =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
        <application>
          <activity android:name=".Foo" android:icon="?attr/iconResource" />
        </application>
      </manifest>
      """
        .trimIndent()
    assertThat(extract(xml)).isEmpty()
  }

  @Test
  fun `non-icon attributes are ignored even if they hold drawable refs`() {
    val xml =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
        <application android:theme="@style/AppTheme">
          <activity android:name=".Foo" android:label="@string/title" />
        </application>
      </manifest>
      """
        .trimIndent()
    assertThat(extract(xml)).isEmpty()
  }

  @Test
  fun `merged manifest without package falls back to caller-supplied package`() {
    val xml =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <application>
          <activity android:name=".MainActivity" android:icon="@drawable/foo" />
        </application>
      </manifest>
      """
        .trimIndent()
    val refs = extract(xml, packageName = "com.example.merged")
    assertThat(refs.single().componentName).isEqualTo("com.example.merged.MainActivity")
  }

  @Test
  fun `parseResourceReference parses canonical forms and rejects others`() {
    assertThat(ManifestReferenceExtractor.parseResourceReference("@drawable/foo"))
      .isEqualTo("drawable" to "foo")
    assertThat(ManifestReferenceExtractor.parseResourceReference("@mipmap/ic_launcher"))
      .isEqualTo("mipmap" to "ic_launcher")
    assertThat(ManifestReferenceExtractor.parseResourceReference("@android:drawable/x")).isNull()
    assertThat(ManifestReferenceExtractor.parseResourceReference("@string/x")).isNull()
    assertThat(ManifestReferenceExtractor.parseResourceReference("?attr/foo")).isNull()
    assertThat(ManifestReferenceExtractor.parseResourceReference("foo")).isNull()
    assertThat(ManifestReferenceExtractor.parseResourceReference("@drawable/")).isNull()
  }
}
