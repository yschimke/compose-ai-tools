package ee.schimke.composeai.samples.instrumented

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A View-system screen, for Espresso. */
class ViewActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val label =
      TextView(this).apply {
        id = LABEL
        text = "Waiting"
      }
    val button =
      Button(this).apply {
        id = BUTTON
        text = "Tap"
        setOnClickListener { label.text = "Tapped" }
      }
    setContentView(
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label)
        addView(button)
      },
    )
  }

  companion object {
    const val LABEL = 0x7f0a0001
    const val BUTTON = 0x7f0a0002
  }
}
