package ee.schimke.composeai.cli.device

import com.android.emulator.control.BootCompletedNotification
import com.android.emulator.control.DisplayConfiguration
import com.android.emulator.control.DisplayConfigurations
import com.android.emulator.control.EmulatorControllerGrpc
import com.android.emulator.control.EmulatorStatus
import com.android.emulator.control.Entry
import com.android.emulator.control.EntryList
import com.android.emulator.control.GpsState
import com.android.emulator.control.Image
import com.android.emulator.control.ImageFormat
import com.android.emulator.control.ImageTransport
import com.android.emulator.control.InputEvent
import com.android.emulator.control.KeyboardEvent
import com.android.emulator.control.MouseEvent
import com.android.emulator.control.Notification
import com.android.emulator.control.PhysicalModelValue
import com.android.emulator.control.TouchEvent
import com.android.emulator.control.VmConfiguration
import com.android.emulator.control.VmRunState
import com.android.emulator.control.WheelEvent
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The emulator's gRPC `EmulatorController` (Android Studio's `emulator_controller.proto`, from
 * `com.android.tools.emulator:proto`), served for the simulator: what Studio's Running Devices
 * window uses to show an emulator's display and pass it the mouse, keyboard and touches. The
 * display comes from the simulator's `screenshot`, input becomes its `touch`, `key`, `text` and
 * `rotary` commands, and a few sensors map to theirs (`setGps` to `location`, the heart rate and
 * wrist tilt physical models to `health` and `tilt`). What has no simulator equivalent answers
 * UNIMPLEMENTED.
 *
 * Calls must carry `authorization: Bearer <token>`, the token the discovery file names, as the
 * emulator's do.
 */
internal class EmulatorGrpc(
  private val device: VirtualDevice,
  port: Int,
  private val token: String,
) : AutoCloseable {

  private val server: Server =
    NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", port))
      .maxInboundMessageSize(16 * 1024 * 1024)
      .addService(ServerInterceptors.intercept(Controller(), Auth()))
      .build()

  val port: Int
    get() = server.port

  fun start(): EmulatorGrpc {
    server.start()
    return this
  }

  override fun close() {
    server.shutdownNow()
  }

  private inner class Auth : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
      call: ServerCall<ReqT, RespT>,
      headers: Metadata,
      next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
      val value = headers.get(AUTHORIZATION)
      if (value != "Bearer $token") {
        call.close(Status.UNAUTHENTICATED.withDescription("bad or missing token"), Metadata())
        return object : ServerCall.Listener<ReqT>() {}
      }
      return next.startCall(call, headers)
    }
  }

  private inner class Controller : EmulatorControllerGrpc.EmulatorControllerImplBase() {

    @Volatile private var mouseDown = false
    @Volatile private var touchDown = false

    override fun getStatus(request: Empty, response: StreamObserver<EmulatorStatus>) =
      answer(response) {
        val config = device.config
        EmulatorStatus.newBuilder()
          .setVersion(VERSION)
          .setUptime(System.currentTimeMillis() - device.bootTimeMs)
          .setBooted(device.booted)
          .setHeartbeat(System.currentTimeMillis() - device.bootTimeMs)
          .setVmConfig(
            VmConfiguration.newBuilder()
              .setHypervisorTypeValue(VmConfiguration.VmHypervisorType.NONE_VALUE)
              .setNumberOfCpuCores(Runtime.getRuntime().availableProcessors())
              .setRamSizeBytes(Runtime.getRuntime().maxMemory())
          )
          .setHardwareConfig(
            EntryList.newBuilder()
              .addAllEntry(
                AvdFiles.hardware(config).map { (k, v) ->
                  Entry.newBuilder().setKey(k).setValue(v).build()
                }
              )
          )
          .putAllGuestConfig(device.properties())
          .build()
      }

    override fun getVmState(request: Empty, response: StreamObserver<VmRunState>) =
      answer(response) {
        VmRunState.newBuilder()
          .setState(if (device.booted) VmRunState.RunState.RUNNING else VmRunState.RunState.START)
          .build()
      }

    override fun setVmState(request: VmRunState, response: StreamObserver<Empty>) =
      answer(response) {
        when (request.state) {
          VmRunState.RunState.SHUTDOWN,
          VmRunState.RunState.TERMINATE -> Thread { device.stop() }.start()
          VmRunState.RunState.RESET,
          VmRunState.RunState.RESTART -> Thread { device.reboot() }.start()
          else -> Unit
        }
        Empty.getDefaultInstance()
      }

    override fun getDisplayConfigurations(
      request: Empty,
      response: StreamObserver<DisplayConfigurations>,
    ) =
      answer(response) {
        val (width, height, dpi) = display()
        DisplayConfigurations.newBuilder()
          .addDisplays(
            DisplayConfiguration.newBuilder()
              .setWidth(width)
              .setHeight(height)
              .setDpi(dpi)
              .setDisplay(0)
          )
          .setMaxDisplays(1)
          .setUserConfigurable(0)
          .build()
      }

    override fun getScreenshot(request: ImageFormat, response: StreamObserver<Image>) =
      answer(response) { frame(request, 0)?.image ?: throw IllegalStateException("no frame") }

    override fun streamScreenshot(request: ImageFormat, response: StreamObserver<Image>) {
      val observer = response as ServerCallStreamObserver<Image>
      Thread(
          {
            var seq = 0
            var last: ByteArray? = null
            try {
              while (!observer.isCancelled && !device.exit.isDone) {
                val frame = frame(request, seq)
                if (frame != null && !frame.png.contentEquals(last)) {
                  last = frame.png
                  observer.onNext(frame.image)
                  seq++
                }
                Thread.sleep(FRAME_INTERVAL_MS)
              }
              if (!observer.isCancelled) observer.onCompleted()
            } catch (e: Exception) {
              if (!observer.isCancelled)
                observer.onError(Status.INTERNAL.withDescription(e.message).asException())
            }
          },
          "grpc-screenshots",
        )
        .apply {
          isDaemon = true
          start()
        }
    }

    override fun sendTouch(request: TouchEvent, response: StreamObserver<Empty>) =
      answer(response) {
        touch(request)
        Empty.getDefaultInstance()
      }

    override fun sendMouse(request: MouseEvent, response: StreamObserver<Empty>) =
      answer(response) {
        mouse(request)
        Empty.getDefaultInstance()
      }

    override fun sendKey(request: KeyboardEvent, response: StreamObserver<Empty>) =
      answer(response) {
        key(request)
        Empty.getDefaultInstance()
      }

    override fun streamInputEvent(response: StreamObserver<Empty>): StreamObserver<InputEvent> =
      object : StreamObserver<InputEvent> {
        override fun onNext(value: InputEvent) {
          runCatching {
            when {
              value.hasTouchEvent() -> touch(value.touchEvent)
              value.hasMouseEvent() -> mouse(value.mouseEvent)
              value.hasKeyEvent() -> key(value.keyEvent)
              value.hasWheelEvent() -> wheel(value.wheelEvent)
            }
          }
            .onFailure { device.log("gRPC input: ${it.message}") }
        }

        override fun onError(t: Throwable) = Unit

        override fun onCompleted() {
          response.onNext(Empty.getDefaultInstance())
          response.onCompleted()
        }
      }

    override fun injectWheel(response: StreamObserver<Empty>): StreamObserver<WheelEvent> =
      object : StreamObserver<WheelEvent> {
        override fun onNext(value: WheelEvent) {
          runCatching { wheel(value) }
        }

        override fun onError(t: Throwable) = Unit

        override fun onCompleted() {
          response.onNext(Empty.getDefaultInstance())
          response.onCompleted()
        }
      }

    override fun setGps(request: GpsState, response: StreamObserver<Empty>) =
      answer(response) {
        device
          .simulator()
          .request(
            "location",
            "lat" to JsonPrimitive(request.latitude),
            "lng" to JsonPrimitive(request.longitude),
            "altitude" to JsonPrimitive(request.altitude),
            "speed" to JsonPrimitive(request.speed),
            "bearing" to JsonPrimitive(request.bearing),
          )
        Empty.getDefaultInstance()
      }

    override fun setPhysicalModel(request: PhysicalModelValue, response: StreamObserver<Empty>) =
      answer(response) {
        val value = request.value.dataList.firstOrNull() ?: 0f
        when (request.target) {
          PhysicalModelValue.PhysicalType.HEART_RATE ->
            device.simulator().request("health", "heartRate" to JsonPrimitive(value.toDouble()))
          PhysicalModelValue.PhysicalType.WRIST_TILT -> device.simulator().request("tilt")
          else -> throw UnsupportedOperationException("physical model ${request.target}")
        }
        Empty.getDefaultInstance()
      }

    override fun streamNotification(request: Empty, response: StreamObserver<Notification>) {
      val observer = response as ServerCallStreamObserver<Notification>
      Thread(
          {
            runCatching {
              device.simulator()
              if (!observer.isCancelled) {
                observer.onNext(
                  Notification.newBuilder()
                    .setBooted(
                      BootCompletedNotification.newBuilder()
                        .setTime(((System.currentTimeMillis() - device.bootTimeMs) / 1000).toInt())
                    )
                    .build()
                )
              }
              // Held open, as the emulator's is, until the client or the device goes.
              while (!observer.isCancelled && !device.exit.isDone) Thread.sleep(500)
              if (!observer.isCancelled) observer.onCompleted()
            }
          },
          "grpc-notifications",
        )
        .apply {
          isDaemon = true
          start()
        }
    }

    private fun touch(event: TouchEvent) {
      val touch = event.touchesList.firstOrNull() ?: return
      val down = touch.pressure > 0
      val action =
        when {
          down && !touchDown -> "down"
          down -> "move"
          touchDown -> "up"
          else -> return
        }
      touchDown = down
      pointer(action, touch.x, touch.y, event.display)
    }

    private fun mouse(event: MouseEvent) {
      val down = event.buttons and 1 != 0
      val action =
        when {
          down && !mouseDown -> "down"
          down -> "move"
          mouseDown -> "up"
          else -> return // hover
        }
      mouseDown = down
      pointer(action, event.x, event.y, event.display)
    }

    private fun pointer(action: String, x: Int, y: Int, display: Int) {
      device
        .simulator()
        .request(
          "touch",
          "action" to JsonPrimitive(action),
          "x" to JsonPrimitive(x),
          "y" to JsonPrimitive(y),
          "display" to JsonPrimitive(display),
        )
    }

    private fun key(event: KeyboardEvent) {
      if (event.eventType == KeyboardEvent.KeyEventType.keydown) return // acted on at keyup
      if (event.text.isNotEmpty()) {
        device.simulator().request("text", "text" to JsonPrimitive(event.text))
        return
      }
      val code = KEYS[event.key] ?: event.key.takeIf { it.length == 1 }?.let { "TEXT" }
      when (code) {
        null -> device.log("gRPC key ${event.key} has no simulator key")
        "TEXT" -> device.simulator().request("text", "text" to JsonPrimitive(event.key))
        else -> device.simulator().request("key", "key" to JsonPrimitive(code))
      }
    }

    private fun wheel(event: WheelEvent) {
      if (event.dy == 0) return
      // A watch's crown; elsewhere the wheel has no simulator equivalent yet.
      if (device.config.formFactor != ee.schimke.composeai.cli.ApkFormFactor.WEAR) return
      device
        .simulator()
        .request(
          "rotary",
          "delta" to JsonPrimitive(if (event.dy > 0) -1 else 1),
          "count" to JsonPrimitive(maxOf(1, kotlin.math.abs(event.dy) / 120)),
        )
    }
  }

  /**
   * The default display as the simulator has it now — a `--device` preset, a `wm size`, a rotation
   * — in pixels and dpi; the launch's own geometry while it is booting.
   */
  private fun display(): Triple<Int, Int, Int> {
    val config = device.config
    val now =
      runCatching { device.simulator(timeoutMs = 2_000).request("display") }.getOrNull()
        ?: return Triple(config.widthPx, config.heightPx, config.densityDpi)
    val width = now["width"]?.jsonPrimitive?.intOrNull ?: config.widthPx
    val height = now["height"]?.jsonPrimitive?.intOrNull ?: config.heightPx
    val dpi =
      now["density"]?.jsonPrimitive?.doubleOrNull?.let { (it * 160).toInt() } ?: config.densityDpi
    return Triple(width, height, dpi)
  }

  private class Frame(val png: ByteArray, val image: Image)

  /** One display frame in the format [request] asks for, or null when the simulator has none. */
  private fun frame(request: ImageFormat, seq: Int): Frame? {
    val result =
      try {
        device
          .simulator(timeoutMs = 2_000)
          .request(
            "screenshot",
            "square" to JsonPrimitive(true),
            "display" to JsonPrimitive(request.display),
          )
      } catch (_: Exception) {
        return null
      }
    val png = Base64.getDecoder().decode(result["png"]!!.jsonPrimitive.content)
    val original = ImageIO.read(ByteArrayInputStream(png)) ?: return null
    val image =
      if (request.width > 0 && request.height > 0)
        scaleToFit(original, request.width, request.height)
      else original
    val bytes =
      when (request.format) {
        ImageFormat.ImgFormat.RGB888 -> pixels(image, alpha = false)
        ImageFormat.ImgFormat.RGBA8888 -> pixels(image, alpha = true)
        else ->
          if (image === original) png
          else ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
      }
    val format =
      ImageFormat.newBuilder(request).setWidth(image.width).setHeight(image.height).clearTransport()
    val builder =
      Image.newBuilder()
        .setSeq(seq)
        .setTimestampUs(System.nanoTime() / 1000)
        .setWidth(image.width)
        .setHeight(image.height)
    val mapped = mmap(request.transport, bytes)
    if (mapped) format.setTransport(request.transport)
    else builder.setImage(ByteString.copyFrom(bytes))
    return Frame(png, builder.setFormat(format).build())
  }

  /** Writes [bytes] into the shared-memory file a local client named, when it named a file. */
  private fun mmap(transport: ImageTransport, bytes: ByteArray): Boolean {
    if (transport.channel != ImageTransport.TransportChannel.MMAP) return false
    val file =
      runCatching { File(URI(transport.handle)) }.getOrNull()?.takeIf { it.exists() }
        ?: return false
    RandomAccessFile(file, "rw").use { raf ->
      if (raf.length() < bytes.size) return false
      raf.channel
        .map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, bytes.size.toLong())
        .put(bytes)
    }
    return true
  }

  private fun <T> answer(response: StreamObserver<T>, body: () -> T) {
    try {
      response.onNext(body())
      response.onCompleted()
    } catch (e: UnsupportedOperationException) {
      response.onError(Status.UNIMPLEMENTED.withDescription(e.message).asException())
    } catch (e: Exception) {
      response.onError(Status.INTERNAL.withDescription(e.message ?: e.toString()).asException())
    }
  }

  companion object {
    /** Reported as the emulator version: Studio gates features on it, and this is a recent one. */
    const val VERSION = "36.1.9.0 (build_id compose-preview-simulator)"

    private const val FRAME_INTERVAL_MS = 66L

    private val AUTHORIZATION: Metadata.Key<String> =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    /** W3C `KeyboardEvent.key` values Studio sends, as simulator key names. */
    val KEYS =
      mapOf(
        "Enter" to "ENTER",
        "Backspace" to "DEL",
        "Delete" to "FORWARD_DEL",
        "Tab" to "TAB",
        "Escape" to "ESCAPE",
        "ArrowUp" to "DPAD_UP",
        "ArrowDown" to "DPAD_DOWN",
        "ArrowLeft" to "DPAD_LEFT",
        "ArrowRight" to "DPAD_RIGHT",
        "PageUp" to "PAGE_UP",
        "PageDown" to "PAGE_DOWN",
        "Home" to "MOVE_HOME",
        "End" to "MOVE_END",
        "GoHome" to "HOME",
        "GoBack" to "BACK",
        "AppSwitch" to "APP_SWITCH",
        "Power" to "POWER",
        "AudioVolumeUp" to "VOLUME_UP",
        "AudioVolumeDown" to "VOLUME_DOWN",
        "AudioVolumeMute" to "VOLUME_MUTE",
        "ContextMenu" to "MENU",
        "Menu" to "MENU",
        "Sleep" to "SLEEP",
        "WakeUp" to "WAKEUP",
        "STEM_PRIMARY" to "STEM_PRIMARY",
        "STEM_1" to "STEM_1",
        "STEM_2" to "STEM_2",
      )

    fun scaleToFit(image: BufferedImage, maxWidth: Int, maxHeight: Int): BufferedImage {
      val scale = minOf(maxWidth.toDouble() / image.width, maxHeight.toDouble() / image.height, 1.0)
      if (scale >= 1.0) return image
      val w = maxOf(1, (image.width * scale).toInt())
      val h = maxOf(1, (image.height * scale).toInt())
      val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
      val g = scaled.createGraphics()
      g.setRenderingHint(
        java.awt.RenderingHints.KEY_INTERPOLATION,
        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      g.drawImage(image, 0, 0, w, h, null)
      g.dispose()
      return scaled
    }

    /** Packed RGB888 or RGBA8888, rows top to bottom. */
    fun pixels(image: BufferedImage, alpha: Boolean): ByteArray {
      val stride = if (alpha) 4 else 3
      val out = ByteArray(image.width * image.height * stride)
      var i = 0
      for (y in 0 until image.height) {
        for (x in 0 until image.width) {
          val argb = image.getRGB(x, y)
          out[i++] = (argb shr 16).toByte()
          out[i++] = (argb shr 8).toByte()
          out[i++] = argb.toByte()
          if (alpha) out[i++] = (argb ushr 24).toByte()
        }
      }
      return out
    }
  }
}
