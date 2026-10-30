package ee.schimke.composeai.cli.device

import com.android.emulator.control.EmulatorControllerGrpc
import com.android.emulator.control.EmulatorStatus
import com.google.protobuf.Empty
import io.grpc.CompressorRegistry
import io.grpc.DecompressorRegistry
import io.grpc.ManagedChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class IdentityEncodingTest {

  private val controller =
    object : EmulatorControllerGrpc.EmulatorControllerImplBase() {
      override fun getStatus(request: Empty, response: StreamObserver<EmulatorStatus>) {
        response.onNext(EmulatorStatus.newBuilder().setVersion("1").build())
        response.onCompleted()
      }
    }

  @Test
  fun `a channel built as Android Studio builds it reads the answers`() {
    val server =
      IdentityEncoding.install(NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0)))
        .addService(controller)
        .build()
        .start()
    // EmulatorController's channel: no compressors, and no decompressors, not even identity.
    val channel =
      ManagedChannelBuilder.forAddress("127.0.0.1", server.port)
        .usePlaintext()
        .compressorRegistry(CompressorRegistry.newEmptyInstance())
        .decompressorRegistry(DecompressorRegistry.emptyInstance())
        .build()
    try {
      val status =
        EmulatorControllerGrpc.newBlockingStub(channel).getStatus(Empty.getDefaultInstance())
      assertEquals("1", status.version)
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }
  }
}
