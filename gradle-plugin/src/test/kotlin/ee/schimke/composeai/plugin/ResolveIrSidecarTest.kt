package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins [resolveIrSidecar] — how the bundle finds a preview's captured IR (`.rc` Remote Compose doc
 * / `.tilelayout` protolayout) next to its render. An exact `<stem>.<ext>` wins; otherwise the
 * first (min-named) `@PreviewParameter` / multi-variant sibling is used, matching the
 * representative cover PNG the bundle bakes for the same preview. Before this, param-driven Remote
 * Compose / tile previews found their cover PNG but no IR and dropped back to bytecode carriage.
 */
class ResolveIrSidecarTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun write(name: String, content: String = "doc") = File(tmp.root, name).writeText(content)

  @Test
  fun `exact stem wins over any sibling`() {
    write("Foo.rc")
    write("Foo_PARAM_0.rc")
    assertThat(resolveIrSidecar(tmp.root, "Foo", "rc")?.name).isEqualTo("Foo.rc")
  }

  @Test
  fun `falls back to the first min-named PreviewParameter sibling`() {
    write("Foo_PARAM_2.rc")
    write("Foo_PARAM_0.rc")
    write("Foo_PARAM_1.rc")
    // The same representative resolvePreviewPng picks (min-named), so the packed IR and cover
    // agree.
    assertThat(resolveIrSidecar(tmp.root, "Foo", "rc")?.name).isEqualTo("Foo_PARAM_0.rc")
  }

  @Test
  fun `matches dimension-suffixed multi-variant siblings`() {
    write("Foo--wearos_small_round.rc")
    assertThat(resolveIrSidecar(tmp.root, "Foo", "rc")?.name)
      .isEqualTo("Foo--wearos_small_round.rc")
  }

  @Test
  fun `ignores a different extension and a different stem`() {
    write("Foo_PARAM_0.tilelayout")
    write("Bar_PARAM_0.rc")
    assertThat(resolveIrSidecar(tmp.root, "Foo", "rc")).isNull()
  }

  @Test
  fun `skips an empty sidecar`() {
    write("Foo.rc", "")
    assertThat(resolveIrSidecar(tmp.root, "Foo", "rc")).isNull()
  }

  @Test
  fun `null when no sidecar exists`() {
    assertThat(resolveIrSidecar(tmp.root, "Foo", "rc")).isNull()
  }
}
