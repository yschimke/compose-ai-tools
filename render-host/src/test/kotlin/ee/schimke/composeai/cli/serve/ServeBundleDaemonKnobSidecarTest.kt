package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeDeclarationsPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Hermetic coverage for [ServeBundleDaemon]'s knob-sidecar plumbing — the serve-advertise half of
 * the "auto-render Remote Compose knobs" chain. Builds a fake bundle (a `previews.json` + a zip of
 * `previews/<id>.*.json` sidecars) entirely in a temp dir, then drives [ServeBundleDaemon
 * .extractKnobSidecars] + [ServeBundleDaemon.readPreviews] directly — no daemon, no Gradle, no
 * packed bundle — and asserts each [ServePreview] carries both channels: the Remote Compose
 * `remoteComposeKnobs` (from `previews/<id>.remotecompose.json`) and the plain-Compose `overrides`
 * (from `previews/<id>.overrides.json`), while a preview with no sidecar carries neither.
 */
class ServeBundleDaemonKnobSidecarTest {

  private val json = Json { encodeDefaults = true }

  @Test
  fun `readPreviews folds both the remotecompose and overrides sidecars onto each preview`() {
    val dir = Files.createTempDirectory("serve-knob-sidecar").toFile()
    val previewsDir = java.io.File(dir, "previews").apply { mkdirs() }

    // Three previews: one with an RC knob, one with a plain-Compose override, one bare.
    val rcId = "pkg.CatalogPreviewsKt.ShaderGradientSticker"
    val overrideId = "pkg.CatalogPreviewsKt.FilledButton_Light"
    val bareId = "pkg.CatalogPreviewsKt.PlainSticker"
    val manifest =
      PreviewManifest(
        module = ":catalog",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(id = rcId, functionName = "ShaderGradientSticker", className = "pkg.X"),
            PreviewInfo(id = overrideId, functionName = "FilledButton", className = "pkg.X"),
            PreviewInfo(id = bareId, functionName = "PlainSticker", className = "pkg.X"),
          ),
      )
    val previewsJson = java.io.File(dir, "previews.json")
    previewsJson.writeText(json.encodeToString(PreviewManifest.serializer(), manifest))

    // A bundle zip carrying the two knob sidecars (and an unrelated PNG entry the extractor must
    // ignore, plus a zip-slip attempt the extractor must refuse to write outside previewsDir).
    val rcPayload =
      RemoteComposeDeclarationsPayload(
        listOf(
          RemoteComposeKnobDeclaration("shaderColor", RemoteNamedValue.ColorValue("#FF7DE2FF"))
        )
      )
    val overridesPayload =
      PreviewOverridesPayload(
        listOf(
          PreviewOverrideDeclaration(
            key = "label",
            type = "string",
            default = PreviewOverrideValue.StringValue("Filled"),
          )
        )
      )
    val zipBytes =
      zipOf(
        "previews/$rcId.remotecompose.json" to
          json.encodeToString(RemoteComposeDeclarationsPayload.serializer(), rcPayload),
        "previews/$overrideId.overrides.json" to
          json.encodeToString(PreviewOverridesPayload.serializer(), overridesPayload),
        "previews/$rcId.png" to "not-json",
        "previews/../escape.remotecompose.json" to "{}",
      )

    ServeBundleDaemon.extractKnobSidecars(zipBytes, previewsDir, SystemFileSystem)
    val previews = ServeBundleDaemon.readPreviews(previewsJson, previewsDir, SystemFileSystem)

    assertEquals(3, previews.size, "all three manifest previews should surface")

    val rc = previews.first { it.id == rcId }
    assertEquals(
      listOf("shaderColor"),
      rc.remoteComposeKnobs.map { it.name },
      "the RC sticker should advertise its declared color knob",
    )
    assertTrue(
      rc.remoteComposeKnobs.single().default is RemoteNamedValue.ColorValue,
      "the knob default should decode as a ColorValue, got ${rc.remoteComposeKnobs.single().default}",
    )
    assertTrue(rc.overrides.isEmpty(), "the RC sticker declared no plain-Compose override")

    val ov = previews.first { it.id == overrideId }
    assertEquals(
      listOf("label"),
      ov.overrides.map { it.key },
      "the button should advertise its declared plain-Compose label knob",
    )
    assertTrue(ov.remoteComposeKnobs.isEmpty(), "the button declared no RC knob")

    val bare = previews.first { it.id == bareId }
    assertTrue(
      bare.remoteComposeKnobs.isEmpty() && bare.overrides.isEmpty(),
      "bare preview: no knobs",
    )

    // The zip-slip entry must not have escaped previewsDir.
    assertTrue(
      !java.io.File(dir, "escape.remotecompose.json").exists(),
      "extractKnobSidecars must refuse a `..` path traversal",
    )
  }

  private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zos ->
      for ((name, body) in entries) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(body.toByteArray())
        zos.closeEntry()
      }
    }
    return bos.toByteArray()
  }
}
