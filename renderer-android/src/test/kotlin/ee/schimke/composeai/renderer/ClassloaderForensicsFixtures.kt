package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Trivial preview fixture used only by [ClassloaderForensicsTest] (Configuration A) so the survey
 * set can include a "user preview class" entry. Same shape as `:daemon:android`'s
 * `RedFixturePreviewsKt.RedSquare` so cross-module reasoning about the loaded `Class<?>` graph
 * is straightforward — only the package differs.
 *
 * Lives in `src/test/kotlin/...` (not testFixtures) because `:renderer-android` doesn't yet wire up
 * a testFixtures source set; a forensic-only fixture in the same package as the test class is the
 * minimum-impact landing site.
 */
@Composable
fun ForensicsPreviewFixtureSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350)))
}
