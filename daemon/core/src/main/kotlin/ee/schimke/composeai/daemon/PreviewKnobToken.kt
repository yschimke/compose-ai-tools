package ee.schimke.composeai.daemon

/**
 * The `knobs=<name>:<index>:<TYPE>,…` render-payload token — how a preview's **parameter knobs**
 * travel on the wire when the spec itself cannot.
 *
 * ### Why a token at all
 *
 * The desktop daemon resolves a `previewId=` render against its `PreviewIndex` and hands the
 * resulting [RenderSpec][ee.schimke.composeai.daemon.PreviewInfoDto] straight to the render body,
 * so there the knobs need no encoding. The Android daemon composes inside a Robolectric **sandbox
 * classloader**: the host reshapes the request into a payload string and the sandbox parses it
 * back, so anything the sandbox needs has to survive that round trip as text. Without this token an
 * Android render sees a preview with no declared knobs and every seed for one is dropped, which is
 * the same silent failure the format had on both backends before it was wired at all.
 *
 * ### Why it is forgiving
 *
 * An entry that is malformed — wrong arity, a non-numeric or negative index, a blank field — is
 * **skipped** rather than failing the parse, and an unknown type name is carried through verbatim
 * for the binder to drop. A newer plugin may name a knob kind an older daemon cannot build; that
 * parameter has to degrade to its author default while the rest of the preview still seeds. Failing
 * the whole render because a knob kind is unfamiliar would turn a forward-compatible wire into a
 * hard version pin.
 */
public object PreviewKnobToken {

  private const val ENTRY_SEPARATOR: Char = ','
  private const val FIELD_SEPARATOR: Char = ':'

  /**
   * The token for [knobs], or null when there is nothing to say — so a preview that declares none
   * (the overwhelming majority) produces a payload byte-identical to what it produced before this
   * token existed.
   *
   * A knob whose name carries one of the payload's own delimiters is **omitted**. Kotlin admits
   * such a name only through backticks (`` `my, knob` ``), so this is vanishingly rare, and
   * emitting it would corrupt the entries around it rather than just its own — one unseedable knob
   * is a far smaller loss than a garbled list.
   */
  public fun encode(knobs: List<PreviewKnobDto>): String? {
    if (knobs.isEmpty()) return null
    val entries = knobs.filter { knob ->
      knob.index >= 0 &&
        knob.name.isNotBlank() &&
        !knob.name.hasDelimiter() &&
        knob.type.isNotBlank() &&
        !knob.type.hasDelimiter()
    }
    if (entries.isEmpty()) return null
    return entries.joinToString(ENTRY_SEPARATOR.toString()) { "${it.name}:${it.index}:${it.type}" }
  }

  /** The knobs [token] names, skipping any entry that is not well formed. */
  public fun parse(token: String?): List<PreviewKnobDto> {
    if (token.isNullOrBlank()) return emptyList()
    return token.split(ENTRY_SEPARATOR).mapNotNull { entry ->
      val parts = entry.split(FIELD_SEPARATOR)
      if (parts.size != 3) return@mapNotNull null
      val name = parts[0].trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val index = parts[1].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
      val type = parts[2].trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
      PreviewKnobDto(name = name, index = index, type = type)
    }
  }

  /** `;` too: it separates payload tokens, so a name carrying one truncates the whole token. */
  private fun String.hasDelimiter(): Boolean =
    contains(ENTRY_SEPARATOR) || contains(FIELD_SEPARATOR) || contains(';') || contains('=')
}
