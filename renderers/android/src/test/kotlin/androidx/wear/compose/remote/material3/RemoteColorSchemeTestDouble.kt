package androidx.wear.compose.remote.material3

import androidx.compose.ui.graphics.Color

/**
 * Stand-ins for the two Wear Remote Material 3 types `RemoteCatalogValues` reads by reflection.
 *
 * They live in androidx's package, and carry androidx's simple names, because that is the contract
 * under test: `RemoteCatalogValues.colorSchemeRoles` gates on the receiver's fully-qualified name
 * before reading anything, so a double named anything else cannot reach the role lookup at all.
 *
 * Nothing shadows here. `renderers/android` deliberately declares no dependency on
 * `remote-material3` — the renderer stays reflection-only so it does not pin a fast-moving alpha —
 * so these are the only classes with these names on the test classpath. If that dependency is ever
 * added, this file has to move to a different name and `requireRemoteType` be reached another way.
 */
class RemoteColorScheme {
  // Declared with each longer sibling BEFORE the role it collides with. `Class.getMethods()`
  // promises no order, but HotSpot reports declared methods in class-file order, so a lookup that
  // went back to matching on `startsWith` would find the sibling first and fail this test rather
  // than passing it by luck. The assertions themselves do not depend on that.
  fun getPrimaryDim(): RemoteColor = role(1)

  fun getPrimaryContainer(): RemoteColor = role(2)

  fun getPrimary(): RemoteColor = role(3)

  fun getOnPrimaryContainer(): RemoteColor = role(4)

  fun getOnPrimary(): RemoteColor = role(5)

  fun getSecondaryDim(): RemoteColor = role(6)

  fun getSecondaryContainer(): RemoteColor = role(7)

  fun getSecondary(): RemoteColor = role(8)

  fun getOnSecondaryContainer(): RemoteColor = role(9)

  fun getOnSecondary(): RemoteColor = role(10)

  fun getTertiaryDim(): RemoteColor = role(11)

  fun getTertiaryContainer(): RemoteColor = role(12)

  fun getTertiary(): RemoteColor = role(13)

  fun getOnTertiaryContainer(): RemoteColor = role(14)

  fun getOnTertiary(): RemoteColor = role(15)

  fun getSurfaceContainerLow(): RemoteColor = role(16)

  fun getSurfaceContainerHigh(): RemoteColor = role(17)

  fun getSurfaceContainer(): RemoteColor = role(18)

  fun getOnSurfaceVariant(): RemoteColor = role(19)

  fun getOnSurface(): RemoteColor = role(20)

  fun getOutlineVariant(): RemoteColor = role(21)

  fun getOutline(): RemoteColor = role(22)

  fun getBackground(): RemoteColor = role(23)

  fun getOnBackground(): RemoteColor = role(24)

  fun getErrorDim(): RemoteColor = role(25)

  fun getErrorContainer(): RemoteColor = role(26)

  fun getError(): RemoteColor = role(27)

  fun getOnErrorContainer(): RemoteColor = role(28)

  fun getOnError(): RemoteColor = role(29)

  companion object {
    /** A colour no other role in this double answers with, so a mix-up names its own culprit. */
    fun expected(index: Int): Color = Color(0xFF000000.toInt() or index)

    private fun role(index: Int): RemoteColor = RemoteColor(expected(index))
  }
}

/**
 * The shape `remoteColorOrNull` reads first: a real `RemoteColor` answers
 * `getConstantValueOrNull()` with its resolved value when it has one. The id-provider fallback
 * beneath it is a separate mechanism and is not what this double is here to exercise.
 */
class RemoteColor(private val value: Color) {
  fun getConstantValueOrNull(): Any = value
}
