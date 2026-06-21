package ee.schimke.composeai.fakeemulator.grpc

import com.squareup.wire.ProtoAdapter
import io.grpc.MethodDescriptor
import java.io.InputStream

/**
 * A grpc [MethodDescriptor.Marshaller] backed by a Wire [ProtoAdapter]. This is the bridge that
 * lets Wire-generated message classes ride grpc-java's transport: encode/decode is the same
 * protobuf wire format any gRPC peer (including Android Studio's emulator client) speaks.
 */
class WireMarshaller<T : Any>(private val adapter: ProtoAdapter<T>) :
  MethodDescriptor.Marshaller<T> {
  override fun stream(value: T): InputStream = adapter.encode(value).inputStream()

  override fun parse(stream: InputStream): T = adapter.decode(stream.readBytes())
}

/**
 * Marshaller for `google.protobuf.Empty` — a zero-field message that is always zero bytes on the
 * wire. Wire doesn't generate the well-known `Empty` type (we generate messages only), so we model
 * it as [Unit]: encode to no bytes, drain and ignore on decode.
 */
object EmptyMarshaller : MethodDescriptor.Marshaller<Unit> {
  override fun stream(value: Unit): InputStream = ByteArray(0).inputStream()

  override fun parse(stream: InputStream) {
    stream.readBytes()
  }
}
