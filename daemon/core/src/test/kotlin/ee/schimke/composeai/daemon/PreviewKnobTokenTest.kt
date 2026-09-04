package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the `knobs=` payload codec. On the Android backend this token is the **only** way a
 * preview's parameter knobs cross into the Robolectric sandbox, so producer and consumer agreeing
 * is load-bearing rather than tidy — hence one object and one test rather than a pair per backend.
 */
class PreviewKnobTokenTest {

  private val knobs =
    listOf(
      PreviewKnobDto("title", 0, "STRING"),
      PreviewKnobDto("enabled", 1, "BOOLEAN"),
      PreviewKnobDto("count", 2, "INT"),
    )

  @Test
  fun `a token round-trips`() {
    val token = PreviewKnobToken.encode(knobs)
    assertEquals("title:0:STRING,enabled:1:BOOLEAN,count:2:INT", token)
    assertEquals(knobs, PreviewKnobToken.parse(token))
  }

  @Test
  fun `a preview with no knobs emits no token at all`() {
    // So its payload stays byte-identical to what it was before this token existed.
    assertNull(PreviewKnobToken.encode(emptyList()))
    assertEquals(emptyList<PreviewKnobDto>(), PreviewKnobToken.parse(null))
    assertEquals(emptyList<PreviewKnobDto>(), PreviewKnobToken.parse("   "))
  }

  @Test
  fun `an unknown kind survives the round trip and is dropped later by the binder`() {
    // A newer plugin may name a knob kind this daemon cannot build. That parameter has to degrade
    // to
    // its author default; rejecting it here would fail the whole render instead.
    val future = listOf(PreviewKnobDto("accent", 0, "COLOR"))
    assertEquals(future, PreviewKnobToken.parse(PreviewKnobToken.encode(future)))
  }

  @Test
  fun `malformed entries are skipped rather than failing the parse`() {
    assertEquals(
      listOf(PreviewKnobDto("title", 0, "STRING")),
      PreviewKnobToken.parse("title:0:STRING,noColons,bad:x:INT,:3:INT,negative:-1:INT,two:1:"),
    )
  }

  @Test
  fun `a name carrying a payload delimiter is omitted rather than corrupting its neighbours`() {
    // Reachable only through a backticked Kotlin name (`my, knob`). Emitting it would split into
    // fragments that swallow the entries around it; dropping the one knob is the smaller loss.
    val token =
      PreviewKnobToken.encode(
        listOf(
          PreviewKnobDto("good", 0, "STRING"),
          PreviewKnobDto("my, knob", 1, "STRING"),
          PreviewKnobDto("colon:knob", 2, "STRING"),
          PreviewKnobDto("semi;knob", 3, "STRING"),
          PreviewKnobDto("eq=knob", 4, "STRING"),
          PreviewKnobDto("alsoGood", 5, "INT"),
        )
      )
    assertEquals("good:0:STRING,alsoGood:5:INT", token)
    assertEquals(
      listOf(PreviewKnobDto("good", 0, "STRING"), PreviewKnobDto("alsoGood", 5, "INT")),
      PreviewKnobToken.parse(token),
    )
  }
}
