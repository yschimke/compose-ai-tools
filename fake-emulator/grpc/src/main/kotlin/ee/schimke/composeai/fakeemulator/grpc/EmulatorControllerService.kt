package ee.schimke.composeai.fakeemulator.grpc

import android.emulation.control.DisplayConfiguration
import android.emulation.control.DisplayConfigurations
import android.emulation.control.EmulatorStatus
import android.emulation.control.Image
import android.emulation.control.ImageFormat
import android.emulation.control.KeyboardEvent
import android.emulation.control.TouchEvent
import android.emulation.control.VmRunState
import ee.schimke.composeai.fakeemulator.EmulatorFrame
import ee.schimke.composeai.fakeemulator.FrameSource
import ee.schimke.composeai.fakeemulator.PlaceholderImage
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import okio.ByteString.Companion.toByteString

/**
 * The fake emulator's `android.emulation.control.EmulatorController` service, bound by hand into
 * grpc-java (Wire generates only the messages — see [WireMarshaller]). Identity/liveness + display
 * geometry are synthesised from the [FrameSource]; `getScreenshot` and the `streamScreenshot`
 * "video" lane are served straight off it (the same frames the ADB `screencap` path serves).
 * Key/touch are forwarded to the supplied callbacks.
 */
class EmulatorControllerService(
  private val frameSource: FrameSource,
  private val startNanos: Long = System.nanoTime(),
  private val onKey: (KeyboardEvent) -> Unit = {},
  private val onTouch: (TouchEvent) -> Unit = {},
) {
  fun bindService(): ServerServiceDefinition =
    ServerServiceDefinition.builder(SERVICE_NAME)
      .addMethod(
        getStatusMethod,
        ServerCalls.asyncUnaryCall { _: Unit, observer: StreamObserver<EmulatorStatus> ->
          observer.completeWith(status())
        },
      )
      .addMethod(
        getVmStateMethod,
        ServerCalls.asyncUnaryCall { _: Unit, observer: StreamObserver<VmRunState> ->
          observer.completeWith(VmRunState(state = VmRunState.RunState.RUNNING))
        },
      )
      .addMethod(
        setVmStateMethod,
        ServerCalls.asyncUnaryCall { _: VmRunState, observer: StreamObserver<Unit> ->
          observer.completeWith(Unit)
        },
      )
      .addMethod(
        getDisplayConfigurationsMethod,
        ServerCalls.asyncUnaryCall { _: Unit, observer: StreamObserver<DisplayConfigurations> ->
          observer.completeWith(displayConfigurations())
        },
      )
      .addMethod(
        getScreenshotMethod,
        ServerCalls.asyncUnaryCall { _: ImageFormat, observer: StreamObserver<Image> ->
          observer.completeWith(currentImage())
        },
      )
      .addMethod(
        streamScreenshotMethod,
        ServerCalls.asyncServerStreamingCall { _: ImageFormat, observer: StreamObserver<Image> ->
          val serverObserver = observer as ServerCallStreamObserver<Image>
          val handle = frameSource.subscribe { frame ->
            if (!serverObserver.isCancelled) runCatching { observer.onNext(toImage(frame)) }
          }
          serverObserver.setOnCancelHandler { handle.close() }
        },
      )
      .addMethod(
        sendKeyMethod,
        ServerCalls.asyncUnaryCall { request: KeyboardEvent, observer: StreamObserver<Unit> ->
          onKey(request)
          observer.completeWith(Unit)
        },
      )
      .addMethod(
        sendTouchMethod,
        ServerCalls.asyncUnaryCall { request: TouchEvent, observer: StreamObserver<Unit> ->
          onTouch(request)
          observer.completeWith(Unit)
        },
      )
      .build()

  private fun status(): EmulatorStatus =
    EmulatorStatus(
      uptime = (System.nanoTime() - startNanos) / 1_000_000,
      booted = true,
      hardwareConfig =
        mapOf(
          "hw.lcd.width" to frameSource.display.width.toString(),
          "hw.lcd.height" to frameSource.display.height.toString(),
          "hw.lcd.density" to frameSource.display.densityDpi.toString(),
        ),
    )

  private fun displayConfigurations(): DisplayConfigurations =
    DisplayConfigurations(
      displays =
        listOf(
          DisplayConfiguration(
            width = frameSource.display.width,
            height = frameSource.display.height,
            dpi = frameSource.display.densityDpi,
            display = 0,
          )
        ),
      maxDisplays = 1,
    )

  private fun currentImage(): Image {
    val frame = frameSource.latest()
    if (frame != null) return toImage(frame)
    val png =
      PlaceholderImage.solidPng(
        frameSource.display.width,
        frameSource.display.height,
        0xFF202124.toInt(),
      )
    return toImage(EmulatorFrame(frameSource.display.width, frameSource.display.height, png, 0))
  }

  private fun toImage(frame: EmulatorFrame): Image =
    Image(
      format =
        ImageFormat(
          format = ImageFormat.ImgFormat.PNG,
          width = frame.width,
          height = frame.height,
          display = 0,
        ),
      seq = frame.seq.toInt(),
      image = frame.png.toByteString(),
      timestampUs = System.nanoTime() / 1_000,
    )

  private companion object {
    const val SERVICE_NAME = "android.emulation.control.EmulatorController"

    private fun <Req : Any, Resp : Any> unary(
      name: String,
      reqMarshaller: MethodDescriptor.Marshaller<Req>,
      respMarshaller: MethodDescriptor.Marshaller<Resp>,
    ): MethodDescriptor<Req, Resp> =
      MethodDescriptor.newBuilder(reqMarshaller, respMarshaller)
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, name))
        .build()

    val getStatusMethod =
      unary("getStatus", EmptyMarshaller, WireMarshaller(EmulatorStatus.ADAPTER))
    val getVmStateMethod = unary("getVmState", EmptyMarshaller, WireMarshaller(VmRunState.ADAPTER))
    val setVmStateMethod = unary("setVmState", WireMarshaller(VmRunState.ADAPTER), EmptyMarshaller)
    val getDisplayConfigurationsMethod =
      unary(
        "getDisplayConfigurations",
        EmptyMarshaller,
        WireMarshaller(DisplayConfigurations.ADAPTER),
      )
    val getScreenshotMethod =
      unary("getScreenshot", WireMarshaller(ImageFormat.ADAPTER), WireMarshaller(Image.ADAPTER))
    val sendKeyMethod = unary("sendKey", WireMarshaller(KeyboardEvent.ADAPTER), EmptyMarshaller)
    val sendTouchMethod = unary("sendTouch", WireMarshaller(TouchEvent.ADAPTER), EmptyMarshaller)

    val streamScreenshotMethod: MethodDescriptor<ImageFormat, Image> =
      MethodDescriptor.newBuilder(
          WireMarshaller(ImageFormat.ADAPTER),
          WireMarshaller(Image.ADAPTER),
        )
        .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
        .setFullMethodName(
          MethodDescriptor.generateFullMethodName(SERVICE_NAME, "streamScreenshot")
        )
        .build()
  }
}

/** Emit one value and complete — the common unary-response shape. */
private fun <T> StreamObserver<T>.completeWith(value: T) {
  onNext(value)
  onCompleted()
}
