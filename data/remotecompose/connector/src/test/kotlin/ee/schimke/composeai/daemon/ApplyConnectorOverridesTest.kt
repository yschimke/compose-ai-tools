package ee.schimke.composeai.daemon

import android.graphics.Bitmap
import androidx.compose.remote.player.core.state.StateUpdater
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [applyConnectorOverrides] — the leaf of the connector→remote-player bridge that
 * [RemoteOverridablePreview]'s `init` callback drives. The composable side is covered by the
 * `:samples:remotecompose` render pass; this file just pins which [StateUpdater] setter each
 * [RemoteNamedValue] variant lands on (incl. the int-as-bool fallback for `BooleanValue` and the
 * "#AARRGGBB" → packed-int decode for `ColorValue`), and that names pass through unprefixed —
 * `setUserLocalString` adds the `USER:` domain prefix internally on the player side.
 */
class ApplyConnectorOverridesTest {

  /** [StateUpdater] capture stub — records every `setUserLocal*` call as a tag/name/value triple. */
  private class CapturingStateUpdater : StateUpdater {
    val calls = mutableListOf<Triple<String, String, Any?>>()

    override fun setUserLocalString(name: String, value: String?) {
      calls += Triple("string", name, value)
    }

    override fun setUserLocalFloat(name: String, value: Float?) {
      calls += Triple("float", name, value)
    }

    override fun setUserLocalInt(name: String, value: Int?) {
      calls += Triple("int", name, value)
    }

    override fun setUserLocalColor(name: String, value: Int?) {
      calls += Triple("color", name, value)
    }

    override fun setUserLocalBitmap(name: String, value: Bitmap?) {
      calls += Triple("bitmap", name, value)
    }

    override fun setNamedLong(name: String, value: Long?) {
      calls += Triple("long", name, value)
    }
  }

  @Test
  fun `string value lands on setUserLocalString`() {
    val updater = CapturingStateUpdater()
    applyConnectorOverrides(updater, mapOf("label" to RemoteNamedValue.StringValue("Hello!")))
    assertEquals(listOf(Triple("string", "label", "Hello!" as Any?)), updater.calls)
  }

  @Test
  fun `float value lands on setUserLocalFloat`() {
    val updater = CapturingStateUpdater()
    applyConnectorOverrides(updater, mapOf("opacity" to RemoteNamedValue.FloatValue(0.5f)))
    assertEquals(listOf(Triple("float", "opacity", 0.5f as Any?)), updater.calls)
  }

  @Test
  fun `dp value lands on setUserLocalFloat as raw float`() {
    val updater = CapturingStateUpdater()
    applyConnectorOverrides(updater, mapOf("corner" to RemoteNamedValue.DpValue(8f)))
    assertEquals(listOf(Triple("float", "corner", 8f as Any?)), updater.calls)
  }

  @Test
  fun `int value lands on setUserLocalInt`() {
    val updater = CapturingStateUpdater()
    applyConnectorOverrides(updater, mapOf("count" to RemoteNamedValue.IntValue(42)))
    assertEquals(listOf(Triple("int", "count", 42 as Any?)), updater.calls)
  }

  @Test
  fun `boolean value collapses to int 0 or 1`() {
    val updater = CapturingStateUpdater()
    applyConnectorOverrides(
      updater,
      mapOf(
        "on" to RemoteNamedValue.BooleanValue(true),
        "off" to RemoteNamedValue.BooleanValue(false),
      ),
    )
    val byName = updater.calls.associate { (_, n, v) -> n to v }
    assertEquals(1, byName["on"])
    assertEquals(0, byName["off"])
  }

  @Test
  fun `color value decodes hash-AARRGGBB to packed int`() {
    val updater = CapturingStateUpdater()
    applyConnectorOverrides(
      updater,
      mapOf("seed" to RemoteNamedValue.ColorValue("#FF3366FF")),
    )
    assertEquals(
      listOf(Triple("color", "seed", 0xFF3366FF.toInt() as Any?)),
      updater.calls,
    )
  }

  @Test
  fun `multiple entries dispatch in iteration order without prefixing names`() {
    val updater = CapturingStateUpdater()
    applyConnectorOverrides(
      updater,
      linkedMapOf(
        "label" to RemoteNamedValue.StringValue("Hi"),
        "scale" to RemoteNamedValue.FloatValue(2f),
        "seed" to RemoteNamedValue.ColorValue("#FFCC0000"),
      ),
    )
    // Names stay bare — the `USER:` prefix is added by the player-side setter, not by the bridge.
    val names = updater.calls.map { it.second }
    assertEquals(listOf("label", "scale", "seed"), names)
  }

  @Test
  fun `empty override map is a no-op`() {
    val updater = CapturingStateUpdater()
    applyConnectorOverrides(updater, emptyMap())
    assertEquals(emptyList<Any>(), updater.calls)
  }
}
