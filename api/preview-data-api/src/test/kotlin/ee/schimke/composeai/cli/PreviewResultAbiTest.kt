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
}
