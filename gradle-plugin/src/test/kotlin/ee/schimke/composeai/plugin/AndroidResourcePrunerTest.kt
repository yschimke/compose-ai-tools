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
  fun `drops named aar file resources but keeps arsc, non-prunable types, and everything unnamed`() {
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
      AndroidResourcePruner.prune(
        input,
        prunableFileResources =
          setOf("drawable/wear_anim", "layout/notif", "mipmap/ic_launcher", "color/my_csl"),
      )
    val kept = entries(result.bytes)

    // Dropped: the AAR file resources of prunable types (drawable, layout, mipmap) — including the
    // generated animated-vector split, which normalises to its base name.
    assertThat(result.droppedEntries).isEqualTo(4)
    assertThat(kept).doesNotContainKey("res/drawable/wear_anim.xml")
    assertThat(kept).doesNotContainKey("res/drawable/\$wear_anim__3.xml")
    assertThat(kept).doesNotContainKey("res/layout-v21/notif.xml")
    assertThat(kept).doesNotContainKey("res/mipmap-hdpi/ic_launcher.png")

    // Kept: arsc (byte-for-byte), manifest, the drawable no data set named as a dependency's, and
    // `color/` — a non-prunable type stays even when the caller names it.
    assertThat(kept.getValue("resources.arsc")).isEqualTo(arsc)
    assertThat(kept).containsKey("AndroidManifest.xml")
    assertThat(kept).containsKey("res/color/my_csl.xml")
    assertThat(kept).containsKey("res/drawable/my_icon.xml")
  }

  @Test
  fun `animated-vector splits are dropped with their base name, not independently`() {
    val input =
      apk(
        Triple("res/drawable/\$my_anim__0.xml", "X".repeat(20).toByteArray(), ZipEntry.DEFLATED),
        Triple("res/drawable/my_anim.xml", "Y".repeat(20).toByteArray(), ZipEntry.DEFLATED),
      )
    assertThat(
        AndroidResourcePruner.prune(input, prunableFileResources = setOf("drawable/my_anim"))
          .droppedEntries
      )
      .isEqualTo(2)
    assertThat(
        AndroidResourcePruner.prune(input, prunableFileResources = emptySet()).droppedEntries
      )
      .isEqualTo(0)
  }

  /**
   * The regression that motivated the positive drop-set (issues #3260 / #3299). An empty set now
   * means "nothing was attributed to a dependency" and must drop nothing — under the old retain-set
   * it meant "this build authors no resources" and deleted the lot, which is how an unreadable
   * blame file turned into `NotFoundException: File res/drawable/ic_play.xml` at render time.
   */
  @Test
  fun `empty ownership prunes nothing`() {
    val input =
      apk(
        Triple("resources.arsc", "T".toByteArray(), ZipEntry.STORED),
        Triple("res/drawable/ic_play.xml", "ICON".toByteArray(), ZipEntry.DEFLATED),
        Triple("res/layout/player.xml", "LAYOUT".toByteArray(), ZipEntry.DEFLATED),
      )

    val result = AndroidResourcePruner.prune(input, prunableFileResources = emptySet())
    val kept = entries(result.bytes)

    assertThat(kept).containsKey("res/drawable/ic_play.xml")
    assertThat(kept).containsKey("res/layout/player.xml")
    assertThat(result.droppedEntries).isEqualTo(0)
    assertThat(result.bytesSaved).isEqualTo(0L)
  }

  @Test
  fun `keeps AppCompat vector configuration probe even when named prunable`() {
    val input =
      apk(
        Triple("res/drawable/abc_vector_test.xml", "VECTOR".toByteArray(), ZipEntry.DEFLATED),
        Triple("res/drawable/dependency_icon.xml", "OTHER".toByteArray(), ZipEntry.DEFLATED),
        Triple("res/drawable/my_icon.xml", "OWN".toByteArray(), ZipEntry.DEFLATED),
      )

    val result =
      AndroidResourcePruner.prune(
        input,
        prunableFileResources = setOf("drawable/abc_vector_test", "drawable/dependency_icon"),
      )
    val kept = entries(result.bytes)

    assertThat(kept).containsKey("res/drawable/abc_vector_test.xml")
    assertThat(kept).containsKey("res/drawable/my_icon.xml")
    assertThat(kept).doesNotContainKey("res/drawable/dependency_icon.xml")
    assertThat(result.droppedEntries).isEqualTo(1)
  }

  @Test
  fun `resourceNameOf strips extension and normalises generated splits`() {
    assertThat(AndroidResourcePruner.resourceNameOf("ic_foo.xml")).isEqualTo("ic_foo")
    assertThat(AndroidResourcePruner.resourceNameOf("ic_foo.9.png")).isEqualTo("ic_foo")
    assertThat(AndroidResourcePruner.resourceNameOf("\$wear_anim__12.xml")).isEqualTo("wear_anim")
  }
}
