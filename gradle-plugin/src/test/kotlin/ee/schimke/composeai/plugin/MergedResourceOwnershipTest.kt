package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MergedResourceOwnershipTest {

  @get:Rule val tmp = TemporaryFolder()

  /** Writes a blame file at the real AGP location so the walk is exercised, not just the parse. */
  private fun writeBlame(variant: String, body: String): File {
    val dir =
      File(tmp.root, "build/intermediates/incremental/$variant/merge${variant}Resources").apply {
        mkdirs()
      }
    return File(dir, "merger.xml").apply { writeText(body) }
  }

  private fun buildDir() = File(tmp.root, "build")

  @Test
  fun `keeps project-module resources and drops aar ones`() {
    writeBlame(
      "debugUnitTest",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <merger version="3">
        <dataSet config="androidx.cardview:cardview:1.0.0">
          <source path="/caches/transforms/abc/transformed/cardview-1.0.0/res">
            <file path="/caches/transforms/abc/transformed/cardview-1.0.0/res/drawable/aar_bg.xml" qualifiers=""/>
          </source>
        </dataSet>
        <dataSet config=":modules:services:ui">
          <source path="/repo/modules/services/ui/build/intermediates/packaged_res/debug/out">
            <file path="/repo/modules/services/ui/build/.../res/drawable/ic_play.xml" qualifiers=""/>
            <file path="/repo/modules/services/ui/build/.../res/drawable-hdpi/ic_logo.png" qualifiers="hdpi"/>
          </source>
        </dataSet>
      </merger>
      """
        .trimIndent(),
    )

    val keys = requireNotNull(MergedResourceOwnership.fileResourceOwnership(buildDir())).firstParty

    assertThat(keys).containsAtLeast("drawable/ic_play", "drawable/ic_logo")
    assertThat(keys).doesNotContain("drawable/aar_bg")
  }

  @Test
  fun `treats the generated twin of a project data set as first-party`() {
    writeBlame(
      "debug",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <merger version="3">
        <dataSet config=":modules:services:ui${'$'}Generated" generated="true">
          <source path="/repo/generated/res">
            <file path="/repo/generated/res/drawable/ic_generated.xml" qualifiers=""/>
          </source>
        </dataSet>
        <dataSet config="androidx.core:core:1.0.0${'$'}Generated" generated="true">
          <source path="/caches/transforms/x/res">
            <file path="/caches/transforms/x/res/drawable/core_bg.xml" qualifiers=""/>
          </source>
        </dataSet>
      </merger>
      """
        .trimIndent(),
    )

    val keys = requireNotNull(MergedResourceOwnership.fileResourceOwnership(buildDir())).firstParty

    assertThat(keys).contains("drawable/ic_generated")
    assertThat(keys).doesNotContain("drawable/core_bg")
  }

  @Test
  fun `skips values files and normalises animated-vector split names`() {
    writeBlame(
      "debug",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <merger version="3">
        <dataSet config=":app">
          <source path="/repo/app/src/main/res">
            <file path="/repo/app/src/main/res/values/strings.xml" qualifiers="">
              <string name="app_name">Demo</string>
            </file>
            <file path="/repo/app/src/main/res/drawable/${'$'}avd_spin__0.xml" qualifiers=""/>
          </source>
        </dataSet>
      </merger>
      """
        .trimIndent(),
    )

    val keys = requireNotNull(MergedResourceOwnership.fileResourceOwnership(buildDir())).firstParty

    assertThat(keys).contains("drawable/avd_spin")
    assertThat(keys.none { it.startsWith("values/") }).isTrue()
  }

  @Test
  fun `unions every blame file under the module build dir`() {
    writeBlame(
      "debug",
      """
      <merger version="3"><dataSet config=":app"><source path="/a">
        <file path="/a/drawable/from_debug.xml"/>
      </source></dataSet></merger>
      """
        .trimIndent(),
    )
    writeBlame(
      "debugUnitTest",
      """
      <merger version="3"><dataSet config=":app"><source path="/b">
        <file path="/b/drawable/from_unit_test.xml"/>
      </source></dataSet></merger>
      """
        .trimIndent(),
    )

    val keys = requireNotNull(MergedResourceOwnership.fileResourceOwnership(buildDir())).firstParty

    assertThat(keys).containsAtLeast("drawable/from_debug", "drawable/from_unit_test")
  }

  /**
   * The exact shapes AGP 9 writes, captured from a real `mergeDebugUnitTestResources` run: a
   * single-file resource spells out `name`/`type`, and the module's OWN source set is configured by
   * bare source-set name (`test`) rather than a project path — so "first-party" cannot just mean
   * "starts with `:`".
   */
  @Test
  fun `keeps a single-file resource from the module's own bare-named source set`() {
    writeBlame(
      "debugUnitTest",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <merger version="3">
        <dataSet aapt-namespace="http://schemas.android.com/apk/res-auto" config="test" generated-set="test${'$'}Generated">
          <source path="/repo/daemon/android/src/test/res">
            <file name="probe_icon" path="/repo/daemon/android/src/test/res/drawable/probe_icon.xml" qualifiers="" type="drawable"/>
          </source>
        </dataSet>
        <dataSet config="androidx.cardview:cardview:1.0.0">
          <source path="/caches/transforms/abc/res">
            <file name="aar_bg" path="/caches/transforms/abc/res/drawable/aar_bg.xml" qualifiers="" type="drawable"/>
          </source>
        </dataSet>
      </merger>
      """
        .trimIndent(),
    )

    val keys = requireNotNull(MergedResourceOwnership.fileResourceOwnership(buildDir())).firstParty

    assertThat(keys).contains("drawable/probe_icon")
    assertThat(keys).doesNotContain("drawable/aar_bg")
  }

  @Test
  fun `returns null when no blame file exists so the caller prunes nothing`() {
    assertThat(MergedResourceOwnership.fileResourceOwnership(buildDir())).isNull()
  }

  @Test
  fun `a malformed blame file returns null instead of trusting partial ownership`() {
    writeBlame("debug", "<merger><dataSet config=\":app\"><source path=\"/a\">")

    assertThat(MergedResourceOwnership.fileResourceOwnership(buildDir())).isNull()
  }

  @Test
  fun `valid blame with no first-party file resources still reports the aar side`() {
    writeBlame(
      "debug",
      """
      <merger version="3">
        <dataSet config="androidx.cardview:cardview:1.0.0">
          <source path="/caches/cardview/res">
            <file name="aar_bg" path="/caches/cardview/res/drawable/aar_bg.xml" type="drawable"/>
          </source>
        </dataSet>
      </merger>
      """
        .trimIndent(),
    )

    val ownership = requireNotNull(MergedResourceOwnership.fileResourceOwnership(buildDir()))
    assertThat(ownership.firstParty).isEmpty()
    assertThat(ownership.prunable).containsExactly("drawable/aar_bg")
  }

  /**
   * A name contributed by BOTH an AAR and a project module is an override: the app's file is what
   * resolves at runtime, so it must survive the prune. Keyed by name rather than by path, the two
   * data sets collide on `drawable/ic_play`, and only subtracting first-party from third-party
   * keeps it.
   */
  @Test
  fun `a resource an app overrides from a dependency is not prunable`() {
    writeBlame(
      "debugUnitTest",
      """
      <merger version="3">
        <dataSet config="androidx.media3:media3-ui:1.0.0">
          <source path="/caches/media3/res">
            <file name="ic_play" path="/caches/media3/res/drawable/ic_play.xml" type="drawable"/>
            <file name="ic_pause" path="/caches/media3/res/drawable/ic_pause.xml" type="drawable"/>
          </source>
        </dataSet>
        <dataSet config=":modules:services:ui">
          <source path="/repo/modules/services/ui/res">
            <file name="ic_play" path="/repo/modules/services/ui/res/drawable/ic_play.xml" type="drawable"/>
          </source>
        </dataSet>
      </merger>
      """
        .trimIndent(),
    )

    val ownership = requireNotNull(MergedResourceOwnership.fileResourceOwnership(buildDir()))

    assertThat(ownership.prunable).containsExactly("drawable/ic_pause")
    assertThat(ownership.prunable).doesNotContain("drawable/ic_play")
  }

  @Test
  fun `well-formed blame in an unrecognised schema returns null rather than a bogus answer`() {
    // A future AGP that keeps the file name but restructures its contents parses cleanly and
    // yields no keys. Null keeps that honest: nothing was classified, so nothing is claimed. (The
    // positive drop-set means an unrecognised file can no longer prune anything either way — this
    // is the metadata-availability signal, not the safety net it once was.)
    writeBlame(
      "debug",
      """
      <merger version="4">
        <contributor config=":modules:services:ui">
          <resource name="ic_play" path="/repo/.../res/drawable/ic_play.xml" type="drawable"/>
        </contributor>
      </merger>
      """
        .trimIndent(),
    )

    assertThat(MergedResourceOwnership.fileResourceOwnership(buildDir())).isNull()
  }

  @Test
  fun `an unreadable blame file does not spoil a sibling one that parses`() {
    writeBlame("debug", "<merger version=\"4\"><contributor config=\":app\"/></merger>")
    writeBlame(
      "debugUnitTest",
      """
      <merger version="3">
        <dataSet config=":modules:services:ui">
          <source path="/repo/modules/services/ui/res">
            <file name="ic_play" path="/repo/modules/services/ui/res/drawable/ic_play.xml" type="drawable"/>
          </source>
        </dataSet>
      </merger>
      """
        .trimIndent(),
    )

    val ownership = requireNotNull(MergedResourceOwnership.fileResourceOwnership(buildDir()))
    assertThat(ownership.firstParty).containsExactly("drawable/ic_play")
  }
}
