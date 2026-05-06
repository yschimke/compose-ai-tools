package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewTargetInferenceTest {

  @Test
  fun `nameMatches strips Preview suffix`() {
    assertThat(PreviewTargetInference.nameMatches("FooPreview", "Foo")).isTrue()
    assertThat(PreviewTargetInference.nameMatches("FooScreenPreview", "FooScreen")).isTrue()
  }

  @Test
  fun `nameMatches strips Preview prefix`() {
    assertThat(PreviewTargetInference.nameMatches("PreviewFoo", "Foo")).isTrue()
    assertThat(PreviewTargetInference.nameMatches("Preview_Foo", "Foo")).isTrue()
  }

  @Test
  fun `nameMatches matches leading segment when separators present`() {
    // `Foo_Light_Preview` → strip `_Preview` → `Foo_Light` → leading `Foo` matches.
    assertThat(PreviewTargetInference.nameMatches("Foo_Light_Preview", "Foo")).isTrue()
    assertThat(PreviewTargetInference.nameMatches("Foo_Dark_Preview", "Foo")).isTrue()
  }

  @Test
  fun `nameMatches strips internal-fun JVM mangle`() {
    // `internal fun InternalFooPreview` compiles to `InternalFooPreview$<module>`.
    assertThat(PreviewTargetInference.nameMatches("InternalFooPreview\$test_module", "InternalFoo"))
      .isTrue()
  }

  @Test
  fun `nameMatches rejects unrelated names`() {
    assertThat(PreviewTargetInference.nameMatches("FooPreview", "Bar")).isFalse()
    assertThat(PreviewTargetInference.nameMatches("FooPreview", "FooBar")).isFalse()
  }

  @Test
  fun `nameMatches rejects bare Preview when nothing left after strip`() {
    assertThat(PreviewTargetInference.nameMatches("Preview", "Anything")).isFalse()
  }
}
