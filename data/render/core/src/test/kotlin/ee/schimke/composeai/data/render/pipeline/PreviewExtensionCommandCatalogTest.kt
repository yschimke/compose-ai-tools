package ee.schimke.composeai.data.render.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewExtensionCommandCatalogTest {
  @Test
  fun accessibilityOverlayCommandIsOwnedByAnnotatedPreviewDescriptor() {
    val overlayDescriptor =
      PreviewExtensionCommandCatalog.extensions.single { it.id == "a11y-overlay" }
    val annotatedDescriptor =
      PreviewExtensionCommandCatalog.extensions.single { it.id == "a11y-annotated-preview" }

    assertTrue(overlayDescriptor.cliCommands.isEmpty())

    val command = annotatedDescriptor.cliCommands.single { it.id == "a11y-overlay.get" }
    assertEquals(listOf("a11y/overlay"), command.productKinds)
    assertEquals(command, PreviewExtensionCommandCatalog.commandById("a11y-overlay.get"))
  }
}
