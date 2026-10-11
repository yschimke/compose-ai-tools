package com.example.designcatalogwearm3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

// The Wear catalog's state holders, shaped like `:samples:design-catalog-m3-shared`'s. Baked and
// live lanes compose the same stateful control; untouched it draws exactly the baked frame.

/**
 * Gives a stateless action component something visible to do when clicked, by tallying clicks into
 * its label: `Filled` → `Filled (1)`. Returns the label and `onClick`; at `0` it draws [base]
 * verbatim. Same shape as `CatalogComponents.counted` on the M3 sheet.
 */
@Composable
fun wearCounted(base: String): Pair<String, () -> Unit> {
  var clicks by remember { mutableIntStateOf(0) }
  return (if (clicks == 0) base else "$base ($clicks)") to { clicks++ }
}

/**
 * A checked-state holder for toggle stickers. Untouched it draws [initial] (the seeded
 * `previewOverrideBoolean("checked", …)`), so `@OverrideVariant` captures render as seeded.
 */
@Composable
fun wearChecked(initial: Boolean): Pair<Boolean, (Boolean) -> Unit> {
  var checked by remember { mutableStateOf(initial) }
  return checked to { checked = it }
}
