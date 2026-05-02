package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val DEVICES_SCHEMA = "compose-preview-devices/v1"

private val devicesJson = Json {
  prettyPrint = true
  encodeDefaults = true
}

@Serializable
data class DeviceCatalogResponse(
  val schema: String = DEVICES_SCHEMA,
  val devices: List<DeviceCatalogEntry>,
)

@Serializable
data class DeviceCatalogEntry(
  val id: String,
  val widthDp: Int,
  val heightDp: Int,
  val density: Float,
)

class DevicesCommand(private val args: List<String>) {
  private val jsonOutput = "--json" in args

  fun run() {
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }

    val response = buildDeviceCatalogResponse()
    if (jsonOutput) {
      println(devicesJson.encodeToString(DeviceCatalogResponse.serializer(), response))
      return
    }

    println("Known @Preview(device=...) ids:")
    val idWidth = response.devices.maxOfOrNull { it.id.length } ?: 0
    response.devices.forEach { device ->
      println(
        "${device.id.padEnd(idWidth)} ${device.widthDp.toString().padStart(4)}x" +
          "${device.heightDp.toString().padEnd(4)} dp  density=${device.density}"
      )
    }
  }

  private fun printUsage() {
    println(
      """
      compose-preview devices [--json]

      Lists the known @Preview(device=...) ids and their resolved geometry.
      This command reads the shared daemon core device catalog directly; it
      does not run Gradle and does not start a daemon.
      """
        .trimIndent()
    )
  }
}

internal fun buildDeviceCatalogResponse(): DeviceCatalogResponse =
  DeviceCatalogResponse(
    devices =
      DeviceDimensions.KNOWN_DEVICE_IDS.sorted().map { id ->
        val spec = DeviceDimensions.resolve(id)
        DeviceCatalogEntry(
          id = id,
          widthDp = spec.widthDp,
          heightDp = spec.heightDp,
          density = spec.density,
        )
      }
  )
