package ee.schimke.composeai.fakeemulator

import java.nio.charset.StandardCharsets

/**
 * Default `getprop` map. Tuned so a host classifies us as an emulator (`ro.kernel.qemu=1`, etc.).
 */
object DeviceProperties {
  fun defaults(serial: String, display: DisplaySize): Map<String, String> =
    linkedMapOf(
      "ro.product.name" to "sdk_gphone_compose_preview",
      "ro.product.model" to "Compose Preview Emulator",
      "ro.product.device" to "compose_preview",
      "ro.product.brand" to "google",
      "ro.product.manufacturer" to "Compose AI Tools",
      "ro.product.cpu.abi" to "arm64-v8a",
      "ro.product.cpu.abilist" to "arm64-v8a",
      "ro.build.version.sdk" to "36",
      "ro.build.version.release" to "16",
      "ro.build.characteristics" to "emulator",
      "ro.build.product" to "compose_preview",
      "ro.hardware" to "ranchu",
      "ro.kernel.qemu" to "1",
      "ro.serialno" to serial,
      "service.adb.root" to "1",
      "qemu.sf.lcd_density" to display.densityDpi.toString(),
    )
}

/** Builds the CNXN banner an adbd device sends: `device::<key=val;…>features=<csv>`. */
object AdbBanner {
  /**
   * Features we actually honour. `shell_v2` makes hosts use the framed shell protocol; `cmd` +
   * `abb_exec` let hosts (adb / Studio / dadb) drive installs over the modern streaming path
   * (`abb_exec:package\0install\0-S\0…` / `exec:cmd package install -S …`) our shell now accepts.
   */
  val FEATURES = listOf("shell_v2", "cmd", "abb_exec")

  fun build(properties: Map<String, String>, features: List<String> = FEATURES): ByteArray {
    val sb = StringBuilder("device::")
    for ((k, v) in properties) sb.append(k).append('=').append(v).append(';')
    sb.append("features=").append(features.joinToString(","))
    // adbd null-terminates the banner payload; include the terminator in the bytes.
    return sb.toString().toByteArray(StandardCharsets.UTF_8) + 0.toByte()
  }
}
