package ee.schimke.composeai.fakeemulator.grpc

import android.emulation.control.ClipData
import android.emulation.control.DisplayConfigurations
import android.emulation.control.EmulatorStatus
import android.emulation.control.Image
import android.emulation.control.ImageFormat
import android.emulation.control.ParameterValue
import android.emulation.control.PhysicalModelValue
import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.fakeemulator.DeviceSettingsController
import ee.schimke.composeai.fakeemulator.DisplaySize
import ee.schimke.composeai.fakeemulator.MutableFrameSource
import ee.schimke.composeai.fakeemulator.PlaceholderImage
import ee.schimke.composeai.fakeemulator.RotationQuadrant
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.ClientCalls
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Exercises the full hand-rolled `EmulatorController` binding end-to-end over grpc's in-process
 * transport (real server + client, Wire marshalling both ways) — proving the method descriptors,
 * marshallers, and the gRPC→[DeviceSettingsController] bridge actually work.
 */
class EmulatorControllerServiceTest {
  private val display = DisplaySize(1080, 2340, 420)
  private val frames = MutableFrameSource(display)
  private val settings = DeviceSettingsController()
  private lateinit var service: EmulatorControllerService
  private lateinit var server: Server
  private lateinit var channel: ManagedChannel

  @Before
  fun setUp() {
    frames.push(PlaceholderImage.solidPng(display.width, display.height, 0xFF101010.toInt()), 1)
    service = EmulatorControllerService(frames, settings)
    val name = InProcessServerBuilder.generateName()
    server =
      InProcessServerBuilder.forName(name)
        .directExecutor()
        .addService(service.bindService())
        .build()
        .start()
    channel = InProcessChannelBuilder.forName(name).directExecutor().build()
  }

  @After
  fun tearDown() {
    runCatching { channel.shutdownNow() }
    runCatching { server.shutdownNow() }
    runCatching { service.close() }
  }

  @Test
  fun `getStatus reports booted`() {
    val status =
      unaryCall(method("getStatus", EmptyMarshaller, WireMarshaller(EmulatorStatus.ADAPTER)), Unit)
    assertThat(status.booted).isTrue()
  }

  @Test
  fun `clipboard round-trips`() {
    unaryCall(
      method("setClipboard", WireMarshaller(ClipData.ADAPTER), EmptyMarshaller),
      ClipData(text = "hi there"),
    )
    val clip =
      unaryCall(method("getClipboard", EmptyMarshaller, WireMarshaller(ClipData.ADAPTER)), Unit)
    assertThat(clip.text).isEqualTo("hi there")
  }

  @Test
  fun `getDisplayConfigurations reflects the frame source`() {
    val configs =
      unaryCall(
        method(
          "getDisplayConfigurations",
          EmptyMarshaller,
          WireMarshaller(DisplayConfigurations.ADAPTER),
        ),
        Unit,
      )
    val d = configs.displays.single()
    assertThat(d.width).isEqualTo(1080)
    assertThat(d.height).isEqualTo(2340)
    assertThat(d.dpi).isEqualTo(420)
  }

  @Test
  fun `setPhysicalModel ROTATION drives the shared settings`() {
    unaryCall(
      method("setPhysicalModel", WireMarshaller(PhysicalModelValue.ADAPTER), EmptyMarshaller),
      PhysicalModelValue(
        target = PhysicalModelValue.PhysicalType.ROTATION,
        value_ = ParameterValue(data_ = listOf(0f, 0f, 90f)),
      ),
    )
    assertThat(settings.current.rotation).isEqualTo(RotationQuadrant.LANDSCAPE)
  }

  @Test
  fun `streamScreenshot emits the current frame`() {
    val descriptor =
      MethodDescriptor.newBuilder(
          WireMarshaller(ImageFormat.ADAPTER),
          WireMarshaller(Image.ADAPTER),
        )
        .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, "streamScreenshot"))
        .build()
    val frames =
      ClientCalls.blockingServerStreamingCall(
        channel,
        descriptor,
        CallOptions.DEFAULT,
        ImageFormat(),
      )
    assertThat(frames.hasNext()).isTrue()
    val image = frames.next()
    assertThat(image.image.size).isGreaterThan(8)
    assertThat(image.format?.format).isEqualTo(ImageFormat.ImgFormat.PNG)
  }

  private fun <Req : Any, Resp : Any> unaryCall(
    method: MethodDescriptor<Req, Resp>,
    request: Req,
  ): Resp = ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT, request)

  private fun <Req : Any, Resp : Any> method(
    name: String,
    req: MethodDescriptor.Marshaller<Req>,
    resp: MethodDescriptor.Marshaller<Resp>,
  ): MethodDescriptor<Req, Resp> =
    MethodDescriptor.newBuilder(req, resp)
      .setType(MethodDescriptor.MethodType.UNARY)
      .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, name))
      .build()

  private companion object {
    const val SERVICE = "android.emulation.control.EmulatorController"
  }
}
