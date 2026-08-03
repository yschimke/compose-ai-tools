package ee.schimke.composeai.rcplayer.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RcOperationInventoryTest {
  @Test
  fun everyImplementedManifestEntryHasExactlyOneCodecAndViceVersa() {
    val decodable =
      RcOperationInventory.entries
        .filter {
          it.status == RcOperationStatus.IMPLEMENTED || it.status == RcOperationStatus.PARSE_ONLY
        }
        .map { it.opcode }
        .toSet()
    val codecs = RcDocumentCodec.supportedOperations.map { it.opcode }.toSet()

    assertEquals(decodable, codecs)
    assertEquals(codecs.size, RcDocumentCodec.supportedOperations.size)
  }

  @Test
  fun inventoryIsUniqueOrderedAndHasAnExplicitDisposition() {
    val entries = RcOperationInventory.entries

    assertEquals(entries.sortedBy { it.opcode }, entries)
    assertEquals(entries.size, entries.map { it.opcode }.distinct().size)
    assertEquals(entries.size, entries.map { it.constantName }.distinct().size)
    assertTrue(entries.all { it.cluster in 0..8 })
  }

  @Test
  fun parseOnlyOperationsCannotBeMistakenForRenderableSupport() {
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), modern = false),
        listOf(RcTheme(-2), RcNoArg(RcOpcodes.DRAW_CONTENT)),
      )

    val support = document.supportReport()

    assertEquals(listOf("DrawContent"), support.parseOnly.map { it.stableName })
    assertTrue(!support.fullyRenderable)
    assertFailsWith<IllegalArgumentException> { support.requireFullyRenderable() }
  }

  @Test
  fun profilesExcludeUnavailableReservedAndParseOnlyOperations() {
    val unavailable = setOf(4, 57, 132, 162, 195)
    val reserved = (251..255).toSet()

    assertTrue(unavailable.none(RcOperationProfiles.ANDROIDX_JAVA_ALPHA16::supports))
    assertTrue(reserved.none(RcOperationProfiles.ANDROIDX_JAVA_ALPHA16::supports))
    assertTrue(unavailable.none(RcOperationProfiles.CMP_WASM_ALPHA16::supports))
    assertTrue(reserved.none(RcOperationProfiles.CMP_WASM_ALPHA16::supports))
    assertTrue(
      RcOperationInventory.entries
        .filter { it.status == RcOperationStatus.PARSE_ONLY }
        .none { RcOperationProfiles.CMP_WASM_ALPHA16.supports(it.opcode) }
    )
    assertEquals(
      RcOperationInventory.entries.count { it.status == RcOperationStatus.IMPLEMENTED },
      RcOperationProfiles.CMP_WASM_ALPHA16.opcodes.size,
    )
  }
}
