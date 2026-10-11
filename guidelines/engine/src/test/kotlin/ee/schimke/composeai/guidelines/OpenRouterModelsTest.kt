package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenRouterModelsTest {
  private val body =
    """
    {"data": [
      {"id": "deepseek/deepseek-v4.1-flash", "name": "DeepSeek V4.1 Flash", "context_length": 131072,
       "architecture": {"input_modalities": ["text", "image"], "output_modalities": ["text"]},
       "pricing": {"prompt": "0.0000003", "completion": "0.0000012"},
       "supported_parameters": ["max_tokens", "structured_outputs", "response_format"]},
      {"id": "text/only", "name": "Text Only",
       "architecture": {"input_modalities": ["text"]},
       "pricing": {"prompt": "0.0000001", "completion": "0.0000002"},
       "supported_parameters": ["structured_outputs"]},
      {"id": "vision/no-schema",
       "architecture": {"input_modalities": ["image", "text"]},
       "pricing": {"prompt": "-1"},
       "supported_parameters": ["max_tokens"]},
      {"name": "no id"}
    ]}
    """

  @Test
  fun `the list is read into what choosing a model needs`() {
    val models = OpenRouterModels.parse(body)
    assertThat(models.map { it.id })
      .containsExactly("deepseek/deepseek-v4.1-flash", "text/only", "vision/no-schema")
    val flash = models.first()
    assertThat(flash.suitable).isTrue()
    assertThat(flash.contextLength).isEqualTo(131072)
    assertThat(flash.promptPerMillion!!).isWithin(1e-9).of(0.3)
    assertThat(flash.completionPerMillion!!).isWithin(1e-9).of(1.2)
    assertThat(models[1].suitable).isFalse()
    assertThat(models[2].vision).isTrue()
    assertThat(models[2].structuredOutputs).isFalse()
    // A negative price is OpenRouter's "varies": no price rather than a wrong one.
    assertThat(models[2].promptPerMillion).isNull()
    assertThat(models[2].name).isEqualTo("vision/no-schema")
  }
}
