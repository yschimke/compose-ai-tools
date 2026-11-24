package ee.schimke.composeai.samples.instrumented

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstrumentationTest {
  @Test
  fun targetContextIsTheApp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("ee.schimke.composeai.samples.instrumented", context.packageName)
  }

  @Test
  fun runOnMainSyncReachesTheMainThread() {
    var onMain = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    }
    assertEquals(true, onMain)
  }
}
