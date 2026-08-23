package ee.schimke.composeai.daemon

import java.io.DataInputStream
import java.net.URLClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the embedded-player availability gate.
 *
 * The gate exists because two different artifacts now define
 * `ee.schimke.composeai.rcembedded.player.*`: the vendored `:third-party-rc-embedded-player` and —
 * from androidx-main build 16130474 — `androidx.compose.remote:remote-player-compose` itself. Their
 * `ExperimentalRemoteDocumentPlayer` signatures differ, so a class-presence check answers "yes" on
 * a classpath where the call this module compiled to does not resolve, and the render dies with a
 * `NoSuchMethodError` that `serve` treats as fatal for the whole catalog render lane.
 *
 * [`signature pin matches the call site this module compiles`] is the load-bearing one: it reads
 * the compiled call site's own constant pool, so the pinned parameter list cannot silently drift
 * away from what the bytecode actually invokes — including when the vendored player is re-vendored
 * from a newer upstream.
 */
class EmbeddedPlayerAvailabilityTest {

  /** A facade shaped like the real one, for the matching logic itself. */
  @Suppress("FunctionNaming", "unused")
  object FakeFacade {
    @JvmStatic
    fun ExperimentalRemoteDocumentPlayer(document: String, theme: Int, flags: Long) = Unit
  }

  private val fakeParameters = listOf("java.lang.String", "int", "long")

  @Test
  fun `matches a facade declaring exactly the expected parameters`() {
    assertTrue(declaresEntryPoint(FakeFacade::class.java, fakeParameters))
  }

  @Test
  fun `rejects a facade whose parameters are reordered`() {
    // Upstream's drift is exactly this shape: `theme` moved, an extra parameter appended.
    assertFalse(
      declaresEntryPoint(FakeFacade::class.java, listOf("int", "java.lang.String", "long"))
    )
    assertFalse(declaresEntryPoint(FakeFacade::class.java, fakeParameters + "boolean"))
    assertFalse(declaresEntryPoint(FakeFacade::class.java, fakeParameters.dropLast(1)))
  }

  /** Same name and parameters, but not the `public static void` an `invokestatic …(…)V` needs. */
  @Suppress("FunctionNaming", "unused")
  object WrongShapeFacade {
    @JvmStatic
    fun ExperimentalRemoteDocumentPlayer(document: String, theme: Int, flags: Long): String = ""

    @JvmStatic private fun ExperimentalRemoteDocumentPlayer(document: String, theme: Int) = Unit
  }

  @Test
  fun `rejects a facade whose entry point is not public static void`() {
    assertFalse(declaresEntryPoint(WrongShapeFacade::class.java, fakeParameters))
    assertFalse(declaresEntryPoint(WrongShapeFacade::class.java, listOf("java.lang.String", "int")))
  }

  @Test
  fun `absent facade is unavailable rather than an error`() {
    // A loader with no classpath and no parent sees none of the Remote Compose artifacts, which is
    // the "consumer doesn't ship the player at all" case.
    assertFalse(embeddedPlayerEntryPointPresent(URLClassLoader(emptyArray(), null)))
    assertFalse(embeddedPlayerEntryPointPresent(null))
  }

  @Test
  fun `signature pin matches the call site this module compiles`() {
    val descriptor =
      invokedDescriptor(
        owner = EMBEDDED_PLAYER_FACADE.replace('.', '/'),
        method = EMBEDDED_PLAYER_ENTRY_POINT,
        inClass = RemoteComposeIrReplay::class.java,
      )
    assertEquals(
      "the pinned parameter list must be what the compiled call site actually invokes",
      descriptorOf(EMBEDDED_PLAYER_ENTRY_POINT_PARAMETERS),
      descriptor,
    )
  }

  /** `listOf("int", "java.lang.String")` -> `"(ILjava/lang/String;)V"`. */
  private fun descriptorOf(parameters: List<String>): String =
    parameters.joinToString(separator = "", prefix = "(", postfix = ")V") { name ->
      when (name) {
        "int" -> "I"
        "long" -> "J"
        "boolean" -> "Z"
        else -> "L${name.replace('.', '/')};"
      }
    }

  /**
   * The descriptor [inClass] invokes `owner.method` with, read straight out of its constant pool.
   *
   * Only the three constant kinds this needs are decoded (`Methodref`, `Class`, `NameAndType`);
   * everything else is skipped by its fixed width, which is all a constant-pool walk requires.
   */
  private fun invokedDescriptor(owner: String, method: String, inClass: Class<*>): String {
    val bytes =
      checkNotNull(
          inClass.classLoader.getResourceAsStream(inClass.name.replace('.', '/') + ".class")
        ) {
          "no class file for ${inClass.name}"
        }
        .use { it.readBytes() }
    DataInputStream(bytes.inputStream()).use { input ->
      check(input.readInt() == -0x35014542) { "not a class file" } // 0xCAFEBABE
      input.readUnsignedShort() // minor
      input.readUnsignedShort() // major
      val count = input.readUnsignedShort()
      val utf8 = HashMap<Int, String>()
      val classIndex = HashMap<Int, Int>()
      val nameAndType = HashMap<Int, Pair<Int, Int>>()
      val methodRefs = ArrayList<Pair<Int, Int>>()
      var index = 1
      while (index < count) {
        when (val tag = input.readUnsignedByte()) {
          1 -> utf8[index] = input.readUTF()
          7 -> classIndex[index] = input.readUnsignedShort()
          10,
          11 -> methodRefs += input.readUnsignedShort() to input.readUnsignedShort()
          12 -> nameAndType[index] = input.readUnsignedShort() to input.readUnsignedShort()
          8,
          16,
          19,
          20 -> input.skipBytes(2)
          15 -> input.skipBytes(3)
          3,
          4,
          9,
          17,
          18 -> input.skipBytes(4)
          5,
          6 -> {
            input.skipBytes(8)
            index++ // longs and doubles take two constant-pool slots
          }
          else -> error("unexpected constant tag $tag")
        }
        index++
      }
      val descriptor = methodRefs.firstNotNullOfOrNull { (ownerRef, nameAndTypeRef) ->
        val ownerName = classIndex[ownerRef]?.let(utf8::get)
        val (nameRef, descriptorRef) =
          nameAndType[nameAndTypeRef] ?: return@firstNotNullOfOrNull null
        if (ownerName == owner && utf8[nameRef] == method) utf8[descriptorRef] else null
      }
      return checkNotNull(descriptor) { "$inClass does not invoke $owner.$method" }
    }
  }
}
