package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ServeEngagementStoreTest {
  @Test
  fun `counts survive reopening the store`() {
    val file = Files.createTempDirectory("engagement").resolve("counts.json").toFile()
    val first = ServeEngagementStore(file)

    assertEquals(1, first.incrementSystem("compose-m3"))
    assertEquals(2, first.incrementSystem("compose-m3"))
    assertEquals(1, first.incrementPreview("compose-m3", "button"))
    assertEquals(2, first.incrementPreview("compose-m3", "button"))

    val reopened = ServeEngagementStore(file)
    assertEquals(2, reopened.systemViews("compose-m3"))
    assertEquals(2, reopened.previewViews("compose-m3", "button"))
    assertFalse(file.readText().contains("127.0.0.1"), "store contains aggregate counts only")
  }

  @Test
  fun `rolling server instances merge instead of overwriting counts`() {
    val file = Files.createTempDirectory("engagement-roll").resolve("counts.json").toFile()
    val retiring = ServeEngagementStore(file)
    val replacement = ServeEngagementStore(file)

    assertEquals(1, retiring.incrementSystem("compose-m3"))
    assertEquals(2, replacement.incrementSystem("compose-m3"))
    assertEquals(3, retiring.incrementSystem("compose-m3"))
    assertEquals(1, replacement.incrementPreview("compose-m3", "button"))
    assertEquals(2, retiring.incrementPreview("compose-m3", "button"))

    assertEquals(3, replacement.systemViews("compose-m3"))
    assertEquals(2, replacement.previewViews("compose-m3", "button"))
  }

  @Test
  fun `old preview counters are bounded without dropping system totals`() {
    val store = ServeEngagementStore(maxPreviewEntries = 2)
    store.incrementSystem("app")
    store.incrementPreview("app", "oldest")
    store.incrementPreview("app", "middle")
    store.incrementPreview("app", "newest")

    assertEquals(1, store.systemViews("app"))
    assertEquals(0, store.previewViews("app", "oldest"))
    assertEquals(1, store.previewViews("app", "middle"))
    assertEquals(1, store.previewViews("app", "newest"))
  }

  @Test
  fun `old system counters and their previews are bounded`() {
    val store = ServeEngagementStore(maxSystemEntries = 2)
    store.incrementSystem("oldest")
    store.incrementPreview("oldest", "button")
    store.incrementSystem("middle")
    store.incrementSystem("newest")

    assertEquals(0, store.systemViews("oldest"))
    assertEquals(0, store.previewViews("oldest", "button"))
    assertEquals(1, store.systemViews("middle"))
    assertEquals(1, store.systemViews("newest"))
  }
}
