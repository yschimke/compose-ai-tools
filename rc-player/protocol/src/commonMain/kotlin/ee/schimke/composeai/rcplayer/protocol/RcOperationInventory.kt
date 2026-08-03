package ee.schimke.composeai.rcplayer.protocol

public enum class RcOperationStatus {
  IMPLEMENTED,
  PARSE_ONLY,
  UNSUPPORTED,
  /** Public AndroidX constant with no usable reader in the authoritative Java player profile. */
  UNAVAILABLE,
  RESERVED,
}

/** One checked-in AndroidX opcode inventory entry, generated from rc-operations.manifest. */
public data class RcOperationInventoryEntry(
  val opcode: Int,
  val constantName: String,
  val stableName: String,
  val cluster: Int,
  val status: RcOperationStatus,
)

/** An explicit opcode allow-list for producers selecting a compatible document subset. */
public data class RcOperationProfile(val name: String, val opcodes: Set<Int>) {
  public fun supports(opcode: Int): Boolean = opcode in opcodes
}

public object RcOperationProfiles {
  /** Operations readable by the authoritative AndroidX alpha16 Java operation registry. */
  public val ANDROIDX_JAVA_ALPHA16: RcOperationProfile =
    RcOperationProfile(
      "androidx-java-alpha16",
      RcOperationInventory.entries
        .filter {
          it.status != RcOperationStatus.UNAVAILABLE && it.status != RcOperationStatus.RESERVED
        }
        .mapTo(linkedSetOf()) { it.opcode },
    )

  /** Operations with executable semantics in this CMP/Wasm player; parse-only is excluded. */
  public val CMP_WASM_ALPHA16: RcOperationProfile =
    RcOperationProfile(
      "cmp-wasm-alpha16",
      RcOperationInventory.entries
        .filter {
          it.status == RcOperationStatus.IMPLEMENTED &&
            // Compose's current Wasm graphics-layer surface disappears when this modifier is
            // present. Keep it available to the shared/iOS renderer but never advertise it to
            // browser producers until that backend behavior is fixed.
            it.opcode != RcOpcodes.MODIFIER_GRAPHICS_LAYER
        }
        .mapTo(linkedSetOf()) { it.opcode },
    )
}

public data class RcDocumentSupport(val parseOnly: List<RcOperationInventoryEntry>) {
  public val fullyRenderable: Boolean
    get() = parseOnly.isEmpty()

  public fun requireFullyRenderable() {
    if (parseOnly.isNotEmpty()) {
      throw IllegalArgumentException(
        "Document contains parse-only operations: " +
          parseOnly.joinToString { "${it.stableName}(${it.opcode})" }
      )
    }
  }
}

/** Report semantic coverage separately from successful binary decoding. */
public fun RcDocument.supportReport(): RcDocumentSupport {
  val parseOnly =
    (listOf(header) + operations)
      .mapNotNull { RcOperationInventory.byOpcode[it.opcode] }
      .filter { it.status == RcOperationStatus.PARSE_ONLY }
      .distinctBy { it.opcode }
  return RcDocumentSupport(parseOnly)
}
