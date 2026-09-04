package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the `knobs=<name>:<index>:<TYPE>,…` payload token — the className-based render path's way of
 * naming a preview's **parameter knobs** when there is no `previews.json` entry to resolve them
 * from (the previewId path takes them off the manifest instead, see [renderSpecFromInfo]).
 *
 * The parse is deliberately forgiving. A newer plugin can name a knob kind, or a shape, this daemon
 * has never heard of, and the only sane degradation is that the parameter takes its compiled
 * default while every other knob in the token still seeds. Failing the render instead would turn a
 * forward-compatible wire into a hard version pin.
 */
class RenderSpecKnobsTokenTest {

  @Test
  fun `a well-formed token parses every entry in order`() {
    assertEquals(
      listOf(
        PreviewKnobDto("title", 0, "STRING"),
        PreviewKnobDto("enabled", 1, "BOOLEAN"),
        PreviewKnobDto("count", 2, "INT"),
      ),
      RenderSpec.parseKnobsToken("title:0:STRING,enabled:1:BOOLEAN,count:2:INT"),
    )
  }

  @Test
  fun `an unknown kind survives the parse and is dropped later by the binder`() {
    assertEquals(
      listOf(PreviewKnobDto("accent", 0, "COLOR")),
      RenderSpec.parseKnobsToken("accent:0:COLOR"),
    )
  }

  @Test
  fun `malformed entries are skipped rather than failing the render`() {
    assertEquals(
      listOf(PreviewKnobDto("title", 0, "STRING")),
      RenderSpec.parseKnobsToken("title:0:STRING,noColons,bad:x:INT,:3:INT,negative:-1:INT,two:1:"),
    )
  }

  @Test
  fun `an absent or blank token is no knobs at all`() {
    assertEquals(emptyList<PreviewKnobDto>(), RenderSpec.parseKnobsToken(null))
    assertEquals(emptyList<PreviewKnobDto>(), RenderSpec.parseKnobsToken("   "))
  }

  @Test
  fun `the token round-trips through parseFromPayload onto the spec`() {
    val spec =
      RenderSpec.parseFromPayload(
        "className=com.example.FooKt;functionName=Foo;knobs=title:0:STRING,count:1:INT"
      )
    assertEquals(
      listOf(PreviewKnobDto("title", 0, "STRING"), PreviewKnobDto("count", 1, "INT")),
      spec.knobs,
    )
    // A payload with no token at all — every client older than this field — decodes to no knobs.
    assertEquals(
      emptyList<PreviewKnobDto>(),
      RenderSpec.parseFromPayload("className=com.example.FooKt;functionName=Foo").knobs,
    )
  }
}
