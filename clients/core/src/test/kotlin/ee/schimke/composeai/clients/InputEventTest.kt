package ee.schimke.composeai.clients

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InputEventTest {

  @Test
  fun kindWireSpellingsMatchTheDaemonContract() {
    assertThat(InputEvent.Kind.values().map { it.wire })
      .containsExactly(
        "click",
        "pointerDown",
        "pointerMove",
        "pointerUp",
        "rotaryScroll",
        "keyDown",
        "keyUp",
      )
      .inOrder()
  }

  @Test
  fun scalePointerWithNoLetterboxIsADirectScale() {
    // A 100x200 frame shown in a 200x400 box: uniform 2x scale, no offset.
    val (x, y) = InputEvent.scalePointer(50f, 100f, 200f, 400f, 100, 200)
    assertThat(x).isEqualTo(25)
    assertThat(y).isEqualTo(50)
  }

  @Test
  fun scalePointerAccountsForLetterboxOffset() {
    // A square 100x100 frame in a wide 400x200 box fits to height (scale 2), drawn 200 wide and
    // centred → 100px bars on each side. A tap at the left bar edge maps to frame x=0.
    val (x, y) = InputEvent.scalePointer(100f, 0f, 400f, 200f, 100, 100)
    assertThat(x).isEqualTo(0)
    assertThat(y).isEqualTo(0)
    // Centre of the box maps to centre of the frame.
    val (cx, cy) = InputEvent.scalePointer(200f, 100f, 400f, 200f, 100, 100)
    assertThat(cx).isEqualTo(50)
    assertThat(cy).isEqualTo(50)
  }

  @Test
  fun scalePointerClampsIntoFrameBounds() {
    val (x, y) = InputEvent.scalePointer(10_000f, 10_000f, 200f, 400f, 100, 200)
    assertThat(x).isEqualTo(99)
    assertThat(y).isEqualTo(199)
  }

  @Test
  fun scalePointerIsSafeOnDegenerateInputs() {
    assertThat(InputEvent.scalePointer(5f, 5f, 0f, 0f, 0, 0)).isEqualTo(0 to 0)
  }
}
