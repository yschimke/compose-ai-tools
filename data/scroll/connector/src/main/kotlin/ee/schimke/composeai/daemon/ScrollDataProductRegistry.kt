package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import ee.schimke.composeai.scroll.ScrollPreviewExtension
import java.io.File

/**
 * Daemon-side registry that advertises `render/scroll/long` and `render/scroll/gif` so a missing
 * scroll artefact triggers a per-preview re-render via `data/fetch` instead of the module-wide
 * `composePreviewRenderAll` round-trip the host used to fall back to (see issue #1528).
 *
 * **On-disk layout** matches what the gradle plugin's discovery writes (and what
 * `gradleService.readPreviewImage` reads on the host):
 * - `<modulePreviewsDir>/data/render-scroll-long/<previewId>.png`
 * - `<modulePreviewsDir>/data/render-scroll-gif/<previewId>.gif`
 *
 * The Gradle path is intentionally unchanged — the daemon writes to the same files Gradle would, so
 * the host's existing PNG/GIF read path keeps working on either producer.
 *
 * **Re-render contract** mirrors [AccessibilityDataProductRegistry]: both kinds are
 * `requiresRerender = true`, and a missing artefact returns
 * [DataProductRegistry.Outcome.RequiresRerender] with the matching `scroll-long` / `scroll-gif`
 * render mode so the dispatcher queues a per-preview re-render in the right scenario. Binary
 * artefacts (PNG / animated GIF) — never parse the file as JSON, so [allowInlineUpgrade] is
 * overridden to `false`.
 *
 * **Why per-kind subdirectories, not per-preview.** Unlike most file-backed registries (which use
 * the [FileBackedDataProductRegistry] default `<rootDir>/<previewId>/<file>` layout), scroll's
 * on-disk shape is per-kind (`render-scroll-long/<id>.png`). The [fileFor] override below pins that
 * layout so `gradleService.readPreviewImage` and `data/fetch` resolve to the exact same file.
 */
class ScrollDataProductRegistry(private val rootDir: File) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = ScrollPreviewExtension.KIND_LONG,
          schemaVersion = 1,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = true,
          displayName = "Long scroll capture",
          facets = listOf(DataProductFacet.ARTIFACT, DataProductFacet.IMAGE),
          mediaTypes = listOf("image/png"),
          sampling = SamplingPolicy.End,
        ),
        DataProductCapability(
          kind = ScrollPreviewExtension.KIND_GIF,
          schemaVersion = 1,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = true,
          displayName = "Scroll GIF capture",
          facets = listOf(DataProductFacet.ARTIFACT, DataProductFacet.ANIMATION),
          mediaTypes = listOf("image/gif"),
          sampling = SamplingPolicy.End,
        ),
      )
  ) {

  override fun fileFor(previewId: String, kind: String): File? {
    val (subdir, ext) =
      when (kind) {
        ScrollPreviewExtension.KIND_LONG -> SCROLL_LONG_SUBDIR to "png"
        ScrollPreviewExtension.KIND_GIF -> SCROLL_GIF_SUBDIR to "gif"
        else -> return null
      }
    return rootDir.resolve(subdir).resolve("$previewId.$ext")
  }

  override fun missingOutcome(previewId: String, kind: String): DataProductRegistry.Outcome =
    when (kind) {
      ScrollPreviewExtension.KIND_LONG ->
        DataProductRegistry.Outcome.RequiresRerender("scroll-long")
      ScrollPreviewExtension.KIND_GIF -> DataProductRegistry.Outcome.RequiresRerender("scroll-gif")
      else -> DataProductRegistry.Outcome.Unknown
    }

  override fun renderModeFor(kind: String): String? =
    when (kind) {
      ScrollPreviewExtension.KIND_LONG -> "scroll-long"
      ScrollPreviewExtension.KIND_GIF -> "scroll-gif"
      else -> null
    }

  override fun allowInlineUpgrade(kind: String): Boolean = false

  companion object {
    const val SCROLL_LONG_SUBDIR: String = "render-scroll-long"
    const val SCROLL_GIF_SUBDIR: String = "render-scroll-gif"
  }
}
