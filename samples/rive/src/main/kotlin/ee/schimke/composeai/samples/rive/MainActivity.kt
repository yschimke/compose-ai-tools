package ee.schimke.composeai.samples.rive

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import app.rive.rememberRiveWorkerOrNull
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.Rive

/**
 * Three [RiveAnimationView]s, one per part of Rive's native runtime the simulator's host build has
 * to get right:
 * - `off_road_car_blog`: vector paths, gradients and a looping animation;
 * - `image_db_cats`: embedded images, which the runtime decodes through `android.graphics`;
 * - `font_db_station_sign`: an embedded font, shaped with HarfBuzz.
 *
 * Off a device with Rive's GPU renderer (the simulator, an emulator without it) every view falls
 * back to Rive's Canvas renderer, which draws into the view's TextureView with `lockCanvas`. Each
 * view's frame is read back (`TextureView.getBitmap`) every quarter second until all of them have
 * drawn something, or for at most [CHECK_TIMEOUT_MS], and logged under the `RiveSample` tag as
 * `PASS` or `FAIL`, with the share of pixels it covered.
 *
 * A fourth row is Rive's Compose API, which has no Canvas renderer: its worker needs OpenGL ES (or
 * Vulkan). The simulator's host build has no GPU, so creating one must fail as it does on a device
 * without OpenGL ES, with an error the app can handle ([rememberRiveWorkerOrNull] returns null),
 * rather than take the process down. It logs `PASS compose` when the error arrives.
 *
 * The `.riv` files are rive-android's own sample files (MIT, Copyright Rive), from
 * https://github.com/rive-app/rive-android/tree/11.17.0/app/src/main/res/raw.
 */
class MainActivity : ComponentActivity() {
  private val main = Handler(Looper.getMainLooper())

  /** The Compose row's outcome, once its worker has started or failed. */
  @Volatile private var composeResult: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Rive.init(this)
    Log.i(TAG, "renderer ${Rive.defaultRendererType}")

    val files =
      listOf(
        "off_road_car_blog" to R.raw.off_road_car_blog,
        "image_db_cats" to R.raw.image_db_cats,
        "font_db_station_sign" to R.raw.font_db_station_sign,
      )
    val column =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
      }
    val views = files.map { (name, resource) ->
      column.addView(
        TextView(this).apply {
          text = name
          setPadding(24, 12, 24, 4)
        },
      )
      val view = RiveAnimationView(this).apply { setRiveResource(resource) }
      column.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
      name to view
    }
    column.addView(
      TextView(this).apply {
        text = "compose (GPU only)"
        setPadding(24, 12, 24, 4)
      },
    )
    column.addView(
      ComposeView(this).apply {
        setContent {
          val error = remember { mutableStateOf<Throwable?>(null) }
          val worker = rememberRiveWorkerOrNull(error)
          LaunchedEffect(worker, error.value) {
            composeResult =
              when {
                worker != null -> "PASS compose: a GPU worker started"
                error.value != null -> "PASS compose: no GPU, the worker failed with ${error.value}"
                else -> null
              }
          }
        }
      },
    )
    setContentView(column)
    val started = System.currentTimeMillis()
    main.post(
      object : Runnable {
        override fun run() {
          val timedOut = System.currentTimeMillis() - started > CHECK_TIMEOUT_MS
          val done = views.all { (_, view) -> drawn(view) > 0 } && composeResult != null
          if (done || timedOut) report(views) else main.postDelayed(this, CHECK_INTERVAL_MS)
        }
      },
    )
  }

  /** How many of [view]'s pixels its last frame covered; 0 before it has a texture or a frame. */
  private fun drawn(view: RiveAnimationView): Int {
    val frame = (if (view.isAvailable) view.bitmap else null) ?: return 0
    val pixels = IntArray(frame.width * frame.height)
    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
    return pixels.count { Color.alpha(it) != 0 }
  }

  private fun report(views: List<Pair<String, RiveAnimationView>>) {
    var passed = 0
    for ((name, view) in views) {
      val drawn = drawn(view)
      val total = (view.width * view.height).coerceAtLeast(1)
      if (drawn > 0) passed++
      Log.i(
        TAG,
        "${if (drawn > 0) "PASS" else "FAIL"} $name: ${view.width}x${view.height}, " +
          "%.1f%% of pixels drawn".format(100.0 * drawn / total),
      )
    }
    val compose = composeResult
    if (compose != null) passed++
    Log.i(TAG, compose ?: "FAIL compose: the worker neither started nor failed")
    Log.i(TAG, "DONE $passed/${views.size + 1} passed")
  }

  private companion object {
    const val TAG = "RiveSample"
    const val CHECK_INTERVAL_MS = 250L
    const val CHECK_TIMEOUT_MS = 15_000L
  }
}
