/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.rcembedded.jvm

/**
 * The knob-seed wire format shared by both entry points into this player — the one-shot [main]
 * (which reads it from a `--seeds` file) and the pooled [rcJvmRenderWorkerMain] (which receives it
 * inline in a request frame).
 *
 * One seed per line, space-separated `<kind> <base64Name> <value>`, where `kind` is `str` / `float`
 * / `int` / `color`. The name is base64 (it may contain any character); for `str` the value is
 * base64 too, for the numeric kinds it is a plain decimal (`color` is a decimal ARGB int). Unknown
 * kinds and malformed lines are skipped rather than failing the render — a seed the caller could
 * not express must degrade to the document's authored default, never to no picture at all.
 *
 * The producer is `RcJvmServerRenderer.seedLines` on the cli side; this is the only parser, so the
 * two lanes can never disagree about what a seed file means.
 */
internal fun parseSeedText(text: String): Map<String, RcSeed> {
  val decoder = java.util.Base64.getDecoder()
  fun decode(s: String) = String(decoder.decode(s), Charsets.UTF_8)
  val seeds = LinkedHashMap<String, RcSeed>()
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
    val seed =
      when (kind) {
        "str" ->
          try {
            RcSeed.StringValue(decode(rawValue))
          } catch (_: IllegalArgumentException) {
            null
          }
        "float" -> rawValue.toFloatOrNull()?.let { RcSeed.FloatValue(it) }
        "int" -> rawValue.toIntOrNull()?.let { RcSeed.IntValue(it) }
        "color" -> rawValue.toIntOrNull()?.let { RcSeed.ColorValue(it) }
        else -> null
      }
    if (seed != null) seeds[name] = seed
  }
  return seeds
}
