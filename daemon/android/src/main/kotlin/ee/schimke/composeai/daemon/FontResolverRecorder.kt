package ee.schimke.composeai.daemon

import android.content.Context
import android.util.TypedValue
import androidx.compose.runtime.State
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.GenericFontFamily
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

class FontResolverRecorder(private val context: Context? = null) {
  private val entries = Collections.synchronizedMap(linkedMapOf<String, FontUsedEntry>())

  fun record(fontFamily: FontFamily?, fontWeight: FontWeight?, fontStyle: Any?, resolved: Any?) {
    val weight = fontWeight?.weight ?: FontWeight.Normal.weight
    val style = styleName(fontStyle)
    val requestedFamily = requestedFamily(fontFamily)
    val resolvedFamily = resolvedFamily(resolved)
    val chain = fallbackCandidates(fontFamily).filterNot { it == resolvedFamily }.take(5)
    // Publish the display name so `compose/figma-svg` can tell a legitimate platform-default text
    // node apart from one whose branded family the capture lost (see [FigmaSvgRenderedFonts]).
    // Recorded here rather than at post-capture because the SVG export is an independent
    // post-capture extension with no ordering guarantee against this one.
    //
    // The *weight-matched* face, not `requestedFamily`: that one is the family's first declared
    // face, which is not what gets drawn when a family mixes families across weights. A branded
    // family that appends non-Latin fallbacks (`Orbitron` 500/600/700 then `Noto Sans JP` 400)
    // draws Noto for normal-weight text, and recording Orbitron there would tell the export a face
    // was used that it never sees — marking a perfectly reproducible export degraded and boxing
    // unrelated family-less text in the same SVG.
    FigmaSvgRenderedFonts.record(
      matchingFont(fontFamily, fontWeight, fontStyle)?.let { displayFamilyName(fontLabel(it)) }
    )
    val key = listOf(requestedFamily, resolvedFamily, weight.toString(), style).joinToString("|")
    entries[key] =
      FontUsedEntry(
        requestedFamily = requestedFamily,
        resolvedFamily = resolvedFamily,
        weight = weight,
        style = style,
        sourceFile = sourceFile(fontFamily, fontWeight, fontStyle),
        fellBackFrom = chain.takeIf { it.isNotEmpty() },
        consumerNodeIds = emptyList(),
      )
  }

  fun payload(): FontsUsedPayload =
    FontsUsedPayload(
      fonts =
        synchronized(entries) {
          entries.values.sortedWith(compareBy({ it.requestedFamily }, { it.weight }, { it.style }))
        }
    )

  private fun sourceFile(
    fontFamily: FontFamily?,
    fontWeight: FontWeight?,
    fontStyle: Any?,
  ): String? {
    val font = matchingFont(fontFamily, fontWeight, fontStyle) ?: return null
    val resId = resourceId(font)
    val ctx = context
    if (resId != null && ctx != null) {
      val value = TypedValue()
      val path =
        runCatching {
            ctx.resources.getValue(resId, value, true)
            value.string?.toString()
          }
          .getOrNull()
      if (!path.isNullOrBlank()) return path
    }
    val label = fontLabel(font)
    return label.takeIf { it.isNotBlank() && !it.startsWith("ResourceFont(") }
  }
}

fun recordingFontFamilyResolver(
  delegate: FontFamily.Resolver,
  recorder: FontResolverRecorder,
): FontFamily.Resolver {
  val handler = InvocationHandler { proxy, method, args ->
    if (method.declaringClass == Any::class.java) {
      return@InvocationHandler when (method.name) {
        "toString" -> "RecordingFontFamilyResolver($delegate)"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> method.invoke(delegate, *(args ?: emptyArray()))
      }
    }
    val result =
      try {
        method.invoke(delegate, *(args ?: emptyArray()))
      } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
      }
    if (method.name.startsWith("resolve")) {
      @Suppress("UNCHECKED_CAST") val state = result as? State<Any>
      recorder.record(
        fontFamily = args?.getOrNull(0) as? FontFamily,
        fontWeight = args?.getOrNull(1) as? FontWeight,
        fontStyle = args?.getOrNull(2),
        resolved = state?.value,
      )
    }
    result
  }
  return Proxy.newProxyInstance(
    FontFamily.Resolver::class.java.classLoader,
    arrayOf(FontFamily.Resolver::class.java),
    handler,
  ) as FontFamily.Resolver
}

/**
 * The human family name inside a recorded label, or null when there isn't one worth reporting.
 *
 * The recorder's labels are whatever identity a `Font` exposes: a downloadable face arrives as
 * `Font(GoogleFont("Orbitron", …), weight=…)`, a bundled one as a file path, and the platform
 * default as `FontFamily.Default`. Only a concrete branded face is interesting to the SVG
 * cross-check — the default is exactly the case that must not raise a warning, and a generic
 * (`sans-serif`) legitimately maps to the default face.
 */
internal fun displayFamilyName(label: String): String? {
  GOOGLE_FONT_LABEL.find(label)?.groupValues?.getOrNull(1)?.let { name ->
    return name.takeIf { it.isNotBlank() }
  }
  if (label == "FontFamily.Default" || label.startsWith("res/font/")) return null
  if (label.lowercase() in GENERIC_FAMILIES) return null
  val leaf = label.substringAfterLast('/').substringBeforeLast('.')
  return leaf.takeIf { it.isNotBlank() && !it.startsWith("Font(") }
}

private val GOOGLE_FONT_LABEL = Regex("""GoogleFont\("([^"]+)"""")

private val GENERIC_FAMILIES =
  setOf("sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui")

private fun requestedFamily(fontFamily: FontFamily?): String =
  when (fontFamily) {
    null -> "FontFamily.Default"
    is GenericFontFamily -> fontFamily.name
    is FontListFontFamily -> fallbackCandidates(fontFamily).firstOrNull() ?: fontFamily.toString()
    else -> fontFamily.toString()
  }

private fun fallbackCandidates(fontFamily: FontFamily?): List<String> =
  when (fontFamily) {
    is FontListFontFamily -> fontFamily.fonts.map(::fontLabel).distinct()
    null -> emptyList()
    else -> emptyList()
  }

private fun matchingFont(fontFamily: FontFamily?, fontWeight: FontWeight?, fontStyle: Any?): Font? {
  val list = fontFamily as? FontListFontFamily ?: return null
  val weight = fontWeight?.weight ?: FontWeight.Normal.weight
  val style = styleName(fontStyle)
  return list.fonts.firstOrNull { it.weight.weight == weight && styleName(it.style) == style }
    ?: list.fonts.firstOrNull { it.weight.weight == weight }
    ?: list.fonts.firstOrNull()
}

private fun fontLabel(font: Font): String {
  val identity =
    runCatching {
        val field = font.javaClass.getDeclaredField("identity")
        field.isAccessible = true
        field.get(font) as? String
      }
      .getOrNull()
  if (!identity.isNullOrBlank()) return identity
  val resId = resourceId(font)
  if (resId != null) return "res/font/$resId"
  return font.toString()
}

private fun resourceId(font: Font): Int? =
  runCatching {
      val field = font.javaClass.getDeclaredField("resId")
      field.isAccessible = true
      field.get(font) as? Int
    }
    .getOrNull()

private fun resolvedFamily(resolved: Any?): String {
  if (resolved == null) return "<unresolved>"
  val reflected =
    listOf("getFamilyName", "getFamily", "getName").firstNotNullOfOrNull { name ->
      runCatching {
          val value =
            resolved.javaClass.methods
              .firstOrNull { it.name == name && it.parameterCount == 0 }
              ?.invoke(resolved)
          value as? String
        }
        .getOrNull()
    }
  return reflected?.takeIf { it.isNotBlank() } ?: resolved.toString()
}

private fun styleName(style: Any?): String {
  val raw = style?.toString()?.lowercase()
  return when {
    raw == "1" || raw?.contains("italic") == true -> "italic"
    else -> "normal"
  }
}
