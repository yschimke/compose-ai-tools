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
import kotlin.test.assertFalse
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
      assertTrue("strokeLineCap=\"round\"" in decodedXml)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `extractResourcesToDirectory skips Zip Slip path traversal entries`() {
    val root = Files.createTempDirectory("apk-dwf-zipslip-test-").toFile()
    try {
      val apk = File(root, "malicious.apk")
      ZipOutputStream(apk.outputStream()).use { zout ->
        zout.putNextEntry(ZipEntry("res/drawable/../../escaped.txt"))
        zout.write("pwned".toByteArray())
        zout.closeEntry()
        zout.putNextEntry(ZipEntry("res/raw/watchface.xml"))
        zout.write("<WatchFace width=\"450\" height=\"450\"><Scene/></WatchFace>".toByteArray())
        zout.closeEntry()
      }

      val resDir = File(root, "nested/extracted-res").apply { mkdirs() }
      ApkDwfExtractor.extractResourcesToDirectory(apk, resDir)

      assertFalse(File(root, "escaped.txt").exists())
      assertFalse(File(root, "nested/escaped.txt").exists())
      assertTrue(File(resDir, "raw/watchface.xml").isFile)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `extractResourcesToDirectory resolves TYPE_REFERENCE attributes from standard and compact resources arsc`() {
    for (compact in listOf(false, true)) {
      val root = Files.createTempDirectory("apk-dwf-arsc-test-").toFile()
      try {
        val apk = File(root, "watchface.apk")
        val arsc = buildMinimalArscTable(compact = compact)
        val vectorAxml =
          buildMinimalVectorBinaryXml(
            widthType = 0x01, // TYPE_REFERENCE -> @dimen/hand_width (0x7f020000)
            widthData = 0x7f020000,
            heightData = (24 shl 8) or 1, // 24dp
            viewportWidth = 24f,
            viewportHeight = 24f,
            pathData = "M2,12L22,12",
            fillColorType = 0x01, // TYPE_REFERENCE -> @color/alias_accent (0x7f010001) -> #FF00E5FF
            fillColor = 0x7f010001,
            fillTypeEnum = 1,
            strokeLineCapEnum = 1,
          )
        ZipOutputStream(apk.outputStream()).use { zout ->
          zout.putNextEntry(ZipEntry("resources.arsc"))
          zout.write(arsc)
          zout.closeEntry()
          zout.putNextEntry(ZipEntry("res/drawable/ic_hand.xml"))
          zout.write(vectorAxml)
          zout.closeEntry()
        }

        val resDir = File(root, "extracted-res").apply { mkdirs() }
        ApkDwfExtractor.extractResourcesToDirectory(apk, resDir)

        val decodedXml = File(resDir, "drawable/ic_hand.xml").readText()
        assertTrue("width=\"24dp\"" in decodedXml, "compact=$compact: $decodedXml")
        assertTrue("fillColor=\"#FF00E5FF\"" in decodedXml, "compact=$compact: $decodedXml")

        val extractedValues = File(resDir, "values/arsc_extracted.xml").readText()
        assertTrue("<color name=\"accent\">#FF00E5FF</color>" in extractedValues, extractedValues)
        assertTrue(
          "<color name=\"alias_accent\">#FF00E5FF</color>" in extractedValues,
          extractedValues,
        )
        assertTrue("<string name=\"wf_label\">Orbit</string>" in extractedValues, extractedValues)
      } finally {
        root.deleteRecursively()
      }
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
    widthType: Int = 0x05,
    widthData: Int,
    heightData: Int,
    viewportWidth: Float,
    viewportHeight: Float,
    pathData: String,
    fillColorType: Int = 0x1c,
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
            AttrSpec(1, -1, widthType, widthData),
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
            AttrSpec(8, -1, fillColorType, fillColor),
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

  private fun buildMinimalArscTable(compact: Boolean): ByteArray {
    val globalPool = buildUtf8StringPool(listOf("Orbit"))
    val typePool = buildUtf8StringPool(listOf("color", "dimen", "string"))
    val keyPool = buildUtf8StringPool(listOf("accent", "alias_accent", "hand_width", "wf_label"))

    data class RawEntry(val keyIdx: Int, val dataType: Int, val data: Int)

    fun buildTypeChunk(typeId: Int, entries: List<RawEntry>): ByteArray {
      val headerSize = 84
      val offsetsBytes =
        if (compact) {
          val raw = entries.size * 2
          (raw + 3) and -4
        } else {
          entries.size * 4
        }
      val entryBytesPerItem = if (compact) 8 else 16
      val entriesStart = headerSize + offsetsBytes
      val totalSize = entriesStart + entries.size * entryBytesPerItem
      val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
      buf.putShort(0x0201.toShort())
      buf.putShort(headerSize.toShort())
      buf.putInt(totalSize)
      buf.put(typeId.toByte())
      buf.put((if (compact) 0x02 else 0x00).toByte()) // FLAG_OFFSET16 when compact
      buf.putShort(0)
      buf.putInt(entries.size)
      buf.putInt(entriesStart)
      buf.putInt(64) // ResTable_config size (default config = all zeros)
      buf.position(headerSize)
      for (i in entries.indices) {
        val byteOff = i * entryBytesPerItem
        if (compact) {
          buf.putShort((byteOff / 4).toShort())
        } else {
          buf.putInt(byteOff)
        }
      }
      buf.position(entriesStart)
      for (e in entries) {
        if (compact) {
          buf.putShort(e.keyIdx.toShort())
          buf.putShort(((e.dataType shl 8) or 0x0008).toShort())
          buf.putInt(e.data)
        } else {
          buf.putShort(8.toShort()) // entrySize
          buf.putShort(0.toShort()) // flags
          buf.putInt(e.keyIdx)
          buf.putShort(8.toShort()) // Res_value size
          buf.put(0.toByte())
          buf.put(e.dataType.toByte())
          buf.putInt(e.data)
        }
      }
      return buf.array()
    }

    val colorType =
      buildTypeChunk(
        typeId = 1,
        entries =
          listOf(
            RawEntry(keyIdx = 0, dataType = 0x1c, data = 0xFF00E5FF.toInt()),
            RawEntry(keyIdx = 1, dataType = 0x01, data = 0x7f010000),
          ),
      )
    val dimenType = buildTypeChunk(typeId = 2, entries = listOf(RawEntry(2, 0x05, (24 shl 8) or 1)))
    val stringType = buildTypeChunk(typeId = 3, entries = listOf(RawEntry(3, 0x03, 0)))

    val pkgHeaderSize = 288
    val typeStringsOffset = pkgHeaderSize
    val keyStringsOffset = typeStringsOffset + typePool.size
    val pkgBody = ByteArrayOutputStream()
    pkgBody.write(typePool)
    pkgBody.write(keyPool)
    pkgBody.write(colorType)
    pkgBody.write(dimenType)
    pkgBody.write(stringType)
    val pkgBytes = pkgBody.toByteArray()
    val pkgTotalSize = pkgHeaderSize + pkgBytes.size
    val pkgBuf = ByteBuffer.allocate(pkgTotalSize).order(ByteOrder.LITTLE_ENDIAN)
    pkgBuf.putShort(0x0200.toShort())
    pkgBuf.putShort(pkgHeaderSize.toShort())
    pkgBuf.putInt(pkgTotalSize)
    pkgBuf.putInt(0x7f) // pkgId
    pkgBuf.position(268)
    pkgBuf.putInt(typeStringsOffset)
    pkgBuf.putInt(0)
    pkgBuf.putInt(keyStringsOffset)
    pkgBuf.putInt(0)
    pkgBuf.position(pkgHeaderSize)
    pkgBuf.put(pkgBytes)

    val tableHeaderSize = 12
    val tableTotalSize = tableHeaderSize + globalPool.size + pkgTotalSize
    val tableBuf = ByteBuffer.allocate(tableTotalSize).order(ByteOrder.LITTLE_ENDIAN)
    tableBuf.putShort(0x0002.toShort())
    tableBuf.putShort(tableHeaderSize.toShort())
    tableBuf.putInt(tableTotalSize)
    tableBuf.putInt(1) // packageCount
    tableBuf.put(globalPool)
    tableBuf.put(pkgBuf.array())
    return tableBuf.array()
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
