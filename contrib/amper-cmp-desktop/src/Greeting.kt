import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.singleWindowApplication

/**
 * Canary `@Preview` for the Amper / non-Gradle integration story.
 *
 * Lifted from
 * [JetBrains/amper:examples/compose-desktop](https://github.com/JetBrains/amper/tree/main/examples/compose-desktop)
 * with `material` swapped for `material3` so it lines up with the rest of compose-ai-tools'
 * Compose Desktop fixtures. The non-Gradle integration test renders this composable through a
 * synthesised `daemon-launch.json`; see `docs/NON_GRADLE_INTEGRATION.md`.
 */
@Composable
@Preview
fun Greeting() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("Hello, World!")
        }
    }
}

fun main() = singleWindowApplication { Greeting() }
