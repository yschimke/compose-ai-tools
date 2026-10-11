package ee.schimke.composeai.discovery

/**
 * One overload of a factory an export projection calls, such as
 * `ButtonDefaults.filledTonalButtonColors(containerColor: Color = …, …): ButtonColors`, as
 * discovered from compiled metadata (`TypedAdapterDiscovery.discoverFactories`).
 *
 * A typed definition calls a factory through this rather than a string, so its arguments are
 * checked by name and type against the real signature, and the type the call writes is the
 * factory's declared return type rather than one the author typed.
 *
 * @property callableFqn the qualified call, `…material3.ButtonDefaults.filledTonalButtonColors`.
 * @property returnTypeFqn the declared return type, `…material3.ButtonColors`.
 * @property returnSupertypes every class and interface [returnTypeFqn] extends, transitively, so a
 *   call can be passed where a supertype is taken: `Arrangement.spacedBy` returns a
 *   `HorizontalOrVertical` for a `verticalArrangement: Arrangement.Vertical`.
 */
data class FactorySignature(
  val callableFqn: String,
  val returnTypeFqn: String,
  val parameters: List<TargetParameter>,
  val returnSupertypes: Set<String> = emptySet(),
)
