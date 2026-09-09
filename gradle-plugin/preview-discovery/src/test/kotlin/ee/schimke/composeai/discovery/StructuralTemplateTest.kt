package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The hole-filler catalog-published structural templates are rendered by.
 *
 * The interesting cases are the two that make it worth existing rather than being `String.replace`:
 * indentation of a multi-line value, and a `${'$'}{call(...)}` override whose value is a Kotlin
 * expression full of the characters a naive parser splits on.
 */
class StructuralTemplateTest {

  private fun render(template: String, values: Map<String, String> = emptyMap()) =
    StructuralTemplate.render(template) { hole ->
      when (hole) {
        is StructuralTemplate.Hole.Named -> values[hole.name]
        is StructuralTemplate.Hole.Call ->
          "Call(${hole.overrides.entries.joinToString { "${it.key}=${it.value}" }})"
      }
    }

  private fun emitted(result: StructuralTemplate.Result): String =
    (result as StructuralTemplate.Result.Emitted).source

  private fun reasons(result: StructuralTemplate.Result): List<String> =
    (result as StructuralTemplate.Result.Refused).reasons

  @Test
  fun `a named hole is substituted and the rest is left alone`() {
    val out =
      emitted(
        render(
          "TransformingLazyColumn(state = \${listState}) { }",
          mapOf("listState" to "columnState"),
        )
      )

    assertThat(out).isEqualTo("TransformingLazyColumn(state = columnState) { }")
  }

  @Test
  fun `a multi-line value is indented to the column of the hole that starts its line`() {
    // The whole reason this is not String.replace. A naive substitution indents the first line and
    // leaves the rest at column zero, and the output is Kotlin somebody reads.
    val template =
      """
      AppScaffold {
        ScreenScaffold(scrollState = state) {
          ${'$'}{content}
        }
      }
      """
        .trimIndent()

    val out =
      emitted(
        render(template, mapOf("content" to "Text(\"one\")\nText(\"two\")\n\nText(\"three\")"))
      )

    assertThat(out)
      .isEqualTo(
        """
        AppScaffold {
          ScreenScaffold(scrollState = state) {
            Text("one")
            Text("two")

            Text("three")
          }
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun `a hole in the middle of a line does not invent an indent for its value`() {
    // There is no single column to align to — the text before it is not indentation — and guessing
    // one produces output worse than leaving the value as the caller wrote it.
    val out = emitted(render("val x = \${value} // trailing", mapOf("value" to "a\nb")))

    assertThat(out).isEqualTo("val x = a\nb // trailing")
  }

  @Test
  fun `a call hole carries its overrides as source text`() {
    val out =
      emitted(
        render(
          "item {\n  \${call(modifier = \"Modifier.transformedHeight(this, spec)\", " +
            "transformation = \"SurfaceTransformation(spec)\")}\n}"
        )
      )

    assertThat(out)
      .contains(
        "Call(modifier=\"Modifier.transformedHeight(this, spec)\", " +
          "transformation=\"SurfaceTransformation(spec)\")"
      )
  }

  @Test
  fun `an override value may contain commas, parentheses and braces inside a string`() {
    // `${'$'}{call(modifier = "Modifier.padding(if (x) 8.dp else 0.dp)")}` is the shape that breaks
    // a splitter written against the happy path, and it is the shape a real template has.
    val result =
      render("\${call(modifier = \"Modifier.padding(if (x) 8.dp else 0.dp), other\", n = 1)}")

    assertThat(emitted(result))
      .isEqualTo("Call(modifier=\"Modifier.padding(if (x) 8.dp else 0.dp), other\", n=1)")
  }

  @Test
  fun `a character literal keeps its comma and its equals sign`() {
    // An override's value is arbitrary Kotlin kept verbatim, and `separator = ','` is an ordinary
    // thing to write. With only double quotes tracked, the comma inside the char literal read as a
    // top-level argument separator and split one argument into two, so a valid template was refused
    // as malformed — and `'='` would have been split in the wrong place by the name/value finder.
    assertThat(emitted(render("\${call(separator = ',', n = 1)}")))
      .isEqualTo("Call(separator=',', n=1)")
    assertThat(emitted(render("\${call(pad = '=')}"))).isEqualTo("Call(pad='=')")
    // An escaped quote inside a char literal does not end it.
    assertThat(emitted(render("\${call(q = '\\'', n = 1)}"))).isEqualTo("Call(q='\\'', n=1)")
  }

  @Test
  fun `a brace inside a character literal does not end the hole`() {
    // The THIRD scanner that has to know what a quote is, and the one that runs first: this ended
    // the hole at the `}` inside the char literal, so the argument splitter's own handling was
    // never reached and the template was refused as malformed.
    assertThat(emitted(render("\${call(separator = '}')}"))).isEqualTo("Call(separator='}')")
    assertThat(emitted(render("\${call(open = '{', n = 1)}"))).isEqualTo("Call(open='{', n=1)")
  }

  @Test
  fun `a raw string keeps its braces, quotes and commas`() {
    // The scanners are told what a quote is in one place now, because they had twice been taught
    // separately and twice disagreed. A triple-quoted Kotlin string carrying JSON is the case that
    // breaks all three at once: the inner `"` would flip string state, and the `}` inside it would
    // end the hole before the argument splitter ever ran.
    val payload = "\${call(payload = \"\"\"{\"end\":\"}\"}\"\"\", n = 1)}"
    assertThat(emitted(render(payload))).isEqualTo("Call(payload=\"\"\"{\"end\":\"}\"}\"\"\", n=1)")
    // A comma and an `=` inside a raw string are not separators either.
    assertThat(emitted(render("\${call(q = \"\"\"a, b = c\"\"\")}")))
      .isEqualTo("Call(q=\"\"\"a, b = c\"\"\")")
  }

  @Test
  fun `a call with no overrides is the ordinary case`() {
    assertThat(emitted(render("\${call()}"))).isEqualTo("Call()")
  }

  @Test
  fun `an unresolved hole is a refusal naming it, never an empty string`() {
    // A screen root whose content silently vanished compiles, renders an empty box, and is the
    // worst possible outcome for an export somebody is about to paste into an IDE.
    val result = render("A \${first} B \${second}", mapOf("first" to "x"))

    assertThat(reasons(result)).containsExactly("no value for \${second}")
  }

  @Test
  fun `every problem is reported, not just the first`() {
    val result = render("\${a} \${b}")

    assertThat(reasons(result)).hasSize(2)
  }

  @Test
  fun `a dollar not opening a hole is ordinary text`() {
    // Generated Kotlin is full of `$`; only `${'$'}{` opens a hole.
    assertThat(emitted(render("\"total: \$count\""))).isEqualTo("\"total: \$count\"")
  }

  @Test
  fun `malformed holes are refused with the offset`() {
    assertThat(reasons(render("a \${ b"))).hasSize(1)
    assertThat(reasons(render("a \${} b")).single()).contains("empty")
    assertThat(reasons(render("a \${1nope} b")).single()).contains("not a name or a call")
    assertThat(reasons(render("a \${call} b")).single()).contains("malformed")
    assertThat(reasons(render("a \${call(x)} b")).single()).contains("malformed")
    assertThat(reasons(render("a \${call(x = 1, x = 2)} b")).single()).contains("malformed")
  }

  @Test
  fun `a generic argument list is not a list of arguments`() {
    // An override's value is documented as arbitrary Kotlin, and the splitter tracked only
    // `()`, `[]` and `{}` — so the comma in `emptyMap<String, Int>()` sat at depth zero and cut one
    // named argument into two, rejecting a valid template as malformed.
    val single =
      StructuralTemplate.holes("\${call(items = emptyMap<String, Int>())}")
        as StructuralTemplate.Result2.Ok
    assertThat(single.value)
      .containsExactly(StructuralTemplate.Hole.Call(mapOf("items" to "emptyMap<String, Int>()")))

    // Nested, and beside a genuine second argument that must still split.
    val nested =
      StructuralTemplate.holes("\${call(rows = listOf<Pair<String, Int>>(), n = 1)}")
        as StructuralTemplate.Result2.Ok
    assertThat(nested.value)
      .containsExactly(
        StructuralTemplate.Hole.Call(mapOf("rows" to "listOf<Pair<String, Int>>()", "n" to "1"))
      )
  }

  @Test
  fun `a function type is an ordinary generic argument`() {
    // `emptyMap<String, (Int, Int) -> Unit>()` is valid Kotlin. Excluding `(` from the scan made it
    // give up there, record no span, and let the comma after `String` split one override into two.
    val functionType =
      StructuralTemplate.holes("\${call(factory = emptyMap<String, (Int, Int) -> Unit>())}")
        as StructuralTemplate.Result2.Ok
    assertThat(functionType.value)
      .containsExactly(
        StructuralTemplate.Hole.Call(mapOf("factory" to "emptyMap<String, (Int, Int) -> Unit>()"))
      )
  }

  @Test
  fun `a type-use annotation is an ordinary generic argument`() {
    // The third shape of the same scan: `@Composable () -> Unit` is the function type Compose
    // actually writes, and `@` was not in the allowed set, so the scan broke at it, recorded no
    // span, and let the comma after `String` split one override into two — the same failure the
    // paren case above was fixed for, reached by a character rather than a bracket.
    val annotated =
      StructuralTemplate.holes("\${call(factory = emptyMap<String, @Composable () -> Unit>())}")
        as StructuralTemplate.Result2.Ok
    assertThat(annotated.value)
      .containsExactly(
        StructuralTemplate.Hole.Call(
          mapOf("factory" to "emptyMap<String, @Composable () -> Unit>()")
        )
      )
  }

  @Test
  fun `a comment inside a generic argument list is skipped`() {
    // The fourth shape of this scan, and the one that shows the fix pattern was wrong: `commentEnd`
    // exists precisely so a scanner does not read a comment as source, and three scanners consult
    // it. `genericSpans` was written afterwards and never did, so a comment broke the scan at `/`
    // exactly as `(` and `@` did before it.
    val commented =
      StructuralTemplate.holes("\${call(factory = emptyMap<String, /* result type */ Int>())}")
        as StructuralTemplate.Result2.Ok
    assertThat(commented.value)
      .containsExactly(
        StructuralTemplate.Hole.Call(
          mapOf("factory" to "emptyMap<String, /* result type */ Int>()")
        )
      )
  }

  @Test
  fun `a less-than that is not a generic list is left alone`() {
    // The pre-scan may only ever un-split, so a comparison expression has to behave exactly as it
    // did before it existed — otherwise closing one false rejection would open another.
    val compared =
      StructuralTemplate.holes("\${call(a = if (x < y) 1 else 2, b = 3)}")
        as StructuralTemplate.Result2.Ok
    assertThat(compared.value)
      .containsExactly(
        StructuralTemplate.Hole.Call(mapOf("a" to "if (x < y) 1 else 2", "b" to "3"))
      )

    // And the shape admitting parentheses could have broken: `n<m(1)` is arithmetic, not a generic
    // list, and its comma must still separate arguments.
    val arithmetic =
      StructuralTemplate.holes("\${call(a = n<m(1), b = 2)}") as StructuralTemplate.Result2.Ok
    assertThat(arithmetic.value)
      .containsExactly(StructuralTemplate.Hole.Call(mapOf("a" to "n<m(1)", "b" to "2")))
  }

  @Test
  fun `a comment is not syntax, in all three scanners`() {
    // An override's value is documented as arbitrary Kotlin, and a brace, comma or `=` inside a
    // comment is not structure. All three scanners tracked quotes and none tracked comments, so a
    // commented brace ended the hole early and the template was rejected as malformed.
    val braceInComment =
      StructuralTemplate.holes("\${call(content = { /* } */ Text(\"x\") })}")
        as StructuralTemplate.Result2.Ok
    assertThat(braceInComment.value)
      .containsExactly(StructuralTemplate.Hole.Call(mapOf("content" to "{ /* } */ Text(\"x\") }")))

    // A comma in a comment is not an argument separator, and an `=` in one is not the name/value
    // split — the other two scanners, taught at the same time rather than one round later.
    val commaInComment =
      StructuralTemplate.holes("\${call(a = 1 /* , b = 2 */, c = 3)}")
        as StructuralTemplate.Result2.Ok
    assertThat(commaInComment.value)
      .containsExactly(StructuralTemplate.Hole.Call(mapOf("a" to "1 /* , b = 2 */", "c" to "3")))

    // Block comments NEST in Kotlin, unlike C: `/* /* */ */` is one comment, and reading the first
    // `*/` as the end would put the rest of the value back into syntax.
    val nested =
      StructuralTemplate.holes("\${call(a = 1 /* /* , */ */, b = 2)}")
        as StructuralTemplate.Result2.Ok
    assertThat(nested.value)
      .containsExactly(StructuralTemplate.Hole.Call(mapOf("a" to "1 /* /* , */ */", "b" to "2")))
  }

  @Test
  fun `holes can be read without rendering, for validating a policy when it is published`() {
    // A policy naming a hole the builder will never resolve is a message for the person editing the
    // policy, not for the person who later drew a screen through it.
    val result = StructuralTemplate.holes("AppScaffold {\n  \${content}\n  \${call(n = 1)}\n}")

    val holes = (result as StructuralTemplate.Result2.Ok).value
    assertThat(holes)
      .containsExactly(
        StructuralTemplate.Hole.Named("content"),
        StructuralTemplate.Hole.Call(mapOf("n" to "1")),
      )
      .inOrder()
  }
}
