package com.example.sampleandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Per-check fixtures for the `compose/semantics` core projection. Each preview isolates one
 * field that `ComposeSemanticsDataProducer` lifts off `SemanticsConfiguration`:
 * `testTag`, `label` (from `contentDescription`), `role` + `clickable`, and `mergeMode`.
 * `ComposeSemanticsCoreFieldsTest` mirrors these composables and asserts the produced JSON.
 */
@Preview(showBackground = true, widthDp = 200, heightDp = 32)
@Composable
fun SemanticsTestTagPreview() {
  Box(modifier = Modifier.size(200.dp, 32.dp).background(Color.White)) {
    Text(text = "Hero", modifier = Modifier.testTag("hero-title"))
  }
}

@Preview(showBackground = true, widthDp = 64, heightDp = 64)
@Composable
fun SemanticsContentDescriptionPreview() {
  Box(
    modifier =
      Modifier.size(64.dp).background(Color.Red).semantics {
        contentDescription = "decorative-heart"
      }
  )
}

@Preview(showBackground = true, widthDp = 200, heightDp = 56)
@Composable
fun SemanticsClickableButtonPreview() {
  Box(modifier = Modifier.size(200.dp, 56.dp).background(Color.White)) {
    Button(onClick = {}) { Text(text = "Buy") }
  }
}

@Preview(showBackground = true, widthDp = 200, heightDp = 64)
@Composable
fun SemanticsMergeDescendantsPreview() {
  Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
    Text(text = "Title")
    Text(text = "Subtitle")
  }
}
