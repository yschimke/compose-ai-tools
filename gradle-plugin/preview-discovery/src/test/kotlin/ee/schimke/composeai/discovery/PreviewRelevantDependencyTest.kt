package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewRelevantDependencyTest {

  @Test
  fun `remote material project jars stay on the component inference classpath`() {
    assertThat(
        PreviewDiscovery.isPreviewRelevant(
          "/checkout/vendor/remote-material3/build/intermediates/full_jar/full.jar"
        )
      )
      .isTrue()
  }

  @Test
  fun `glimmer AAR classes stay on the component inference classpath`() {
    assertThat(PreviewDiscovery.isPreviewRelevant("androidx.xr.glimmer:glimmer:1.0.0-alpha19"))
      .isTrue()
  }

  @Test
  fun `unrelated project jars remain outside the scan`() {
    assertThat(PreviewDiscovery.isPreviewRelevant("/checkout/feature/build/libs/feature.jar"))
      .isFalse()
  }

  @get:Rule val temp = TemporaryFolder()

  private fun jar(vararg entries: String) =
    temp.newFile("design-system.jar").also { file ->
      ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach {
          zip.putNextEntry(ZipEntry(it))
          zip.closeEntry()
        }
      }
    }

  @Test
  fun `a configured component library stays on the scan classpath whatever its coordinate`() {
    val jar = jar("com/acme/design/ButtonKt.class", "com/acme/design/util/Shapes.class")
    assertThat(PreviewDiscovery.isPreviewRelevant("com.acme:design-system:1.0")).isFalse()
    assertThat(PreviewDiscovery.holdsComponentLibrary(jar, listOf("com.acme.design."))).isTrue()
    assertThat(PreviewDiscovery.holdsComponentLibrary(jar, listOf("com.acme.design.ButtonKt")))
      .isTrue()
    assertThat(PreviewDiscovery.holdsComponentLibrary(jar, listOf("com.acme.design.Button")))
      .isFalse()
    assertThat(PreviewDiscovery.holdsComponentLibrary(jar, listOf("com.other."))).isFalse()
    assertThat(PreviewDiscovery.holdsComponentLibrary(jar, emptyList())).isFalse()
  }
}
