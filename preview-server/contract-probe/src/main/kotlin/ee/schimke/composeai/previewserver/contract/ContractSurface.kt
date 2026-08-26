package ee.schimke.composeai.previewserver.contract

import ee.schimke.composeai.daemon.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeDocumentPayload
import ee.schimke.composeai.data.render.PreviewBackground
import ee.schimke.composeai.data.theme.ThemePayload
import ee.schimke.composeai.designpages.DesignPagesManifest
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import kotlin.reflect.KClass
import okio.FileSystem

/**
 * The surface `compose-preview serve` needs from outside itself, compiled against the published
 * artifacts rather than against the workspace.
 *
 * This file exists to fail. Issue #3824's claim is that the preview server is a *protocol client* —
 * that everything it reaches for outside its own package is a versioned contract someone else could
 * depend on. That claim is only worth acting on if it is checked, and the honest check is a
 * compilation against artifacts, in a build that cannot see the projects behind them.
 *
 * Every reference below is drawn from serve's real import set (`scripts/check-serve-seam.py`
 * enumerates it). If one of these types stops being published, changes shape, or moves into a
 * module that isn't a contract, the split's dependency floor moved — and it moved here, in a PR,
 * rather than on the day someone tries the extraction.
 *
 * Two things it deliberately does not do: exercise behaviour (there is no server here to exercise),
 * and cover `serve`'s dependency on `:cli`'s bundle format or on `:daemon:bta-host`. Neither is a
 * published contract yet; both are preparation items in `docs/design/PREVIEW_SERVER_SPLIT.md`, and
 * the day they can be named here is the day they stop being blockers.
 */
object ContractSurface {

  /** The protocol handshake and render request/response types — the server's primary language. */
  val protocol: List<KClass<*>> =
    listOf(UiMode::class, StreamCodec::class, PreviewOverrides::class, DeviceDimensions::class)

  /**
   * The payload schemas the viewer renders. These are `-core` modules on purpose: the *connectors*
   * that produce them run inside the daemon, on the render side of the boundary, and an extracted
   * server must never see one.
   */
  val payloads: List<KClass<*>> =
    listOf(
      ComposeSemanticsPayload::class,
      LayoutInspectorPayload::class,
      PreviewOverridesPayload::class,
      RemoteComposeDocumentPayload::class,
      ThemePayload::class,
      PreviewBackground::class,
      DesignPagesManifest::class,
    )

  /** How the server starts and drives a daemon it does not itself contain. */
  val daemonLaunch: KClass<DaemonLaunchDescriptor> = DaemonLaunchDescriptor::class

  /**
   * The render-session library — the "eat our own dogfood" surface. The server is one of its
   * consumers, not a privileged caller.
   */
  val renderSessions: RenderSessionFactory = SubprocessRenderSessions

  /** File IO. The server funnels reads/writes through `:common-io` like every other module. */
  val fileSystem: FileSystem = SystemFileSystem
}
