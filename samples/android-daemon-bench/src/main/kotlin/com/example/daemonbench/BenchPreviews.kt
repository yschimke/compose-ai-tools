package com.example.daemonbench

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Deliberately small, deliberately stable workload for the daemon latency
// bench. No animations, no scrolls, no Wear, no @PreviewParameter providers —
// each preview is a single capture, so the per-render cost in the JUnit XML
// timing maps cleanly onto the "Render N previews" row in DESIGN.md § 13.
//
// Five previews keeps the render phase visible in the wall-clock total
// without dwarfing the fork / sandbox-init phase we're trying to isolate.
// If you grow this set, update the per-render baseline numbers in
// docs/daemon/baseline-latency.csv at the same time.

@Preview(name = "RedSquare", showBackground = true)
@Composable
fun RedSquarePreview() {
  MaterialTheme {
    Surface(color = Color(0xFFEF5350)) {
      Box(Modifier.size(96.dp).padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Color.White))
    }
  }
}

@Preview(name = "BlueLabel", showBackground = true, widthDp = 200)
@Composable
fun BlueLabelPreview() {
  MaterialTheme {
    Surface(color = Color(0xFF42A5F5)) {
      Box(Modifier.fillMaxSize().padding(16.dp)) { Text("blue", color = Color.White) }
    }
  }
}

@Preview(name = "GreenButton", showBackground = true, widthDp = 220)
@Composable
fun GreenButtonPreview() {
  MaterialTheme {
    Surface(color = Color(0xFFE8F5E9)) {
      Box(Modifier.fillMaxWidth().padding(16.dp)) { Button(onClick = {}) { Text("Go") } }
    }
  }
}

@Preview(name = "Stack", showBackground = true, widthDp = 240)
@Composable
fun StackPreview() {
  MaterialTheme {
    Surface(color = Color.White) {
      Column(Modifier.padding(16.dp)) {
        Text("one")
        Spacer(Modifier.size(8.dp))
        Text("two")
        Spacer(Modifier.size(8.dp))
        Text("three")
      }
    }
  }
}

@Preview(name = "Row", showBackground = true, widthDp = 280)
@Composable
fun RowPreview() {
  MaterialTheme {
    Surface(color = Color.White) {
      Row(Modifier.padding(16.dp)) {
        Box(Modifier.size(24.dp).background(Color(0xFFEF5350)))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(24.dp).background(Color(0xFF42A5F5)))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(24.dp).background(Color(0xFF66BB6A)))
      }
    }
  }
}
