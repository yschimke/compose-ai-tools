package ee.schimke.composeai.daemon

/**
 * The `knobs=<name>:<index>:<TYPE>[:<default>],…` render-payload token — how a preview's
 * **parameter knobs** travel on the wire when the spec itself cannot.
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
 *
 * ### The fourth field
 *
 * A knob's **literal default** rides as an optional fourth field, base64url-encoded. It is not
 * there for the render — the compiled default runs on its own when a position is unseeded — but for
 * the declaration a viewer draws its control from, which has to say what the field held before
 * anyone touched it. Encoding it is not optional: a default is author-written text and may
 * legitimately contain `:`, `,`, `;` or `=`, all of which are payload delimiters. A knob with no
 * recoverable default emits three fields, exactly as before, so a preview whose defaults are all
 * expressions produces the token it always did.
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
    return entries.joinToString(ENTRY_SEPARATOR.toString()) { knob ->
      val head = "${knob.name}:${knob.index}:${knob.type}"
      knob.default?.let { "$head:${encodeDefault(it)}" } ?: head
    }
  }

  /** The knobs [token] names, skipping any entry that is not well formed. */
  public fun parse(token: String?): List<PreviewKnobDto> {
    if (token.isNullOrBlank()) return emptyList()
    return token.split(ENTRY_SEPARATOR).mapNotNull { entry ->
      val parts = entry.split(FIELD_SEPARATOR)
      if (parts.size != 3 && parts.size != 4) return@mapNotNull null
      val name = parts[0].trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val index = parts[1].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
      val type = parts[2].trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
      // A fourth field that will not decode drops the *default*, not the knob: the knob still
      // seeds, it just cannot be declared. Losing the whole entry would cost the seeding too.
      val default = parts.getOrNull(3)?.let(::decodeDefault)
      PreviewKnobDto(name = name, index = index, type = type, default = default)
    }
  }

  /**
   * Base64url without padding — `=` is a payload delimiter, so the padded alphabet cannot be used
   * here. An empty default encodes to the empty string, which round-trips as an empty default
   * rather than as none: `title: String = ""` is a real default a viewer should show.
   */
  private fun encodeDefault(default: String): String =
    java.util.Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(default.toByteArray(Charsets.UTF_8))

  private fun decodeDefault(encoded: String): String? =
    try {
      String(java.util.Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
      null
    }

  /** `;` too: it separates payload tokens, so a name carrying one truncates the whole token. */
  private fun String.hasDelimiter(): Boolean =
    contains(ENTRY_SEPARATOR) || contains(FIELD_SEPARATOR) || contains(';') || contains('=')
}
