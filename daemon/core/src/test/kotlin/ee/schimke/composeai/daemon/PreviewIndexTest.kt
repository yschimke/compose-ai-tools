package ee.schimke.composeai.daemon

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2.2 phase 1 — pins the daemon's `previews.json` parse contract. The plugin owns the
 * authoritative shape of the file (gradle-plugin's `PreviewData.kt`); these tests assert that
 * [PreviewIndex] tolerates the documented happy path AND every documented degraded mode (missing
 * file, malformed JSON, plugin-side schema additions) without throwing.
 */
class PreviewIndexTest {

  @Test
  fun `loadFromFile happy path indexes previews by id`() {
    val tmp = Files.createTempFile("previews", ".json")
    Files.writeString(
      tmp,
      """
      {
        "module": ":samples:android",
        "variant": "debug",
        "previews": [
          {
            "id": "Foo_1",
            "className": "com.example.FooKt",
            "functionName": "Foo",
            "sourceFile": "Foo.kt"
          },
          {
            "id": "Bar_1",
            "className": "com.example.BarKt",
            "functionName": "Bar"
          },
          {
            "id": "Baz_1",
            "className": "com.example.BazKt",
            "functionName": "Baz",
            "sourceFile": "Baz.kt"
          }
        ]
      }
      """
        .trimIndent(),
    )
    try {
      val index = PreviewIndex.loadFromFile(tmp)
      assertEquals(3, index.size)
      assertEquals(setOf("Foo_1", "Bar_1", "Baz_1"), index.ids())
      val foo = index.byId("Foo_1")
      assertNotNull(foo)
      assertEquals("com.example.FooKt", foo!!.className)
      assertEquals("Foo", foo.methodName)
      assertEquals("Foo.kt", foo.sourceFile)
      // Optional sourceFile parses as null when omitted.
      assertNull(index.byId("Bar_1")?.sourceFile)
      assertEquals(tmp.toAbsolutePath(), index.path)
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  @Test
  fun `loadFromFile returns empty index when file is missing`() {
    // Build a path that's guaranteed not to exist.
    val tmp = Files.createTempFile("previews-missing", ".json")
    Files.deleteIfExists(tmp)
    val index = PreviewIndex.loadFromFile(tmp)
    assertEquals(0, index.size)
    assertNull(index.path)
    assertTrue(index.ids().isEmpty())
  }

  @Test
  fun `loadFromFile returns empty index on malformed JSON`() {
    val tmp = Files.createTempFile("previews-bad", ".json")
    Files.writeString(tmp, "this is not JSON {{{")
    try {
      val index = PreviewIndex.loadFromFile(tmp)
      assertEquals(0, index.size)
      assertNull(index.path)
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  @Test
  fun `loadFromFile ignores unknown plugin-side fields`() {
    // Mirrors the plugin's actual shape: nested `params` block, `captures` array,
    // `accessibilityReport` pointer — all fields the daemon does NOT model.
    val tmp = Files.createTempFile("previews-extras", ".json")
    Files.writeString(
      tmp,
      """
      {
        "module": ":samples:android",
        "variant": "debug",
        "accessibilityReport": "accessibility.json",
        "previews": [
          {
            "id": "Foo_1",
            "className": "com.example.FooKt",
            "functionName": "Foo",
            "sourceFile": "Foo.kt",
            "params": {
              "name": "Foo",
              "device": "spec:width=400dp,height=800dp",
              "fontScale": 1.0,
              "showSystemUi": false,
              "showBackground": false,
              "backgroundColor": 0,
              "uiMode": 0,
              "kind": "COMPOSE"
            },
            "captures": [
              { "renderOutput": "renders/Foo_1.png", "cost": 1.0 }
            ]
          }
        ]
      }
      """
        .trimIndent(),
    )
    try {
      val index = PreviewIndex.loadFromFile(tmp)
      assertEquals(1, index.size)
      val foo = index.byId("Foo_1")
      assertNotNull(foo)
      assertEquals("Foo.kt", foo!!.sourceFile)
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  @Test
  fun `empty has size 0 and null path`() {
    val empty = PreviewIndex.empty()
    assertEquals(0, empty.size)
    assertNull(empty.path)
    assertTrue(empty.ids().isEmpty())
    assertNull(empty.byId("anything"))
  }
}
