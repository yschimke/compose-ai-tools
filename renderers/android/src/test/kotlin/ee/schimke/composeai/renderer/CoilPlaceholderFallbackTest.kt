package ee.schimke.composeai.renderer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil3.ColorImage
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoilPlaceholderFallbackTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `coil 2 failed result falls back to request placeholder`() {
    val placeholder = ColorDrawable(Color.MAGENTA)
    val request =
      ImageRequest.Builder(context)
        .data("https://artwork.invalid/living-room-speaker.png")
        .placeholder(placeholder)
        .build()

    val result =
      coil2ResultWithPlaceholderFallback(
        ErrorResult(drawable = null, request = request, throwable = IllegalStateException("boom"))
      )

    assertSame(placeholder, (result as ErrorResult).drawable)
  }

  @Test
  fun `coil 3 missing image falls back to request placeholder`() {
    val placeholder = ColorImage(color = 0xffff00ff.toInt(), width = 10, height = 10)
    val empty = ColorImage(color = 0, width = 0, height = 0)
    val request =
      coil3.request.ImageRequest.Builder(context)
        .data("https://artwork.invalid/living-room-speaker.png")
        .placeholder(placeholder)
        .build()

    assertSame(
      placeholder,
      coil3ImageWithPlaceholderFallback(request, loaded = null, empty = empty),
    )
  }

  @Test
  fun `coil 3 resolved image wins over request placeholder`() {
    val loaded = ColorImage(color = 0xff00ff00.toInt(), width = 10, height = 10)
    val placeholder = ColorImage(color = 0xffff00ff.toInt(), width = 10, height = 10)
    val request =
      coil3.request.ImageRequest.Builder(context)
        .data("https://artwork.invalid/living-room-speaker.png")
        .placeholder(placeholder)
        .build()

    assertSame(
      loaded,
      coil3ImageWithPlaceholderFallback(request, loaded = loaded, empty = placeholder),
    )
  }
}
