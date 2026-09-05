package androidx.wear.compose.remote.material3

import androidx.compose.ui.graphics.Color

/**
 * Stand-ins for the Wear Remote Material 3 types `RemoteCatalogValues` reads by reflection.
 *
 * They live in androidx's package, and carry androidx's simple names, because that is the contract
 * under test: `RemoteCatalogValues.colorSchemeRoles` gates on the receiver's fully-qualified name
 * before reading anything, so a double named anything else cannot reach the role lookup at all.
 *
 * Nothing shadows here. `renderers/android` deliberately declares no dependency on
 * `remote-material3` — the renderer stays reflection-only so it does not pin a fast-moving alpha —
 * so these are the only classes with these names on the test classpath. If that dependency is ever
 * added, this file has to move and `requireRemoteType` be satisfied another way.
 *
 * [named] picks which of the two shapes a role answers with. `false` is the plain public constant;
 * `true` is what a real catalog actually holds — see [NamedRemoteColor].
 */
class RemoteColorScheme(
  private val named: Boolean = false,
  /**
   * Answer every role with a provider carrying TWO captured `long`s (see [TwoLongFallback]) — the
   * shape that makes the field lookup's ordering observable.
   */
  private val twoLong: Boolean = false,
) {
  // Declared with each longer sibling BEFORE the role it collides with, so a lookup that went back
  // to matching on `startsWith` finds the sibling first and fails these tests rather than passing
  // them by luck. The assertions themselves do not depend on that ordering.
  fun getPrimaryDim(): Any = role(1)

  fun getPrimaryContainer(): Any = role(2)

  fun getPrimary(): Any = role(3)

  fun getOnPrimaryContainer(): Any = role(4)

  fun getOnPrimary(): Any = role(5)

  fun getSecondaryDim(): Any = role(6)

  fun getSecondaryContainer(): Any = role(7)

  fun getSecondary(): Any = role(8)

  fun getOnSecondaryContainer(): Any = role(9)

  fun getOnSecondary(): Any = role(10)

  fun getTertiaryDim(): Any = role(11)

  fun getTertiaryContainer(): Any = role(12)

  fun getTertiary(): Any = role(13)

  fun getOnTertiaryContainer(): Any = role(14)

  fun getOnTertiary(): Any = role(15)

  fun getSurfaceContainerLow(): Any = role(16)

  fun getSurfaceContainerHigh(): Any = role(17)

  fun getSurfaceContainer(): Any = role(18)

  fun getOnSurfaceVariant(): Any = role(19)

  fun getOnSurface(): Any = role(20)

  fun getOutlineVariant(): Any = role(21)

  fun getOutline(): Any = role(22)

  fun getBackground(): Any = role(23)

  fun getOnBackground(): Any = role(24)

  fun getErrorDim(): Any = role(25)

  fun getErrorContainer(): Any = role(26)

  fun getError(): Any = role(27)

  fun getOnErrorContainer(): Any = role(28)

  fun getOnError(): Any = role(29)

  private fun role(index: Int): Any =
    when {
      twoLong -> TwoLongNamedRemoteColor(expected(index), DECOY_BITS)
      named -> NamedRemoteColor(expected(index))
      else -> RemoteColor(expected(index))
    }

  companion object {
    /** A colour no other role in this double answers with, so a mix-up names its own culprit. */
    fun expected(index: Int): Color = Color(0xFF000000.toInt() or index)

    /**
     * The value the SECOND captured `long` holds — never a role's colour, so reading the wrong
     * field is visible as this exact colour rather than as a plausible near-miss.
     */
    val DECOY_BITS: Long = Color(0xFFFF00FF.toInt()).value.toLong()
  }
}

/**
 * The shape `remoteColorOrNull` reads first: a `RemoteColor` that has a resolved constant answers
 * `getConstantValueOrNull()` with it.
 */
class RemoteColor(private val value: Color) {
  fun getConstantValueOrNull(): Any = value
}

/**
 * The shape it falls back to, which is the one a real colour catalog actually takes.
 *
 * A `RemoteColor` built by `createNamedRemoteColor` exposes every role as a named value so a replay
 * can override it, so its public constant is null and the packed fallback survives only inside the
 * id-provider lambda. Two details of that are load-bearing and are reproduced here:
 * * the accessor is `internal`, so Kotlin appends the module name and the real JVM method is
 *   `getIdProvider$remote_creation_compose`; and
 * * the packed `Color` is held as the lambda's single captured `long`.
 *
 * Narrowing the role lookup to an exact name would silently cut this path — every role would
 * resolve to null and the sheet would render empty — which is why it is a test and not a comment.
 */
class NamedRemoteColor(value: Color) {
  private val provider = PackedFallback(value.value.toLong())

  fun getConstantValueOrNull(): Any? = null

  // Declared `internal` rather than spelled out, because Kotlin rejects `$` inside a backticked
  // identifier — the decoration cannot be written by hand. An `internal` member compiles to a
  // PUBLIC JVM method (which is exactly why it needs mangling to stay module-private), so it
  // reaches `Class.getMethods()` under a `getIdProvider$<module>` name, the same shape
  // `remote-creation-compose` publishes. The test asserts the mangling really happened rather than
  // trusting it.
  internal fun getIdProvider(): Any = provider
}

/** Stands in for the capturing lambda: one captured `long`, read back without invoking it. */
class PackedFallback(@Suppress("unused") private val bits: Long)

/**
 * The same lambda if `remote-creation-compose` ever captured a second `long`.
 *
 * Not speculative for its own sake: `Class.getDeclaredFields()` returns its elements in no
 * specified order, which is the same unspecified order that made eleven colour roles read a
 * sibling's colour. Today's lambda holds exactly one `long`, so the ambiguity is latent rather than
 * live — and a latent one is worth pinning cheaply, because the symptom is a catalog raster whose
 * colours change between runs of identical bytecode, which reads as everything except a bug.
 *
 * [other] is declared FIRST and is not the packed colour, so a lookup taking whatever the JVM
 * happened to list first can produce the wrong answer here, while a lookup that sorts cannot.
 */
class TwoLongFallback(
  @Suppress("unused") private val alsoLong: Long,
  @Suppress("unused") private val bits: Long,
)

/** A named colour whose provider carries the two-`long` shape above. */
class TwoLongNamedRemoteColor(value: Color, decoy: Long) {
  // The decoy is the FIRST constructor parameter, so `bits` is the second declared field: a lookup
  // taking whatever `getDeclaredFields()` listed first can land on either, and a sorted one lands
  // on `alsoLong` every time. The test asserts the sorted pick, which is the point — stability, not
  // which of the two it happens to be.
  private val provider = TwoLongFallback(decoy, value.value.toLong())

  fun getConstantValueOrNull(): Any? = null

  internal fun getIdProvider(): Any = provider
}
