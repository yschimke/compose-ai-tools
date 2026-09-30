package ee.schimke.composeai.rcjvm

import ee.schimke.composeai.rcplayer.runtime.RcNamedValue

/**
 * The knob-seed wire format shared by both entry points — the one-shot [main] (which reads it from
 * a `--seeds` file) and the pooled [rcJvmRenderWorkerMain] (which receives it inline in a request
 * frame).
 *
 * One seed per line, space-separated `<kind> <base64Name> <value>`, where `kind` is `str` / `float`
 * / `int` / `color`. The name is base64 (it may contain any character); for `str` the value is
 * base64 too, for the numeric kinds it is a plain decimal (`color` is a decimal ARGB int). Unknown
 * kinds and malformed lines are skipped rather than failing the render: a seed the caller could not
 * express must degrade to the document's authored default, never to no picture at all.
 *
 * The producer is `RcJvmServerRenderer.seedLines` on the serve side; this is the only parser, so
 * the one-shot and pooled lanes can never disagree about what a seed file means.
 *
 * Names are handed to the player as written. `RcPlayerState` resolves a name with no `:` to
 * `USER:<name>` when the document declares one, which is how the connector registers author knobs.
 */
internal fun parseSeedText(text: String): Map<String, RcNamedValue> {
  val decoder = java.util.Base64.getDecoder()
  fun decode(s: String) = String(decoder.decode(s), Charsets.UTF_8)
  val seeds = LinkedHashMap<String, RcNamedValue>()
  text.lineSequence().forEach { line ->
    if (line.isBlank()) return@forEach
    val parts = line.split(' ')
    if (parts.size < 3) return@forEach
    val (kind, nameB64, rawValue) = Triple(parts[0], parts[1], parts[2])
    val name =
      try {
        decode(nameB64)
      } catch (_: IllegalArgumentException) {
        return@forEach
      }
    val seed: RcNamedValue? =
      when (kind) {
        "str" ->
          try {
            RcNamedValue.Text(decode(rawValue))
          } catch (_: IllegalArgumentException) {
            null
          }
        "float" -> rawValue.toFloatOrNull()?.let { RcNamedValue.FloatValue(it) }
        "int" -> rawValue.toIntOrNull()?.let { RcNamedValue.Integer(it) }
        "color" -> rawValue.toIntOrNull()?.let { RcNamedValue.Color(it) }
        else -> null
      }
    if (seed != null) seeds[name] = seed
  }
  return seeds
}
