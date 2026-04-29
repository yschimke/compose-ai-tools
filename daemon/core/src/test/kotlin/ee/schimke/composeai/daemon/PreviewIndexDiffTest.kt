package ee.schimke.composeai.daemon

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2.2 phase 2 — pins the diff path against the cached [PreviewIndex]. The diff is the producer of
 * the `discoveryUpdated` wire payload, so these cases lock its arithmetic verbatim against the
 * scenarios documented in
 * [DESIGN § 8 Tier 2](../../../../../../docs/daemon/DESIGN.md).
 */
class PreviewIndexDiffTest {

  private fun preview(
    id: String,
    sourceFile: String?,
    displayName: String? = null,
    group: String? = null,
  ): PreviewInfoDto =
    PreviewInfoDto(
      id = id,
      className = "com.example.${id.replaceFirstChar { it.uppercase() }}Kt",
      methodName = id,
      sourceFile = sourceFile,
      displayName = displayName,
      group = group,
    )

  @Test
  fun `pure addition — empty index plus two-preview scan emits two added`() {
    val index = PreviewIndex.empty()
    val scan =
      setOf(preview("Foo", sourceFile = "Foo.kt"), preview("Foo_dark", sourceFile = "Foo.kt"))
    val diff = index.diff(scan, Path.of("Foo.kt"))
    assertEquals(scan.toList(), diff.added.sortedBy { it.id })
    assertTrue(diff.removed.isEmpty())
    assertTrue(diff.changed.isEmpty())
    assertEquals(2, diff.totalPreviews)
    assertFalse(discoveryDiffEmpty(diff))

    index.applyDiff(diff)
    assertEquals(2, index.size)
    assertEquals(setOf("Foo", "Foo_dark"), index.ids())
  }

  @Test
  fun `pure removal — index has two from Foo, scan returns empty, both removed`() {
    val foo1 = preview("Foo", sourceFile = "Foo.kt")
    val foo2 = preview("Foo_dark", sourceFile = "Foo.kt")
    val index = PreviewIndex.fromMap(path = null, byId = mapOf("Foo" to foo1, "Foo_dark" to foo2))
    val diff = index.diff(emptySet(), Path.of("Foo.kt"))
    assertTrue(diff.added.isEmpty())
    assertEquals(setOf("Foo", "Foo_dark"), diff.removed.toSet())
    assertTrue(diff.changed.isEmpty())
    assertEquals(0, diff.totalPreviews)
    assertFalse(discoveryDiffEmpty(diff))

    index.applyDiff(diff)
    assertEquals(0, index.size)
  }

  @Test
  fun `field change — same id with different displayName is changed not added`() {
    val before = preview("Foo_bar", sourceFile = "Foo.kt", displayName = "X")
    val after = preview("Foo_bar", sourceFile = "Foo.kt", displayName = "Y")
    val index = PreviewIndex.fromMap(path = null, byId = mapOf("Foo_bar" to before))
    val diff = index.diff(setOf(after), Path.of("Foo.kt"))
    assertTrue(diff.added.isEmpty())
    assertTrue(diff.removed.isEmpty())
    assertEquals(listOf(after), diff.changed)
    assertEquals(1, diff.totalPreviews)
    assertFalse(discoveryDiffEmpty(diff))

    index.applyDiff(diff)
    assertEquals("Y", index.byId("Foo_bar")?.displayName)
  }

  @Test
  fun `no-op — scan matches index exactly, diff is empty`() {
    val foo = preview("Foo", sourceFile = "Foo.kt", displayName = "Foo!")
    val index = PreviewIndex.fromMap(path = null, byId = mapOf("Foo" to foo))
    val diff = index.diff(setOf(foo), Path.of("Foo.kt"))
    assertTrue(diff.added.isEmpty())
    assertTrue(diff.removed.isEmpty())
    assertTrue(diff.changed.isEmpty())
    assertEquals(1, diff.totalPreviews)
    assertTrue(discoveryDiffEmpty(diff))
  }

  @Test
  fun `file-scoped — Bar slice survives a Foo-targeted scan that returns nothing`() {
    val fooPreview = preview("Foo", sourceFile = "Foo.kt")
    val barPreview = preview("Bar", sourceFile = "Bar.kt")
    val index =
      PreviewIndex.fromMap(path = null, byId = mapOf("Foo" to fooPreview, "Bar" to barPreview))
    val diff = index.diff(emptySet(), Path.of("Foo.kt"))
    // Only Foo.kt's slice is touched. Bar's preview is NOT in `removed`.
    assertEquals(listOf("Foo"), diff.removed)
    assertTrue(diff.added.isEmpty())
    assertTrue(diff.changed.isEmpty())
    assertEquals(1, diff.totalPreviews)

    index.applyDiff(diff)
    assertEquals(setOf("Bar"), index.ids())
  }

  @Test
  fun `discoveryDiffEmpty true when all three lists empty`() {
    val empty = DiscoveryDiff(added = emptyList(), removed = emptyList(), changed = emptyList(), totalPreviews = 7)
    assertTrue(discoveryDiffEmpty(empty))
  }
}
