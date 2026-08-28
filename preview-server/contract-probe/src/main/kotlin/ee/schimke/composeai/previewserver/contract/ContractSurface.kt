package ee.schimke.composeai.previewserver.contract

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.WebEmbed
import ee.schimke.composeai.bundle.coordinates.CoordinateResolver
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
import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import ee.schimke.composeai.web.WebEscaping
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
 * What it deliberately does not do is exercise behaviour — there is no server here to exercise.
 *
 * This paragraph used to name two things it also could not cover: `serve`'s dependency on `:cli`'s
 * bundle format and on `:daemon:bta-host`. Both have since resolved, in different ways. The bundle
 * format became `:bundle-format`, published, and is named below. `:daemon:bta-host` was a
 * misreading: `ee.schimke.composeai.daemon.bta` is declared by BOTH it and `:daemon:core`, and the
 * types serve uses (`BtaCompileSession`, `DiagnosticCollector`) are the `:daemon:core` ones — see
 * the correction in `docs/design/PREVIEW_SERVER_SPLIT.md`. What the probe still cannot see is the
 * two unpublished modules serve loads by class name; those are recorded under
 * `reflectiveDependencies` in `scripts/serve-seam-allowlist.json`, where no import scan reaches
 * either.
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

  /**
   * The two web surfaces #4666 extracted. Naming the module in `contracts` proves an artifact with
   * that coordinate resolves; it does not prove the type inside it is still there, still public,
   * and still shaped the way serve calls it. These two references are what make that a checked
   * claim — if `WebEscaping.attr` is renamed or `WebEmbed` moves again, this file stops compiling
   * against the published jars, which is the whole point of the probe.
   */
  val webEscaping: WebEscaping = WebEscaping

  val webEmbed: WebEmbed = WebEmbed

  /** File IO. The server funnels reads/writes through `:common-io` like every other module. */
  val fileSystem: FileSystem = SystemFileSystem

  /**
   * The `.previewbundle` format. `serve` reads bundles — the manifest, the classpath entries, the
   * baked previews — and for most of this document's life it did that by reaching into `:cli`,
   * which is why the dependency-floor table listed the bundle format as the one entry that was not
   * a module at all. It is `:bundle-format` now, and naming its manifest here is what makes that a
   * checked claim rather than a stated one.
   */
  val bundleManifest: KClass<BundleReader.Manifest> = BundleReader.Manifest::class

  /**
   * Turning a bundle's recorded Maven coordinates back into local jars. Serve resolves a bundle's
   * classpath before it can hand the daemon a `-cp`, and while this lived in `:cli` it was the last
   * thing that made an extracted server depend on the CLI (preparation item 7).
   */
  val coordinateResolver: KClass<CoordinateResolver> = CoordinateResolver::class

  /**
   * Cropping a render down to the pixels that carry content — what the home page's hero thumbnails
   * and the compare wall's cells are cut with (`ServeWeb`, `ServeHttpServer`).
   *
   * `:common-image-crop` was added to `CONTRACT_PROJECTS` without a reference here, and adding the
   * artifact to the probe's `contracts` only proves that a JAR with that coordinate resolves. That
   * is the weaker half of what this file is for: removing or renaming the exact crop surface serve
   * calls would leave `checkContractSurface` green, which is the failure this whole file exists to
   * make impossible.
   *
   * `ContentCrop` alone, because that is serve's whole import from the module — `ServeWeb` and
   * `ServeHttpServer` name it and nothing else. The sibling `ContentBox` is published but unused
   * here, and naming a type serve does not reach for would have this file assert a dependency floor
   * higher than the real one.
   */
  val imageCrop: KClass<ContentCrop> = ContentCrop::class
}
