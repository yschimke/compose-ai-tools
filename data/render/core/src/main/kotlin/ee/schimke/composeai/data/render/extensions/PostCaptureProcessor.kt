package ee.schimke.composeai.data.render.extensions

/**
 * Pure-data context passed to [PostCaptureProcessor.process] after a render's bitmap has been
 * captured. No Compose runtime, no platform-specific types — extensions read declared inputs and
 * write declared outputs through [products], which the executor scopes to the extension's
 * `inputs`/`outputs` set so contract violations fail loudly at runtime.
 *
 * Non-product context inputs the host hands to extensions go through [data] (typed
 * [ExtensionContextKey] entries) for new extensions. The legacy [attributes] map is kept for
 * back-compat with extensions that haven't migrated to typed keys yet (overlay attribute slots in
 * particular); prefer [data] in new code.
 */
data class ExtensionPostCaptureContext(
  val extensionId: DataExtensionId,
  val previewId: String?,
  val renderMode: String?,
  val products: DataProductStore,
  val data: ExtensionContextData = ExtensionContextData.Empty,
  val attributes: Map<String, Any?> = emptyMap(),
) {
  fun <T : Any> get(key: ExtensionContextKey<T>): T? = data.get(key)

  fun <T : Any> require(key: ExtensionContextKey<T>): T = data.require(key)
}

/**
 * Hook for extensions that run once after a render's bitmap is captured. Distinct from
 * [ee.schimke.composeai.data.render.extensions.compose.ImageFrameTransformHook]: post-capture
 * processors don't transform the image directly — they read/write typed data products such as
 * accessibility hierarchies, ATF findings, derived touch targets, or annotated overlay artifacts.
 *
 * The executor invokes [process] in planner-determined order with a [DataProductStore] already
 * populated by upstream producers. Implementations should fail fast on missing required inputs via
 * `context.products.require(...)` and emit outputs with `context.products.put(key, value)`.
 */
interface PostCaptureProcessor : PlannedDataExtension {
  fun process(context: ExtensionPostCaptureContext)
}
