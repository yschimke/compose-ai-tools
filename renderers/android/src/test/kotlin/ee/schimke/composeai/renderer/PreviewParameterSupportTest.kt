package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Provider whose first value is deliberately distinct from the second — see the test kdoc. */
class TintProviderFixture : PreviewParameterProvider<Long> {
  override val values = sequenceOf(0xFF43A047L, 0xFF1E88E5L)
}

@Composable
fun TintedSquareFixture(@PreviewParameter(TintProviderFixture::class) tint: Long) {
  Box(modifier = Modifier.fillMaxSize().background(Color(tint)))
}

@Composable
fun PlainSquareFixture() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350)))
}

/** Idiomatic `private fun` preview — compiles to a JVM-private static method. */
@Composable
private fun PrivateTintedSquareFixture(@PreviewParameter(TintProviderFixture::class) tint: Long) {
  Box(modifier = Modifier.fillMaxSize().background(Color(tint)))
}

/**
 * Resolution-level cover for [PreviewParameterSupport], the shared seam behind issue #3027.
 *
 * These assertions are the part of the fix that doesn't need a Robolectric sandbox: whether the
 * right JVM overload is found and which provider value is bound. The end-to-end "does it produce
 * pixels" half lives in `:daemon:android`'s `RenderEngineTest`.
 */
class PreviewParameterSupportTest {

  private val fixtureClass: Class<*> =
    Class.forName("ee.schimke.composeai.renderer.PreviewParameterSupportTestKt")

  @Test
  fun resolvesParameterlessPreviewWithNoArgs() {
    val resolved =
      PreviewParameterSupport.resolve(fixtureClass, "PlainSquareFixture", providerClassName = null)
    assertEquals(emptyList<Any?>(), resolved.args)
    // `(Composer, int)` — no leading value parameter.
    assertEquals(2, resolved.method.asMethod().parameterCount)
  }

  @Test
  fun resolvesParameterizedPreviewToItsFirstProviderValue() {
    // Before the fix this shape was resolved with the parameterless lookup, which matches only
    // `foo(Composer, int)` and therefore threw `NoSuchMethodException` — the failure reported in
    // issue #3027. The daemon renders one frame per preview id, so the FIRST value is the contract.
    val resolved =
      PreviewParameterSupport.resolve(
        fixtureClass,
        "TintedSquareFixture",
        providerClassName = TintProviderFixture::class.java.name,
      )
    assertEquals(listOf<Any?>(0xFF43A047L), resolved.args)
    val jvmMethod = resolved.method.asMethod()
    assertEquals("TintedSquareFixture", jvmMethod.name)
    // `(long, Composer, int)` — the parameterized overload, not the parameterless one.
    assertEquals(Long::class.javaPrimitiveType, jvmMethod.parameterTypes.first())
  }

  @Test
  fun resolvedMethodIsOpenedForReflectiveInvocation() {
    // A `private fun` preview resolves fine but `ComposableMethod.invoke` throws
    // IllegalAccessException unless the method is opened. `resolve` does it for every caller, so
    // the daemon's scroll / figma-svg-long / held-session paths can't each forget to.
    val resolved =
      PreviewParameterSupport.resolve(
        fixtureClass,
        "PrivateTintedSquareFixture",
        providerClassName = TintProviderFixture::class.java.name,
      )
    assertTrue("private preview method must be accessible", resolved.method.asMethod().isAccessible)

    val plain =
      PreviewParameterSupport.resolve(fixtureClass, "PlainSquareFixture", providerClassName = null)
    assertTrue("parameterless path opens its method too", plain.method.asMethod().isAccessible)
  }

  @Test
  fun limitedToOneValueEvenForAnUnboundedProvider() {
    // `take(1)` on the sequence, so an infinite `generateSequence` provider terminates.
    val values = PreviewParameterSupport.loadValues(TintProviderFixture::class.java.name, limit = 1)
    assertEquals(listOf<Any?>(0xFF43A047L), values)
  }

  @Test
  fun missingProviderClassFailsWithAnActionableMessage() {
    try {
      PreviewParameterSupport.resolve(
        fixtureClass,
        "TintedSquareFixture",
        providerClassName = "com.example.NotOnTheClasspath",
      )
      fail("expected a PreviewParameterLoadException")
    } catch (e: PreviewParameterLoadException) {
      assertTrue(
        "message should name the missing provider: ${e.message}",
        e.message!!.contains("com.example.NotOnTheClasspath"),
      )
    }
  }
}
