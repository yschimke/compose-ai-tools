package ee.schimke.composeai.daemon.rpc

import ee.schimke.composeai.daemon.protocol.JsonRpcRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Targeted tests for [RpcMethodRegistry], the map `JsonRpcServer.handleRequest` dispatches through
 * instead of the `when (req.method)` it used to carry (issue #5166). The invariants that used to be
 * upheld by the `when` -- one arm per method, an unmatched method falling through to `method not
 * found` -- now live here, so a feature registering its own handlers can't quietly shadow
 * another's.
 */
class RpcMethodRegistryTest {

  private fun request(method: String) = JsonRpcRequest(id = 1L, method = method, params = null)

  @Test
  fun `handler dispatches to the registered method`() {
    val seen = mutableListOf<String>()
    val registry = RpcMethodRegistry.build { register("history/list") { seen += it.method } }
    registry.handler("history/list")!!.handle(request("history/list"))
    assertEquals(listOf("history/list"), seen)
  }

  @Test
  fun `an unregistered method has no handler -- the caller answers method not found`() {
    val registry = RpcMethodRegistry.build { register("renderNow") {} }
    assertNull(registry.handler("nope"))
  }

  @Test
  fun `registering the same method twice fails loudly rather than shadowing`() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        RpcMethodRegistry.build {
          register("history/list") {}
          register("history/list") {}
        }
      }
    assertTrue(e.message!!.contains("history/list"))
  }

  @Test
  fun `methods reports every registered name`() {
    val registry = RpcMethodRegistry.build {
      register("initialize") {}
      register("shutdown") {}
    }
    assertEquals(setOf("initialize", "shutdown"), registry.methods)
  }
}
