package ee.schimke.composeai.renderer

/**
 * Desktop mirror of the Android renderer's [PreviewParameterLabels] — see that file for the full
 * commentary on label derivation and fallback rules. Duplicated here (not shared via a common
 * module) so the two renderer artefacts stay independently buildable.
 */
internal object PreviewParameterLabels {

  fun suffixesFor(values: List<Any?>): List<String> {
    val labels = values.map {
      rawLabel(it)?.let(::sanitize)?.takeIf { s ->
        s.isNotEmpty() && !RESERVED_INDEX_LABEL.matches(s)
      }
    }
    val nonNull = labels.filterNotNull()
    val hasCollision = nonNull.size != nonNull.toSet().size
    return labels.mapIndexed { idx, label ->
      when {
        label == null || hasCollision -> "_PARAM_$idx"
        else -> "_$label"
      }
    }
  }

  private fun rawLabel(value: Any?): String? {
    if (value == null) return null
    if (value is Pair<*, *>) {
      val first = value.first ?: return null
      return (first as? String) ?: first.toString()
    }
    val fromProperty = propertyLabel(value)
    if (fromProperty != null) return fromProperty
    val str = runCatching { value.toString() }.getOrNull() ?: return null
    val defaultPrefix = value.javaClass.name + "@"
    return if (str.startsWith(defaultPrefix)) null else str
  }

  private fun propertyLabel(value: Any?): String? {
    val v = value ?: return null
    for (candidate in listOf("name", "label", "id")) {
      val getter = "get" + candidate.replaceFirstChar { it.uppercase() }
      val method =
        runCatching {
            v.javaClass.methods.firstOrNull {
              it.name == getter && it.parameterCount == 0 && it.returnType == String::class.java
            }
          }
          .getOrNull() ?: continue
      val result = runCatching { method.invoke(v) as? String }.getOrNull()
      if (!result.isNullOrBlank()) return result
    }
    return null
  }

  private fun sanitize(label: String): String {
    val replaced = label.trim().replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    val collapsed = replaced.replace(Regex("""_+"""), "_").trim('_', '.', '-')
    return if (collapsed.length > MAX_LABEL_LEN) {
      collapsed.substring(0, MAX_LABEL_LEN).trim('_', '.', '-')
    } else {
      collapsed
    }
  }

  private const val MAX_LABEL_LEN = 32

  /**
   * The `PARAM_<digits>` shape is **reserved** for the positional fallback below, so a value whose
   * derived label happens to take it is treated as unlabelled and gets its own `_PARAM_<idx>`.
   *
   * Without this the two meanings collide and a caller can't tell them apart: the daemon addresses
   * one row of a fan-out by this same suffix (issue #3749), reading `PARAM_<n>` as "value n". A
   * value at index 0 labelled `PARAM_1` would emit `<stem>_PARAM_1.png` from the fan-out while the
   * daemon resolved that id to value *1* — silently the wrong state, and an out-of-range `<n>`
   * would fail a row that genuinely exists. Reserving the shape at generation time is what keeps
   * the filenames the renderer writes and the ids the daemon accepts describing the same row.
   */
  private val RESERVED_INDEX_LABEL = Regex(INDEX_LABEL_PATTERN)

  private const val INDEX_LABEL_PATTERN = "PARAM_[0-9]+"
}
