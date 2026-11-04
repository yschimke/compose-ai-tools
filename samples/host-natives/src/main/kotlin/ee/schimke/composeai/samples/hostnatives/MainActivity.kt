package ee.schimke.composeai.samples.hostnatives

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs every [nativeChecks] entry once at start, and lists the results. */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ChecksScreen() } } }
  }
}

@Composable
private fun ChecksScreen() {
  val results = remember { mutableStateListOf<CheckResult>() }
  LaunchedEffect(Unit) {
    for (check in nativeChecks) results += withContext(Dispatchers.Default) { runCheck(check) }
    val passed = results.count { it.passed }
    Log.i(TAG, "DONE $passed/${results.size} passed")
  }
  Column(Modifier.padding(16.dp)) {
    Text("Host native libraries", style = MaterialTheme.typography.titleLarge)
    Text(
      "${results.count { it.passed }} of ${nativeChecks.size} loaded",
      style = MaterialTheme.typography.bodyMedium,
    )
    LazyColumn(Modifier.padding(top = 12.dp)) {
      items(results) { result ->
        Text(
          result.line,
          color = if (result.passed) Color(0xFF1B5E20) else Color(0xFFB71C1C),
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(vertical = 4.dp),
        )
      }
    }
  }
}
