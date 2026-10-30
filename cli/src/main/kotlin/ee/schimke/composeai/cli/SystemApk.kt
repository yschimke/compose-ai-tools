package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The package the simulator boots when `launch` is given no APK: a manifest and nothing else, so
 * Robolectric has a resource APK to stand the device up on and the system UI (the phone home screen
 * or the Wear OS one) is all that shows. `:simulator-host`'s `ApkAppLoader` sees
 * `composeai.simulator.noApp` and starts its own `NoAppActivity` in place of a launcher activity.
 *
 * The manifest is written as binary XML here rather than by `aapt2`, which a machine with only an
 * SDK platform does not have; it is a handful of elements, and [Axml] writes exactly those.
 */
internal object SystemApk {

  const val PACKAGE = "ee.schimke.composeai.simulator.system"

  /** Writes (once) and returns the system APK for [formFactor] under [dir]. */
  fun ensure(dir: File, formFactor: ApkFormFactor, targetSdk: Int): File {
    val apk = File(dir, "system-${formFactor.id}-$targetSdk-v$VERSION.apk")
    if (apk.isFile) return apk
    dir.mkdirs()
    val tmp = File(dir, apk.name + ".tmp")
    ZipOutputStream(tmp.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zip.write(manifest(formFactor, targetSdk))
      zip.closeEntry()
    }
    check(tmp.renameTo(apk) || apk.isFile) { "could not write $apk" }
    return apk
  }

  /** Bumped when [manifest] changes, so a cached APK is rewritten. */
  private const val VERSION = 1

  fun manifest(formFactor: ApkFormFactor, targetSdk: Int): ByteArray {
    val children = buildList {
      add(
        Axml.Element(
          "uses-sdk",
          listOf(
            Axml.Attr.int("minSdkVersion", MIN_SDK_VERSION, 21),
            Axml.Attr.int("targetSdkVersion", TARGET_SDK_VERSION, targetSdk),
          ),
        )
      )
      if (formFactor == ApkFormFactor.WEAR) {
        add(
          Axml.Element(
            "uses-feature",
            listOf(
              Axml.Attr.string("name", NAME, "android.hardware.type.watch"),
              Axml.Attr.bool("required", REQUIRED, true),
            ),
          )
        )
      }
      add(Axml.Element("application", listOf(Axml.Attr.string("label", LABEL, "System"))))
    }
    return Axml.write(
      Axml.Element(
        "manifest",
        listOf(
          Axml.Attr.int("versionCode", VERSION_CODE, 1),
          Axml.Attr.string("versionName", VERSION_NAME, "1.0"),
          Axml.Attr.plainString("package", PACKAGE),
        ),
        children,
      )
    )
  }

  // android.R.attr ids.
  private const val LABEL = 0x01010001
  private const val NAME = 0x01010003
  private const val MIN_SDK_VERSION = 0x0101020c
  private const val VERSION_CODE = 0x0101021b
  private const val VERSION_NAME = 0x0101021c
  private const val TARGET_SDK_VERSION = 0x01010270
  private const val REQUIRED = 0x0101028e
}

/**
 * A minimal Android binary XML (`ResXMLTree`) writer: one namespace (`android`), elements with
 * string, integer and boolean attributes. Attribute names with a resource id come first in the
 * string pool, matching the resource map, and each element's attributes are sorted by that id, as
 * the framework's attribute lookup expects.
 */
internal object Axml {

  const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

  class Element(
    val name: String,
    val attrs: List<Attr> = emptyList(),
    val children: List<Element> = emptyList(),
  )

  class Attr
  private constructor(
    val name: String,
    /** 0 for an attribute outside the `android` namespace (`package`). */
    val resId: Int,
    val type: Int,
    val int: Int,
    val string: String?,
  ) {
    companion object {
      fun string(name: String, resId: Int, value: String) = Attr(name, resId, TYPE_STRING, 0, value)

      fun plainString(name: String, value: String) = Attr(name, 0, TYPE_STRING, 0, value)

      fun int(name: String, resId: Int, value: Int) = Attr(name, resId, TYPE_INT_DEC, value, null)

      fun bool(name: String, resId: Int, value: Boolean) =
        Attr(name, resId, TYPE_INT_BOOLEAN, if (value) -1 else 0, null)
    }
  }

  private const val TYPE_STRING = 0x03
  private const val TYPE_INT_DEC = 0x10
  private const val TYPE_INT_BOOLEAN = 0x12

  fun write(root: Element): ByteArray {
    val all = mutableListOf<Element>()
    fun walk(e: Element) {
      all += e
      e.children.forEach(::walk)
    }
    walk(root)
    // Resource-id'd attribute names first (the resource map indexes them), then everything else.
    val resNames =
      all.flatMap { it.attrs }.filter { it.resId != 0 }.distinctBy { it.name }.sortedBy { it.resId }
    val strings = LinkedHashMap<String, Int>()
    fun intern(s: String): Int = strings.getOrPut(s) { strings.size }
    resNames.forEach { intern(it.name) }
    intern("android")
    intern(ANDROID_NS)
    for (e in all) {
      intern(e.name)
      for (a in e.attrs) {
        intern(a.name)
        a.string?.let(::intern)
      }
    }

    val body = ByteArrayOutputStream()
    body.write(stringPool(strings.keys.toList()))
    body.write(chunk(0x0180, 8, ints(resNames.map { it.resId })))
    val ns = ints(listOf(intern("android"), intern(ANDROID_NS)))
    body.write(chunk(0x0100, 16, node(ns)))
    fun element(e: Element) {
      val attrs = e.attrs.sortedWith(compareBy({ it.resId == 0 }, { it.resId }))
      val ext = le(20 + attrs.size * 20)
      ext.putInt(-1).putInt(intern(e.name))
      ext.putShort(20).putShort(20).putShort(attrs.size.toShort())
      ext.putShort(0).putShort(0).putShort(0)
      for (a in attrs) {
        ext.putInt(if (a.resId != 0) intern(ANDROID_NS) else -1)
        ext.putInt(intern(a.name))
        ext.putInt(a.string?.let(::intern) ?: -1)
        ext.putShort(8).put(0).put(a.type.toByte())
        ext.putInt(a.string?.let(::intern) ?: a.int)
      }
      body.write(chunk(0x0102, 16, node(ext.array())))
      e.children.forEach(::element)
      body.write(chunk(0x0103, 16, node(ints(listOf(-1, intern(e.name))))))
    }
    element(root)
    body.write(chunk(0x0101, 16, node(ns)))
    return chunk(0x0003, 8, body.toByteArray())
  }

  /** A UTF-16 string pool. */
  private fun stringPool(strings: List<String>): ByteArray {
    val data = ByteArrayOutputStream()
    val offsets = mutableListOf<Int>()
    for (s in strings) {
      offsets += data.size()
      val b = le(2 + s.length * 2 + 2)
      b.putShort(s.length.toShort())
      s.forEach { b.putChar(it) }
      b.putShort(0)
      data.write(b.array())
    }
    while (data.size() % 4 != 0) data.write(0)
    val header = le(20)
    header.putInt(strings.size).putInt(0).putInt(0).putInt(28 + strings.size * 4).putInt(0)
    return chunk(0x0001, 28, header.array() + ints(offsets) + data.toByteArray())
  }

  /** A tree node's line number and comment, then [ext]. */
  private fun node(ext: ByteArray): ByteArray = ints(listOf(1, -1)) + ext

  /** A chunk: type, header size, total size, then [rest] (header remainder and body). */
  private fun chunk(type: Int, headerSize: Int, rest: ByteArray): ByteArray {
    val b = le(8 + rest.size)
    b.putShort(type.toShort()).putShort(headerSize.toShort()).putInt(8 + rest.size).put(rest)
    return b.array()
  }

  private fun ints(values: List<Int>): ByteArray {
    val b = le(values.size * 4)
    values.forEach { b.putInt(it) }
    return b.array()
  }

  private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
