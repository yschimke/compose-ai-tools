package ee.schimke.composeai.samples.instrumented

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A counter: the Compose tests click its button and read its text. */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Counter() } }
  }
}

@androidx.compose.runtime.Composable
fun Counter() {
  var count by rememberSaveable { mutableIntStateOf(0) }
  Column(Modifier.padding(24.dp)) {
    Text("Count: $count", Modifier.testTag("count"))
    Button(onClick = { count++ }, modifier = Modifier.testTag("increment")) { Text("Increment") }
  }
}
