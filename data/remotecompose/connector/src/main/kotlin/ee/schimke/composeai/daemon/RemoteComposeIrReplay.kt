@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.collection.MutableObjectIntMap
import androidx.collection.ObjectIntMap
import androidx.collection.emptyObjectIntMap
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.render.IrSidecarChannel
import ee.schimke.composeai.data.render.extensions.IrReplayComposableProvider
import java.lang.reflect.Modifier

/**
 * Replays a Remote Compose preview from a bundle's captured IR (schema v5): the serialized
 * `RemoteDocument` bytes ([IrSidecarChannel.FORMAT_REMOTECOMPOSE], `ir/<id>.rc`) that
 * [RemoteOverridablePreview]'s capture path emitted. Reconstructs the document with
 * `RemoteDocument(bytes)` and hands it to the same `RemoteDocumentPlayer` the live path uses — so a
 * bundle renders with **no** reference to the `@RemoteComposable` body that produced it (its class
 * was dropped at pack time).
 *
 * The daemon reaches this reflectively (it can't compile against the alpha player), so [Replay] is
 * an instance method with a fixed `(ByteArray)` signature and the class has a no-arg constructor —
 * matching what `RenderEngine` resolves via `getDeclaredComposableMethod`, the same shape as
 * `PreviewWrapperProvider.Wrap`. Seeded `renderNow.overrides.remoteCompose` named values are
 * applied through the player's `StateUpdater` via [applyConnectorOverrides], so replay honours
 * overrides exactly like the live path (a no-op when none are seeded).
 */
class RemoteComposeIrReplay {
  @Composable
  fun Replay(bytes: ByteArray) {
    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics
    val remoteDocument = remember(bytes) { RemoteDocument(bytes) }
    val seededOverrides = RemoteComposeController.namedValues.value

    // Which player draws is read from the controller rather than passed in: the daemon resolves
    // this
    // composable reflectively against a fixed `(ByteArray)` signature (see the class doc), so there
    // is no parameter to thread a choice through. Null — nothing asked for a player — takes the
    // embedded one, matching what a capture bakes through and what the viewer opens on; only an
    // explicit `?rcPlayer=java` (RemoteComposePlayerKind.VIEW) selects the view-backed lane.
    val embedded =
      RemoteComposeController.player.value != RemoteComposePlayerKind.VIEW &&
        isEmbeddedPlayerAvailable

    if (embedded) {
      // The embedded player owns its own `RemoteContext` and applies the document itself, so there
      // is no `init`/`StateUpdater` hook to seed through the way the view player has. Named colour
      // overrides are the one seeded facet it accepts up front.
      ExperimentalRemoteDocumentPlayer(
        document = remoteDocument,
        namedColorOverrides = seededOverrides.toNamedColorOverrides(),
      )
    } else {
      RemoteDocumentPlayer(
        document = remoteDocument.document,
        documentWidth = displayMetrics.widthPixels,
        documentHeight = displayMetrics.heightPixels,
        init = { player ->
          applyConnectorOverrides(player.stateUpdater, seededOverrides)
          installGoogleFontTypefaceResolver(player)
        },
      )
    }
  }
}

/**
 * The colour subset of the seeded named values, in the shape [ExperimentalRemoteDocumentPlayer]
 * takes (variable name -> ARGB int).
 *
 * This is deliberately *narrower* than [applyConnectorOverrides], which the view player gets: that
 * one also pushes string / float / int / dp / boolean seeds through the player's `StateUpdater`.
 * The embedded player exposes no equivalent seeding hook — it builds its own `RemoteContext` and
 * applies the document during composition — so non-colour overrides do **not** reach it. A render
 * that seeds them and then selects the embedded player will differ from the view player for that
 * reason alone, which is a property of the two players' APIs rather than a rendering divergence;
 * keep it in mind when reading a `rc-compare` row for a preview that carries knobs.
 *
 * Invalid hex is skipped rather than thrown, and a six-digit value is read as opaque — both through
 * the shared [rcColorToArgb], so the embedded player and the view player cannot disagree about what
 * the same seed means.
 */
internal fun Map<String, RemoteNamedValue>.toNamedColorOverrides(): ObjectIntMap<String> {
  val colors = entries.mapNotNull { (name, value) ->
    val color = value as? RemoteNamedValue.ColorValue ?: return@mapNotNull null
    val argb = rcColorToArgb(color.argb) ?: return@mapNotNull null
    name to argb
  }
  if (colors.isEmpty()) return emptyObjectIntMap()
  return MutableObjectIntMap<String>(colors.size).apply { colors.forEach { (n, v) -> put(n, v) } }
}

internal const val EMBEDDED_PLAYER_FACADE =
  "androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayerKt"

internal const val EMBEDDED_PLAYER_ENTRY_POINT = "ExperimentalRemoteDocumentPlayer"

/**
 * The parameter types of the [EMBEDDED_PLAYER_ENTRY_POINT] overload the two call sites in this
 * module compile down to, in declaration order.
 *
 * The tail — `Composer, int, int` — is Compose's own ABI (composer, changed mask, defaults mask); a
 * Kotlin call site that omits defaults still invokes this full method rather than a `$default`
 * bridge, which is why an argument-order change upstream is a *link* error at render time and not
 * something the compiler can see.
 *
 * Pinned as strings rather than `Class` literals on purpose: the whole point is to answer "is the
 * method this code was compiled against actually on the runtime classpath" without loading a single
 * one of those types, so a classpath missing them answers `false` instead of throwing. Kept honest
 * by `EmbeddedPlayerAvailabilityTest`, which reads this module's own compiled call site out of its
 * constant pool and asserts the descriptor there is the one this list spells.
 */
internal val EMBEDDED_PLAYER_ENTRY_POINT_PARAMETERS: List<String> =
  listOf(
    "androidx.compose.remote.player.core.RemoteDocument",
    "androidx.compose.ui.Modifier",
    "int",
    "androidx.collection.ObjectIntMap",
    "androidx.compose.remote.player.compose.embedded.RcImageLoader",
    "kotlin.jvm.functions.Function1",
    "kotlin.jvm.functions.Function2",
    "kotlin.jvm.functions.Function3",
    "androidx.compose.runtime.Composer",
    "int",
    "int",
  )

/**
 * Whether [EMBEDDED_PLAYER_FACADE] on [classLoader] declares the exact entry point this module was
 * compiled against.
 *
 * A class-presence check is NOT enough, and that is not hypothetical. The vendored player
 * (`:third-party-rc-embedded-player`) shares its package with what `androidx.compose.remote:
 * remote-player-compose` publishes, and from androidx-main build 16130474 that artifact ships the
 * embedded player itself — same fully-qualified names, a different
 * `ExperimentalRemoteDocumentPlayer` signature (`theme` moved to the end, a `customPlugins`
 * parameter added, our removed `autoUpdate`). Two copies of one class in front of one classloader
 * means whichever jar comes first wins, so on a runtime classpath carrying both, `Class.forName`
 * succeeds against a class that does not have the method — and the render dies with
 * `NoSuchMethodError: 'void
 * …ExperimentalRemoteDocumentPlayerKt.ExperimentalRemoteDocumentPlayer(…)'`. That error is
 * non-recoverable by construction, so `serve` disables the catalog's whole live render lane on it
 * (`remote-m3` on preview.coo.ee, 22 Aug 2026) and falls back to baked PNGs.
 *
 * Resolving the *method* instead makes that case what it should always have been: the same graceful
 * degrade to the View-backed `RemoteDocumentPlayer` a consumer without the player at all gets.
 */
internal fun embeddedPlayerEntryPointPresent(classLoader: ClassLoader?): Boolean = runCatching {
  declaresEntryPoint(
    Class.forName(EMBEDDED_PLAYER_FACADE, false, classLoader),
    EMBEDDED_PLAYER_ENTRY_POINT_PARAMETERS,
  )
}
  .getOrDefault(false)

/**
 * Whether [facade] declares [EMBEDDED_PLAYER_ENTRY_POINT] in the exact shape the call site links
 * against: `public static void` taking exactly [parameters].
 *
 * The modifiers and return type are checked alongside the signature because they are separately
 * load-bearing — the compiled call is an `invokestatic …(…)V`, so a same-named method that is
 * non-static, non-public or returns something else fails to link just as hard, with
 * `IncompatibleClassChangeError` / `IllegalAccessError` / `NoSuchMethodError` respectively. A
 * Kotlin top-level `@Composable fun` always compiles to `public static void` on its `…Kt` facade,
 * so this costs nothing today; it is here so the predicate answers the question it appears to
 * answer rather than a near neighbour of it.
 */
internal fun declaresEntryPoint(facade: Class<*>, parameters: List<String>): Boolean =
  facade.declaredMethods.any { method ->
    method.name == EMBEDDED_PLAYER_ENTRY_POINT &&
      method.parameterTypes.map { it.name } == parameters &&
      method.returnType == Void.TYPE &&
      Modifier.isStatic(method.modifiers) &&
      Modifier.isPublic(method.modifiers)
  }

/**
 * Whether an embedded player this connector can actually call is on the runtime classpath. Resolved
 * once per JVM. A consumer that doesn't ship one — or ships one whose entry point has drifted —
 * silently falls back to the view player instead of dying with `NoClassDefFoundError` /
 * `NoSuchMethodError`, the same classloader gate `:daemon:android` uses before registering the
 * Remote Compose extension at all.
 */
internal val isEmbeddedPlayerAvailable: Boolean by lazy {
  embeddedPlayerEntryPointPresent(RemoteComposeIrReplay::class.java.classLoader)
}

/** Registers [RemoteComposeIrReplay] as the replay composable for `remotecompose` IR. */
class RemoteComposeIrReplayProvider : IrReplayComposableProvider {
  override val format: String = IrSidecarChannel.FORMAT_REMOTECOMPOSE

  override fun replayClass(): Class<*> = RemoteComposeIrReplay::class.java
}
