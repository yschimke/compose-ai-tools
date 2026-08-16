package ee.schimke.composeai.preview

/**
 * Selects the environment used to preview an additive Glimmer display.
 *
 * The composable is captured unchanged as opaque RGB on black. After capture,
 * `:data-glimmer-environment-connector` preserves that raw image and ADD-composites a separate
 * preview artifact over the selected environment. Environment imagery is therefore tooling data,
 * not application UI that would run on glasses.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class GlimmerEnvironmentPreview(val environment: GlimmerEnvironment)

/** Environment presets supported by [GlimmerEnvironmentPreview]. */
enum class GlimmerEnvironment {
  Light,
  Dark,
  Busy,
  VeniceCanalCats,
}
