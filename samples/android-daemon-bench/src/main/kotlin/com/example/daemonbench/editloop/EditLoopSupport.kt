package com.example.daemonbench.editloop

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Fixture for `benchWarmEditLoop`: an `internal` declaration in one file, called from another
// (`EditLoopTarget.kt`). The warm in-process compiler compiles only the edited file and reads this
// one from the module's class directory, so it only sees `internal` here when that directory is a
// friend path (compose-preview-daemon#465). Never edited by the bench; keep its ABI stable.

internal fun editLoopSuffix(): String = " (edit loop)"

@Composable
internal fun EditLoopLabel(text: String) {
  Text(text + editLoopSuffix())
}
