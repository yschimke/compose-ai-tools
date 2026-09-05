/*
 * The Compose UI builder's own output, checked in unchanged, so a real Wear render can be held
 * against the canvas that authored it.
 *
 * ## What this file is
 *
 * What `WearScreenCodeExporter` emits for the `wear-list` template in
 * yschimke/compose-preview-server — the design a `wear-m3` screen is created from. Two edits, and
 * only two: a package declaration, and `ktfmt`, which this repository's format gate requires and
 * which moves no code. Regenerate by running that repository's `WearScreenCodeExporterTest`, which
 * writes the source to `ui-builder/build/generated-wear-screen-source/ActivityScreen.kt`, then
 * `./gradlew :samples:design-catalog-wear-m3:ktfmtFormat`.
 *
 * ## Why it lives in this catalog
 *
 * `wear-m3` is a harness catalog: it exists to exercise the preview pipeline rather than to be a
 * design system, and this is the pipeline being exercised end to end. It is also the catalog those
 * designs are pinned to, so the pairing reads without a mapping table.
 *
 * ## What it proves
 *
 * The builder cannot draw Wear Compose — its canvas is Compose Multiplatform for Wasm, which cannot
 * link an Android AAR — so it draws a stand-in and claims the stand-in is honest. This is the claim
 * under test. `ActivityScreenLongPreview` stitches the whole scroll into one tall PNG with the row
 * transformation off, which is exactly the picture the builder's stadium canvas draws, so the two
 * are comparable pixel for pixel; `ActivityScreenPreview` is the same screen as a watch actually
 * shows it, one screenful at a time, transformed.
 *
 * Note what the scaffold does with `LocalScrollCaptureInProgress`. That is generated, not added
 * here: a stitched capture composites many frames, and an indicator drawn at a different offset in
 * each of them lands as a column of dashes down the edge. Suppressing transient chrome while the
 * platform is taking a long screenshot is what the signal is for, and it is why this capture is
 * clean enough to compare.
 *
 * Do not edit by hand. A change here that is not a regeneration is a claim that the builder emits
 * something it does not.
 */

package com.example.designcatalogwearm3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview

@Composable
fun ActivityScreen() {
  val listState = rememberTransformingLazyColumnState()
  val spec = rememberTransformationSpec()
  AppScaffold(timeText = { TimeText { timeTextCurvedText("10:10") } }) {
    ScreenScaffold(
      scrollState = listState,
      scrollIndicator = { if (!LocalScrollCaptureInProgress.current) ScrollIndicator(listState) },
    ) { contentPadding ->
      TransformingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        item {
          ListHeader(
            modifier = Modifier.transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
          ) {
            Text(text = "Activity")
          }
        }
        item {
          TitleCard(
            onClick = {},
            title = { Text(text = "Session 1") },
            subtitle = { Text(text = "4 min") },
            modifier = Modifier.transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
          )
        }
        item {
          TitleCard(
            onClick = {},
            title = { Text(text = "Session 2") },
            subtitle = { Text(text = "8 min") },
            modifier = Modifier.transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
          )
        }
        item {
          TitleCard(
            onClick = {},
            title = { Text(text = "Session 3") },
            subtitle = { Text(text = "12 min") },
            modifier = Modifier.transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
          )
        }
        item {
          TitleCard(
            onClick = {},
            title = { Text(text = "Session 4") },
            subtitle = { Text(text = "16 min") },
            modifier = Modifier.transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
          )
        }
        item {
          TitleCard(
            onClick = {},
            title = { Text(text = "Session 5") },
            subtitle = { Text(text = "20 min") },
            modifier = Modifier.transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
          )
        }
        item {
          TitleCard(
            onClick = {},
            title = { Text(text = "Session 6") },
            subtitle = { Text(text = "24 min") },
            modifier = Modifier.transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
          )
        }
      }
    }
  }
}

@WearPreviewDevices @Composable fun ActivityScreenPreview() = ActivityScreen()

@Preview(device = "id:wearos_small_round", showBackground = true, backgroundColor = 0xFF000000)
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun ActivityScreenLongPreview() = ActivityScreen()
