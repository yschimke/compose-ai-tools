package ee.schimke.composeai.renderer

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Encode a Skia [Image] to PNG through whatever `encodeToData` the resolved skiko actually exposes.
 *
 * ## Why this is not just `image.encodeToData(EncodedImageFormat.PNG)`
 *
 * skiko 0.150.0 added a parameter to `Image.encodeToData` — `(format, quality)` became `(format,
 * quality, compressionLevel)` — so the method the renderer was COMPILED against no longer exists on
 * the consumer's classpath. skiko resolves to a single version across
 * `composePreviewRenderClasspath`, and a consumer on a newer Compose Multiplatform line wins that
 * conflict (1.12.0-beta01 is the first release pulling skiko 0.150.0), so the renderer's call site
 * stopped linking and every capture died with `NoSuchMethodError: …Image.encodeToData$default(…)` —
 * drawn, then lost at the encode step, while the task still exited 0 (compose-ai-tools#4190).
 *
 * The renderer is a tool consumed against somebody else's Compose version, so it cannot pin skiko.
 * Binding the encode late is the only thing that survives the consumer choosing the version.
 *
 * ## Why it calls the synthetic `$default` bridge rather than the method itself
 *
 * Calling `encodeToData` directly would mean supplying every parameter, which means KNOWING what
 * each one is — and a value invented here for a parameter skiko added (0.150's compression level)
 * is a guess that silently changes output the day it is wrong. Kotlin's `$default` bridge takes a
 * bitmask of "use the declared default for this one", so a mask with every bit set except the
 * format's asks skiko for ITS OWN defaults for every parameter it has, now and after the next one
 * is added. Only the format is ours, because PNG is the one thing the caller is asserting.
 *
 * Resolution happens once and is cached; a reflective invoke is immaterial beside encoding a PNG.
 */
object SkiaPngEncoder {

  /**
   * The bound bridge plus the argument template it is invoked with. Internal so a test can bind
   * against a stand-in shaped like a skiko this build does not resolve — the whole point of the
   * indirection is the version it is NOT compiled against, which no test on the real `Image` can
   * reach.
   */
  internal class Binding(
    private val method: Method,
    private val template: Array<Any?>,
    val description: String,
  ) {
    fun invoke(receiver: Any): Any? {
      val args = template.copyOf()
      args[0] = receiver
      return method.invoke(null, *args)
    }
  }

  private val binding: Result<Binding> by lazy { runCatching { bind(Image::class.java) } }

  /**
   * What was bound, for a diagnostic — the resolved skiko version and the signature it exposed, or
   * why binding failed. Named in the render failure so a classpath skew reads as a classpath skew
   * rather than as a broken preview.
   */
  val diagnostic: String
    get() =
      binding.fold(
        onSuccess = { "skiko ${skikoVersion()}: ${it.description}" },
        onFailure = { "skiko ${skikoVersion()}: no usable Image.encodeToData — ${it.message}" },
      )

  /**
   * The PNG bytes of [image], or null when skiko itself declined to encode — its own `null` return,
   * which every call site already treats as a failure.
   *
   * Throws when no `encodeToData` could be bound at all: that is a classpath fault, not a preview
   * fault, and must not be mistaken for "this component would not draw".
   */
  fun encode(image: Image): Data? {
    val bound = binding.getOrElse { throw IllegalStateException(diagnostic, it) }
    return bound.invoke(image) as Data?
  }

  internal fun bind(owner: Class<*>): Binding {
    // `encodeToData$default(Image, EncodedImageFormat, …ints…, int mask, Object marker)`. The
    // receiver leads, the declared parameters follow, then one mask int per 32 parameters (always
    // one here), then the marker.
    val bridge =
      owner.methods
        .filter { it.name == BRIDGE_NAME && Modifier.isStatic(it.modifiers) }
        .minByOrNull { it.parameterCount } ?: error("${owner.name} declares no $BRIDGE_NAME bridge")

    val types = bridge.parameterTypes
    val maskAt = types.size - 2
    require(types.size >= 4 && types[maskAt] == Int::class.javaPrimitiveType) {
      "unexpected $BRIDGE_NAME shape: ${types.joinToString { it.simpleName }}"
    }

    val template = arrayOfNulls<Any?>(types.size)
    // [0] is the receiver, filled per call. [1] is the format — the only parameter this asserts.
    template[1] = EncodedImageFormat.PNG
    // Everything between the format and the mask is skiko's business: a placeholder of the right
    // primitive shape so the reflective invoke boxes, and the mask below tells skiko to ignore it.
    for (i in 2 until maskAt) template[i] = placeholder(types[i])
    // Every default except bit 0 (the format), which we supplied. `-1` with bit 0 cleared, so a
    // parameter skiko adds tomorrow takes skiko's default rather than a value invented here.
    template[maskAt] = -2
    template[types.size - 1] = null

    val declared = types.copyOfRange(1, maskAt).joinToString { it.simpleName }
    return Binding(bridge, template, "Image.encodeToData($declared)")
  }

  private fun placeholder(type: Class<*>): Any? =
    when (type) {
      Int::class.javaPrimitiveType -> 0
      Long::class.javaPrimitiveType -> 0L
      Float::class.javaPrimitiveType -> 0f
      Double::class.javaPrimitiveType -> 0.0
      Boolean::class.javaPrimitiveType -> false
      Byte::class.javaPrimitiveType -> 0.toByte()
      Short::class.javaPrimitiveType -> 0.toShort()
      Char::class.javaPrimitiveType -> ' '
      else -> null
    }

  /** The skiko actually on the classpath, or `unknown` on a build that dropped `Version`. */
  private fun skikoVersion(): String = runCatching {
    val cls = Class.forName("org.jetbrains.skiko.Version")
    val instance = cls.getField("INSTANCE").get(null)
    cls.getMethod("getSkiko").invoke(instance) as String
  }
    .getOrDefault("unknown")

  private const val BRIDGE_NAME = "encodeToData\$default"
}

/** [SkiaPngEncoder.encode] as the extension every call site used to spell `encodeToData(PNG)`. */
fun Image.encodePngData(): Data? = SkiaPngEncoder.encode(this)
