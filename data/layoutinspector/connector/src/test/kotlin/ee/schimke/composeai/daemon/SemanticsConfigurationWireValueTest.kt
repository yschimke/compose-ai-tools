package ee.schimke.composeai.daemon

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticsConfigurationWireValueTest {
  @Test
  fun `wire value is independent of semantics property insertion order`() {
    val roleFirst =
      SemanticsConfiguration().apply {
        this[SemanticsProperties.Role] = Role.Image
        this[SemanticsProperties.ContentDescription] = listOf("Mail")
      }
    val descriptionFirst =
      SemanticsConfiguration().apply {
        this[SemanticsProperties.ContentDescription] = listOf("Mail")
        this[SemanticsProperties.Role] = Role.Image
      }

    assertEquals(
      ComposeLayoutInspector.canonicalWireValue(roleFirst),
      ComposeLayoutInspector.canonicalWireValue(descriptionFirst),
    )
    assertEquals(
      "{ContentDescription=[Mail], Role=Image}",
      ComposeLayoutInspector.canonicalWireValue(roleFirst),
    )
  }
}
