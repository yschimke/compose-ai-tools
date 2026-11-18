package ee.schimke.composeai.dwf

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.rccreation.dwf.DwfDefaultImages
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFontData
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * A compiled face must not need a resource host the browser (CMP Wasm) player does not have: its
 * custom fonts travel as `FontData`, and its images — local drawables and the complication and
 * photo placeholders — as inline bitmaps.
 */
class WffSelfContainedDocumentTest {
  private val fontBytes = File(checkNotNull(System.getProperty("dwf.testFont"))).readBytes()

  /** An original, minimal face: custom-font digital time, a local drawable, three complications. */
  private val xml =
    """
    <WatchFace width="450" height="450">
      <Scene backgroundColor="#FF000000">
        <PartImage x="200" y="40" width="50" height="50">
          <Image resource="dot"/>
        </PartImage>
        <PartText x="50" y="180" width="350" height="90">
          <Text align="CENTER">
            <Font family="lobster" size="72" color="#FFFFFFFF">
              <Template>%s<Parameter expression="[HOUR_1_12_Z]"/></Template>
            </Font>
          </Text>
        </PartText>
        <PartText x="50" y="270" width="350" height="50">
          <Text align="CENTER">
            <Font family="lobster" size="32" color="#FFAAAAAA">Lobster</Font>
          </Text>
        </PartText>
        <ComplicationSlot x="60" y="330" width="80" height="80" slotId="1"
            supportedTypes="MONOCHROMATIC_IMAGE EMPTY">
          <Complication type="MONOCHROMATIC_IMAGE">
            <PartImage x="0" y="0" width="80" height="80">
              <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]"/>
            </PartImage>
          </Complication>
        </ComplicationSlot>
        <ComplicationSlot x="185" y="330" width="80" height="80" slotId="2"
            supportedTypes="SMALL_IMAGE EMPTY">
          <Complication type="SMALL_IMAGE">
            <PartImage x="0" y="0" width="80" height="80">
              <Image resource="[COMPLICATION.SMALL_IMAGE]"/>
            </PartImage>
          </Complication>
        </ComplicationSlot>
        <ComplicationSlot x="310" y="330" width="80" height="80" slotId="3"
            supportedTypes="PHOTO_IMAGE EMPTY">
          <Complication type="PHOTO_IMAGE">
            <PartImage x="0" y="0" width="80" height="80">
              <Image resource="[COMPLICATION.PHOTO_IMAGE]"/>
            </PartImage>
          </Complication>
        </ComplicationSlot>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  private fun dotPng(): ByteArray {
    val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color(0xFF, 0x80, 0x00)
    g.fillOval(0, 0, 16, 16)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(img, "PNG", it) }.toByteArray()
  }

  private fun compile(): RcDocument {
    val result =
      WffRemoteCompose.compile(
        xml,
        WffResourceSource.of(
          mapOf("font/lobster.ttf" to fontBytes, "drawable/dot.png" to dotPng()),
        ),
      )
    assertThat(result.diagnostics.filter { it.severity == WffSeverity.ERROR }).isEmpty()
    assertThat(result.isSuccess).isTrue()
    return RcDocumentCodec.decode(result.rcBytes!!)
  }

  @Test
  fun `a custom font is embedded once, under the family text it is named by`() {
    val doc = compile()
    val familyIds = doc.operations.filterIsInstance<RcTextData>().filter { it.text == "lobster" }
    assertThat(familyIds).isNotEmpty()
    val fonts = doc.operations.filterIsInstance<RcFontData>()
    assertThat(fonts.map { it.fontId }).containsExactlyElementsIn(familyIds.map { it.id })
    assertThat(fonts.map { it.fontId }).containsNoDuplicates()
    for (font in fonts) assertThat(font.data).isEqualTo(fontBytes)
    // Loaded before anything draws: the fonts directly follow the header.
    assertThat(doc.operations.first()).isInstanceOf(RcFontData::class.java)
  }

  @Test
  fun `no bitmap needs an image host`() {
    val doc = compile()
    val bitmaps = doc.operations.filterIsInstance<RcBitmapData>()
    assertThat(bitmaps).isNotEmpty()
    val hosted = bitmaps.filter {
      it.encoding != RcBitmapData.ENCODING_INLINE &&
        it.encoding != RcBitmapData.ENCODING_EMPTY &&
        it.encoding != RcBitmapData.ENCODING_COMPONENT_OFFSCREEN_BUFFER
    }
    assertThat(hosted.map { "${it.imageId}: ${String(it.data, Charsets.UTF_8)}" }).isEmpty()
  }

  @Test
  fun `the CMP player can render it with no host fonts or images`() {
    val report = compile().composeSupportReport()
    assertThat(
        report.issues
          .filter { it.operation == "BitmapData" || "font" in it.detail.lowercase() }
          .map { "${it.operation}[${it.operationIndex}]: ${it.detail}" },
      )
      .isEmpty()
    assertThat(report.issues.map { "${it.operation}[${it.operationIndex}]: ${it.detail}" })
      .isEmpty()
  }

  @Test
  fun `a face without custom fonts is not rewritten`() {
    val plain = xml.replace("family=\"lobster\"", "family=\"SYNC_TO_DEVICE\"")
    val result = WffRemoteCompose.compile(plain, WffResourceSource.of(emptyMap()))
    val doc = RcDocumentCodec.decode(result.rcBytes!!)
    assertThat(doc.operations.filterIsInstance<RcFontData>()).isEmpty()
  }

  @Test
  fun `every default placeholder renders`() {
    for (url in DwfDefaultImages.urls) {
      val png = DwfDefaultImages.png(url)
      assertThat(png).isNotNull()
      assertThat(ImageIO.read(png!!.inputStream())).isNotNull()
    }
    assertThat(DwfDefaultImages.png("https://example.test/elsewhere.png")).isNull()
  }
}
