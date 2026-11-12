package ee.schimke.composeai.cli

import ee.schimke.composeai.previewdata.Capture
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewParams
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApkDwfExtractorTest {

  @Test
  fun `extractResourcesToDirectory decodes compiled AXML vector drawables with dimensions and enums`() {
    val root = Files.createTempDirectory("apk-dwf-extractor-test-").toFile()
    try {
      val apk = File(root, "watchface.apk")
      val vectorAxml =
        buildMinimalVectorBinaryXml(
          widthData = (24 shl 8) or 1, // 24dp
          heightData = (24 shl 8) or 1, // 24dp
          viewportWidth = 24f,
          viewportHeight = 24f,
          pathData = "M2,12L22,12",
          fillColor = 0xFF00E5FF.toInt(),
          fillTypeEnum = 1, // evenOdd
          strokeLineCapEnum = 1, // round
        )
      ZipOutputStream(apk.outputStream()).use { zout ->
        zout.putNextEntry(ZipEntry("res/drawable/ic_hand.xml"))
        zout.write(vectorAxml)
        zout.closeEntry()
      }

      val resDir = File(root, "extracted-res").apply { mkdirs() }
      ApkDwfExtractor.extractResourcesToDirectory(apk, resDir)

      val decodedXml = File(resDir, "drawable/ic_hand.xml").readText()
      assertTrue("<vector" in decodedXml, decodedXml)
      assertTrue("width=\"24dp\"" in decodedXml, decodedXml)
      assertTrue("height=\"24dp\"" in decodedXml, decodedXml)
      assertTrue("viewportWidth=\"24.0\"" in decodedXml, decodedXml)
      assertTrue("pathData=\"M2,12L22,12\"" in decodedXml, decodedXml)
      assertTrue("fillColor=\"#FF00E5FF\"" in decodedXml, decodedXml)
      assertTrue("fillType=\"evenOdd\"" in decodedXml, decodedXml)
      assertTrue("strokeLineCap=\"round\"" in decodedXml, decodedXml)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `PreviewPermutationsCli skips DWF watch face entries when expanding accessibility`() {
    val previews =
      listOf(
        PreviewInfo(
          id = "dwf__raw_sample_dwf_watchface",
          functionName = "sample_dwf_watchface",
          className = "",
          includeInA11y = false,
          params = PreviewParams(name = "sample_dwf_watchface", kind = "COMPOSE"),
          captures =
            listOf(Capture(renderOutput = "dwf-renders/dwf__raw_sample_dwf_watchface.png")),
        ),
        PreviewInfo(
          id = "com.example.WearKt.CardPreview",
          functionName = "CardPreview",
          className = "com.example.WearKt",
          params = PreviewParams(name = "CardPreview", kind = "COMPOSE"),
          captures = listOf(Capture(renderOutput = "renders/CardPreview.png")),
        ),
      )

    val expanded = PreviewPermutationsCli.expand(previews, listOf("accessibility"))
    assertEquals(
      listOf(
        "dwf__raw_sample_dwf_watchface",
        "com.example.WearKt.CardPreview",
        "com.example.WearKt.CardPreview_dark",
        "com.example.WearKt.CardPreview_rtl",
        "com.example.WearKt.CardPreview_fontscale-2x",
      ),
      expanded.map { it.id },
    )
  }

  private fun buildMinimalVectorBinaryXml(
    widthData: Int,
    heightData: Int,
    viewportWidth: Float,
    viewportHeight: Float,
    pathData: String,
    fillColor: Int,
    fillTypeEnum: Int,
    strokeLineCapEnum: Int,
  ): ByteArray {
    val strings =
      listOf(
        "vector",
        "width",
        "height",
        "viewportWidth",
        "viewportHeight",
        "path",
        "pathData",
        pathData,
        "fillColor",
        "fillType",
        "strokeLineCap",
      )
    val stringPoolChunk = buildUtf8StringPool(strings)

    data class AttrSpec(val nameIdx: Int, val rawIdx: Int, val type: Int, val data: Int)

    fun startElement(nameIdx: Int, attrs: List<AttrSpec>): ByteArray {
      val headerSize = 16
      val extSize = 20
      val attrSize = 20
      val totalSize = headerSize + extSize + attrs.size * attrSize
      val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
      buf.putShort(0x0102.toShort())
      buf.putShort(headerSize.toShort())
      buf.putInt(totalSize)
      buf.putInt(1) // lineNumber
      buf.putInt(-1) // comment
      buf.putInt(-1) // ns
      buf.putInt(nameIdx)
      buf.putShort(20.toShort()) // attributeStart
      buf.putShort(attrSize.toShort()) // attributeSize
      buf.putShort(attrs.size.toShort()) // attributeCount
      buf.putShort(0)
      buf.putShort(0)
      buf.putShort(0)
      for (a in attrs) {
        buf.putInt(-1) // ns
        buf.putInt(a.nameIdx)
        buf.putInt(a.rawIdx)
        buf.putShort(8.toShort()) // typedValue size
        buf.put(0.toByte()) // res0
        buf.put(a.type.toByte())
        buf.putInt(a.data)
      }
      return buf.array()
    }

    fun endElement(nameIdx: Int): ByteArray {
      val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
      buf.putShort(0x0103.toShort())
      buf.putShort(16.toShort())
      buf.putInt(24)
      buf.putInt(1)
      buf.putInt(-1)
      buf.putInt(-1)
      buf.putInt(nameIdx)
      return buf.array()
    }

    val chunks = ByteArrayOutputStream()
    chunks.write(stringPoolChunk)
    chunks.write(
      startElement(
        nameIdx = 0,
        attrs =
          listOf(
            AttrSpec(1, -1, 0x05, widthData),
            AttrSpec(2, -1, 0x05, heightData),
            AttrSpec(3, -1, 0x04, java.lang.Float.floatToRawIntBits(viewportWidth)),
            AttrSpec(4, -1, 0x04, java.lang.Float.floatToRawIntBits(viewportHeight)),
          ),
      ),
    )
    chunks.write(
      startElement(
        nameIdx = 5,
        attrs =
          listOf(
            AttrSpec(6, 7, 0x03, 7),
            AttrSpec(8, -1, 0x1c, fillColor),
            AttrSpec(9, -1, 0x10, fillTypeEnum),
            AttrSpec(10, -1, 0x10, strokeLineCapEnum),
          ),
      ),
    )
    chunks.write(endElement(5))
    chunks.write(endElement(0))

    val body = chunks.toByteArray()
    val total = 8 + body.size
    val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    out.putShort(0x0003.toShort())
    out.putShort(8.toShort())
    out.putInt(total)
    out.put(body)
    return out.array()
  }

  private fun buildUtf8StringPool(strings: List<String>): ByteArray {
    val encoded = strings.map { s ->
      val utf8 = s.toByteArray(Charsets.UTF_8)
      ByteArrayOutputStream()
        .apply {
          write(s.length)
          write(utf8.size)
          write(utf8)
          write(0)
        }
        .toByteArray()
    }
    val headerSize = 28
    val offsetsSize = strings.size * 4
    val stringsStart = headerSize + offsetsSize
    val rawStringsBytes = encoded.sumOf { it.size }
    val paddedStringsBytes = (rawStringsBytes + 3) and -4
    val totalSize = stringsStart + paddedStringsBytes
    val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(0x0001.toShort())
    buf.putShort(headerSize.toShort())
    buf.putInt(totalSize)
    buf.putInt(strings.size)
    buf.putInt(0) // styleCount
    buf.putInt(0x0100) // UTF8_FLAG
    buf.putInt(stringsStart)
    buf.putInt(0) // stylesStart
    var running = 0
    for (e in encoded) {
      buf.putInt(running)
      running += e.size
    }
    for (e in encoded) {
      buf.put(e)
    }
    while (buf.position() < totalSize) {
      buf.put(0.toByte())
    }
    return buf.array()
  }
}
