package com.example.daemonbench.editloop

import androidx.compose.runtime.Composable

// The file `benchWarmEditLoop` edits: each cycle rewrites the marker string, then asks the daemon
// to compile it in process. It calls `EditLoopSupport.kt`'s `internal` composable on purpose; see
// that file. Not a @Preview, so the five bench previews and their baseline are unchanged.

@Composable
fun EditLoopTarget() {
  EditLoopLabel("edit-loop-marker")
}
