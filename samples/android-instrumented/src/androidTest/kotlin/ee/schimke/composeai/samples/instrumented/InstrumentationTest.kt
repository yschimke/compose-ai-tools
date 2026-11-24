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
  fun testContextReadsItsOwnAssets() {
    val context = InstrumentationRegistry.getInstrumentation().context
    assertEquals("ee.schimke.composeai.samples.instrumented.test", context.packageName)
    val text = context.assets.open("fixture.txt").bufferedReader().use { it.readText() }
    assertEquals("from the test APK\n", text)
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
