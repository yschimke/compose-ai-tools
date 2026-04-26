package com.example.sampleandroidscreenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Previews wrapped in a regular `class` — the idiomatic style under Google's
 * docs for `com.android.compose.screenshot`. Without
 * [ComposePreviewStrategy]'s `resolveReceiver(...)` these blow up with
 * `NullPointerException: Cannot invoke "Object.getClass()" because "obj" is null`
 * inside `ComposableMethod.invoke`, because `@Composable fun` on a
 * non-object, non-companion class compiles to an instance method and needs
 * a real receiver. Two previews here so the regression surface isn't a
 * single preview.
 */
class ScreenshotPreviews {

    @Preview(name = "instance-red")
    @Composable
    fun InstanceRed() {
        MaterialTheme {
            Box(Modifier.size(64.dp).background(Color.Red)) {
                Text("red")
            }
        }
    }

    @Preview(name = "instance-green")
    @Composable
    fun InstanceGreen() {
        MaterialTheme {
            Box(Modifier.size(64.dp).background(Color.Green)) {
                Text("green")
            }
        }
    }
}

/**
 * Top-level preview *inside* the `screenshotTest` source set. Exercises the
 * other half of discovery: the class dir is the screenshotTest output dir,
 * but the function itself is static (top-level), so `resolveReceiver`
 * returns `null` — the original code path. Catches the case where we'd
 * accidentally filter out screenshotTest top-level previews.
 */
@Preview(name = "screenshotTest-toplevel")
@Composable
fun ScreenshotTopLevelPreview() {
    MaterialTheme {
        Box(Modifier.size(64.dp).background(Color.Blue)) {
            Text("blue")
        }
    }
}
