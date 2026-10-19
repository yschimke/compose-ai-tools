package ee.schimke.composeai.bundle

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The app's final-ID R classes, widened with the fields the renderer's own copy of each R class has
 * and the app's lacks.
 *
 * A detached Android render puts the app's R jar ahead of the renderer's jars, so its IDs, which
 * name the resources in the linked APK, win. But when the renderer's Compose runs (the daemon's
 * `appCompose` decision: `runs=daemon`, the app's Compose being older), that newer Compose links
 * against R fields the app's older libraries never declared: Compose 1.12.1's
 * `androidx.compose.ui.R$id.androidx_compose_ui_view_compose_view_context` is not in 1.7.5's
 * resources, so the app's `androidx.compose.ui.R$id` fails the link with `NoSuchFieldError`.
 *
 * So each app R class the renderer also carries gains the renderer's extra `int` fields, with the
 * renderer's values. Every field the app declares keeps the app's ID, which is the one its resource
 * APK resolves; an added field names a resource that APK does not have, as it did when the
 * renderer's copy was the only one, and is enough for what most of them are used for: view tag
 * keys. A missing `int[]` (a styleable new to the renderer) cannot be added without class
 * initialisation code and is left out. The union is harmless when the app's Compose runs: its code
 * never names the added fields.
 */
public object AndroidRClassUnion {

  /**
   * [carriage] (what [AndroidBundleResources.daemonClasspath] returns) with its R classes jar
   * replaced by the union with [rendererJars], written beside it. Returned unchanged when there is
   * no R jar or no R class to widen.
   */
  public fun widen(carriage: List<File>, rendererJars: List<File>): List<File> =
    carriage.map { entry ->
      if (entry.isFile && entry.name.endsWith(".jar")) {
        union(
          entry,
          rendererJars,
          File(entry.parentFile, entry.nameWithoutExtension + "-union.jar"),
        )
      } else entry
    }

  /**
   * Writes [rJar] to [out] with each R class widened by the renderer's copy in [rendererJars] (the
   * first jar that carries it, as a classpath would find it), and returns [out]; or returns [rJar]
   * itself when nothing needs widening.
   */
  public fun union(rJar: File, rendererJars: List<File>, out: File): File {
    val widened = linkedMapOf<String, ByteArray>()
    // Not a jar (an empty placeholder, say): there is nothing to widen.
    val appJar = openJar(rJar) ?: return rJar
    appJar.use { app ->
      val rClasses =
        app.entries().asSequence().map { it.name }.filter(::isRClass).toCollection(LinkedHashSet())
      if (rClasses.isEmpty()) return rJar
      val remaining = rClasses.toMutableSet()
      for (jar in rendererJars) {
        if (remaining.isEmpty()) break
        if (!jar.isFile) continue
        val carried =
          openJar(jar)?.use { renderer -> remaining.filter { renderer.getEntry(it) != null } }
            ?: continue
        if (carried.isEmpty()) continue
        remaining -= carried.toSet()
        URLClassLoader(arrayOf(jar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use {
          loader ->
          for (name in carried) {
            val bytes = app.getInputStream(app.getEntry(name)).use { it.readBytes() }
            val missing = rendererOnlyInts(loader, name, fieldNames(bytes)) ?: continue
            if (missing.isNotEmpty()) widened[name] = addIntConstants(bytes, missing)
          }
        }
      }
      if (widened.isEmpty()) return rJar
      out.parentFile?.mkdirs()
      ZipOutputStream(out.outputStream().buffered()).use { zip ->
        for (entry in app.entries()) {
          val copy = ZipEntry(entry.name).apply { time = entry.time }
          zip.putNextEntry(copy)
          val replaced = widened[entry.name]
          if (replaced != null) zip.write(replaced)
          else app.getInputStream(entry).use { it.copyTo(zip) }
          zip.closeEntry()
        }
      }
    }
    return out
  }

  private fun openJar(file: File): ZipFile? =
    try {
      ZipFile(file)
    } catch (_: ZipException) {
      null
    }

  /** `…/R.class` or `…/R$<type>.class`. */
  internal fun isRClass(entry: String): Boolean {
    val simple = entry.substringAfterLast('/')
    return simple == "R.class" || (simple.startsWith("R$") && simple.endsWith(".class"))
  }

  /**
   * The renderer's static `int` fields of class [entry] that [declared] lacks, by name and value;
   * null when the class cannot be loaded from [loader].
   */
  private fun rendererOnlyInts(
    loader: ClassLoader,
    entry: String,
    declared: Set<String>,
  ): Map<String, Int>? {
    val type =
      try {
        Class.forName(entry.removeSuffix(".class").replace('/', '.'), true, loader)
      } catch (_: ReflectiveOperationException) {
        return null
      } catch (_: LinkageError) {
        return null
      }
    val missing = sortedMapOf<String, Int>()
    for (field in type.declaredFields) {
      if (!Modifier.isStatic(field.modifiers) || field.type != Int::class.javaPrimitiveType)
        continue
      if (field.name in declared) continue
      field.isAccessible = true
      missing[field.name] = field.getInt(null)
    }
    return missing
  }

  /** Skips one constant pool entry of [tag]; returns the slots it takes (2 for long and double). */
  private fun skipConstant(tag: Int, input: DataInputStream, utf8: (String) -> Unit = {}): Int {
    when (tag) {
      1 -> utf8(input.readUTF())
      7,
      8,
      16,
      19,
      20 -> input.skipNBytes(2)
      15 -> input.skipNBytes(3)
      3,
      4,
      9,
      10,
      11,
      12,
      17,
      18 -> input.skipNBytes(4)
      5,
      6 -> {
        input.skipNBytes(8)
        return 2
      }
      else -> throw IllegalArgumentException("unknown constant pool tag $tag")
    }
    return 1
  }

  /** The names of the fields class file [bytes] declares. */
  internal fun fieldNames(bytes: ByteArray): Set<String> {
    val input = DataInputStream(bytes.inputStream())
    input.skipNBytes(8)
    val count = input.readUnsignedShort()
    val utf8 = arrayOfNulls<String>(count)
    var i = 1
    while (i < count) {
      val index = i
      i += skipConstant(input.readUnsignedByte(), input) { utf8[index] = it }
    }
    input.skipNBytes(6) // access_flags, this_class, super_class
    input.skipNBytes(2L * input.readUnsignedShort()) // interfaces
    val names = mutableSetOf<String>()
    repeat(input.readUnsignedShort()) {
      input.skipNBytes(2)
      names += requireNotNull(utf8[input.readUnsignedShort()])
      input.skipNBytes(2)
      skipAttributes(input)
    }
    return names
  }

  private fun skipAttributes(input: DataInputStream) {
    repeat(input.readUnsignedShort()) {
      input.skipNBytes(2)
      input.skipNBytes(input.readInt().toLong())
    }
  }

  /**
   * Class file [bytes] with a `public static final int` field per entry of [fields], each with a
   * `ConstantValue` of its value: what javac writes for an R field with a final ID.
   */
  internal fun addIntConstants(bytes: ByteArray, fields: Map<String, Int>): ByteArray {
    val input = DataInputStream(bytes.inputStream())
    val header = input.readNBytes(8)
    val count = input.readUnsignedShort()
    // The constant pool is copied as it is; new entries are appended after it.
    val poolStart = 10
    var i = 1
    while (i < count) i += skipConstant(input.readUnsignedByte(), input)
    val poolEnd = bytes.size - input.available()
    val appended = ByteArrayOutputStream()
    val pool = DataOutputStream(appended)
    var next = count
    fun utf8(value: String): Int {
      pool.writeByte(1)
      pool.writeUTF(value)
      return next++
    }
    val descriptor = utf8("I")
    val constantValue = utf8("ConstantValue")
    val added = ByteArrayOutputStream()
    val out = DataOutputStream(added)
    for ((name, value) in fields) {
      val nameIndex = utf8(name)
      pool.writeByte(3)
      pool.writeInt(value)
      val valueIndex = next++
      out.writeShort(0x0019) // ACC_PUBLIC | ACC_STATIC | ACC_FINAL
      out.writeShort(nameIndex)
      out.writeShort(descriptor)
      out.writeShort(1)
      out.writeShort(constantValue)
      out.writeInt(2)
      out.writeShort(valueIndex)
    }
    require(next <= 0xFFFF) { "constant pool overflow widening an R class" }
    input.skipNBytes(6) // access_flags, this_class, super_class
    input.skipNBytes(2L * input.readUnsignedShort()) // interfaces
    val fieldsCountAt = bytes.size - input.available()
    val fieldCount = input.readUnsignedShort()
    repeat(fieldCount) {
      input.skipNBytes(6)
      skipAttributes(input)
    }
    val fieldsEnd = bytes.size - input.available()
    val result = ByteArrayOutputStream(bytes.size + appended.size() + added.size())
    val data = DataOutputStream(result)
    data.write(header)
    data.writeShort(next)
    data.write(bytes, poolStart, poolEnd - poolStart)
    appended.writeTo(data)
    data.write(bytes, poolEnd, fieldsCountAt - poolEnd)
    data.writeShort(fieldCount + fields.size)
    data.write(bytes, fieldsCountAt + 2, fieldsEnd - fieldsCountAt - 2)
    added.writeTo(data)
    // Methods and class attributes refer to the pool by index, which appending left unchanged.
    data.write(bytes, fieldsEnd, bytes.size - fieldsEnd)
    data.flush()
    return result.toByteArray()
  }
}
