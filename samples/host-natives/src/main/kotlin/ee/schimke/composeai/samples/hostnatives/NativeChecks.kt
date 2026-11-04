package ee.schimke.composeai.samples.hostnatives

import android.util.Log
import androidx.compose.ui.graphics.Path
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.github.luben.zstd.Zstd
import com.sun.jna.Library
import com.sun.jna.Native
import java.security.MessageDigest
import org.conscrypt.Conscrypt
import org.signal.libsignal.protocol.ecc.ECKeyPair

/** One native library and the call that makes it load: what `host-natives/index.json` serves. */
data class NativeCheck(val library: String, val run: () -> String)

/** The outcome of a [NativeCheck]: [detail] is its result, or the error that stopped it. */
data class CheckResult(val library: String, val passed: Boolean, val detail: String) {
  /** The line logged and shown: `PASS sqliteJni 3.50.4`. */
  val line: String
    get() = "${if (passed) "PASS" else "FAIL"} $library $detail"
}

private interface CLibrary : Library {
  fun strlen(text: String): Long
}

/** Every library in `host-natives/index.json`, each exercised through its own Java API. */
val nativeChecks: List<NativeCheck> =
  listOf(
    // Compose's Path.iterator() is graphics-path's PathIterator; 1.0.1 loads its library first.
    NativeCheck("androidx.graphics.path") {
      val path =
        Path().apply {
          moveTo(0f, 0f)
          lineTo(10f, 0f)
          quadraticTo(10f, 10f, 0f, 10f)
          close()
        }
      "${path.iterator().asSequence().count()} segments"
    },
    NativeCheck("sqliteJni") {
      BundledSQLiteDriver().open(":memory:").use { connection ->
        connection.prepare("SELECT sqlite_version()").use { statement ->
          statement.step()
          "SQLite ${statement.getText(0)}"
        }
      }
    },
    NativeCheck("ink") {
      val inputs =
        MutableStrokeInputBatch().apply {
          for (i in 0 until 10) {
            add(InputToolType.STYLUS, x = i * 5f, y = i * 3f, elapsedTimeMillis = i * 16L)
          }
        }
      val brush =
        Brush.createWithColorIntArgb(
          family = StockBrushes.marker(),
          colorIntArgb = 0xFF0000FF.toInt(),
          size = 5f,
          epsilon = 0.1f,
        )
      val stroke = Stroke(brush, inputs)
      "stroke of ${stroke.inputs.size} inputs, ${stroke.shape.getRenderGroupCount()} render groups"
    },
    NativeCheck("zstd-jni") {
      val original = "host natives ".repeat(100).toByteArray()
      val compressed = Zstd.compress(original)
      val restored = Zstd.decompress(compressed, original.size)
      check(restored.contentEquals(original)) { "round trip changed the bytes" }
      "${original.size} -> ${compressed.size} bytes, zstd ${Zstd::class.java.`package`?.implementationVersion ?: ""}"
        .trim()
    },
    NativeCheck("jnidispatch") {
      val libc = Native.load("c", CLibrary::class.java)
      "strlen = ${libc.strlen("host natives")}, JNA ${Native.VERSION}"
    },
    NativeCheck("conscrypt_jni") {
      check(Conscrypt.isAvailable()) { "Conscrypt.isAvailable() is false" }
      val provider = Conscrypt.newProvider()
      val digest = MessageDigest.getInstance("SHA-256", provider).digest("host".toByteArray())
      "${provider.name} SHA-256 ${digest.take(4).joinToString("") { "%02x".format(it) }}…"
    },
    NativeCheck("signal_jni") {
      val pair = ECKeyPair.generate()
      "Curve25519 public key of ${pair.publicKey.serialize().size} bytes"
    },
  )

/** Runs [check], logging its line under [TAG] for a headless launch to read. */
fun runCheck(check: NativeCheck): CheckResult {
  val result =
    try {
      CheckResult(check.library, passed = true, detail = check.run())
    } catch (e: Throwable) {
      CheckResult(check.library, passed = false, detail = "${e.javaClass.simpleName}: ${e.message}")
    }
  Log.i(TAG, result.line)
  return result
}

const val TAG = "HostNatives"
