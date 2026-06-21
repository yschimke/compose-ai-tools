package ee.schimke.composeai.fakeemulator.grpc

import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerServiceDefinition

/**
 * Hosts the emulator-control [ServerServiceDefinition] on a gRPC port (grpc-netty-shaded). Bind to
 * port 0 for an ephemeral port and read it back from [boundPort] (record it in the Studio discovery
 * file so the embedded-emulator catalog can reach the control endpoint).
 */
class FakeEmulatorGrpcServer(
  private val requestedPort: Int,
  private val service: ServerServiceDefinition,
) : AutoCloseable {
  private lateinit var server: Server

  val boundPort: Int
    get() = server.port

  fun start(): FakeEmulatorGrpcServer {
    server = ServerBuilder.forPort(requestedPort).addService(service).build().start()
    return this
  }

  fun awaitTermination() {
    server.awaitTermination()
  }

  override fun close() {
    if (::server.isInitialized) server.shutdownNow()
  }
}
