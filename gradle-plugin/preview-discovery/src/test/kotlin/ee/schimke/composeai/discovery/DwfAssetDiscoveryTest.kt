package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DwfAssetDiscoveryTest {

  @get:Rule val tempDir = TemporaryFolder()

  private fun discover(
    dwfResourceDirs: List<File>,
    failOnEmpty: Boolean = true,
  ): PreviewDiscovery.Outcome =
    PreviewDiscovery.discover(
      PreviewDiscovery.Input(
        classDirs = emptyList(),
        dependencyJars = emptyList(),
        sourceFiles = emptyList(),
        moduleName = ":wear-face",
        variantName = "debug",
        projectDirectory = tempDir.root,
        failOnEmpty = failOnEmpty,
        dwfResourceDirs = dwfResourceDirs,
        dwfRenderSubdir = "dwf-renders",
      ),
    )

  @Test
  fun `discovers WatchFace XML under raw and xml while ignoring non-DWF resource XMLs`() {
    val res = tempDir.newFolder("res")
    File(res, "raw").mkdirs()
    File(res, "values").mkdirs()
    File(res, "drawable").mkdirs()

    File(res, "raw/chrono_face.xml")
      .writeText(
        """
        <?xml version="1.0" encoding="utf-8"?>
        <WatchFace width="450" height="450" clipShape="CIRCLE">
          <Scene backgroundColor="#FF000000" />
        </WatchFace>
        """
          .trimIndent(),
      )
    File(res, "values/strings.xml")
      .writeText("""<resources><string name="a">A</string></resources>""")
    File(res, "drawable/icon.xml")
      .writeText("""<vector xmlns:android="http://schemas.android.com/apk/res/android"/>""")

    val outcome = discover(listOf(res), failOnEmpty = true)
    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    val previews = (outcome as PreviewDiscovery.Outcome.Success).manifest.previews
    assertThat(previews).hasSize(1)

    val preview = previews.single()
    assertThat(preview.id).isEqualTo("dwf__raw_chrono_face")
    assertThat(preview.functionName).isEqualTo("chrono_face")
    assertThat(preview.className).isEmpty()
    assertThat(preview.includeInA11y).isFalse()
    assertThat(preview.params.kind).isEqualTo(PreviewKind.COMPOSE)
    assertThat(preview.params.dwfAssetPath).isEqualTo("raw/chrono_face.xml")
    assertThat(preview.params.widthDp).isEqualTo(450)
    assertThat(preview.params.heightDp).isEqualTo(450)
    assertThat(preview.params.density).isEqualTo(1.0f)
    assertThat(preview.params.device).isEqualTo("id:wearos_large_round")
    assertThat(preview.captures.single().renderOutput)
      .isEqualTo("dwf-renders/dwf__raw_chrono_face.png")
  }

  @Test
  fun `small round and rectangular WatchFace dimensions map to matching Wear devices`() {
    val res = tempDir.newFolder("res")
    File(res, "raw").mkdirs()
    File(res, "raw/small_round.xml")
      .writeText("""<WatchFace width="384" height="384" clipShape="CIRCLE"><Scene/></WatchFace>""")
    File(res, "raw/rect_face.xml")
      .writeText("""<WatchFace width="400" height="480" clipShape="NONE"><Scene/></WatchFace>""")

    val outcome = discover(listOf(res)) as PreviewDiscovery.Outcome.Success
    val byName = outcome.manifest.previews.associateBy { it.functionName }
    assertThat(byName.getValue("small_round").params.device).isEqualTo("id:wearos_small_round")
    assertThat(byName.getValue("rect_face").params.device).isEqualTo("id:wearos_rect")
  }
}
