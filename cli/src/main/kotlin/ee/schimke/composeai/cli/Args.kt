package ee.schimke.composeai.cli

/**
 * Returns the value of `flag` from argv (`--module foo` or `--module=foo`), or `null` if the flag
 * is missing or unaccompanied.
 *
 * Trivial parser shared by every command. If we ever grow more flag types (repeatable, comma-list,
 * equals-form), promote this file to a real argv helper rather than duplicating the parser per
 * call-site.
 */
internal fun List<String>.flagValue(flag: String): String? {
  firstOrNull { it.startsWith("$flag=") }
    ?.let {
      return it.substringAfter("=")
    }
  val idx = indexOf(flag)
  return if (idx >= 0 && idx + 1 < size) this[idx + 1] else null
}

/**
 * Repeatable flag values: every `--flag value` and every `--flag=value` form, in order. Empty list
 * when the flag isn't present. Used by `mcp install --module :a --module :b` and similar.
 */
internal fun List<String>.flagValuesAll(flag: String): List<String> {
  val out = mutableListOf<String>()
  var i = 0
  while (i < size) {
    val arg = this[i]
    when {
      arg == flag && i + 1 < size -> {
        out.add(this[i + 1])
        i += 2
      }
      arg.startsWith("$flag=") -> {
        out.add(arg.substringAfter("="))
        i++
      }
      else -> i++
    }
  }
  return out
}
