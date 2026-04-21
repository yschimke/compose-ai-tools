package ee.schimke.composeai.renderer

/**
 * Derives a human-readable filename suffix from a `@PreviewParameter`
 * provider's values so the fan-out lands on `<id>_on.png` / `<id>_off.png`
 * instead of `<id>_PARAM_0.png` / `<id>_PARAM_1.png`. Falls back to
 * `_PARAM_<idx>` per value when no label can be derived, or across the whole
 * fan-out when any two values would collide after sanitization — keeping the
 * output directory unambiguous is more important than pretty names when the
 * provider makes labeling awkward.
 *
 * Recognised shapes:
 * - `Pair<*, *>` — takes `first.toString()` as the label (matches the common
 *   `"on" to snapshot(...)` provider idiom).
 * - Any value exposing a `name`, `label`, or `id` Kotlin property (or the
 *   corresponding Java bean getter) returning a `String` — covers data class
 *   conventions without pinning us to a specific base interface.
 * - Otherwise the value's `toString()`, provided it's not the default
 *   `ClassName@hash` form.
 *
 * Duplicated between the Android and Desktop renderers so neither pulls the
 * other's artifact onto the classpath — matches the existing split for
 * `loadProviderValues` and `insertBeforeExtension`.
 */
internal object PreviewParameterLabels {

    fun suffixesFor(values: List<Any?>): List<String> {
        val labels = values.map { rawLabel(it)?.let(::sanitize)?.takeIf { s -> s.isNotEmpty() } }
        // Duplicate labels would clobber each other on disk. When any two
        // values produce the same label, fall back to `_PARAM_<idx>` for
        // every value in the fan-out (not just the colliders) so the
        // filenames stay internally consistent: either every entry is a
        // label or every entry is a numbered PARAM.
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
        // `Object.toString()` default shape — `ClassName@hexHash`. That's
        // never useful as a filename, so treat it as no label and fall back
        // to `_PARAM_<idx>`.
        val defaultPrefix = value.javaClass.name + "@"
        return if (str.startsWith(defaultPrefix)) null else str
    }

    private fun propertyLabel(value: Any?): String? {
        val v = value ?: return null
        for (candidate in listOf("name", "label", "id")) {
            val getter = "get" + candidate.replaceFirstChar { it.uppercase() }
            val method = runCatching {
                v.javaClass.methods.firstOrNull {
                    it.name == getter &&
                        it.parameterCount == 0 &&
                        it.returnType == String::class.java
                }
            }.getOrNull() ?: continue
            val result = runCatching { method.invoke(v) as? String }.getOrNull()
            if (!result.isNullOrBlank()) return result
        }
        return null
    }

    private fun sanitize(label: String): String {
        // Whitelist the POSIX-plain character set `[A-Za-z0-9._-]`. Every
        // other character (Unicode dashes, parens, punctuation, whitespace,
        // emoji…) collapses to `_`. Enumerating a blacklist is a losing
        // game — a whitelist keeps the output predictable across shells,
        // URL consumers, and CI systems that reject non-ASCII paths.
        val replaced = label.trim().replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val collapsed = replaced.replace(Regex("""_+"""), "_").trim('_', '.', '-')
        // Cap length so an accidental long `toString()` doesn't blow up
        // filesystem path limits. 32 chars is enough for human-readable
        // labels and matches the stem-sanitization budget on the plugin
        // side.
        return if (collapsed.length > MAX_LABEL_LEN) {
            collapsed.substring(0, MAX_LABEL_LEN).trim('_', '.', '-')
        } else {
            collapsed
        }
    }

    private const val MAX_LABEL_LEN = 32
}
