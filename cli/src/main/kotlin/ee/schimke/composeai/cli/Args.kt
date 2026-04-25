package ee.schimke.composeai.cli

/**
 * Returns the value of `flag` from a positional argv (e.g. `--module foo`), or `null` if the flag
 * is missing or unaccompanied.
 *
 * Trivial parser shared by every command. If we ever grow more flag types (repeatable, comma-list,
 * equals-form), promote this file to a real argv helper rather than duplicating the parser per
 * call-site.
 */
internal fun List<String>.flagValue(flag: String): String? {
  val idx = indexOf(flag)
  return if (idx >= 0 && idx + 1 < size) this[idx + 1] else null
}
