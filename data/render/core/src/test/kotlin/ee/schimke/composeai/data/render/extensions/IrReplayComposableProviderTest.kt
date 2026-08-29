package ee.schimke.composeai.data.render.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies [loadIrReplayClass] discovers a registered [IrReplayComposableProvider] by format. The
 * fake provider below is wired through this module's test `META-INF/services/...` resource, exactly
 * as `:data-remotecompose-connector` registers its real one — so this exercises the same
 * ServiceLoader path the daemon uses.
 */
class IrReplayComposableProviderTest {

  @Test
  fun `resolves the registered provider's class by format`() {
    assertEquals(
      FakeReplayComposable::class.java,
      loadIrReplayClass("test-fmt", javaClass.classLoader),
    )
  }

  @Test
  fun `returns null for an unregistered format`() {
    assertNull(loadIrReplayClass("no-such-format", javaClass.classLoader))
  }
}

/** Registered via `src/test/resources/META-INF/services/...IrReplayComposableProvider`. */
class FakeReplayProvider : IrReplayComposableProvider {
  override val format: String = "test-fmt"

  override fun replayClass(): Class<*> = FakeReplayComposable::class.java
}

/** Stand-in for a connector's replay composable host — only its identity matters to the test. */
class FakeReplayComposable
