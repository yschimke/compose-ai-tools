package ee.schimke.composeai.cli.simulator

import ee.schimke.composeai.cli.HostNativesProvision
import java.io.File
import kotlin.system.exitProcess

/**
 * Standalone CLI entrypoint for `android-simulator` when packaged as its own distribution
 * (`:simulator-cli:installDist` / `:simulator-cli:distTar`).
 */
fun main(args: Array<String>) {
  val list = args.toList()
  when (list.firstOrNull()) {
    "dwf-runtime" -> DwfRuntimeCommand(list.drop(1)).run()
    "host-natives" -> {
      val apks = list.drop(1).flagValuesAll("--apk").map(::File)
      val root = HostNativesProvision.provisionAll(apks)
      if (root == null) exitProcess(1)
      println(root.absolutePath)
    }
    else -> AndroidSimulatorCommand(list, invokedAs = "android-simulator").run()
  }
}
