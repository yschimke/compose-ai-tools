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
  private const val RES_XML_TYPE = 0x0003
  private const val RES_STRING_POOL_TYPE = 0x0001
  private const val RES_XML_START_ELEMENT_TYPE = 0x0102
  private const val RES_XML_END_ELEMENT_TYPE = 0x0103
  private const val RES_XML_CDATA_TYPE = 0x0104
  private const val UTF8_FLAG = 0x0100

  private const val TYPE_STRING = 0x03
  private const val TYPE_FLOAT = 0x04
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
      zip
        .entries()
        .asSequence()
        .filter { !it.isDirectory && isRawXmlEntry(it.name) }
        .any { entry ->
          val xml = decodeXmlOrNull(zip.getInputStream(entry).readBytes()) ?: return@any false
          looksLikeWatchFaceXml(xml)
        }
    }
  }
    .getOrDefault(false)

  /**
   * Extracts any `<WatchFace>` `res/raw/<name>.xml` entries and their supporting `res/`
   * drawables/fonts from [apk] into a content-addressed directory under [cacheRoot], compiles each
   * watch face to `.rc` + `compiled-manifest.json` via `DwfRenderMainKt`, and returns that
   * directory (or `null` when [apk] contains no declarative watch faces).
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
        val xml = decodeXmlOrNull(zip.getInputStream(entry).readBytes()) ?: continue
        if (looksLikeWatchFaceXml(xml)) {
          val stem = File(entry.name).nameWithoutExtension
          candidateWatchFaces += stem to xml
        }
      }
      if (candidateWatchFaces.isEmpty()) return null

      workDir.deleteRecursively()
      val watchfacesDir = File(workDir, "watchfaces").apply { mkdirs() }
      val resDir = File(workDir, "res").apply { mkdirs() }

      for ((stem, xml) in candidateWatchFaces) {
        val relPath = "watchfaces/$stem.xml"
        File(workDir, relPath).writeText(xml)
        val fullId = if (packageName.isNotEmpty()) "$packageName/$stem" else stem
        extractedInputs += WatchFaceInput(id = fullId, label = stem, xmlFile = relPath)
      }

      val entries = zip.entries()
      while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (entry.isDirectory || !entry.name.startsWith("res/")) continue
        val subPath = entry.name.removePrefix("res/")
        if (
          subPath.startsWith("drawable") ||
            subPath.startsWith("mipmap") ||
            subPath.startsWith("font") ||
            subPath.startsWith("raw")
        ) {
          val target = File(resDir, subPath)
          target.parentFile?.mkdirs()
          zip.getInputStream(entry).use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
          }
        }
      }
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
  internal fun decodeXmlOrNull(bytes: ByteArray): String? {
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
    return runCatching { decodeBinaryXml(buffer, bytes) }.getOrNull()
  }

  private fun decodeBinaryXml(buffer: ByteBuffer, bytes: ByteArray): String {
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
                dataType == TYPE_STRING -> strings.getOrNull(data)
                dataType == TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
                dataType == TYPE_FLOAT -> java.lang.Float.intBitsToFloat(data).toString()
                dataType == TYPE_INT_HEX -> "0x${Integer.toUnsignedString(data, 16)}"
                dataType == TYPE_INT_COLOR_ARGB8 || dataType == TYPE_INT_COLOR_ARGB4 ->
                  "#%08X".format(data)
                dataType == TYPE_INT_COLOR_RGB8 || dataType == TYPE_INT_COLOR_RGB4 ->
                  "#%06X".format(data and 0xFFFFFF)
                dataType == TYPE_INT_DEC -> data.toString()
                else -> data.toString()
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
