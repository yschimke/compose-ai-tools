package ee.schimke.composeai.cli.serve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a catalog declares about **its own scaffolding** — the vocabulary [PlaygroundSourceCleaner]
 * needs to turn a sticker's source into the usage code a developer would actually write.
 *
 * ### Why this is data the catalog owns, and not rules this repo hard-codes
 *
 * A sticker is not a usage example. It carries the machinery that lets one declaration serve a
 * baked PNG, a live clickable session, six themes and a variant matrix at once — `Sticker { }` for
 * the frame, `counted(…)` so no sticker ships a dead handler, `catalogButtonSize()` so a matrix
 * cell can drive a knob. All of that is *true* and all of it is noise to somebody who just wants to
 * know how to call `Button`.
 *
 * Which names those are is a fact about the catalog, not about this server, so the catalog declares
 * them. And the ratio is the whole argument for doing it this way: m3-catalog has ~400 catalogued
 * components and **17** scaffolding helpers, nearly all of them in three files. Declaring the
 * seventeen costs a file that fits on a screen; annotating the four hundred would be a
 * hand-maintained mapping that drifts the moment a sticker is edited — which is exactly the failure
 * mode that repo's README already rejects for name-keyed mapping files.
 *
 * ### Where it comes from
 *
 * `compose-usage.json` at the root of the catalog's own repo, read at the same `ref` the catalog
 * was published from (see [PlaygroundSeedResolver]). A catalog that ships no such file gets
 * [GENERIC] — annotation stripping and import pruning, which need no catalog knowledge and already
 * remove most of the noise.
 *
 * This is deliberately a *file* for the prototype. The intended end state is
 * `@CatalogScaffold(...)` on the helpers themselves, projected into the manifest by discovery, so
 * the declaration sits next to the thing it describes and a helper cannot be added without one.
 * That change swaps how this object is *populated*; every consumer below stays as it is.
 */
@Serializable
data class UsageRules(
  /**
   * Packages whose annotations are catalog machinery: `@CatalogComponent`, `@CatalogModes`,
   * `@SizeShapeMatrix`, `@file:CatalogGroup`. An annotation is matched by resolving its simple name
   * through the file's own imports, so a rule here can never strike an unrelated annotation that
   * happens to share a name.
   */
  @SerialName("scaffoldAnnotationPackages")
  val scaffoldAnnotationPackages: List<String> = emptyList(),

  /** Helper name → what the cleaner should do with it. */
  @SerialName("scaffolds") val scaffolds: Map<String, Scaffold> = emptyMap(),

  /**
   * Module-relative path to the English string resources, so `stringResource(Res.string.label_x)`
   * can be inlined as the literal the sticker actually renders. Null ⇒ string resources are left
   * alone (and their imports kept), which compiles but reads worse.
   */
  @SerialName("stringsPath") val stringsPath: String? = null,

  /**
   * The `@Preview` annotation to stamp on the cleaned entry point, since the catalog's own
   * (`@CatalogModes`) was just stripped. Must be one of the FQNs
   * [PlaygroundPreviewDiscoverer.DEFAULT_PREVIEW_ANNOTATION_FQNS] recognises, or the playground
   * will compile the snippet and then find nothing to render.
   */
  @SerialName("previewAnnotation")
  val previewAnnotation: String = "androidx.compose.ui.tooling.preview.Preview",
) {

  /** What to do with one scaffolding helper. */
  @Serializable
  data class Scaffold(
    @SerialName("kind") val kind: Kind,
    /** [Kind.RENAME] only: the plain-Compose name to call instead. */
    @SerialName("renameTo") val renameTo: String? = null,
    /** An import the replacement needs, e.g. `androidx.compose.material3.MaterialTheme`. */
    @SerialName("addImport") val addImport: String? = null,
    /** [Kind.SUBSTITUTE] only: what the call reads as, with `$0`, `$1`… for its arguments. */
    @SerialName("plain") val plain: String? = null,
    /**
     * [Kind.INLINE] only: member → replacement, where `$0`, `$1`… are the call's arguments.
     * `counted` declares `{"label": "$0", "onClick": "{}"}`, which is the whole of what that helper
     * means to a reader: the label you passed, and a click handler.
     */
    @SerialName("members") val members: Map<String, String> = emptyMap(),
  )

  enum class Kind {
    /**
     * Call the plain-Compose equivalent instead. `Sticker { }` → `MaterialTheme { }`: the sticker
     * frame *is* a `MaterialTheme` over the baseline scheme, so the rename is the honest reading —
     * and unwrapping it instead would leave the snippet unthemed, which is worse than noisy.
     */
    RENAME,

    /**
     * Drop the call and keep its trailing lambda's body. For layout scaffolding that exists to make
     * the *render* consistent (`ButtonFrame`) rather than to say anything about the component.
     */
    UNWRAP,

    /**
     * The helper, and everything downstream of it, is knob plumbing: delete it.
     *
     * This is the rule that earns its keep. Declaring `catalogButtonSize` as DROP removes its `val`
     * binding *and* every named argument whose value mentions that binding — so one line of
     * declaration deletes `contentPadding = size.contentPadding`, `modifier =
     * Modifier.height(size.containerHeight)` and the rest of the matrix wiring at once. What is
     * left is the call with its defaults, which is what the default render shows and what a
     * developer would write.
     */
    DROP,

    /** Substitute the call's [Scaffold.members] at their use sites; delete the binding. */
    INLINE,

    /**
     * Replace the whole call expression with [Scaffold.plain], which may cite the call's own
     * arguments as `$0`, `$1`…
     *
     * This is for a knob that already *has* a plain reading: `catalogChoice(key, default, …)`
     * returns `default` on the baked lane by construction, so `catalogChoice("style", "outlined",
     * "outlined", "elevated")` declares `"$1"` and the snippet says `"outlined"` — the value the
     * render on screen was made with. Unlike [DROP], it works on an expression anywhere, not only
     * on a named argument.
     */
    SUBSTITUTE,
  }

  companion object {
    /**
     * The rules that need no catalog knowledge at all: strip this repo's own preview/catalog
     * annotations, prune the imports that leaves unused. Every catalog gets at least this, so a
     * catalog that has declared nothing still opens in the playground without a stack of
     * annotations naming a machinery the visitor has no way to run.
     */
    val GENERIC =
      UsageRules(
        scaffoldAnnotationPackages = listOf("ee.schimke.composeai.preview"),
        // The preview-override knobs are **this repo's** API, not a catalog's, so no catalog should
        // have to declare them — and until they were here, every catalog's "plain Compose" leaked
        // `previewOverrideString(...)` into code a developer was invited to copy. Each takes
        // `(key, default, …)`, so the default is what the render on screen was made with and `$1`
        // is its plain reading.
        //
        // Found by the snippet corpus (`scripts/usage-corpus.sh`): two of m3-catalog's ten sampled
        // snippets failed to compile on this alone, and neither was reported as residue — the
        // helpers are not in a scaffold package, so nothing flagged them.
        scaffolds =
          listOf(
              "previewOverrideString",
              "previewOverrideInt",
              "previewOverrideFloat",
              "previewOverrideBoolean",
              "previewOverrideColor",
              "previewOverrideDp",
            )
            .associateWith { Scaffold(kind = Kind.SUBSTITUTE, plain = "\$1") },
      )

    /**
     * A catalog's declared rules **plus** the ones that are this repo's business rather than any
     * catalog's — the preview-override knobs and this repo's annotation packages.
     *
     * Without this a declared `compose-usage.json` replaced [GENERIC] wholesale, so the catalogs
     * that had gone to the trouble of declaring their scaffolding were the only ones NOT getting
     * the shared rules. A catalog can still override any of them by naming the same helper: its own
     * entry wins.
     */
    private fun UsageRules.withGenericDefaults(): UsageRules =
      copy(
        scaffoldAnnotationPackages =
          (scaffoldAnnotationPackages + GENERIC.scaffoldAnnotationPackages).distinct(),
        scaffolds = GENERIC.scaffolds + scaffolds,
      )

    private val json = Json {
      ignoreUnknownKeys = true
      isLenient = true
    }

    /**
     * Parse `compose-usage.json`, or null when it isn't valid — a catalog's malformed rules file
     * must degrade to [GENERIC], never take the playground handoff down with it.
     */
    fun parse(text: String, onLog: (String) -> Unit = {}): UsageRules? =
      try {
        json.decodeFromString<UsageRules>(text).withGenericDefaults()
      } catch (e: Exception) {
        onLog("compose-usage.json is not valid usage rules (${e.message}); using generic rules")
        null
      }
  }
}
