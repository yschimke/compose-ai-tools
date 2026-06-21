package ee.schimke.composeai.fakeemulator.grpc

import android.emulation.control.AudioFormat
import android.emulation.control.AudioPacket
import android.emulation.control.BatteryState
import android.emulation.control.ClipData
import android.emulation.control.DisplayConfiguration
import android.emulation.control.DisplayConfigurations
import android.emulation.control.EmulatorStatus
import android.emulation.control.Entry
import android.emulation.control.EntryList
import android.emulation.control.Fingerprint
import android.emulation.control.GpsState
import android.emulation.control.Image
import android.emulation.control.ImageFormat
import android.emulation.control.KeyboardEvent
import android.emulation.control.LogMessage
import android.emulation.control.MouseEvent
import android.emulation.control.Notification
import android.emulation.control.ParameterValue
import android.emulation.control.PhoneCall
import android.emulation.control.PhoneResponse
import android.emulation.control.PhysicalModelValue
import android.emulation.control.Posture
import android.emulation.control.RotationRadian
import android.emulation.control.SensorValue
import android.emulation.control.SmsMessage
import android.emulation.control.TouchEvent
import android.emulation.control.Velocity
import android.emulation.control.VmRunState
import ee.schimke.composeai.fakeemulator.DevicePosture
import ee.schimke.composeai.fakeemulator.DeviceSettingsController
import ee.schimke.composeai.fakeemulator.EmulatorFrame
import ee.schimke.composeai.fakeemulator.FrameSource
import ee.schimke.composeai.fakeemulator.PlaceholderImage
import ee.schimke.composeai.fakeemulator.RotationQuadrant
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import okio.ByteString.Companion.toByteString

/**
 * The fake emulator's `android.emulation.control.EmulatorController` service, bound by hand into
 * grpc-java (Wire generates only the messages — see [WireMarshaller]). Implements the surface
 * Android Studio's embedded "Running Devices" view + Extended Controls drive:
 *
 * - **Display + screenshots** — geometry from the [FrameSource]; `getScreenshot` and the
 *   `streamScreenshot` "video" lane serve its frames.
 * - **Input** — `sendKey` / `sendTouch` / `sendMouse` forward to the supplied callbacks.
 * - **Rotation / posture** — `setPhysicalModel(ROTATION)` and `setPosture` drive the shared
 *   [DeviceSettingsController], so the preview re-renders rotated/folded; `streamPhysicalModel` /
 *   `streamNotification` emit when that state (or an `adb`-driven one) changes.
 * - **Clipboard, battery, GPS, sensors** — in-memory state with get/set/stream.
 * - **Telephony, fingerprint, audio, logcat, virtual-scene camera** — accepted with sensible
 *   defaults / no-ops so Studio never hits `UNIMPLEMENTED`.
 *
 * [close] detaches the settings listener.
 */
class EmulatorControllerService(
  private val frameSource: FrameSource,
  private val settings: DeviceSettingsController,
  private val startNanos: Long = System.nanoTime(),
  private val onKey: (KeyboardEvent) -> Unit = {},
  private val onTouch: (TouchEvent) -> Unit = {},
  private val onMouse: (MouseEvent) -> Unit = {},
) : AutoCloseable {
  private val clipboard = AtomicReference("")
  private val battery = AtomicReference(defaultBattery())
  private val gps = AtomicReference(GpsState())
  private val sensors = ConcurrentHashMap<SensorValue.SensorType, List<Float>>()
  private val physical = ConcurrentHashMap<PhysicalModelValue.PhysicalType, List<Float>>()

  private val clipboardObservers = CopyOnWriteArrayList<StreamObserver<ClipData>>()
  private val notificationObservers = CopyOnWriteArrayList<StreamObserver<Notification>>()
  private val rotationObservers = CopyOnWriteArrayList<StreamObserver<PhysicalModelValue>>()

  // adb- or gRPC-driven rotation/display changes both flow through settings; mirror them onto the
  // physical-model + notification streams Studio listens on.
  private val settingsSubscription = settings.addListener {
    val update = rotationModel(it.rotation)
    rotationObservers.forEach { obs -> runCatching { obs.onNext(update) } }
    val notification =
      Notification(event = Notification.EventType.DISPLAY_CONFIGURATIONS_CHANGED_UI)
    notificationObservers.forEach { obs -> runCatching { obs.onNext(notification) } }
  }

  override fun close() {
    settingsSubscription.close()
  }

  fun bindService(): ServerServiceDefinition =
    ServerServiceDefinition.builder(SERVICE_NAME)
      // ---- identity / vm / display ----
      .unary(getStatusMethod) { _: Unit -> status() }
      .unary(getVmStateMethod) { _: Unit -> VmRunState(state = VmRunState.RunState.RUNNING) }
      .unary(setVmStateMethod) { _: VmRunState -> Unit }
      .unary(getDisplayConfigurationsMethod) { _: Unit -> displayConfigurations() }
      .unary(setDisplayConfigurationsMethod) { request: DisplayConfigurations ->
        request.displays.firstOrNull()?.let { d ->
          settings.update {
            it.copy(
              widthPx = d.width.takeIf { v -> v > 0 } ?: it.widthPx,
              heightPx = d.height.takeIf { v -> v > 0 } ?: it.heightPx,
              densityDpi = d.dpi.takeIf { v -> v > 0 } ?: it.densityDpi,
            )
          }
        }
        displayConfigurations()
      }
      // ---- screenshots ----
      .unary(getScreenshotMethod) { _: ImageFormat -> currentImage() }
      .serverStream(streamScreenshotMethod) { _: ImageFormat, observer ->
        val server = observer as ServerCallStreamObserver<Image>
        val handle = frameSource.subscribe { frame ->
          if (!server.isCancelled) runCatching { observer.onNext(toImage(frame)) }
        }
        server.setOnCancelHandler { handle.close() }
      }
      // ---- input ----
      .unary(sendKeyMethod) { request: KeyboardEvent -> onKey(request) }
      .unary(sendTouchMethod) { request: TouchEvent -> onTouch(request) }
      .unary(sendMouseMethod) { request: MouseEvent -> onMouse(request) }
      // ---- sensors / physical model / posture ----
      .unary(getSensorMethod) { request: SensorValue ->
        SensorValue(
          target = request.target,
          status = SensorValue.State.OK,
          value_ = ParameterValue(data_ = sensors[request.target] ?: emptyList()),
        )
      }
      .unary(setSensorMethod) { request: SensorValue ->
        sensors[request.target] = request.value_?.data_ ?: emptyList()
      }
      .serverStream(streamSensorMethod) { request: SensorValue, observer ->
        observer.onNext(
          SensorValue(
            target = request.target,
            status = SensorValue.State.OK,
            value_ = ParameterValue(data_ = sensors[request.target] ?: emptyList()),
          )
        )
        // No change source for raw sensors — leave the stream open until the client cancels.
      }
      .unary(getPhysicalModelMethod) { request: PhysicalModelValue ->
        physicalModel(request.target)
      }
      .unary(setPhysicalModelMethod) { request: PhysicalModelValue ->
        val data = request.value_?.data_ ?: emptyList()
        physical[request.target] = data
        if (request.target == PhysicalModelValue.PhysicalType.ROTATION) {
          // value = [x, y, z] angles in degrees; z is the screen rotation about the view axis.
          val z = data.getOrNull(2) ?: 0f
          settings.update {
            it.copy(rotation = RotationQuadrant.fromUserRotation(Math.round(z / 90f)))
          }
        }
      }
      .serverStream(streamPhysicalModelMethod) { request: PhysicalModelValue, observer ->
        val server = observer as ServerCallStreamObserver<PhysicalModelValue>
        observer.onNext(physicalModel(request.target))
        if (request.target == PhysicalModelValue.PhysicalType.ROTATION) {
          rotationObservers.add(observer)
          server.setOnCancelHandler { rotationObservers.remove(observer) }
        }
      }
      .unary(setPostureMethod) { request: Posture ->
        settings.update { it.copy(posture = posture(request.value_)) }
      }
      // ---- clipboard ----
      .unary(getClipboardMethod) { _: Unit -> ClipData(text = clipboard.get()) }
      .unary(setClipboardMethod) { request: ClipData ->
        clipboard.set(request.text)
        clipboardObservers.forEach { runCatching { it.onNext(request) } }
      }
      .serverStream(streamClipboardMethod) { _: Unit, observer ->
        val server = observer as ServerCallStreamObserver<ClipData>
        observer.onNext(ClipData(text = clipboard.get()))
        clipboardObservers.add(observer)
        server.setOnCancelHandler { clipboardObservers.remove(observer) }
      }
      // ---- extended controls ----
      .unary(getBatteryMethod) { _: Unit -> battery.get() }
      .unary(setBatteryMethod) { request: BatteryState -> battery.set(request) }
      .unary(getGpsMethod) { _: Unit -> gps.get() }
      .unary(setGpsMethod) { request: GpsState -> gps.set(request) }
      .unary(sendFingerprintMethod) { _: Fingerprint -> Unit }
      .unary(sendPhoneMethod) { _: PhoneCall ->
        PhoneResponse(response = PhoneResponse.Response.OK)
      }
      .unary(sendSmsMethod) { _: SmsMessage -> PhoneResponse(response = PhoneResponse.Response.OK) }
      // ---- notifications ----
      .serverStream(streamNotificationMethod) { _: Unit, observer ->
        val server = observer as ServerCallStreamObserver<Notification>
        notificationObservers.add(observer)
        server.setOnCancelHandler { notificationObservers.remove(observer) }
      }
      // ---- audio / logcat / virtual scene (accepted; minimal behaviour) ----
      .serverStream(streamAudioMethod) { _: AudioFormat, _ ->
        // No audio source — keep the stream open until the client cancels.
      }
      .clientStream(injectAudioMethod) { responseObserver ->
        object : StreamObserver<AudioPacket> {
          override fun onNext(value: AudioPacket) {}

          override fun onError(t: Throwable) {}

          override fun onCompleted() {
            responseObserver.onNext(Unit)
            responseObserver.onCompleted()
          }
        }
      }
      .unary(getLogcatMethod) { request: LogMessage ->
        LogMessage(
          contents = "",
          start = request.start,
          next = request.start,
          sort = LogMessage.LogType.Text,
        )
      }
      .serverStream(streamLogcatMethod) { _: LogMessage, _ ->
        // No serial-console buffer — keep open until cancel.
      }
      .unary(rotateVirtualSceneCameraMethod) { _: RotationRadian -> Unit }
      .unary(setVirtualSceneCameraVelocityMethod) { _: Velocity -> Unit }
      .build()

  // ---- builders --------------------------------------------------------------------------------

  private fun status(): EmulatorStatus =
    EmulatorStatus(
      version = "compose-preview-fake-emulator",
      uptime = (System.nanoTime() - startNanos) / 1_000_000,
      booted = true,
      hardwareConfig =
        EntryList(
          entry =
            listOf(
              Entry(key = "hw.lcd.width", value_ = displayWidth().toString()),
              Entry(key = "hw.lcd.height", value_ = displayHeight().toString()),
              Entry(key = "hw.lcd.density", value_ = displayDensity().toString()),
            )
        ),
    )

  private fun displayConfigurations(): DisplayConfigurations =
    DisplayConfigurations(
      displays =
        listOf(
          DisplayConfiguration(
            width = displayWidth(),
            height = displayHeight(),
            dpi = displayDensity(),
            display = 0,
          )
        )
    )

  private fun physicalModel(target: PhysicalModelValue.PhysicalType): PhysicalModelValue =
    if (target == PhysicalModelValue.PhysicalType.ROTATION) {
      rotationModel(settings.current.rotation)
    } else {
      PhysicalModelValue(
        target = target,
        status = PhysicalModelValue.State.OK,
        value_ = ParameterValue(data_ = physical[target] ?: emptyList()),
      )
    }

  private fun rotationModel(rotation: RotationQuadrant): PhysicalModelValue =
    PhysicalModelValue(
      target = PhysicalModelValue.PhysicalType.ROTATION,
      status = PhysicalModelValue.State.OK,
      value_ = ParameterValue(data_ = listOf(0f, 0f, rotation.degrees.toFloat())),
    )

  private fun displayWidth(): Int = settings.current.widthPx ?: frameSource.display.width

  private fun displayHeight(): Int = settings.current.heightPx ?: frameSource.display.height

  private fun displayDensity(): Int = settings.current.densityDpi ?: frameSource.display.densityDpi

  private fun currentImage(): Image {
    val frame = frameSource.latest()
    if (frame != null) return toImage(frame)
    val png = PlaceholderImage.solidPng(displayWidth(), displayHeight(), 0xFF202124.toInt())
    return toImage(EmulatorFrame(displayWidth(), displayHeight(), png, 0))
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
      image = frame.png.toByteString(),
      seq = frame.seq.toInt(),
      timestampUs = System.nanoTime() / 1_000,
    )

  private companion object {
    const val SERVICE_NAME = "android.emulation.control.EmulatorController"

    fun defaultBattery(): BatteryState =
      BatteryState(
        hasBattery = true,
        isPresent = true,
        charger = BatteryState.BatteryCharger.AC,
        chargeLevel = 100,
        health = BatteryState.BatteryHealth.GOOD,
        status = BatteryState.BatteryStatus.FULL,
      )

    fun posture(value: Posture.PostureValue): DevicePosture =
      when (value) {
        Posture.PostureValue.POSTURE_CLOSED -> DevicePosture.CLOSED
        Posture.PostureValue.POSTURE_HALF_OPENED -> DevicePosture.HALF_OPENED
        Posture.PostureValue.POSTURE_OPENED -> DevicePosture.OPENED
        Posture.PostureValue.POSTURE_FLIPPED -> DevicePosture.FLIPPED
        Posture.PostureValue.POSTURE_TENT -> DevicePosture.TENT
        else -> DevicePosture.UNKNOWN
      }

    // -- method descriptors --
    private fun <Req : Any, Resp : Any> unary(
      name: String,
      req: MethodDescriptor.Marshaller<Req>,
      resp: MethodDescriptor.Marshaller<Resp>,
    ): MethodDescriptor<Req, Resp> = method(name, MethodDescriptor.MethodType.UNARY, req, resp)

    private fun <Req : Any, Resp : Any> serverStreaming(
      name: String,
      req: MethodDescriptor.Marshaller<Req>,
      resp: MethodDescriptor.Marshaller<Resp>,
    ): MethodDescriptor<Req, Resp> =
      method(name, MethodDescriptor.MethodType.SERVER_STREAMING, req, resp)

    private fun <Req : Any, Resp : Any> clientStreaming(
      name: String,
      req: MethodDescriptor.Marshaller<Req>,
      resp: MethodDescriptor.Marshaller<Resp>,
    ): MethodDescriptor<Req, Resp> =
      method(name, MethodDescriptor.MethodType.CLIENT_STREAMING, req, resp)

    private fun <Req : Any, Resp : Any> method(
      name: String,
      type: MethodDescriptor.MethodType,
      req: MethodDescriptor.Marshaller<Req>,
      resp: MethodDescriptor.Marshaller<Resp>,
    ): MethodDescriptor<Req, Resp> =
      MethodDescriptor.newBuilder(req, resp)
        .setType(type)
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
    val setDisplayConfigurationsMethod =
      unary(
        "setDisplayConfigurations",
        WireMarshaller(DisplayConfigurations.ADAPTER),
        WireMarshaller(DisplayConfigurations.ADAPTER),
      )
    val getScreenshotMethod =
      unary("getScreenshot", WireMarshaller(ImageFormat.ADAPTER), WireMarshaller(Image.ADAPTER))
    val streamScreenshotMethod =
      serverStreaming(
        "streamScreenshot",
        WireMarshaller(ImageFormat.ADAPTER),
        WireMarshaller(Image.ADAPTER),
      )
    val sendKeyMethod = unary("sendKey", WireMarshaller(KeyboardEvent.ADAPTER), EmptyMarshaller)
    val sendTouchMethod = unary("sendTouch", WireMarshaller(TouchEvent.ADAPTER), EmptyMarshaller)
    val sendMouseMethod = unary("sendMouse", WireMarshaller(MouseEvent.ADAPTER), EmptyMarshaller)
    val getSensorMethod =
      unary("getSensor", WireMarshaller(SensorValue.ADAPTER), WireMarshaller(SensorValue.ADAPTER))
    val setSensorMethod = unary("setSensor", WireMarshaller(SensorValue.ADAPTER), EmptyMarshaller)
    val streamSensorMethod =
      serverStreaming(
        "streamSensor",
        WireMarshaller(SensorValue.ADAPTER),
        WireMarshaller(SensorValue.ADAPTER),
      )
    val getPhysicalModelMethod =
      unary(
        "getPhysicalModel",
        WireMarshaller(PhysicalModelValue.ADAPTER),
        WireMarshaller(PhysicalModelValue.ADAPTER),
      )
    val setPhysicalModelMethod =
      unary("setPhysicalModel", WireMarshaller(PhysicalModelValue.ADAPTER), EmptyMarshaller)
    val streamPhysicalModelMethod =
      serverStreaming(
        "streamPhysicalModel",
        WireMarshaller(PhysicalModelValue.ADAPTER),
        WireMarshaller(PhysicalModelValue.ADAPTER),
      )
    val setPostureMethod = unary("setPosture", WireMarshaller(Posture.ADAPTER), EmptyMarshaller)
    val getClipboardMethod =
      unary("getClipboard", EmptyMarshaller, WireMarshaller(ClipData.ADAPTER))
    val setClipboardMethod =
      unary("setClipboard", WireMarshaller(ClipData.ADAPTER), EmptyMarshaller)
    val streamClipboardMethod =
      serverStreaming("streamClipboard", EmptyMarshaller, WireMarshaller(ClipData.ADAPTER))
    val getBatteryMethod =
      unary("getBattery", EmptyMarshaller, WireMarshaller(BatteryState.ADAPTER))
    val setBatteryMethod =
      unary("setBattery", WireMarshaller(BatteryState.ADAPTER), EmptyMarshaller)
    val getGpsMethod = unary("getGps", EmptyMarshaller, WireMarshaller(GpsState.ADAPTER))
    val setGpsMethod = unary("setGps", WireMarshaller(GpsState.ADAPTER), EmptyMarshaller)
    val sendFingerprintMethod =
      unary("sendFingerprint", WireMarshaller(Fingerprint.ADAPTER), EmptyMarshaller)
    val sendPhoneMethod =
      unary("sendPhone", WireMarshaller(PhoneCall.ADAPTER), WireMarshaller(PhoneResponse.ADAPTER))
    val sendSmsMethod =
      unary("sendSms", WireMarshaller(SmsMessage.ADAPTER), WireMarshaller(PhoneResponse.ADAPTER))
    val streamNotificationMethod =
      serverStreaming("streamNotification", EmptyMarshaller, WireMarshaller(Notification.ADAPTER))
    val streamAudioMethod =
      serverStreaming(
        "streamAudio",
        WireMarshaller(AudioFormat.ADAPTER),
        WireMarshaller(AudioPacket.ADAPTER),
      )
    val injectAudioMethod =
      clientStreaming("injectAudio", WireMarshaller(AudioPacket.ADAPTER), EmptyMarshaller)
    val getLogcatMethod =
      unary("getLogcat", WireMarshaller(LogMessage.ADAPTER), WireMarshaller(LogMessage.ADAPTER))
    val streamLogcatMethod =
      serverStreaming(
        "streamLogcat",
        WireMarshaller(LogMessage.ADAPTER),
        WireMarshaller(LogMessage.ADAPTER),
      )
    val rotateVirtualSceneCameraMethod =
      unary("rotateVirtualSceneCamera", WireMarshaller(RotationRadian.ADAPTER), EmptyMarshaller)
    val setVirtualSceneCameraVelocityMethod =
      unary("setVirtualSceneCameraVelocity", WireMarshaller(Velocity.ADAPTER), EmptyMarshaller)
  }
}

/**
 * Register a unary handler that returns a response value (Unit response = the Empty marshaller).
 */
private fun <Req : Any, Resp : Any> ServerServiceDefinition.Builder.unary(
  method: MethodDescriptor<Req, Resp>,
  handler: (Req) -> Resp,
): ServerServiceDefinition.Builder =
  addMethod(
    method,
    ServerCalls.asyncUnaryCall { request: Req, observer: StreamObserver<Resp> ->
      observer.onNext(handler(request))
      observer.onCompleted()
    },
  )

/** Register a server-streaming handler; the handler keeps the [StreamObserver] and pushes to it. */
private fun <Req : Any, Resp : Any> ServerServiceDefinition.Builder.serverStream(
  method: MethodDescriptor<Req, Resp>,
  handler: (Req, StreamObserver<Resp>) -> Unit,
): ServerServiceDefinition.Builder =
  addMethod(
    method,
    ServerCalls.asyncServerStreamingCall { request: Req, observer -> handler(request, observer) },
  )

/**
 * Register a client-streaming handler; returns the request observer that consumes the client lane.
 */
private fun <Req : Any, Resp : Any> ServerServiceDefinition.Builder.clientStream(
  method: MethodDescriptor<Req, Resp>,
  handler: (StreamObserver<Resp>) -> StreamObserver<Req>,
): ServerServiceDefinition.Builder =
  addMethod(method, ServerCalls.asyncClientStreamingCall { observer -> handler(observer) })
