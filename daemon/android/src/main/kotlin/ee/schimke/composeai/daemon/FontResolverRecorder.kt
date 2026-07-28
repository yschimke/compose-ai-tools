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
    val matched = matchingFont(fontFamily, fontWeight, fontStyle)
    // An Android resource-backed face (`FontFamily(Font(R.font.montserrat_regular, …))`) reaches
    // the capture as the bare `res/font/<resId>` handle. Recover its bytes out of the render's
    // resource table now — this is the only place they are reachable — so the figma-svg export can
    // embed the real face instead of emitting the numeric id as a CSS family with nothing behind
    // it (issue #2886). Best-effort: a face we can't recover is published as a *rendered* family
    // below, which makes the export box the text and write its warning sidecar rather than
    // silently substituting Roboto.
    val resourceFamily = matched?.let(::recoverResourceFont)
    FigmaSvgRenderedFonts.record(resourceFamily ?: matched?.let { displayFamilyName(fontLabel(it)) })
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

  /**
   * Extracts an Android resource-backed [font]'s bytes to a real file and publishes the mapping on
   * [FigmaResourceFonts], returning the family name the `compose/figma-svg` export will end up
   * naming it (issue #2886).
   *
   * Returns null for a non-resource font (nothing to recover — the caller falls back to the usual
   * label path). For a resource font that could **not** be recovered it returns the resource's own
   * entry name (`montserrat_regular`): that is deliberately a name the export cannot produce a
   * face for, so it lands in `FigmaSvgRenderedFonts.unnamedIn(...)` and the text exports as
   * missing-glyph boxes plus a warning sidecar — the house rule that wrong-and-obvious beats
   * wrong-and-plausible. Emitting the numeric id as a CSS family with no `@font-face` behind it is
   * exactly the silent-substitution failure this closes.
   */
  private fun recoverResourceFont(font: Font): String? {
    val resId = resourceId(font) ?: return null
    val ctx = context ?: return null
    val identity = FigmaResourceFonts.identityFor(resId)
    FigmaResourceFonts.pathFor(identity)?.let { cached ->
      val file = java.io.File(cached)
      if (file.isFile && file.length() > 0) {
        fontFamilyOf(file.readBytes())?.let { return it }
      }
    }
    // The resource table's own path tells us the on-disk extension; a `res/font/<name>.xml` family
    // descriptor is not a face we can embed, so it takes the unrecoverable branch below.
    val value = TypedValue()
    val resPath =
      runCatching {
          ctx.resources.getValue(resId, value, true)
          value.string?.toString()
        }
        .getOrNull()
        .orEmpty()
    val entryName =
      runCatching { ctx.resources.getResourceEntryName(resId) }.getOrNull()?.takeIf {
        it.isNotBlank()
      } ?: resId.toString()
    val extension =
      when {
        resPath.endsWith(".otf", ignoreCase = true) -> "otf"
        resPath.endsWith(".ttf", ignoreCase = true) -> "ttf"
        // No usable path (or an XML font-family descriptor): the raw stream is still worth a try
        // for the common binary-resource case, and a non-font payload simply fails to parse below.
        resPath.isEmpty() -> "ttf"
        else -> return entryName
      }
    val bytes =
      runCatching { ctx.resources.openRawResource(resId).use { it.readBytes() } }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() } ?: return entryName
    val target =
      runCatching {
          val dir = java.io.File(System.getProperty("java.io.tmpdir"), "composeai-res-fonts")
          dir.mkdirs()
          java.io.File(dir, "$entryName-$resId.$extension").apply { writeBytes(bytes) }
        }
        .getOrNull() ?: return entryName
    val family = fontFamilyOf(bytes) ?: return entryName
    FigmaResourceFonts.register(identity, target.absolutePath)
    return family
  }

  /**
   * The font's real family name read out of [bytes] (`"Montserrat"`) — the same read
   * `ComposeFigmaSvgDataProducer.fontFileFamily` performs, so the name published here is exactly
   * the one the export emits on the `<text>` and in its `@font-face`. Null when the bytes aren't a
   * parseable font, which is how an XML font-family descriptor (or any non-font payload) is
   * rejected rather than registered as an unusable face.
   */
  private fun fontFamilyOf(bytes: ByteArray): String? =
    runCatching {
        java.awt.Font.createFont(
            java.awt.Font.TRUETYPE_FONT,
            java.io.ByteArrayInputStream(bytes),
          )
          .family
      }
      .getOrNull()
      ?.takeIf { it.isNotBlank() }
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
