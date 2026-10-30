package ee.schimke.composeai.cli.device

import io.grpc.netty.shaded.io.grpc.netty.InternalProtocolNegotiators
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.channel.ChannelHandler
import io.grpc.netty.shaded.io.netty.channel.ChannelHandlerAdapter
import io.grpc.netty.shaded.io.netty.channel.ChannelHandlerContext
import io.grpc.netty.shaded.io.netty.channel.ChannelOutboundHandlerAdapter
import io.grpc.netty.shaded.io.netty.channel.ChannelPromise
import io.grpc.netty.shaded.io.netty.handler.codec.http2.DefaultHttp2Headers
import io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2Headers
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Answers without `grpc-encoding: identity`, as the emulator's own (C++) gRPC server does.
 *
 * Android Studio's `EmulatorController` builds its channel with an empty `DecompressorRegistry`,
 * which has no `identity` either, and grpc-java's server always names the encoding in its response
 * headers — so every call Studio makes fails with "Can't find decompressor for identity" and
 * Running Devices stays at "Connecting to the Emulator". The header is added inside grpc-java after
 * every interceptor has run, so it is taken out of the response headers on their way to the HTTP/2
 * encoder: a handler at the tail of each connection's pipeline, put there by a protocol negotiator
 * wrapping plaintext. grpc-java keeps both the command and the negotiator type package-private,
 * hence the reflection.
 */
internal object IdentityEncoding {

  private const val PACKAGE = "io.grpc.netty.shaded.io.grpc.netty"

  private val command = Class.forName("$PACKAGE.SendResponseHeadersCommand")
  private val headersOf = command.declared("headers")
  private val streamOf = command.declared("stream")
  private val endOfStream = command.declared("endOfStream")
  private val createHeaders =
    command.declared(
      "createHeaders",
      Class.forName("$PACKAGE.StreamIdHolder"),
      Http2Headers::class.java,
    )

  /**
   * Serves [builder]'s connections in plaintext with responses stripped of the identity encoding.
   */
  fun install(builder: NettyServerBuilder): NettyServerBuilder {
    val negotiatorType = Class.forName("$PACKAGE.ProtocolNegotiator")
    val plaintext = InternalProtocolNegotiators.serverPlaintext()
    val negotiator =
      Proxy.newProxyInstance(
        negotiatorType.classLoader,
        arrayOf(Class.forName("$PACKAGE.InternalProtocolNegotiator\$ProtocolNegotiator")),
      ) { _, method, args ->
        method.isAccessible = true
        val result =
          try {
            method.invoke(plaintext, *(args ?: emptyArray()))
          } catch (e: InvocationTargetException) {
            throw e.targetException
          }
        if (method.name == "newHandler") withStripper(result as ChannelHandler) else result
      }
    NettyServerBuilder::class
      .java
      .getMethod("protocolNegotiator", negotiatorType)
      .invoke(builder, negotiator)
    return builder
  }

  /** The negotiation handler, with [Stripper] added at the pipeline's tail beside it. */
  private fun withStripper(negotiation: ChannelHandler): ChannelHandler =
    object : ChannelHandlerAdapter() {
      override fun handlerAdded(ctx: ChannelHandlerContext) {
        ctx.pipeline().addAfter(ctx.name(), null, negotiation)
        ctx.pipeline().addLast(Stripper)
        ctx.pipeline().remove(this)
      }
    }

  @ChannelHandler.Sharable
  private object Stripper : ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
      ctx.write(if (command.isInstance(msg)) strip(msg) else msg, promise)
    }
  }

  /** The response headers without `grpc-encoding: identity`; trailers pass as they are. */
  private fun strip(msg: Any): Any {
    if (endOfStream.invoke(msg) as Boolean) return msg
    // grpc's outbound headers only iterate (no get or remove), reusing one entry as they go.
    val headers =
      (headersOf.invoke(msg) as Http2Headers).map { (name, value) ->
        name.toString() to value.toString()
      }
    val identity = headers.any { (name, value) -> isEncoding(name) && value == "identity" }
    if (!identity) return msg
    val stripped = DefaultHttp2Headers(false)
    for ((name, value) in headers) if (!isEncoding(name)) stripped.add(name, value)
    return createHeaders.invoke(null, streamOf.invoke(msg), stripped)
  }

  private fun isEncoding(name: CharSequence) =
    name.toString().equals("grpc-encoding", ignoreCase = true)

  private fun Class<*>.declared(name: String, vararg types: Class<*>): Method =
    getDeclaredMethod(name, *types).apply { isAccessible = true }
}
