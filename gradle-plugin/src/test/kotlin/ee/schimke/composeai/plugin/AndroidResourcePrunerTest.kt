package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Test

class AndroidResourcePrunerTest {

  private fun apk(vararg entries: Triple<String, ByteArray, Int>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
      for ((name, data, method) in entries) {
        val e = ZipEntry(name)
        e.method = method
        if (method == ZipEntry.STORED) {
          e.size = data.size.toLong()
          e.crc = CRC32().apply { update(data) }.value
        }
        zos.putNextEntry(e)
        zos.write(data)
        zos.closeEntry()
      }
    }
    return out.toByteArray()
  }

  private fun entries(zip: ByteArray): Map<String, ByteArray> {
    val map = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
      while (true) {
        val e = zis.nextEntry ?: break
        map[e.name] = zis.readBytes()
      }
    }
    return map
  }

  @Test
  fun `drops merged aar file resources but keeps arsc, non-prunable types, and module-own`() {
    val arsc = "ARSC-TABLE".toByteArray()
    val input =
      apk(
        Triple("resources.arsc", arsc, ZipEntry.STORED),
        Triple("AndroidManifest.xml", "MANIFEST".toByteArray(), ZipEntry.DEFLATED),
        Triple("res/drawable/wear_anim.xml", "A".repeat(100).toByteArray(), ZipEntry.DEFLATED),
        Triple("res/drawable/\$wear_anim__3.xml", "B".repeat(50).toByteArray(), ZipEntry.DEFLATED),
        Triple("res/layout-v21/notif.xml", "C".repeat(80).toByteArray(), ZipEntry.DEFLATED),
        Triple("res/mipmap-hdpi/ic_launcher.png", "D".repeat(60).toByteArray(), ZipEntry.STORED),
        Triple("res/drawable/my_icon.xml", "E".repeat(40).toByteArray(), ZipEntry.DEFLATED),
        Triple("res/color/my_csl.xml", "F".repeat(30).toByteArray(), ZipEntry.DEFLATED),
      )

    val result =
      AndroidResourcePruner.prune(input, moduleOwnFileResources = setOf("drawable/my_icon"))
    val kept = entries(result.bytes)

    // Dropped: the four AAR file resources of prunable types (drawable, layout, mipmap).
    assertThat(result.droppedEntries).isEqualTo(4)
    assertThat(kept).doesNotContainKey("res/drawable/wear_anim.xml")
    assertThat(kept).doesNotContainKey("res/drawable/\$wear_anim__3.xml")
    assertThat(kept).doesNotContainKey("res/layout-v21/notif.xml")
    assertThat(kept).doesNotContainKey("res/mipmap-hdpi/ic_launcher.png")

    // Kept: arsc (byte-for-byte), manifest, a non-prunable type (color), and the module-own
    // drawable.
    assertThat(kept.getValue("resources.arsc")).isEqualTo(arsc)
    assertThat(kept).containsKey("AndroidManifest.xml")
    assertThat(kept).containsKey("res/color/my_csl.xml")
    assertThat(kept).containsKey("res/drawable/my_icon.xml")
  }

  @Test
  fun `module-authored animated-vector splits are retained by base name`() {
    val input =
      apk(
        Triple("res/drawable/\$my_anim__0.xml", "X".repeat(20).toByteArray(), ZipEntry.DEFLATED),
        Triple("res/drawable/my_anim.xml", "Y".repeat(20).toByteArray(), ZipEntry.DEFLATED),
      )
    val result =
      AndroidResourcePruner.prune(input, moduleOwnFileResources = setOf("drawable/my_anim"))
    assertThat(result.droppedEntries).isEqualTo(0)
  }

  @Test
  fun `nothing dropped when there are no prunable file resources`() {
    val input =
      apk(
        Triple("resources.arsc", "T".toByteArray(), ZipEntry.STORED),
        Triple("res/color/csl.xml", "C".toByteArray(), ZipEntry.DEFLATED),
        Triple("res/raw/data.json", "R".toByteArray(), ZipEntry.DEFLATED),
      )
    val result = AndroidResourcePruner.prune(input, moduleOwnFileResources = emptySet())
    assertThat(result.droppedEntries).isEqualTo(0)
    assertThat(result.bytesSaved).isEqualTo(0L)
  }

  @Test
  fun `resourceNameOf strips extension and normalises generated splits`() {
    assertThat(AndroidResourcePruner.resourceNameOf("ic_foo.xml")).isEqualTo("ic_foo")
    assertThat(AndroidResourcePruner.resourceNameOf("ic_foo.9.png")).isEqualTo("ic_foo")
    assertThat(AndroidResourcePruner.resourceNameOf("\$wear_anim__12.xml")).isEqualTo("wear_anim")
  }
}
