package ee.schimke.composeai.data.render.extensions

/**
 * Typed key for a non-product context value handed to an extension hook (e.g. the captured Android
 * `View` root, a coroutine scope, an inspectable composition table). Distinct from
 * [DataProductKey]: products are emitted by an extension and consumed by another via the planner
 * graph; context values are render-host-controlled inputs the host hands to extensions when it
 * invokes them. Both use a string [name] + payload [type] for the same compile-time-typed-but-
 * stringly-keyed lookup pattern.
 *
 * Lives in :data-render-core so non-Compose hook contexts (e.g. [ExtensionPostCaptureContext]) can
 * use the same typed-key shape the Compose-side hooks already rely on.
 */
data class ExtensionContextKey<T : Any>(val name: String, val type: Class<T>) {
  init {
    require(name.isNotBlank()) { "Extension context key name must not be blank." }
  }
}

data class ExtensionContextValue<T : Any>(val key: ExtensionContextKey<T>, val value: T)

infix fun <T : Any> ExtensionContextKey<T>.provides(value: T): ExtensionContextValue<T> =
  ExtensionContextValue(this, value)

/** Read-only typed container for [ExtensionContextKey] entries. */
class ExtensionContextData
private constructor(private val values: Map<ExtensionContextKey<*>, Any>) {
  fun <T : Any> get(key: ExtensionContextKey<T>): T? {
    val value = values[key] ?: return null
    return key.type.cast(value)
  }

  fun <T : Any> require(key: ExtensionContextKey<T>): T =
    get(key) ?: error("Extension context value '${key.name}' is not available.")

  fun contains(key: ExtensionContextKey<*>): Boolean = key in values

  companion object {
    val Empty: ExtensionContextData = ExtensionContextData(emptyMap())

    fun of(vararg values: ExtensionContextValue<*>): ExtensionContextData =
      ExtensionContextData(values.associate { it.key to it.value })
  }
}
