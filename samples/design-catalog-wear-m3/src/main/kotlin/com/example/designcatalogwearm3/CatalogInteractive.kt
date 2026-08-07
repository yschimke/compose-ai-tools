package com.example.designcatalogwearm3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * The Wear catalog's **interactive lane** — the same baked-vs-live split
 * `:samples:design-catalog-m3-shared` runs, brought to this sheet.
 *
 * A Wear sticker serves two render lanes from one `@Preview`:
 * * the **baked** snapshot / one-shot `/render`, where Compose sets `LocalInspectionMode = true`.
 *   Here [catalogInteractive] is `false` and every helper below collapses to exactly the static
 *   frame this catalog has always published — same seeded `previewOverride*` values, same no-op
 *   handlers, byte-identical PNG.
 * * the held **Live Compose** daemon session, where the Android interactive session seeds
 *   `inspectionMode = false`. Here [catalogInteractive] is `true` and the helpers become real,
 *   stateful widgets, so the session's pointer dispatch actually moves the UI.
 *
 * Before this, every handler on the sheet was a literal `{}`: a click in a live Wear session was a
 * guaranteed no-op, including on `SwitchButton` and `CheckboxButton`, the two components a viewer
 * is most likely to try to toggle.
 */
@Composable fun catalogInteractive(): Boolean = !LocalInspectionMode.current

/**
 * Gives a stateless action component — a button, an icon button, a card — something visible to do
 * when clicked, by tallying clicks into its label: `Filled` → `Filled (1)` → `Filled (2)`.
 *
 * Returns the label to draw and the `onClick` to wire. Off the interactive lane it returns [base]
 * verbatim with a no-op handler, so the published capture doesn't move. The `remember` is
 * unconditional, so both lanes compose the same slot-table shape and only the values read out of it
 * differ.
 *
 * Wear's counterpart to `CatalogComponents.counted` on the Compose M3 sheet — deliberately the same
 * `label (n)` shape, so the two catalogs read alike in a live session.
 */
@Composable
fun wearCounted(base: String): Pair<String, () -> Unit> {
  var clicks by remember { mutableIntStateOf(0) }
  if (!catalogInteractive()) return base to {}
  return (if (clicks == 0) base else "$base ($clicks)") to { clicks++ }
}

/**
 * A checked-state holder for the toggle stickers. Returns the value to draw and the handler to wire;
 * off the interactive lane the handler is inert and the value stays pinned at [initial] — which is
 * the seeded `previewOverrideBoolean("checked", …)` knob, so the `@OverrideVariant` captures (the
 * `off` / `unchecked` folds) render exactly as before.
 */
@Composable
fun wearChecked(initial: Boolean): Pair<Boolean, (Boolean) -> Unit> {
  var checked by remember { mutableStateOf(initial) }
  if (!catalogInteractive()) return initial to {}
  return checked to { checked = it }
}
