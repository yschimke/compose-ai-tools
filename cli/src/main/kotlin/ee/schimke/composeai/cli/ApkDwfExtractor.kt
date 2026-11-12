package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.locateBundleSidecarJars
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Extracts and compiles Declarative Watch Face Format (`<WatchFace>` under `res/raw/<name>.xml`)
 * assets from a Wear OS APK into `.rc` Remote Compose documents and `compiled-manifest.json` for
 * `:simulator-host`'s `DwfWatchFaceClient`.
 */
internal object ApkDwfExtractor {

  private const val DWF_RENDER_MAIN = "ee.schimke.composeai.rcjvm.DwfRenderMainKt"
  private const val RES_STRING_POOL_TYPE = 0x0001
  private const val RES_TABLE_TYPE = 0x0002
  private const val RES_XML_TYPE = 0x0003
  private const val RES_XML_START_ELEMENT_TYPE = 0x0102
  private const val RES_XML_END_ELEMENT_TYPE = 0x0103
  private const val RES_XML_CDATA_TYPE = 0x0104
  private const val RES_TABLE_PACKAGE_TYPE = 0x0200
  private const val RES_TABLE_TYPE_TYPE = 0x0201
  private const val UTF8_FLAG = 0x0100

  private const val TYPE_TYPE_FLAG_SPARSE = 0x01
  private const val TYPE_TYPE_FLAG_OFFSET16 = 0x02
  private const val ENTRY_FLAG_COMPLEX = 0x0001
  private const val ENTRY_FLAG_COMPACT = 0x0008

  private const val TYPE_REFERENCE = 0x01
  private const val TYPE_STRING = 0x03
  private const val TYPE_FLOAT = 0x04
  private const val TYPE_DIMENSION = 0x05
  private const val TYPE_INT_DEC = 0x10
  private const val TYPE_INT_HEX = 0x11
  private const val TYPE_INT_BOOLEAN = 0x12
  private const val TYPE_INT_COLOR_ARGB8 = 0x1c
  private const val TYPE_INT_COLOR_RGB8 = 0x1d
  private const val TYPE_INT_COLOR_ARGB4 = 0x1e
  private const val TYPE_INT_COLOR_RGB4 = 0x1f

  private val json = Json { encodeDefaults = true }

  @Serializable
  private data class WatchFaceInput(val id: String, val label: String, val xmlFile: String)

  internal data class ArscEntry(
    val typeName: String,
    val keyName: String,
    val dataType: Int,
    val data: Int,
    val stringValue: String?,
    val isDefaultConfig: Boolean,
  )

  internal class ArscTable(val entriesById: Map<Int, ArscEntry>) {
    fun resolveReferenceAttribute(resId: Int, attrName: String, depth: Int = 0): String? {
      if (depth > 16 || resId == 0) return null
      when (resId) {
        0x0106000b -> return "#FFFFFFFF" // @android:color/white
        0x0106000c -> return "#FF000000" // @android:color/black
        0x0106000d -> return "#00000000" // @android:color/transparent
      }
      val entry = entriesById[resId] ?: return null
      if (entry.dataType == TYPE_REFERENCE) {
        if (entry.data == resId) return null
        return resolveReferenceAttribute(entry.data, attrName, depth + 1)
      }
      if (entry.dataType == TYPE_STRING) {
        val s = entry.stringValue ?: return "@${entry.typeName}/${entry.keyName}"
        if (isFileBackedResourceType(entry.typeName) && s.startsWith("res/")) {
          return "@${entry.typeName}/${entry.keyName}"
        }
        return s
      }
      return formatScalarTypedValue(attrName, entry.dataType, entry.data)
    }

    private fun isFileBackedResourceType(typeName: String): Boolean =
      typeName == "drawable" ||
        typeName == "mipmap" ||
        typeName == "color" ||
        typeName == "font" ||
        typeName == "raw" ||
        typeName == "xml"
  }

  /**
   * True when [apk] declares `<property android:name="com.google.wear.watchface.format.version">`
   * or packages at least one `res/raw/<name>.xml` `<WatchFace>` document.
   */
  fun hasDeclarativeWatchFace(apk: File): Boolean = runCatching {
    ZipFile(apk).use { zip ->
      val manifestSignals =
        zip.getEntry("AndroidManifest.xml")?.let {
          ManifestSignals.read(zip.getInputStream(it).readBytes())
        }
      if (
        manifestSignals != null &&
          ApkFormFactor.WATCH_FACE_FORMAT_PROPERTY in manifestSignals.properties
      ) {
        return@use true
      }
      val arsc = readArscTableOrNull(zip)
      zip
        .entries()
        .asSequence()
        .filter { !it.isDirectory && isRawXmlEntry(it.name) }
        .any { entry ->
          val xml = decodeXmlOrNull(zip.getInputStream(entry).readBytes(), arsc) ?: return@any false
          looksLikeWatchFaceXml(xml)
        }
    }
  }
    .getOrDefault(false)

  /**
   * Extracts any `<WatchFace>` `res/raw/<name>.xml` entries and their supporting `res/`
   * drawables/fonts/colors from [apk] into a content-addressed directory under [cacheRoot],
   * compiles each watch face to `.rc` + `compiled-manifest.json` via `DwfRenderMainKt`, and returns
   * that directory (or `null` when [apk] contains no declarative watch faces).
   */
  fun extractAndCompile(
    apk: File,
    javaBinary: File,
    robolectricJars: List<File>,
    cacheRoot: File,
    verbose: Boolean = false,
  ): File? {
    val rcJvmJars = locateBundleSidecarJars("lib-rcjvm")
    if (rcJvmJars.isEmpty()) return null

    val key = cacheKey(apk, rcJvmJars)
    val workDir = cacheRoot.resolve("dwf/$key")
    val compiledManifest = File(workDir, "compiled-manifest.json")
    if (compiledManifest.isFile && compiledManifest.length() > 0L) {
      return workDir
    }

    val extractedInputs = mutableListOf<WatchFaceInput>()
    ZipFile(apk).use { zip ->
      val packageName =
        zip
          .getEntry("AndroidManifest.xml")
          ?.let { ManifestSignals.read(zip.getInputStream(it).readBytes()).packageName }
          .orEmpty()
      val arsc = readArscTableOrNull(zip)

      val rawXmlEntries =
        zip
          .entries()
          .asSequence()
          .filter { !it.isDirectory && isRawXmlEntry(it.name) }
          .sortedBy { it.name }
          .toList()
      if (rawXmlEntries.isEmpty()) return null

      val candidateWatchFaces = mutableListOf<Pair<String, String>>()
      for (entry in rawXmlEntries) {
        val xml = decodeXmlOrNull(zip.getInputStream(entry).readBytes(), arsc) ?: continue
        if (looksLikeWatchFaceXml(xml)) {
          val stem =
            entry.name
              .substringAfterLast('/')
              .substringBeforeLast('.')
              .replace(Regex("[^A-Za-z0-9._-]"), "_")
          if (stem.isNotEmpty() && stem != "." && stem != "..") {
            candidateWatchFaces += stem to xml
          }
        }
      }
      if (candidateWatchFaces.isEmpty()) return null

      workDir.deleteRecursively()
      val watchfacesDir = File(workDir, "watchfaces").apply { mkdirs() }
      val watchfacesPrefix = watchfacesDir.canonicalPath + File.separator
      val resDir = File(workDir, "res").apply { mkdirs() }

      for ((stem, xml) in candidateWatchFaces) {
        val outXml = File(watchfacesDir, "$stem.xml")
        if (!outXml.canonicalPath.startsWith(watchfacesPrefix)) continue
        outXml.writeText(xml, Charsets.UTF_8)
        val relPath = "watchfaces/${outXml.name}"
        val fullId = if (packageName.isNotEmpty()) "$packageName/$stem" else stem
        extractedInputs += WatchFaceInput(id = fullId, label = stem, xmlFile = relPath)
      }

      extractResourcesToDirectoryFromZip(zip, resDir, arsc)
    }

    if (extractedInputs.isEmpty()) return null
    File(workDir, "watchfaces-input.json").writeText(json.encodeToString(extractedInputs))

    val desktopJars = locateBundleSidecarJars("lib-daemon-desktop")
    val runtimeSidecar = desktopJars.ifEmpty { robolectricJars }
    val classpath = (rcJvmJars + runtimeSidecar).joinToString(File.pathSeparator) { it.path }
    val command =
      listOf(
        javaBinary.path,
        "-Xmx512m",
        "-Djava.awt.headless=true",
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        classpath,
        DWF_RENDER_MAIN,
        "--compile-apk-dwf-dir",
        workDir.absolutePath,
      )
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    if (exit != 0 || !compiledManifest.isFile) {
      if (verbose || output.isNotBlank()) {
        System.err.println(
          "compose-preview launch: failed to compile declarative watch face XMLs (exit $exit): " +
            output.trim(),
        )
      }
      return null
    }
    if (verbose) {
      System.err.println(
        "compose-preview launch: compiled ${extractedInputs.size} declarative watch face(s) in " +
          workDir,
      )
    }
    return workDir
  }

  /**
   * Extracts supporting `res/` drawables/fonts/colors/raw files and `resources.arsc` color/string
   * values from [apk] into [resDir], decoding compiled `aapt2` binary XML (`AXML`) back into UTF-8
   * text XML and resolving `@color`/`@dimen`/`@string` references so `DwfResourceDirectoryResolver`
   * can parse them offline.
   */
  internal fun extractResourcesToDirectory(apk: File, resDir: File) {
    ZipFile(apk).use { zip ->
      val arsc = readArscTableOrNull(zip)
      extractResourcesToDirectoryFromZip(zip, resDir, arsc)
    }
  }

  private fun extractResourcesToDirectoryFromZip(zip: ZipFile, resDir: File, arsc: ArscTable?) {
    val canonicalRoot = resDir.canonicalFile
    canonicalRoot.mkdirs()
    val canonicalRootPath = canonicalRoot.toPath()
    val canonicalRootPrefix = canonicalRoot.canonicalPath + File.separator

    val entries = zip.entries()
    while (entries.hasMoreElements()) {
      val entry = entries.nextElement()
      if (entry.isDirectory || !entry.name.startsWith("res/")) continue
      val subPath = entry.name.removePrefix("res/")
      if (
        !subPath.startsWith("drawable") &&
          !subPath.startsWith("mipmap") &&
          !subPath.startsWith("color") &&
          !subPath.startsWith("font") &&
          !subPath.startsWith("raw")
      ) {
        continue
      }
      val resolvedPath = canonicalRootPath.resolve(subPath).normalize()
      if (!resolvedPath.startsWith(canonicalRootPath)) continue
      val target = File(canonicalRoot, subPath)
      if (!target.canonicalPath.startsWith(canonicalRootPrefix)) continue
      target.parentFile?.mkdirs()
      if (subPath.endsWith(".xml", ignoreCase = true)) {
        val rawBytes = zip.getInputStream(entry).use { it.readBytes() }
        val decoded = decodeXmlOrNull(rawBytes, arsc)
        if (decoded != null) {
          target.writeText(decoded, Charsets.UTF_8)
        } else {
          target.writeBytes(rawBytes)
        }
      } else {
        zip.getInputStream(entry).use { input ->
          target.outputStream().use { out -> input.copyTo(out) }
        }
      }
    }

    if (arsc != null) {
      writeExtractedValuesXml(canonicalRoot, arsc)
    }
  }

  private fun writeExtractedValuesXml(canonicalResRoot: File, arsc: ArscTable) {
    val colorLines = mutableListOf<String>()
    val stringLines = mutableListOf<String>()
    val seenColors = HashSet<String>()
    val seenStrings = HashSet<String>()
    for ((resId, entry) in arsc.entriesById) {
      when (entry.typeName) {
        "color" -> {
          if (!seenColors.add(entry.keyName)) continue
          val resolved = arsc.resolveReferenceAttribute(resId, "color") ?: continue
          if (resolved.startsWith("#")) {
            colorLines +=
              "  <color name=\"${escapeXml(entry.keyName)}\">${escapeXml(resolved)}</color>"
          }
        }
        "string" -> {
          if (!seenStrings.add(entry.keyName)) continue
          val resolved = arsc.resolveReferenceAttribute(resId, "text") ?: continue
          stringLines +=
            "  <string name=\"${escapeXml(entry.keyName)}\">${escapeXml(resolved)}</string>"
        }
      }
    }
    if (colorLines.isEmpty() && stringLines.isEmpty()) return
    val valuesDir = File(canonicalResRoot, "values").apply { mkdirs() }
    val outXml = buildString {
      append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
      for (line in colorLines) append(line).append('\n')
      for (line in stringLines) append(line).append('\n')
      append("</resources>\n")
    }
    File(valuesDir, "arsc_extracted.xml").writeText(outXml, Charsets.UTF_8)
  }

  private fun readArscTableOrNull(zip: ZipFile): ArscTable? {
    val entry = zip.getEntry("resources.arsc") ?: return null
    val bytes = runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
    return bytes?.let(::parseResourceTableOrNull)
  }

  internal fun parseResourceTableOrNull(bytes: ByteArray): ArscTable? = runCatching {
    if (bytes.size < 12) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val tableType = buffer.getShort(0).toInt() and 0xffff
    if (tableType != RES_TABLE_TYPE) return null
    val tableHeaderSize = buffer.getShort(2).toInt() and 0xffff
    val tableSize = buffer.getInt(4).coerceAtMost(bytes.size)

    var globalStrings: List<String> = emptyList()
    val entries = LinkedHashMap<Int, ArscEntry>()
    var offset = tableHeaderSize
    while (offset + 8 <= tableSize) {
      val chunkType = buffer.getShort(offset).toInt() and 0xffff
      val chunkHeaderSize = buffer.getShort(offset + 2).toInt() and 0xffff
      val chunkSize = buffer.getInt(offset + 4)
      if (chunkSize <= 0 || offset + chunkSize > tableSize) break
      when (chunkType) {
        RES_STRING_POOL_TYPE -> globalStrings = readStringPool(buffer, offset)
        RES_TABLE_PACKAGE_TYPE ->
          parsePackageChunk(
            buffer,
            bytes,
            offset,
            chunkHeaderSize,
            chunkSize,
            globalStrings,
            entries,
          )
      }
      offset += chunkSize
    }
    ArscTable(entries)
  }
    .getOrNull()

  private fun parsePackageChunk(
    buffer: ByteBuffer,
    bytes: ByteArray,
    pkgOffset: Int,
    pkgHeaderSize: Int,
    pkgSize: Int,
    globalStrings: List<String>,
    outEntries: MutableMap<Int, ArscEntry>,
  ) {
    if (pkgSize < 284) return
    val pkgId = buffer.getInt(pkgOffset + 8) and 0xff
    val typeStringsRel = buffer.getInt(pkgOffset + 268)
    val keyStringsRel = buffer.getInt(pkgOffset + 276)
    val typeStrings =
      if (typeStringsRel > 0 && pkgOffset + typeStringsRel + 28 <= pkgOffset + pkgSize) {
        readStringPool(buffer, pkgOffset + typeStringsRel)
      } else {
        emptyList()
      }
    val keyStrings =
      if (keyStringsRel > 0 && pkgOffset + keyStringsRel + 28 <= pkgOffset + pkgSize) {
        readStringPool(buffer, pkgOffset + keyStringsRel)
      } else {
        emptyList()
      }

    var subOffset = pkgOffset + pkgHeaderSize
    val pkgEnd = pkgOffset + pkgSize
    while (subOffset + 8 <= pkgEnd) {
      val subType = buffer.getShort(subOffset).toInt() and 0xffff
      val subHeaderSize = buffer.getShort(subOffset + 2).toInt() and 0xffff
      val subSize = buffer.getInt(subOffset + 4)
      if (subSize <= 0 || subOffset + subSize > pkgEnd) break
      if (subType == RES_TABLE_TYPE_TYPE && subHeaderSize >= 24) {
        parseTypeChunk(
          buffer = buffer,
          bytes = bytes,
          typeOffset = subOffset,
          typeHeaderSize = subHeaderSize,
          typeSize = subSize,
          pkgId = pkgId,
          typeStrings = typeStrings,
          keyStrings = keyStrings,
          globalStrings = globalStrings,
          outEntries = outEntries,
        )
      }
      subOffset += subSize
    }
  }

  private fun parseTypeChunk(
    buffer: ByteBuffer,
    bytes: ByteArray,
    typeOffset: Int,
    typeHeaderSize: Int,
    typeSize: Int,
    pkgId: Int,
    typeStrings: List<String>,
    keyStrings: List<String>,
    globalStrings: List<String>,
    outEntries: MutableMap<Int, ArscEntry>,
  ) {
    val typeId = bytes[typeOffset + 8].toInt() and 0xff
    if (typeId == 0) return
    val typeName = typeStrings.getOrNull(typeId - 1).orEmpty()
    val typeFlags = bytes[typeOffset + 9].toInt() and 0xff
    val entryCount = buffer.getInt(typeOffset + 12)
    val entriesStart = buffer.getInt(typeOffset + 16)
    val configSize = buffer.getInt(typeOffset + 20)
    val configEnd = (typeOffset + 20 + configSize).coerceAtMost(typeOffset + typeHeaderSize)
    var isDefaultConfig = true
    for (pos in (typeOffset + 24) until configEnd) {
      if (bytes[pos].toInt() != 0) {
        isDefaultConfig = false
        break
      }
    }

    val offsetsStart = typeOffset + typeHeaderSize
    val entriesBase = typeOffset + entriesStart
    val chunkEnd = typeOffset + typeSize
    val isSparse = typeFlags and TYPE_TYPE_FLAG_SPARSE != 0
    val isOffset16 = typeFlags and TYPE_TYPE_FLAG_OFFSET16 != 0

    for (i in 0 until entryCount) {
      val entryId: Int
      val entryByteOffset: Int
      when {
        isSparse -> {
          val pos = offsetsStart + i * 4
          if (pos + 4 > entriesBase) break
          entryId = buffer.getShort(pos).toInt() and 0xffff
          entryByteOffset = (buffer.getShort(pos + 2).toInt() and 0xffff) * 4
        }
        isOffset16 -> {
          val pos = offsetsStart + i * 2
          if (pos + 2 > entriesBase) break
          val off16 = buffer.getShort(pos).toInt() and 0xffff
          if (off16 == 0xffff) continue
          entryId = i
          entryByteOffset = off16 * 4
        }
        else -> {
          val pos = offsetsStart + i * 4
          if (pos + 4 > entriesBase) break
          val off32 = buffer.getInt(pos)
          if (off32 == -1) continue
          entryId = i
          entryByteOffset = off32
        }
      }

      val ep = entriesBase + entryByteOffset
      if (ep + 8 > chunkEnd) continue
      val entryFlags = buffer.getShort(ep + 2).toInt() and 0xffff
      val keyIndex: Int
      val dataType: Int
      val data: Int
      if (entryFlags and ENTRY_FLAG_COMPACT != 0) {
        keyIndex = buffer.getShort(ep).toInt() and 0xffff
        dataType = (entryFlags ushr 8) and 0xff
        data = buffer.getInt(ep + 4)
      } else {
        if (entryFlags and ENTRY_FLAG_COMPLEX != 0) continue
        val entrySize = buffer.getShort(ep).toInt() and 0xffff
        if (ep + entrySize + 8 > chunkEnd) continue
        keyIndex = buffer.getInt(ep + 4)
        dataType = bytes[ep + entrySize + 3].toInt() and 0xff
        data = buffer.getInt(ep + entrySize + 4)
      }

      val keyName = keyStrings.getOrNull(keyIndex).orEmpty()
      if (keyName.isEmpty()) continue
      val resId = (pkgId shl 24) or (typeId shl 16) or (entryId and 0xffff)
      val existing = outEntries[resId]
      if (existing != null && existing.isDefaultConfig && !isDefaultConfig) continue
      val stringVal = if (dataType == TYPE_STRING) globalStrings.getOrNull(data) else null
      outEntries[resId] =
        ArscEntry(
          typeName = typeName,
          keyName = keyName,
          dataType = dataType,
          data = data,
          stringValue = stringVal,
          isDefaultConfig = isDefaultConfig,
        )
    }
  }

  private fun isRawXmlEntry(name: String): Boolean =
    name.startsWith("res/raw") && name.endsWith(".xml", ignoreCase = true)

  private fun looksLikeWatchFaceXml(xml: String): Boolean =
    xml.contains("<WatchFace") && xml.contains("<Scene")

  private fun cacheKey(apk: File, rcJvmJars: List<File>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("${apk.absolutePath}:${apk.length()}:${apk.lastModified()}\n".toByteArray())
    for (jar in rcJvmJars.sortedBy { it.name }) {
      digest.update("${jar.name}:${jar.length()}:${jar.lastModified()}\n".toByteArray())
    }
    return digest.digest().take(12).joinToString("") { "%02x".format(it) }
  }

  /**
   * Decodes [bytes] as UTF-8 text XML when uncompiled, or converts Android Binary XML (`AXML`,
   * chunk type `0x0003`) back into text XML when `aapt2` compiled the resource.
   */
  internal fun decodeXmlOrNull(bytes: ByteArray, arsc: ArscTable? = null): String? {
    if (bytes.size < 4) return null
    val trimmedStart = bytes.indexOfFirst {
      it != ' '.code.toByte() &&
        it != '\t'.code.toByte() &&
        it != '\r'.code.toByte() &&
        it != '\n'.code.toByte() &&
        it != 0xEF.toByte() &&
        it != 0xBB.toByte() &&
        it != 0xBF.toByte()
    }
    if (trimmedStart >= 0 && bytes[trimmedStart] == '<'.code.toByte()) {
      return String(bytes, Charsets.UTF_8)
    }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val fileType = buffer.getShort(0).toInt() and 0xffff
    if (fileType != RES_XML_TYPE) return null
    return runCatching { decodeBinaryXml(buffer, bytes, arsc) }.getOrNull()
  }

  private fun decodeBinaryXml(buffer: ByteBuffer, bytes: ByteArray, arsc: ArscTable?): String {
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
    var strings: List<String> = emptyList()
    var offset = buffer.getShort(2).toInt() and 0xffff
    while (offset + 8 <= bytes.size) {
      val type = buffer.getShort(offset).toInt() and 0xffff
      val headerSize = buffer.getShort(offset + 2).toInt() and 0xffff
      val size = buffer.getInt(offset + 4)
      if (size <= 0 || offset + size > bytes.size) break
      when (type) {
        RES_STRING_POOL_TYPE -> strings = readStringPool(buffer, offset)
        RES_XML_START_ELEMENT_TYPE -> {
          val ext = offset + headerSize
          val name = strings.getOrNull(buffer.getInt(ext + 4)).orEmpty()
          val attributeStart = buffer.getShort(ext + 8).toInt() and 0xffff
          val attributeSize = buffer.getShort(ext + 10).toInt() and 0xffff
          val count = buffer.getShort(ext + 12).toInt() and 0xffff
          sb.append('<').append(name)
          for (i in 0 until count) {
            val at = ext + attributeStart + i * attributeSize
            val attrName = strings.getOrNull(buffer.getInt(at + 4)).orEmpty()
            if (attrName.isEmpty()) continue
            val rawIndex = buffer.getInt(at + 8)
            val dataType = bytes[at + 15].toInt() and 0xff
            val data = buffer.getInt(at + 16)
            val attrValue =
              when {
                rawIndex >= 0 -> strings.getOrNull(rawIndex)
                dataType == TYPE_REFERENCE ->
                  arsc?.resolveReferenceAttribute(data, attrName)
                    ?: "@0x${Integer.toUnsignedString(data, 16)}"
                dataType == TYPE_STRING -> strings.getOrNull(data)
                else -> formatScalarTypedValue(attrName, dataType, data)
              }.orEmpty()
            sb.append(' ').append(attrName).append("=\"").append(escapeXml(attrValue)).append('"')
          }
          sb.append(">\n")
        }
        RES_XML_END_ELEMENT_TYPE -> {
          val ext = offset + headerSize
          val name = strings.getOrNull(buffer.getInt(ext + 4)).orEmpty()
          sb.append("</").append(name).append(">\n")
        }
        RES_XML_CDATA_TYPE -> {
          val ext = offset + headerSize
          val text = strings.getOrNull(buffer.getInt(ext)).orEmpty()
          sb.append(escapeXml(text))
        }
      }
      offset += size
    }
    return sb.toString()
  }

  private fun formatScalarTypedValue(attrName: String, dataType: Int, data: Int): String =
    when (dataType) {
      TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
      TYPE_FLOAT -> java.lang.Float.intBitsToFloat(data).toString()
      TYPE_DIMENSION -> decodeDimension(data)
      TYPE_INT_HEX ->
        decodeEnumAttributeOrNull(attrName, data) ?: "0x${Integer.toUnsignedString(data, 16)}"
      TYPE_INT_COLOR_ARGB8,
      TYPE_INT_COLOR_ARGB4 -> "#%08X".format(data)
      TYPE_INT_COLOR_RGB8,
      TYPE_INT_COLOR_RGB4 -> "#%06X".format(data and 0xFFFFFF)
      TYPE_INT_DEC -> decodeEnumAttributeOrNull(attrName, data) ?: data.toString()
      else -> data.toString()
    }

  private fun decodeDimension(data: Int): String {
    val mantissa = (data shr 8).toFloat()
    val scale =
      when ((data ushr 4) and 0x3) {
        0 -> 1f
        1 -> 1f / 128f
        2 -> 1f / 32768f
        else -> 1f / 8388608f
      }
    val value = mantissa * scale
    val formatted =
      if (value % 1f == 0f) {
        value.toInt().toString()
      } else {
        value.toString()
      }
    val unit =
      when (data and 0xf) {
        0 -> "px"
        1 -> "dp"
        2 -> "sp"
        3 -> "pt"
        4 -> "in"
        5 -> "mm"
        else -> "px"
      }
    return "$formatted$unit"
  }

  private fun decodeEnumAttributeOrNull(attrName: String, data: Int): String? =
    when (attrName.substringAfterLast(':')) {
      "fillType" ->
        when (data) {
          0 -> "nonZero"
          1 -> "evenOdd"
          else -> null
        }
      "strokeLineCap" ->
        when (data) {
          0 -> "butt"
          1 -> "round"
          2 -> "square"
          else -> null
        }
      "strokeLineJoin" ->
        when (data) {
          0 -> "miter"
          1 -> "round"
          2 -> "bevel"
          else -> null
        }
      else -> null
    }

  private fun escapeXml(s: String): String =
    buildString(s.length) {
      for (ch in s) {
        when (ch) {
          '&' -> append("&amp;")
          '<' -> append("&lt;")
          '>' -> append("&gt;")
          '"' -> append("&quot;")
          '\'' -> append("&apos;")
          else -> append(ch)
        }
      }
    }

  private fun readStringPool(buffer: ByteBuffer, chunk: Int): List<String> {
    val count = buffer.getInt(chunk + 8)
    val flags = buffer.getInt(chunk + 16)
    val stringsStart = buffer.getInt(chunk + 20)
    val headerSize = buffer.getShort(chunk + 2).toInt() and 0xffff
    val utf8 = flags and UTF8_FLAG != 0
    return List(count) { i ->
      val at = chunk + stringsStart + buffer.getInt(chunk + headerSize + i * 4)
      if (utf8) readUtf8(buffer, at) else readUtf16(buffer, at)
    }
  }

  private fun readUtf8(buffer: ByteBuffer, start: Int): String {
    var at = start
    at += if (buffer.get(at).toInt() and 0x80 != 0) 2 else 1
    var length = buffer.get(at).toInt() and 0xff
    if (length and 0x80 != 0) {
      length = ((length and 0x7f) shl 8) or (buffer.get(at + 1).toInt() and 0xff)
      at += 2
    } else {
      at += 1
    }
    val bytes = ByteArray(length) { buffer.get(at + it) }
    return String(bytes, Charsets.UTF_8)
  }

  private fun readUtf16(buffer: ByteBuffer, start: Int): String {
    var at = start
    var length = buffer.getShort(at).toInt() and 0xffff
    if (length and 0x8000 != 0) {
      length = ((length and 0x7fff) shl 16) or (buffer.getShort(at + 2).toInt() and 0xffff)
      at += 4
    } else {
      at += 2
    }
    val chars = CharArray(length) { buffer.getShort(at + it * 2).toInt().toChar() }
    return String(chars)
  }
}
