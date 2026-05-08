package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.displayfilter.DisplayFilter

/**
 * Sysprop-driven configuration for the display-filter extension. DaemonMain reads it to decide
 * whether to register the extension; the host's render pipeline reads the same prop to decide which
 * filters to actually apply per render. Keeping the parsing in one place avoids drift between
 * "extension is registered" and "filters get produced".
 */
object DisplayFilterConfig {

  /**
   * Comma-separated list of [DisplayFilter.id] values. Empty / unset disables the feature entirely.
   * Unknown ids are dropped with a warning so a typo in one filter doesn't strand the rest.
   * Example: `-Dcomposeai.displayfilter.filters=grayscale,deuteranopia`.
   */
  const val FILTERS_PROP: String = "composeai.displayfilter.filters"

  fun parseFilters(raw: String?): List<DisplayFilter> {
    if (raw.isNullOrBlank()) return emptyList()
    val out = mutableListOf<DisplayFilter>()
    for (token in raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
      val filter = DisplayFilter.fromId(token)
      if (filter == null) {
        System.err.println(
          "DisplayFilterConfig: ignoring unknown filter id '$token' (known: ${
            DisplayFilter.entries.joinToString { it.id }
          })"
        )
        continue
      }
      if (filter !in out) out += filter
    }
    return out
  }

  fun fromSystemProperties(): List<DisplayFilter> = parseFilters(System.getProperty(FILTERS_PROP))
}
