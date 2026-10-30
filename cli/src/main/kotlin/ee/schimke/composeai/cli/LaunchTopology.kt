package ee.schimke.composeai.cli

import java.io.File

/**
 * What `launch` runs: devices, each with one or more apps, every app in a simulator JVM of its own.
 * `--app <device>:<apk>` names it directly; the APK argument, `--pair` and `--install` are
 * shorthands (the phone's first app, the watch's, another app beside the first). Apps on one device
 * share its device bus (`DeviceBus`); devices share the Wearable Data Layer, each one node, which
 * delivers between the same package on both sides.
 */
internal object LaunchTopology {

  /** One app of the topology, on [device]. */
  data class App(val device: String, val apk: File)

  /** A device name: lower case, a letter first; `watch` or `watch-…` is a Wear OS watch. */
  private val DEVICE = Regex("[a-z][a-z0-9-]*")

  /** `--app`'s `<device>:<apk>`. */
  fun parse(spec: String): App {
    val colon = spec.indexOf(':')
    require(colon > 0) {
      "--app takes <device>:<apk>, as phone:app.apk or watch:wear.apk; not $spec"
    }
    val device = spec.substring(0, colon)
    require(DEVICE.matches(device)) {
      "--app's device is a lower-case name, as phone, watch or tablet; not $device"
    }
    return App(device, File(spec.substring(colon + 1)).absoluteFile)
  }

  fun isWatch(device: String): Boolean = device == "watch" || device.startsWith("watch-")

  /** The devices of [apps], in the order their first app appears. */
  fun devices(apps: List<App>): List<String> = apps.map { it.device }.distinct()

  /** The apps sharing [app]'s device, itself excluded. */
  fun deviceMates(apps: List<App>, app: App): List<App> = apps.filter {
    it.device == app.device && it !== app
  }

  /** Whether [app] is its device's first, the one on screen. */
  fun isFirstOnDevice(apps: List<App>, app: App): Boolean =
    apps.first { it.device == app.device } === app

  /**
   * Each app's name on the control channel, in [apps]' order, and the aliases a command may use
   * instead: an app alone on its device is the device (`phone`, `watch`, as with `--pair`); apps
   * sharing one are `<device>/<package>`, and the device's name is its first app.
   */
  fun names(apps: List<App>, packageOf: (File) -> String): Pair<List<String>, Map<String, String>> {
    val packages = apps.map { packageOf(it.apk) }
    for (device in devices(apps)) {
      val onDevice = apps.indices.filter { apps[it].device == device }.map { packages[it] }
      onDevice
        .groupBy { it }
        .filterValues { it.size > 1 }
        .keys
        .firstOrNull()
        ?.let {
          throw IllegalArgumentException(
            "$it is on $device twice: each app on a device is one package"
          )
        }
    }
    val names = apps.mapIndexed { i, app ->
      if (apps.count { it.device == app.device } == 1) app.device
      else "${app.device}/${packages[i]}"
    }
    val aliases = mutableMapOf<String, String>()
    for ((i, app) in apps.withIndex()) {
      if (names[i] != app.device && app.device !in aliases) aliases[app.device] = names[i]
      // A command may name an app by its package alone when only one device has it.
      if (packages.count { it == packages[i] } == 1) aliases.putIfAbsent(packages[i], names[i])
    }
    return names to aliases
  }
}
