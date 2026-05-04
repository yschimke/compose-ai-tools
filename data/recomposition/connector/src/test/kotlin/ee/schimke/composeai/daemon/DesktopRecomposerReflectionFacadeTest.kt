package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopRecomposerReflectionFacadeTest {
  @Test
  fun readsPrivateFieldsFromSuperclass() {
    val receiver = DerivedFieldHolder("value")

    val value = DesktopRecomposerReflectionFacade.fieldValue(receiver, "held")

    assertEquals("value", value)
  }

  @Test(expected = NoSuchFieldException::class)
  fun failsClearlyForMissingFields() {
    DesktopRecomposerReflectionFacade.fieldValue(DerivedFieldHolder("value"), "missing")
  }

  private open class BaseFieldHolder(private val held: String)

  private class DerivedFieldHolder(value: String) : BaseFieldHolder(value)
}
