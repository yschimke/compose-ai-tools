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
  val handler =
    InvocationHandler { proxy, method, args ->
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
    )
    as FontFamily.Resolver
}

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
    listOf("getFamilyName", "getFamily", "getName")
      .firstNotNullOfOrNull { name ->
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
