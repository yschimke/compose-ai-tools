package ee.schimke.composeai.samples.surfaceview

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Two [SurfaceView]s, one per way an app draws into a surface, each with a label the window draws
 * over it:
 * - the top one with `holder.lockCanvas()`, behind its window, so the label shows over it;
 * - the bottom one with `holder.surface.lockHardwareCanvas()` and `setZOrderOnTop(true)`, so it
 *   covers the label.
 *
 * A tap on the top half hides or shows the bottom surface. Every holder callback is logged under
 * the `SurfaceViewSample` tag.
 */
class MainActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val software =
      surface("software") { holder, width, height ->
        holder.lockCanvas()?.let { canvas ->
          try {
            quadrants(
              canvas,
              width,
              height,
              intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            )
          } finally {
            holder.unlockCanvasAndPost(canvas)
          }
        } ?: Log.i(TAG, "software: lockCanvas returned null")
      }
    val hardware =
      surface("hardware") { holder, width, height ->
        val surface = holder.surface
        val canvas = surface.lockHardwareCanvas()
        try {
          quadrants(
            canvas,
            width,
            height,
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
          )
        } finally {
          surface.unlockCanvasAndPost(canvas)
        }
      }
    hardware.setZOrderOnTop(true)
    setContentView(
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFFEEEEEE.toInt())
        addView(
          labelled(software, "Window label, over the surface").apply {
            // A tap hides or shows the bottom surface: destroyed, then created again.
            setOnClickListener {
              hardware.visibility =
                if (hardware.visibility == View.VISIBLE) View.GONE else View.VISIBLE
              Log.i(TAG, "hardware: visibility ${hardware.visibility}")
            }
          },
          weight(),
        )
        addView(labelled(hardware, "Window label, under the on-top surface"), weight())
      },
    )
  }

  private fun surface(name: String, draw: (SurfaceHolder, Int, Int) -> Unit) =
    SurfaceView(this).apply {
      holder.addCallback(
        object : SurfaceHolder.Callback {
          override fun surfaceCreated(holder: SurfaceHolder) {
            Log.i(TAG, "$name: surfaceCreated valid=${holder.surface.isValid}")
          }

          override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.i(TAG, "$name: surfaceChanged ${width}x$height")
            draw(holder, width, height)
            Log.i(TAG, "$name: drew")
          }

          override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.i(TAG, "$name: surfaceDestroyed")
          }
        },
      )
    }

  private fun labelled(surface: SurfaceView, text: String) =
    FrameLayout(this).apply {
      addView(surface)
      addView(
        TextView(context).apply {
          this.text = text
          setTextColor(Color.WHITE)
          setBackgroundColor(0x80000000.toInt())
          textSize = 16f
          setPadding(24, 24, 24, 24)
        },
        FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
          Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ),
      )
    }

  private fun weight() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

  /**
   * Four colored quadrants and a white circle: easy to recognise, and to check the orientation of.
   */
  private fun quadrants(canvas: Canvas, width: Int, height: Int, colors: IntArray) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (i in 0 until 4) {
      paint.color = colors[i]
      val x = (i % 2) * width / 2f
      val y = (i / 2) * height / 2f
      canvas.drawRect(x, y, x + width / 2f, y + height / 2f, paint)
    }
    paint.color = Color.WHITE
    canvas.drawCircle(width / 2f, height / 2f, height / 4f, paint)
  }

  private companion object {
    const val TAG = "SurfaceViewSample"
  }
}
