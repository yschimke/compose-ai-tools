package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Desktop counterpart of `:data-keyboard-connector`'s `SoftKeyboardBand`. Duplicated rather than
 * shared because the Android module is `android.library` and its outputs can't be consumed from
 * `:daemon:desktop`'s JVM classpath — same pattern `:data-focus-connector-desktop` follows. The
 * layout / palette / key tokens are kept in lockstep; bump both together.
 */
@Composable
internal fun SoftKeyboardBand(pressedKey: String?, night: Boolean, modifier: Modifier = Modifier) {
  val palette = if (night) DarkPalette else LightPalette
  val normalized = pressedKey?.lowercase()

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .height(KEYBOARD_HEIGHT_DP.dp)
        .background(palette.background)
        .padding(horizontal = SIDE_INSET_DP.dp, vertical = ROW_INSET_DP.dp),
    verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
  ) {
    LetterRow(row = ROW_TOP, pressed = normalized, palette = palette)
    LetterRow(row = ROW_MIDDLE, pressed = normalized, palette = palette, sideInsetWeight = 0.5f)
    BottomLetterRow(pressed = normalized, palette = palette)
    ActionRow(pressed = normalized, palette = palette)
  }
}

@Composable
private fun ColumnScope.LetterRow(
  row: String,
  pressed: String?,
  palette: KeyboardPalette,
  sideInsetWeight: Float = 0f,
) {
  Row(
    modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
    horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp),
  ) {
    if (sideInsetWeight > 0f) Spacer(modifier = Modifier.weight(sideInsetWeight))
    row.forEach { ch ->
      val label = ch.toString()
      LetterKey(
        label = label,
        pressed = pressed == label,
        palette = palette,
        modifier = Modifier.weight(1f),
      )
    }
    if (sideInsetWeight > 0f) Spacer(modifier = Modifier.weight(sideInsetWeight))
  }
}

@Composable
private fun ColumnScope.BottomLetterRow(pressed: String?, palette: KeyboardPalette) {
  Row(
    modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
    horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp),
  ) {
    SpecialKey(
      label = "⇧",
      isPressed = pressed == "shift",
      palette = palette,
      modifier = Modifier.weight(1.5f),
    )
    ROW_BOTTOM.forEach { ch ->
      val label = ch.toString()
      LetterKey(
        label = label,
        pressed = pressed == label,
        palette = palette,
        modifier = Modifier.weight(1f),
      )
    }
    SpecialKey(
      label = "⌫",
      isPressed = pressed == "backspace",
      palette = palette,
      modifier = Modifier.weight(1.5f),
    )
  }
}

@Composable
private fun ColumnScope.ActionRow(pressed: String?, palette: KeyboardPalette) {
  Row(
    modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
    horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp),
  ) {
    SpecialKey(
      label = "?123",
      isPressed = pressed == "sym",
      palette = palette,
      modifier = Modifier.weight(1.5f),
    )
    LetterKey(
      label = ",",
      pressed = pressed == ",",
      palette = palette,
      modifier = Modifier.weight(1f),
    )
    SpecialKey(
      label = "space",
      isPressed = pressed == "space",
      palette = palette,
      modifier = Modifier.weight(5f),
    )
    LetterKey(
      label = ".",
      pressed = pressed == ".",
      palette = palette,
      modifier = Modifier.weight(1f),
    )
    SpecialKey(
      label = "⏎",
      isPressed = pressed == "enter",
      palette = palette,
      modifier = Modifier.weight(1.5f),
    )
  }
}

@Composable
private fun LetterKey(
  label: String,
  pressed: Boolean,
  palette: KeyboardPalette,
  modifier: Modifier,
) {
  KeyCap(
    label = label,
    pressed = pressed,
    background = if (pressed) palette.pressed else palette.letterKey,
    foreground = if (pressed) palette.pressedForeground else palette.foreground,
    modifier = modifier,
  )
}

@Composable
private fun SpecialKey(
  label: String,
  isPressed: Boolean,
  palette: KeyboardPalette,
  modifier: Modifier,
) {
  KeyCap(
    label = label,
    pressed = isPressed,
    background = if (isPressed) palette.pressed else palette.specialKey,
    foreground = if (isPressed) palette.pressedForeground else palette.foreground,
    modifier = modifier,
  )
}

@Composable
private fun KeyCap(
  label: String,
  pressed: Boolean,
  background: Color,
  foreground: Color,
  modifier: Modifier,
) {
  Box(modifier = modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
    Box(
      modifier =
        Modifier.fillMaxSize().clip(RoundedCornerShape(KEY_CORNER_DP.dp)).background(background),
      contentAlignment = Alignment.Center,
    ) {
      BasicText(
        text = label,
        style =
          TextStyle(
            color = foreground,
            fontSize = KEY_LABEL_SP.sp,
            fontWeight = if (pressed) FontWeight.SemiBold else FontWeight.Normal,
          ),
      )
    }
  }
}

private data class KeyboardPalette(
  val background: Color,
  val letterKey: Color,
  val specialKey: Color,
  val foreground: Color,
  val pressed: Color,
  val pressedForeground: Color,
)

private val LightPalette =
  KeyboardPalette(
    background = Color(0xFFE8EBEF),
    letterKey = Color(0xFFFFFFFF),
    specialKey = Color(0xFFC8CCD3),
    foreground = Color(0xFF1F1F1F),
    pressed = Color(0xFF1A73E8),
    pressedForeground = Color(0xFFFFFFFF),
  )

private val DarkPalette =
  KeyboardPalette(
    background = Color(0xFF1B1C1E),
    letterKey = Color(0xFF3C4043),
    specialKey = Color(0xFF24262A),
    foreground = Color(0xFFE8EAED),
    pressed = Color(0xFF8AB4F8),
    pressedForeground = Color(0xFF202124),
  )

private const val ROW_TOP = "qwertyuiop"
private const val ROW_MIDDLE = "asdfghjkl"
private const val ROW_BOTTOM = "zxcvbnm"

private const val KEYBOARD_HEIGHT_DP = 240
private const val SIDE_INSET_DP = 4
private const val ROW_INSET_DP = 6
private const val ROW_GAP_DP = 6
private const val KEY_GAP_DP = 4
private const val KEY_CORNER_DP = 6
private const val KEY_LABEL_SP = 14
