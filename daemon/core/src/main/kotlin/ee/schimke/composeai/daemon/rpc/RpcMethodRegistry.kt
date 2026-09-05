package ee.schimke.composeai.daemon.rpc

import ee.schimke.composeai.daemon.protocol.JsonRpcRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The slice of the JSON-RPC connection a method handler needs: decode the request's params, encode
 * a result, and put exactly one response frame on the wire.
 *
 * Handlers live beside the feature they serve (`history/…` in
 * [ee.schimke.composeai.daemon.history], and so on) rather than inside `JsonRpcServer`, so they get
 * the connection through this port instead of through the server's private members. It is
 * deliberately narrow — nothing here exposes the render queue, the notification surface or the
 * server's lifecycle; a handler that needs one of those takes it as its own constructor parameter.
 *
 * Implemented by `JsonRpcServer` itself; the members mirror the private helpers that predated this
 * interface, so the extracted handlers behave byte-for-byte like the `when`-arm bodies they came
 * from.
 */
public interface RpcPeer {
  /** The server's configured [Json] — `ignoreUnknownKeys`, `encodeDefaults = false`. */
  public val json: Json

  /** Writes a success response for [id]. Exactly one response per request. */
  public fun sendResponse(id: Long, result: JsonElement)

  /** Writes an error response. [id] is null only for frames whose id could not be parsed. */
  public fun sendErrorResponse(id: Long?, code: Int, message: String)

  /** Decodes [params] with [serializer], treating an absent params object as `{}`. */
  public fun <T> decodeParams(params: JsonElement?, serializer: KSerializer<T>): T

  /** Encodes [value] with [serializer] for use as a response result. */
  public fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement
}

/**
 * One JSON-RPC method's handler. Owns the whole request: decoding params, doing the work, and
 * sending exactly one response (success or error) through [RpcPeer].
 */
public fun interface RpcMethodHandler {
  public fun handle(req: JsonRpcRequest)
}

/**
 * Method-name → handler map for the daemon's request surface, the dispatch counterpart of
 * [ee.schimke.composeai.daemon.ExtensionRegistry] and friends: a feature registers the methods it
 * serves at construction time, and `JsonRpcServer.handleRequest` becomes a lookup plus a call
 * instead of an arm in an ever-growing `when` (issue #5166).
 *
 * Immutable once built. Registration is a build-time concern — a method whose availability depends
 * on a feature flag or an injected collaborator registers a handler that replies with the
 * appropriate error rather than being left out, so an unregistered method always means "method not
 * found".
 */
public class RpcMethodRegistry
private constructor(private val handlers: Map<String, RpcMethodHandler>) {

  /** The method names this registry dispatches, for `initialize` capability reporting and tests. */
  public val methods: Set<String>
    get() = handlers.keys

  /** The handler for [method], or null when nothing claims it (→ `method not found`). */
  public fun handler(method: String): RpcMethodHandler? = handlers[method]

  /** Accumulates registrations. Rejects a duplicate method name rather than silently shadowing. */
  public class Builder {
    private val handlers = LinkedHashMap<String, RpcMethodHandler>()

    public fun register(method: String, handler: RpcMethodHandler): Builder {
      val prior = handlers.put(method, handler)
      require(prior == null) { "Duplicate RPC method handler for '$method'" }
      return this
    }

    internal fun build(): RpcMethodRegistry = RpcMethodRegistry(LinkedHashMap(handlers))
  }

  public companion object {
    public fun build(block: Builder.() -> Unit): RpcMethodRegistry = Builder().apply(block).build()
  }
}
