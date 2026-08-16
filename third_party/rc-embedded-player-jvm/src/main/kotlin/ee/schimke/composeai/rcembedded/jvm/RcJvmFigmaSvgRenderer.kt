/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.rcembedded.jvm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.remote.core.operations.Theme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.jetbrains.skia.EncodedImageFormat

/**
 * Render a captured Remote Compose document through the embedded CMP/JVM player and export the
 * resulting ordinary Compose tree as a self-contained layered `compose/figma-svg` document.
 *
 * This deliberately mirrors the desktop [ImageComposeScene] post-capture path: collect composition
 * slot tables, retain the scene's semantics root after the frame is drawn, build the layout and
 * semantics payloads, then run [ComposeFigmaSvgDataProducer]. Draw operations that the structural
 * model cannot represent remain small PNG layers beneath editable text; those files are inlined
 * before returning because the one-shot serve subprocess removes its temporary export directory.
 */
@OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)
public fun renderRemoteDocumentToSvg(
  bytes: ByteArray,
  widthPx: Int,
  heightPx: Int,
  density: Float = 2f,
  seeds: Map<String, RcSeed> = emptyMap(),
  theme: Int = Theme.LIGHT,
  systemColorLookup: (name: String) -> Int? = { null },
): ByteArray {
  val rootDir = Files.createTempDirectory("rcjvm-svg-").toFile()
  val previewId = "rc-jvm"
  val slotTables = mutableSetOf<CompositionData>()
  val scene =
    ImageComposeScene(width = widthPx, height = heightPx, density = Density(density)) {
      InspectableRcJvmContent(slotTables) {
        // Keep the document's authored pixel viewport as an explicit layout node. This is the same
        // frame used by the Android embedded export test and prevents a sparse document from making
        // the SVG shrink-wrap to only its semantic text nodes.
        Box(
          Modifier.size(
            with(LocalDensity.current) { widthPx.toDp() },
            with(LocalDensity.current) { heightPx.toDp() },
          )
        ) {
          val document = remember(bytes) { parseDocument(bytes) }
          RcPlayerJvm(document, Modifier.fillMaxSize(), seeds, theme, systemColorLookup)
        }
      }
    }

  try {
    val framePng = File(rootDir, "frame.png")
    val image = scene.render()
    val encoded =
      image.encodeToData(EncodedImageFormat.PNG)
        ?: error("skiko could not encode the rendered image for SVG export")
    framePng.writeBytes(encoded.bytes)

    val semanticsRoot =
      scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode
        ?: error("cmp-jvm SVG export found no Compose semantics owner")
    val layout =
      LayoutInspectorDataProducer.buildPayload(
        root = semanticsRoot,
        slotTables = slotTables.toList(),
        density = density,
      ) ?: error("cmp-jvm SVG export could not build a layout tree")
    val semantics = ComposeSemanticsDataProducer.buildPayload(semanticsRoot, density)

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = previewId,
      layout = layout,
      semantics = semantics,
      density = density,
      frameImage = framePng,
    )

    val previewDir = File(rootDir, previewId)
    val svgFile = File(previewDir, ComposeFigmaSvgDataProducer.FILE_SVG)
    val svg = svgFile.takeIf { it.isFile }?.readText().orEmpty()
    check(svg.startsWith("<svg")) { "cmp-jvm SVG producer wrote no SVG" }
    return inlineRasterLayers(svg, previewDir).toByteArray(Charsets.UTF_8)
  } finally {
    scene.close()
    rootDir.deleteRecursively()
  }
}

/** Inline only producer-owned relative PNG layers; this directory contains no untrusted files. */
private fun inlineRasterLayers(svg: String, svgDir: File): String {
  if (!svg.contains("figma-raster/")) return svg
  val href = Regex("href=\"([^\"]*figma-raster/[^\"]+\\.png)\"")
  return href.replace(svg) { match ->
    val relative = match.groupValues[1]
    if (relative.startsWith('/') || relative.contains("..") || relative.contains(':')) {
      return@replace match.value
    }
    val file = File(svgDir, relative)
    if (!file.isFile) return@replace match.value
    val data = Base64.getEncoder().encodeToString(file.readBytes())
    "href=\"data:image/png;base64,$data\""
  }
}

/** Captures the composition data consumed by the layout-inspector half of figma-svg export. */
@OptIn(InternalComposeApi::class)
@Composable
internal fun InspectableRcJvmContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
