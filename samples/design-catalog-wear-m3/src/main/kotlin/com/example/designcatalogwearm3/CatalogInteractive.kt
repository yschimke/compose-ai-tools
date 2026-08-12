package com.example.designcatalogwearm3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

// The Wear catalog's state holders — the same shape `:samples:design-catalog-m3-shared` uses on the
// Compose M3 sheet.
//
// A Wear sticker serves two render lanes from one `@Preview`: the **baked** snapshot / one-shot
// `/render`, and the held **Live Compose** daemon session. Both compose the *same* control. These
// helpers used to branch on `catalogInteractive()` (`!LocalInspectionMode.current`) and hand the
// baked lane an inert value with a no-op handler, which meant the published capture was not the
// composable that runs live (issue #3674). The branch was also redundant: its inert side returned
// exactly the first frame the stateful side draws — `base` with the counter at `0`, `initial`
// before anything toggles it — so removing it moves no published pixel.
//
// Before any of this, every handler on the sheet was a literal `{}`: a click in a live Wear session
// was a guaranteed no-op, including on `SwitchButton` and `CheckboxButton`, the two components a
// viewer is most likely to try to toggle.

/**
 * Gives a stateless action component — a button, an icon button, a card — something visible to do
 * when clicked, by tallying clicks into its label: `Filled` → `Filled (1)` → `Filled (2)`.
 *
 * Returns the label to draw and the `onClick` to wire. The tally starts at `0` and the `0` case
 * draws [base] verbatim, so a render nothing has clicked — every baked capture — is unchanged.
 *
 * Wear's counterpart to `CatalogComponents.counted` on the Compose M3 sheet — deliberately the same
 * `label (n)` shape, so the two catalogs read alike in a live session.
 */
@Composable
fun wearCounted(base: String): Pair<String, () -> Unit> {
  var clicks by remember { mutableIntStateOf(0) }
  return (if (clicks == 0) base else "$base ($clicks)") to { clicks++ }
}

/**
 * A checked-state holder for the toggle stickers. Returns the value to draw and the handler to
 * wire. Untouched it draws [initial] — the seeded `previewOverrideBoolean("checked", …)` knob — so
 * the `@OverrideVariant` captures (the `off` / `unchecked` folds) render exactly as before; a tap
 * then moves it from there.
 */
@Composable
fun wearChecked(initial: Boolean): Pair<Boolean, (Boolean) -> Unit> {
  var checked by remember { mutableStateOf(initial) }
  return checked to { checked = it }
}
