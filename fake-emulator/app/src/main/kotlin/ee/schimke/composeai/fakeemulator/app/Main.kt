package ee.schimke.composeai.fakeemulator.app

import ee.schimke.composeai.fakeemulator.DeviceSettingsController
import ee.schimke.composeai.fakeemulator.DisplaySize
import ee.schimke.composeai.fakeemulator.FakeEmulator
import ee.schimke.composeai.fakeemulator.FakeEmulatorConfig
import ee.schimke.composeai.fakeemulator.FrameSource
import ee.schimke.composeai.fakeemulator.MutableFrameSource
import ee.schimke.composeai.fakeemulator.PlaceholderImage
import ee.schimke.composeai.fakeemulator.PreviewLauncher
import ee.schimke.composeai.fakeemulator.grpc.EmulatorControllerService
import ee.schimke.composeai.fakeemulator.grpc.FakeEmulatorGrpcServer
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File

/**
 * Launches a fake Android emulator. With `--descriptor <daemon-launch.json>` the display is driven
 * by a real [RenderSession][ee.schimke.composeai.render.session.RenderSession] (preview pixels via
 * the daemon stream); without it the emulator runs against a placeholder screen — handy for ADB /
 * Studio bring-up with no project to render.
 *
 * ```
 * fake-emulator --descriptor build/compose-previews/daemon-launch.json \
 *   --adb-port 5555 --console-port 5554
 * # then: adb connect localhost:5555
 * #       adb -s emulator-5554 shell am start -n app/androidx.compose.ui.tooling.PreviewActivity \
 * #            --es composable com.example.PreviewsKt.MyPreview
 * ```
 */
fun main(args: Array<String>) {
  val options = Options.parse(args)
  val display = DisplaySize(options.width, options.height, options.dpi)

  // One shared settings model: the ADB shell + the gRPC controller mutate it; the render bridge
  // observes it and re-renders the preview under Studio's toggles.
  val settings = DeviceSettingsController()

  val frameSource: FrameSource
  val launcher: PreviewLauncher
  val extraClose: AutoCloseable

  if (options.descriptor != null) {
    val session =
      SubprocessRenderSessions.open(RenderSessionConfig(descriptorPath = File(options.descriptor)))
    val bridge = RenderSessionFrameSource(session, display, settings)
    frameSource = bridge
    launcher = bridge
    extraClose = AutoCloseable {
      bridge.close()
      session.close()
    }
  } else {
    val mutable = MutableFrameSource(display)
    mutable.push(PlaceholderImage.solidPng(display.width, display.height, PLACEHOLDER_ARGB), 0)
    frameSource = mutable
    launcher = PreviewLauncher.NOOP
    extraClose = AutoCloseable {}
  }

  val controllerService = EmulatorControllerService(frameSource, settings)
  val grpc = FakeEmulatorGrpcServer(options.grpcPort, controllerService.bindService()).start()

  val emulator =
    FakeEmulator(
        FakeEmulatorConfig(
          display = display,
          consolePort = options.consolePort,
          adbPort = options.adbPort,
          avdName = options.avdName,
          enableConsole = !options.noConsole,
          writeDiscovery = !options.noDiscovery,
          grpcPort = grpc.boundPort,
        ),
        frameSource = frameSource,
        previewLauncher = launcher,
        settings = settings,
      )
      .start()

  println("fake-emulator started")
  println("  serial:  ${emulator.serial}")
  println("  adb:     localhost:${emulator.adbPort}  (adb connect localhost:${emulator.adbPort})")
  if (emulator.consolePort > 0) println("  console: localhost:${emulator.consolePort}")
  println("  grpc:    localhost:${grpc.boundPort}")

  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        runCatching { emulator.close() }
        runCatching { grpc.close() }
        runCatching { controllerService.close() }
        runCatching { extraClose.close() }
      }
    )

  grpc.awaitTermination()
}

private const val PLACEHOLDER_ARGB = 0xFF202124.toInt()

private class Options(
  val descriptor: String?,
  val adbPort: Int,
  val consolePort: Int,
  val grpcPort: Int,
  val width: Int,
  val height: Int,
  val dpi: Int,
  val avdName: String,
  val noConsole: Boolean,
  val noDiscovery: Boolean,
) {
  companion object {
    fun parse(args: Array<String>): Options {
      var descriptor: String? = null
      var adbPort = 0
      var consolePort = 0
      var grpcPort = 0
      var width = 1080
      var height = 2340
      var dpi = 420
      var avdName = "Compose_Preview"
      var noConsole = false
      var noDiscovery = false
      var i = 0
      fun next(): String = args.getOrNull(++i) ?: error("missing value for ${args[i - 1]}")
      while (i < args.size) {
        when (val arg = args[i]) {
          "--descriptor" -> descriptor = next()
          "--adb-port" -> adbPort = next().toInt()
          "--console-port" -> consolePort = next().toInt()
          "--grpc-port" -> grpcPort = next().toInt()
          "--width" -> width = next().toInt()
          "--height" -> height = next().toInt()
          "--dpi" -> dpi = next().toInt()
          "--avd-name" -> avdName = next()
          "--no-console" -> noConsole = true
          "--no-discovery" -> noDiscovery = true
          else -> error("unknown argument: $arg")
        }
        i++
      }
      return Options(
        descriptor,
        adbPort,
        consolePort,
        grpcPort,
        width,
        height,
        dpi,
        avdName,
        noConsole,
        noDiscovery,
      )
    }
  }
}
