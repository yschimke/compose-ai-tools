package ee.schimke.composeai.daemon

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
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

  @Test
  fun `wire value canonicalizes runtime identities inside string properties`() {
    val custom = SemanticsPropertyKey<String>("Custom")
    val first = SemanticsConfiguration().apply { this[custom] = "example.Value@1234abcd" }
    val second = SemanticsConfiguration().apply { this[custom] = "example.Value@8765dcba" }

    assertEquals(
      ComposeLayoutInspector.canonicalWireValue(first),
      ComposeLayoutInspector.canonicalWireValue(second),
    )
    assertEquals(
      "{Custom=example.Value@<identity>}",
      ComposeLayoutInspector.canonicalWireValue(first),
    )
  }

  @Test
  fun `wire value uses property value to break duplicate name ties`() {
    val firstKey = SemanticsPropertyKey<String>("Flag")
    val secondKey = SemanticsPropertyKey<String>("Flag")
    val configuration =
      SemanticsConfiguration().apply {
        this[firstKey] = "initial-a"
        this[secondKey] = "initial-b"
      }
    val firstValueInIterationOrder = configuration.first().value
    if (firstValueInIterationOrder == "initial-a") {
      configuration[firstKey] = "b"
      configuration[secondKey] = "a"
    } else {
      configuration[firstKey] = "a"
      configuration[secondKey] = "b"
    }

    assertEquals("{Flag=a, Flag=b}", ComposeLayoutInspector.canonicalWireValue(configuration))
  }
}
