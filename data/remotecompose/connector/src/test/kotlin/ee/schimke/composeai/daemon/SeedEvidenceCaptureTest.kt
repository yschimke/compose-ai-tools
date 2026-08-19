package ee.schimke.composeai.daemon

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Throwaway capture harness for the PR's before/after evidence. Not part of the committed tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h300dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeedEvidenceCaptureTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun capture() {
    val seed =
      ee.schimke.composeai.daemon.protocol.RemoteComposeOverride(
        namedValues =
          mapOf(
            "fill" to ee.schimke.composeai.daemon.protocol.RemoteNamedValue.ColorValue("#FF42A5F5")
          )
      )
    val extension = RemoteComposeOverrideExtension(seed)
    rule.setContent {
      extension.Around(
        ee.schimke.composeai.data.render.extensions.compose.ExtensionComposeContext(
          extensionId = RemoteComposeOverrideExtension.ID,
          previewId = "evidence",
          renderMode = null,
        )
      ) {
        Column(
          modifier = Modifier.background(Color.White).padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          BasicText(
            "?themeProvider=… → rc.fill=color:#FF42A5F5",
            style = TextStyle(fontSize = 13.sp, color = Color(0xFF202124)),
          )
          val fill = LocalRemoteComposeHost.current.namedColor("fill", default = "#FFEF5350")
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Swatch("remember { }", remember { fill }.toComposeColor())
            val animated by animateColorAsState(targetValue = fill.toComposeColor())
            Swatch("animateColorAsState", animated)
          }
          BasicText(
            "seeded #FF42A5F5 · author default #FFEF5350",
            style = TextStyle(fontSize = 11.sp, color = Color(0xFF5F6368)),
          )
        }
      }
    }
    rule.waitForIdle()
    val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
    val out = File("/tmp/claude-502/seed-evidence.png")
    out.parentFile?.mkdirs()
    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    println("wrote ${out.absolutePath}")
  }

  @Composable
  private fun Swatch(label: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      androidx.compose.foundation.layout.Box(
        modifier = Modifier.size(120.dp, 72.dp).background(color)
      )
      BasicText(label, style = TextStyle(fontSize = 12.sp, color = Color(0xFF202124)))
    }
  }

  private fun String.toComposeColor(): Color = Color(removePrefix("#").toLong(16).toInt())
}
