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
  fun `wire value preserves authored strings that resemble runtime identities`() {
    val custom = SemanticsPropertyKey<String>("Custom")
    val configuration = SemanticsConfiguration().apply { this[custom] = "account.name@abcdef.com" }

    assertEquals(
      "{Custom=account.name@abcdef.com}",
      ComposeLayoutInspector.canonicalWireValue(configuration),
    )
  }

  @Test
  fun `wire value canonicalizes short runtime identities from non-string values`() {
    val custom = SemanticsPropertyKey<Any>("Custom")
    val runtimeValue =
      object {
        override fun toString() = "example.Value@abcde"
      }
    val configuration = SemanticsConfiguration().apply { this[custom] = runtimeValue }

    assertEquals(
      "{Custom=example.Value@<identity>}",
      ComposeLayoutInspector.canonicalWireValue(configuration),
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
