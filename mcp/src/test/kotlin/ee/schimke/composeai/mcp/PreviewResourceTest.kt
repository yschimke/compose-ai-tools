package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewResourceTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `WorkspaceId derives stable id from canonical path`() {
    val a = tmp.newFolder("a")
    val b = tmp.newFolder("b")
    val idA1 = WorkspaceId.derive("compose-ai-tools", a)
    val idA2 = WorkspaceId.derive("compose-ai-tools", a)
    val idB = WorkspaceId.derive("compose-ai-tools", b)
    assertThat(idA1).isEqualTo(idA2)
    assertThat(idA1).isNotEqualTo(idB)
    assertThat(idA1.value).startsWith("compose-ai-tools-")
    assertThat(idA1.value.removePrefix("compose-ai-tools-").length).isEqualTo(8)
  }

  @Test
  fun `WorkspaceId distinguishes worktrees with the same project name`() {
    val main = tmp.newFolder("repos", "main")
    val feature = tmp.newFolder("repos", "feature")
    val mainId = WorkspaceId.derive("compose-ai-tools", main)
    val featureId = WorkspaceId.derive("compose-ai-tools", feature)
    assertThat(mainId).isNotEqualTo(featureId)
    assertThat(mainId.value.startsWith("compose-ai-tools-")).isTrue()
    assertThat(featureId.value.startsWith("compose-ai-tools-")).isTrue()
  }

  @Test
  fun `PreviewUri round-trip preserves all four segments`() {
    val ws = WorkspaceId("compose-ai-tools-abc12345")
    val uri =
      PreviewUri(
        workspaceId = ws,
        modulePath = ":samples:android",
        previewFqn = "com.example.app.RedSquare",
        config = "phone-portrait",
      )
    val s = uri.toUri()
    assertThat(s)
      .isEqualTo(
        "compose-preview://compose-ai-tools-abc12345/_samples_android/com.example.app.RedSquare?config=phone-portrait"
      )
    val parsed = PreviewUri.parse(s)
    assertThat(parsed).isEqualTo(uri)
  }

  @Test
  fun `PreviewUri rejects malformed input`() {
    assertThat(PreviewUri.parseOrNull("file:///foo")).isNull()
    assertThat(PreviewUri.parseOrNull("compose-preview://onlyws/")).isNull()
    assertThat(PreviewUri.parseOrNull("compose-preview://")).isNull()
  }

  @Test
  fun `FqnGlob matches single and double-star semantics`() {
    val singleStar = FqnGlob("com.example.*")
    assertThat(singleStar.matches("com.example.RedSquare")).isTrue()
    // Single-star doesn't cross dot boundaries.
    assertThat(singleStar.matches("com.example.sub.RedSquare")).isFalse()

    val doubleStar = FqnGlob("com.example.**")
    assertThat(doubleStar.matches("com.example.RedSquare")).isTrue()
    assertThat(doubleStar.matches("com.example.sub.RedSquare")).isTrue()

    val questionMark = FqnGlob("Red?quare")
    assertThat(questionMark.matches("RedSquare")).isTrue()
    assertThat(questionMark.matches("Red.quare")).isFalse()
  }

  @Test
  fun `WatchEntry matches by workspace + module + glob`() {
    val ws = WorkspaceId("ws-abc12345")
    val other = WorkspaceId("other-abc12345")
    val uri =
      PreviewUri(
        workspaceId = ws,
        modulePath = ":samples:android",
        previewFqn = "com.example.RedSquare",
      )

    // Workspace-only watch matches.
    assertThat(WatchEntry(workspaceId = ws).matches(uri)).isTrue()
    // Different workspace doesn't match.
    assertThat(WatchEntry(workspaceId = other).matches(uri)).isFalse()
    // Module filter matches.
    assertThat(WatchEntry(workspaceId = ws, modulePath = ":samples:android").matches(uri)).isTrue()
    // Wrong module doesn't match.
    assertThat(WatchEntry(workspaceId = ws, modulePath = ":other").matches(uri)).isFalse()
    // FQN glob matches.
    assertThat(WatchEntry(workspaceId = ws, fqnGlobPattern = "com.example.*").matches(uri)).isTrue()
    // Wrong glob doesn't match.
    assertThat(WatchEntry(workspaceId = ws, fqnGlobPattern = "org.other.**").matches(uri)).isFalse()
  }
}
