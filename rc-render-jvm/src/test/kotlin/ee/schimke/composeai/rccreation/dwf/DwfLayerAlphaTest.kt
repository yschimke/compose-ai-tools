package ee.schimke.composeai.rccreation.dwf

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcjvm.defaultDwfImageLoader
import ee.schimke.composeai.rcplayer.compose.LocalRcImageLoader
import ee.schimke.composeai.rcplayer.compose.LocalRcTimeSource
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcTimeSnapshot
import ee.schimke.composeai.rcplayer.runtime.RcTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap

/** A Group / PartDraw with alpha < 1 composites as one layer: overlapping children read uniform. */
class DwfLayerAlphaTest {
  private fun render(
    xml: String,
    hour: Int = 15,
    minute: Int = 8,
    overrides: Map<String, RcNamedValue> = emptyMap(),
  ): Bitmap = runBlocking {
    val bytes = captureDwfRemoteDocumentBytes(xml = xml)
    val time =
      object : RcTimeSource {
        override fun currentTimeMillis(): Long = 1_763_000_000_000L

        override fun snapshot(epochMillis: Long): RcTimeSnapshot =
          RcTimeSnapshot(
            epochMillis = epochMillis,
            year = 2026,
            month = 11,
            dayOfMonth = 18,
            dayOfYear = 322,
            hour = hour,
            minute = minute,
            second = 0,
            isoDayOfWeek = 3,
            offsetSeconds = 0,
          )
      }
    val scene = ImageComposeScene(width = 450, height = 450, density = Density(1f))
    try {
      val state = mutableStateMapOf<String, RcNamedValue>().apply { putAll(overrides) }
      scene.setContent {
        CompositionLocalProvider(
          LocalRcTimeSource provides time,
          LocalRcImageLoader provides defaultDwfImageLoader(),
        ) {
          RcComposePlayer(
            bytes = bytes,
            modifier = Modifier.size(450.dp, 450.dp),
            namedValues = state,
          )
        }
      }
      Bitmap.makeFromImage(scene.render(0L))
    } finally {
      scene.close()
    }
  }

  private fun Bitmap.rgb(x: Int, y: Int): Int = getColor(x, y) and 0xFFFFFF

  private fun face(groupAttrs: String, inner: String = "") =
    """
    <WatchFace width="450" height="450"><Scene backgroundColor="#FF000000">
      <Group x="0" y="0" width="450" height="450" $groupAttrs>
        $inner
        <PartDraw x="0" y="0" width="450" height="450">
          <Rectangle x="50" y="100" width="200" height="200"><Fill color="#FFFFFFFF"/></Rectangle>
          <Rectangle x="150" y="100" width="200" height="200"><Fill color="#FFFFFFFF"/></Rectangle>
        </PartDraw>
      </Group>
    </Scene></WatchFace>
    """
      .trimIndent()

  private fun Bitmap.r(x: Int, y: Int) = (rgb(x, y) shr 16) and 0xFF

  @Test
  fun constantGroupAlphaReadsUniformWhereChildrenOverlap() {
    val f = render(face("alpha=\"128\""))
    val solo = f.r(100, 200) // only the first rectangle
    val overlap = f.r(200, 200) // both rectangles
    assertTrue(solo in 124..132, "50% white over black ~128, was $solo")
    assertEquals(solo, overlap, "overlap must not darken/brighten: one layer, not per-draw alpha")
  }

  @Test
  fun variantDrivenGroupAlphaReadsUniformWhereChildrenOverlap() {
    val xml = face("", """<Variant mode="AMBIENT" target="alpha" value="128"/>""")
    val interactive = render(xml)
    assertEquals(255, interactive.r(200, 200), "interactive stays opaque")
    val ambient =
      render(xml, overrides = mapOf(DwfVariables.IS_AMBIENT to RcNamedValue.BooleanValue(true)))
    val solo = ambient.r(100, 200)
    assertEquals(solo, ambient.r(200, 200), "ambient overlap must match the single-shape pixel")
    assertTrue(solo in 100..125, "~128 then the ambient scrim, was $solo")
  }

  @Test
  fun nestedGroupAlphaMultiplies() {
    val xml =
      """
      <WatchFace width="450" height="450"><Scene backgroundColor="#FF000000">
        <Group x="0" y="0" width="450" height="450" alpha="128">
          <Group x="0" y="0" width="450" height="450" alpha="128">
            <PartDraw x="0" y="0" width="450" height="450">
              <Rectangle x="50" y="100" width="200" height="200"><Fill color="#FFFFFFFF"/></Rectangle>
              <Rectangle x="150" y="100" width="200" height="200"><Fill color="#FFFFFFFF"/></Rectangle>
            </PartDraw>
          </Group>
        </Group>
      </Scene></WatchFace>
      """
        .trimIndent()
    val f = render(xml)
    val solo = f.r(100, 200)
    assertTrue(solo in 60..68, "25% white, was $solo")
  }

  @Test
  fun partDrawAlphaIsALayerToo() {
    val xml =
      """
      <WatchFace width="450" height="450"><Scene backgroundColor="#FF000000">
        <PartDraw x="0" y="0" width="450" height="450" alpha="128">
          <Rectangle x="50" y="100" width="200" height="200"><Fill color="#FFFFFFFF"/></Rectangle>
          <Rectangle x="150" y="100" width="200" height="200"><Fill color="#FFFFFFFF"/></Rectangle>
        </PartDraw>
      </Scene></WatchFace>
      """
        .trimIndent()
    val f = render(xml)
    assertEquals(f.r(100, 200), f.r(200, 200))
  }

  @Test
  fun dataDrivenGeometryUnderDataDrivenAlphaIsInitialisedInEitherBranch() {
    // The opaque and layered branches draw the same content. An expression first emitted inside
    // the branch the player skips must not leave the other branch reading an uninitialised id.
    val xml =
      """
      <WatchFace width="450" height="450"><Scene backgroundColor="#FF000000">
        <Group x="0" y="0" width="450" height="450">
          <Variant mode="AMBIENT" target="alpha" value="128"/>
          <Transform target="x" value="[HOUR_0_23] * 10"/>
          <PartDraw x="0" y="0" width="450" height="450">
            <Rectangle x="0" y="100" width="100" height="200"><Fill color="#FFFFFFFF"/></Rectangle>
          </PartDraw>
        </Group>
        <Group x="0" y="0" width="450" height="450">
          <Variant mode="AMBIENT" target="alpha" value="128"/>
          <PartDraw x="0" y="0" width="450" height="450">
            <Rectangle x="0" y="320" width="100" height="100">
              <Transform target="x" value="[HOUR_0_23] * 10"/>
              <Fill color="#FFFFFFFF"><Transform target="color" value="[HOUR_0_23] > 100 ? #FF000000 : #FFFFFFFF"/></Fill>
            </Rectangle>
          </PartDraw>
        </Group>
      </Scene></WatchFace>
      """
        .trimIndent()
    val ambient =
      render(xml, overrides = mapOf(DwfVariables.IS_AMBIENT to RcNamedValue.BooleanValue(true)))
    assertEquals(0, ambient.r(50, 200), "the group is translated to x=150, not left at 0")
    assertTrue(ambient.r(200, 200) in 100..125, "dimmed white at x=150..250")
    assertEquals(0, ambient.r(50, 370), "child-level x Transform applied in the layered branch")
    assertTrue(
      ambient.r(200, 370) in 100..125,
      "child rectangle at x=150, was ${ambient.r(200, 370)}",
    )
    val interactive = render(xml)
    assertEquals(0, interactive.r(50, 200))
    assertEquals(255, interactive.r(200, 200))
  }
}
