package ee.schimke.composeai.samples.rive

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import app.rive.Result
import app.rive.Rive
import app.rive.RiveFileSource
import app.rive.RiveRenderException
import app.rive.rememberRiveFile
import app.rive.rememberRiveWorkerOrNull
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.Rive as RiveRuntime

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

  /** The Compose row's outcome once it is settled: its worker failed, or its file did not load. */
  @Volatile private var composeResult: String? = null

  /** Reads the Compose row's last frame back, once Rive has drawn one (its GPU worker started). */
  @Volatile private var composeFrame: (() -> android.graphics.Bitmap)? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    RiveRuntime.init(this)
    Log.i(TAG, "renderer ${RiveRuntime.defaultRendererType}")

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
            if (worker != null) return@LaunchedEffect
            composeResult =
              when {
                error.value == null -> null
                // Rive's own error for a host without the GPU it needs; anything else (a crash
                // inside the runtime) is a failure.
                generateSequence(error.value) { it.cause }.any { it is RiveRenderException } ->
                  "PASS compose: no GPU, the worker failed with ${error.value}"
                else -> "FAIL compose: the worker failed with ${error.value}"
              }
          }
          if (worker != null) {
            val file = rememberRiveFile(RiveFileSource.RawRes.from(R.raw.image_db_cats), worker)
            when (file) {
              is Result.Success ->
                Rive(file.value, Modifier.fillMaxSize(), onBitmapAvailable = { composeFrame = it })
              is Result.Error -> composeResult = "FAIL compose: ${file.throwable}"
              is Result.Loading -> Unit
            }
          }
        }
      },
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
    )
    setContentView(column)
    val started = System.currentTimeMillis()
    main.post(
      object : Runnable {
        override fun run() {
          val timedOut = System.currentTimeMillis() - started > CHECK_TIMEOUT_MS
          val done = views.all { (_, view) -> drawn(view) > 0 } && composeDone()
          if (done || timedOut) report(views) else main.postDelayed(this, CHECK_INTERVAL_MS)
        }
      },
    )
  }

  /** Whether the Compose row has settled: failed, or drawn a frame with something in it. */
  private fun composeDone(): Boolean =
    composeResult != null || (composeFrame?.let { drawn(it()) > 0 } ?: false)

  private fun drawn(frame: android.graphics.Bitmap): Int {
    val pixels = IntArray(frame.width * frame.height)
    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
    return pixels.count { Color.alpha(it) != 0 }
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
    val frame = composeFrame?.invoke()
    val compose =
      composeResult
        ?: when {
          frame == null -> "FAIL compose: the worker neither drew nor failed"
          drawn(frame) > 0 ->
            "PASS compose: the GPU renderer drew ${frame.width}x${frame.height}, " +
              "%.1f%% of pixels".format(100.0 * drawn(frame) / (frame.width * frame.height))
          else -> "FAIL compose: the GPU renderer drew an empty frame"
        }
    if (compose.startsWith("PASS")) passed++
    Log.i(TAG, compose)
    Log.i(TAG, "DONE $passed/${views.size + 1} passed")
  }

  private companion object {
    const val TAG = "RiveSample"
    const val CHECK_INTERVAL_MS = 250L
    const val CHECK_TIMEOUT_MS = 15_000L
  }
}
