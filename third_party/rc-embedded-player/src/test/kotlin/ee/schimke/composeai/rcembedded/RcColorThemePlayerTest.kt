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

package ee.schimke.composeai.rcembedded

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.remote.core.operations.Theme
import androidx.compose.remote.core.operations.layout.managers.BoxLayout
import androidx.compose.remote.creation.Rc
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.modifiers.RecordingModifier
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cold-start coverage for ColorTheme's indexed Android system-color form. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "night-xhdpi")
class RcColorThemePlayerTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun viewPlayer_coldStart_darkTheme_resolvesSystemColors() {
    assertThemeColorResolved(render(Player.VIEW))
  }

  @Test
  fun embeddedPlayer_coldStart_darkTheme_resolvesSystemColors() {
    assertThemeColorResolved(render(Player.EMBEDDED))
  }

  private fun assertThemeColorResolved(result: RenderResult) {
    val expected = expectedDarkColor()
    assertEquals("mapped dark system color", expected, result.mappedDarkColor)
    assertEquals("color loaded into player state", expected, result.resolvedColor)
  }

  private fun render(player: Player): RenderResult {
    val fixture = colorThemeDocument()
    lateinit var remoteDocument: RemoteDocument
    composeRule.setContent {
      val density = LocalDensity.current
      Box(Modifier.size(with(density) { WIDTH.toDp() }, with(density) { HEIGHT.toDp() })) {
        val document = remember { RemoteDocument(fixture.bytes) }
        remoteDocument = document
        when (player) {
          Player.VIEW ->
            RemoteDocumentPlayer(
              document = document.document,
              documentWidth = WIDTH,
              documentHeight = HEIGHT,
            )
          Player.EMBEDDED ->
            ExperimentalRemoteDocumentPlayer(
              document = document,
              modifier = Modifier.fillMaxSize(),
              theme = Theme.DARK,
            )
        }
      }
    }
    composeRule.waitForIdle()

    val root = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
    root.measure(
      MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(HEIGHT, MeasureSpec.EXACTLY),
    )
    root.layout(0, 0, WIDTH, HEIGHT)
    Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
    val coreDocument = remoteDocument.document
    return RenderResult(
      mappedDarkColor = checkNotNull(coreDocument.themedColors).single().mDarkMode,
      resolvedColor = coreDocument.remoteComposeState.getColor(fixture.colorId.toInt()),
    )
  }

  private fun expectedDarkColor(): Int =
    composeRule.activity.getColor(android.R.color.system_accent2_800)

  private fun colorThemeDocument(): DocumentFixture {
    val writer = RemoteComposeWriter.obtain(WIDTH, HEIGHT, RcPlatformProfiles.ANDROIDX)
    val fallbackColor = 0xffff00ff.toInt()
    val colorId =
      writer.addThemedColor(
        Rc.AndroidColors.GROUP,
        Rc.AndroidColors.SYSTEM_ACCENT2_50,
        Rc.AndroidColors.SYSTEM_ACCENT2_800,
        fallbackColor,
        fallbackColor,
      )
    writer.root {
      writer.box(
        RecordingModifier().backgroundId(colorId).fillMaxSize(),
        BoxLayout.CENTER,
        BoxLayout.CENTER,
      ) {}
    }
    return DocumentFixture(writer.encodeToByteArray(), colorId)
  }

  private data class DocumentFixture(val bytes: ByteArray, val colorId: Short)

  private data class RenderResult(
    val mappedDarkColor: Int,
    val resolvedColor: Int,
  )

  private enum class Player {
    VIEW,
    EMBEDDED,
  }

  private companion object {
    const val WIDTH = 100
    const val HEIGHT = 100
  }
}
