package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertTrue

class PreviewResultAbiTest {
  @Test
  fun `retains the constructor descriptor from before project directory`() {
    assertTrue(
      PreviewResult::class.java.constructors.any { constructor ->
        constructor.parameterTypes.toList() ==
          listOf(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            PreviewParams::class.java,
            List::class.java,
            String::class.java,
            String::class.java,
            java.lang.Boolean::class.java,
            Map::class.java,
          )
      }
    )
  }

  @Test
  fun `CatalogEntry retains the constructor descriptor from before kitValue`() {
    // Appending a defaulted parameter keeps SOURCE compatibility and changes the JVM descriptor,
    // so a consumer of the published `preview-data-api` compiled against the previous artifact
    // would fail with NoSuchMethodError. `CatalogEntry` is decoded far more often than it is
    // constructed, which is exactly why the break would surface late and somewhere else.
    assertTrue(
      CatalogEntry::class.java.constructors.any { constructor ->
        constructor.parameterTypes.toList() ==
          listOf(
            CatalogRole::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Boolean.TYPE,
            String::class.java,
            String::class.java,
            List::class.java,
            java.lang.Boolean.TYPE,
            String::class.java,
          )
      }
    )
  }

  @Test
  fun `Capture retains the constructor descriptor from before settle`() {
    // `@SettledPreview` (issue #4202) appended `settle` to `Capture`. Same break as the two above:
    // source-compatible, descriptor-incompatible. `Capture` is reached through `PreviewInfo`, so a
    // consumer that builds one to drive a render would fail with NoSuchMethodError on upgrade.
    assertTrue(
      Capture::class.java.constructors.any { constructor ->
        constructor.parameterTypes.toList() ==
          listOf(
            java.lang.Long::class.java,
            ScrollCapture::class.java,
            String::class.java,
            java.lang.Boolean.TYPE,
            kotlinx.serialization.json.JsonElement::class.java,
            kotlinx.serialization.json.JsonElement::class.java,
            kotlinx.serialization.json.JsonElement::class.java,
            kotlinx.serialization.json.JsonElement::class.java,
          )
      }
    )
  }
}
