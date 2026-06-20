package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.deviceframe.DeviceArtCatalog
import ee.schimke.composeai.data.deviceframe.DeviceFrameCompositor
import ee.schimke.composeai.io.SystemFileSystem
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Writes a per-render device-frame artifact: the captured PNG composited into a real device-art
 * bezel (with its hardware buttons), plus a manifest and the required CC-BY attribution.
 *
 * Layout under `<rootDir>/<previewId>/`:
 * - `deviceframe_<artId>.png` — the framed image.
 * - `deviceframe.json` — `{ device, artId, path, mediaType, attribution }`.
 * - `deviceframe-attribution.txt` — the CC-BY 3.0 attribution string (one per framed preview).
 *
 * Returns null (a no-op) when the device class has no shipped frame, the bezel can't be fetched, or
 * the screenshot can't be decoded — framing is best-effort and never invalidates the base capture.
 */
object DeviceFrameDataProducer {

  private val json = Json {
    encodeDefaults = true
    prettyPrint = false
  }

  const val MANIFEST: String = "deviceframe.json"
  const val ATTRIBUTION_FILE: String = "deviceframe-attribution.txt"
  const val MEDIA_TYPE: String = "image/png"

  @Serializable
  data class Manifest(
    val device: String?,
    val artId: String,
    val path: String,
    val mediaType: String,
    val attribution: String,
  )

  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    pngFile: File,
    device: String?,
    settings: DeviceFrameConfig.Settings,
    source: DeviceArtSource,
    fileSystem: FileSystem = SystemFileSystem,
  ): Manifest? {
    val spec =
      when (val selection = settings.selection) {
        is DeviceFrameConfig.Selection.Auto -> DeviceArtCatalog.forDeviceString(device)
        is DeviceFrameConfig.Selection.Forced -> DeviceArtCatalog.byArtId(selection.artId)
      } ?: return null

    val layers = LinkedHashMap<String, BufferedImage>()
    for (resource in spec.resources) {
      // Honour the shadow/glare toggles before paying for the fetch.
      if (resource == DeviceArtCatalog.SHADOW && !settings.includeShadow) continue
      if (resource == DeviceArtCatalog.FORE && !settings.includeGlare) continue
      val bytes = source.fetch(spec.artId, resource)
      if (bytes == null) {
        if (resource == DeviceArtCatalog.BACK) return null // required layer unavailable
        continue
      }
      val image = ImageIO.read(bytes.inputStream())
      if (image == null) {
        if (resource == DeviceArtCatalog.BACK) return null
        continue
      }
      layers[resource] = image
    }
    if (DeviceArtCatalog.BACK !in layers) return null

    val screenshot =
      ImageIO.read(fileSystem.read(pngFile.path.toPath()) { readByteArray() }.inputStream())
        ?: return null

    val framed =
      DeviceFrameCompositor.composite(
        screenshot = screenshot,
        layers = layers,
        spec = spec,
        includeShadow = settings.includeShadow,
        includeGlare = settings.includeGlare,
      )

    val previewDir = rootDir.resolve(previewId)
    val outFile = previewDir.resolve("deviceframe_${spec.artId}.png")
    fileSystem.createDirectories(previewDir.path.toPath())
    fileSystem.write(outFile.path.toPath()) { ImageIO.write(framed, "png", outputStream()) }

    val manifest =
      Manifest(
        device = device,
        artId = spec.artId,
        path = outFile.absolutePath,
        mediaType = MEDIA_TYPE,
        attribution = DeviceArtCatalog.ATTRIBUTION,
      )
    fileSystem.write(previewDir.resolve(MANIFEST).path.toPath()) {
      writeUtf8(json.encodeToString(Manifest.serializer(), manifest))
    }
    fileSystem.write(previewDir.resolve(ATTRIBUTION_FILE).path.toPath()) {
      writeUtf8(DeviceArtCatalog.ATTRIBUTION + "\n")
    }
    return manifest
  }
}
