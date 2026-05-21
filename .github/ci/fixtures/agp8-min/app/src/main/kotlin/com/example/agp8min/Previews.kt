package com.example.agp8min

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

// `VerticalDragHandle` is a material3 1.4 composable (added with the
// expressive drag-handle component family, stable in 1.4.0 — no opt-in
// needed). compose-bom 2026.05.00 — what the fixture pins above and what
// `:samples:android` uses too — ships M3 1.4.0, so this preview compiles
// cleanly there. compose-bom 2024.12.01, which the agp8-min CI job
// downgrades to in Phase 1, ships M3 1.3.1 without this composable; the
// import then fails to resolve and `compileDebugKotlin` errors out
// before `composePreviewRender` can even start. That's the same failure shape
// a real consumer hits when their source reaches for a new Compose API
// ahead of their BOM, and it's what `compose-preview doctor`'s
// `env.compose-bom-version` pre-flight warns about.
@Preview
@Composable
fun GreetingPreview() {
  Surface {
    Column {
      Text(text = "agp8-min fixture")
      VerticalDragHandle()
    }
  }
}
