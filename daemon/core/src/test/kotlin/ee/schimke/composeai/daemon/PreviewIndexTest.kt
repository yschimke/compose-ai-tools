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
  fun `loadFromFile parses nested params block (issue 420)`() {
    // Verifies the `params` block widened in #420 round-trips into [PreviewParamsDto]. Mirrors the
    // shape `DiscoverPreviewsTask` actually emits for a `@Preview(widthDp=200, heightDp=600,
    // density=3.0f, fontScale=1.3f, uiMode=0x20, locale="ja-JP")`.
    val tmp = Files.createTempFile("previews-params", ".json")
    Files.writeString(
      tmp,
      """
      {
        "module": ":samples:android",
        "variant": "debug",
        "previews": [
          {
            "id": "Foo_dark",
            "className": "com.example.FooKt",
            "functionName": "Foo",
            "params": {
              "name": "dark",
              "device": "id:pixel_5",
              "widthDp": 200,
              "heightDp": 600,
              "density": 3.0,
              "fontScale": 1.3,
              "showSystemUi": false,
              "showBackground": true,
              "backgroundColor": 4294967295,
              "uiMode": 32,
              "locale": "ja-JP",
              "kind": "COMPOSE"
            },
            "captures": [
              { "renderOutput": "renders/Foo_dark.png", "cost": 1.0 }
            ]
          }
        ]
      }
      """
        .trimIndent(),
    )
    try {
      val index = PreviewIndex.loadFromFile(tmp)
      val foo = index.byId("Foo_dark")!!
      val params = foo.params!!
      assertEquals(200, params.widthDp)
      assertEquals(600, params.heightDp)
      assertEquals(3.0f, params.density!!, 0.0f)
      assertEquals(1.3f, params.fontScale!!, 0.0f)
      assertEquals("ja-JP", params.locale)
      assertEquals(32, params.uiMode)
      assertEquals("id:pixel_5", params.device)
      assertEquals(true, params.showBackground)
      assertEquals(0xFFFFFFFFL, params.backgroundColor)
      // Night bit set ⇒ uiModeIsNight is true.
      assertTrue(uiModeIsNight(params.uiMode))
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  @Test
  fun `loadFromFile leaves params null when the block is absent`() {
    val tmp = Files.createTempFile("previews-no-params", ".json")
    Files.writeString(
      tmp,
      """
      {
        "previews": [
          { "id": "Bar", "className": "com.example.BarKt", "functionName": "Bar" }
        ]
      }
      """
        .trimIndent(),
    )
    try {
      val index = PreviewIndex.loadFromFile(tmp)
      assertNull(index.byId("Bar")?.params)
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  @Test
  fun `uiModeIsNight handles null and 0 as not-night`() {
    org.junit.Assert.assertFalse(uiModeIsNight(null))
    org.junit.Assert.assertFalse(uiModeIsNight(0))
    // UI_MODE_NIGHT_NO = 0x10 — the explicit "not-night" bit; should still be not night.
    org.junit.Assert.assertFalse(uiModeIsNight(0x10))
    // UI_MODE_NIGHT_YES = 0x20.
    assertTrue(uiModeIsNight(0x20))
    // The night bit can ride alongside other type bits (e.g. UI_MODE_TYPE_NORMAL = 0x01).
    assertTrue(uiModeIsNight(0x21))
  }

  @Test
  fun `loadFromFile ignores unknown plugin-side fields`() {
    // Mirrors the plugin's actual shape: nested `params` block, `captures` array,
    // `dataExtensionReports` pointer, and the legacy `accessibilityReport` alias an older plugin
    // might emit — all fields the daemon does NOT model. The legacy alias is here as a
    // forward-compat regression guard: even after the plugin drops the field, a manifest
    // produced by an older plugin must still parse cleanly when read by a newer daemon.
    val tmp = Files.createTempFile("previews-extras", ".json")
    Files.writeString(
      tmp,
      """
      {
        "module": ":samples:android",
        "variant": "debug",
        "dataExtensionReports": {"a11y": "accessibility.json"},
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
