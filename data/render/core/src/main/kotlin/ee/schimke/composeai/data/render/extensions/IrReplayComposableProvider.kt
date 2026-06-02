package ee.schimke.composeai.data.render.extensions

import java.util.ServiceLoader

/**
 * SPI for replaying a bundle's captured intermediate representation (schema v5) through a
 * connector-provided composable, when the IR's runtime can't be a compile dependency of the daemon.
 *
 * Protolayout/tile IR replays through `:renderer-android`'s `TileIrReplayComposable`, which the
 * daemon compiles against directly. Remote Compose can't: its player (`RemoteDocumentPlayer`) lives
 * in the alpha `:data-remotecompose-connector` (compiled against a higher `compileSdk` the daemon
 * can't depend on). So the connector registers an [IrReplayComposableProvider] and the renderer
 * reaches the replay composable reflectively — the same indirection [loadPreviewWrapperClass] uses
 * for `@PreviewWrapper` substitution.
 *
 * Discovered through `ServiceLoader`; implementations register with a
 * `META-INF/services/ee.schimke.composeai.data.render.extensions.IrReplayComposableProvider` file
 * in the connector's resources. [replayClass] must expose a `@Composable Replay(bytes: ByteArray)`
 * method and a public no-arg constructor, because the renderer instantiates it and invokes `Replay`
 * via `getDeclaredComposableMethod` exactly as it does `PreviewWrapperProvider.Wrap`.
 */
interface IrReplayComposableProvider {
  /**
   * The IR format this provider replays — matches `IrSidecarChannel.FORMAT_*` (e.g.
   * `remotecompose`).
   */
  val format: String

  /** The class whose `@Composable Replay(bytes: ByteArray)` method renders the IR. */
  fun replayClass(): Class<*>
}

/**
 * Resolve the replay class registered for [format], or null when no provider is on the classpath
 * (the common non-bundle case, and any format without a connector). The renderer then falls back to
 * its normal class-reflection path. "First wins" in `ServiceLoader` order.
 */
fun loadIrReplayClass(
  format: String,
  classLoader: ClassLoader =
    Thread.currentThread().contextClassLoader ?: IrReplayComposableProvider::class.java.classLoader,
): Class<*>? =
  ServiceLoader.load(IrReplayComposableProvider::class.java, classLoader)
    .asSequence()
    .firstOrNull { it.format == format }
    ?.replayClass()
