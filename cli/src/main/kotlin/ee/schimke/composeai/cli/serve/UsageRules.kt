package ee.schimke.composeai.cli.serve

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
  /**
   * Gradle module paths these rules describe — `["samples:design-catalog-m3", …]`.
   *
   * **Empty means every module in the repo**, which is what a single-catalog repo wants and what
   * every existing rules file gets.
   *
   * It matters for a repo that publishes SEVERAL catalogs, as this one does. The rules file is read
   * once per `(repo, ref)`, so without scoping the m3 sticker sheet's `Sticker`/`CatalogComponent`
   * scaffolds and its `scaffoldSources` were also handed to the Wear and Remote catalogs, whose
   * helpers are not declared there at all. Two consequences, both silent: their snippets could not
   * resolve a helper the rules never named (Wear previews call the cross-file `wearCounted`), and
   * `declaresCatalogScaffolds` — the Source panel's "the catalog declared its scaffolding, so what
   * is left is usage code" note — answered *true* for catalogs that had declared nothing, dressing
   * an unresolved snippet in the stronger claim.
   *
   * A location whose module is not listed falls back to [GENERIC], the same path a catalog with no
   * rules file takes.
   */
  @SerialName("modules") val modules: List<String> = emptyList(),
  @SerialName("scaffoldAnnotationPackages")
  val scaffoldAnnotationPackages: List<String> = emptyList(),

  /**
   * Catalog annotations by **bare simple name**, for the ones an import can never resolve: an
   * annotation a catalog declares in the *same package* as the previews that stack it
   * (`@CatalogModes`) is referenced with no import line at all, so [scaffoldAnnotationPackages] —
   * which resolves a simple name through the file's own imports — structurally cannot see it. That
   * is not a corner case: it is where a catalog's own multipreview normally lives, and it is why
   * the m3 sticker sheet's snippets kept showing `@CatalogModes` above a `@Preview` that had just
   * been stamped to replace it.
   *
   * Matched on the name alone, so it is the blunter of the two and the catalog opts into it by
   * naming each annotation exactly. Prefer [scaffoldAnnotationPackages] whenever the annotation is
   * imported.
   */
  @SerialName("scaffoldAnnotationNames") val scaffoldAnnotationNames: List<String> = emptyList(),

  /**
   * **Repo-root-relative** Kotlin files whose declarations the cleaner may read — the catalog's own
   * scaffolding sources, so a sticker that *delegates* can be followed to what it delegates to.
   *
   * The cleaner's closure pass only ever reached declarations in the preview's **own file**, which
   * is the right default and is exactly wrong for a catalog whose component bodies deliberately
   * live in one shared place. `fun FilledButton() = Sticker("button-filled")` is the whole of the
   * m3 sticker sheet's `Button/Filled`, and the honest reduction of it — `Button(onClick = {}) {
   * Text("Filled") }` — is two files away. Without these the Source panel showed the wrapper and
   * nothing else: a snippet with no component in it (issue #4169).
   *
   * Repo-root-relative rather than module-relative because the point is to cross a module boundary:
   * the m3 catalog's stickers are in `:samples:design-catalog-m3` and their bodies are in
   * `:samples:design-catalog-m3-shared`. Read at the same `ref` as the preview's own source, so a
   * helper and the sticker citing it are never from different revisions.
   *
   * A name declared by more than one of these files is **ambiguous and dropped**, not first-wins —
   * a repo publishing several catalogs lists every catalog's scaffolding here, and silently picking
   * one repo-mate's `counted` for another's would rewrite a snippet into something that never ran.
   */
  @SerialName("scaffoldSources") val scaffoldSources: List<String> = emptyList(),

  /**
   * Packages the [scaffolds] themselves live in, so a **package-qualified** call
   * (`ee.schimke.composeai.overrides.previewOverrideString(…)`) is recognised as the same call.
   *
   * An allow-list rather than a shape test, because the shape is ambiguous: `state.metrics.counted
   * { }` looks exactly like a two-segment package prefix, and unqualifying it would let the
   * scaffold passes rewrite somebody's ordinary receiver chain. Only a prefix named here is
   * stripped, so a chain this does not know about keeps its meaning.
   */
  @SerialName("scaffoldPackages") val scaffoldPackages: List<String> = emptyList(),

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
    /**
     * What to do with the helper. A kind this build cannot read decodes as [Kind.UNKNOWN] rather
     * than failing the document — see [Kind.UNKNOWN] and [LenientKind].
     */
    @SerialName("kind") @Serializable(with = LenientKind::class) val kind: Kind = Kind.UNKNOWN,
    /** [Kind.RENAME] only: the plain-Compose name to call instead. */
    @SerialName("renameTo") val renameTo: String? = null,
    /**
     * Optional reusable semantics layered onto [Kind.RENAME].
     *
     * A string rather than another enum on purpose: an older server ignores this new JSON key and
     * still performs the ordinary [renameTo], so a catalog may publish the special before every
     * server has upgraded. This build recognises [MATERIAL3_SYSTEM_THEME]. An unrecognised value is
     * left untouched and reported as residue rather than silently degraded to an incomplete rename.
     */
    @SerialName("special") val special: String? = null,
    /** An import the replacement needs, e.g. `androidx.compose.material3.MaterialTheme`. */
    @SerialName("addImport") val addImport: String? = null,
    /**
     * Imports the replacement needs, when one is not enough — a state helper substituted to `var x
     * by remember { mutableStateOf(…) }` needs four, including the `getValue`/`setValue` the `by`
     * delegation reads and which nothing in the snippet mentions by name.
     *
     * Applies alongside [addImport] to every kind that emits a replacement — RENAME, SUBSTITUTE,
     * INLINE; see [imports]. The [MATERIAL3_SYSTEM_THEME] special supplies its standard imports
     * itself, so every catalog gets the same complete, runnable expansion. DROP and UNWRAP write no
     * new code, so an import declared on one of those has nothing to serve.
     */
    @SerialName("addImports") val addImports: List<String> = emptyList(),
    /** [Kind.SUBSTITUTE] only: what the call reads as, with `$0`, `$1`… for its arguments. */
    @SerialName("plain") val plain: String? = null,
    /**
     * [Kind.SUBSTITUTE] only: the helper's parameter names, in declaration order, so `$0`/`$1`
     * resolve the way Kotlin does when the call uses **named** arguments —
     * `previewOverrideString(key = "title", default = "Shopping")` must put `"Shopping"` at `$1`,
     * not `default = "Shopping"`.
     *
     * Optional: a rule that omits it keeps the plain positional reading, and declines to rewrite a
     * call that uses named arguments at all (reporting it as residue) rather than guessing.
     */
    @SerialName("params") val params: List<String> = emptyList(),
    /**
     * [Kind.INLINE] only: member → replacement, where `$0`, `$1`… are the call's arguments.
     * `counted` declares `{"label": "$0", "onClick": "{}"}`, which is the whole of what that helper
     * means to a reader: the label you passed, and a click handler.
     */
    @SerialName("members") val members: Map<String, String> = emptyMap(),
  ) {
    /**
     * Every import this rule's replacement needs — [addImport] and [addImports] together.
     *
     * Two fields rather than one is a convenience for the rules author: most replacements need
     * exactly one import and `"addImport": "…"` reads better than a one-element list. Every rewrite
     * reads *this*, so a rule that spells its imports either way is honoured the same. Reading only
     * `addImport` is how the state helpers first emitted `remember { mutableStateOf(true) }` with
     * no `remember` import: a snippet that looks right and does not compile, which is exactly the
     * failure the compile gate exists to catch.
     */
    val imports: List<String>
      get() = buildList {
        addAll(addImports)
        addImport?.let(::add)
        if (special == MATERIAL3_SYSTEM_THEME) addAll(MATERIAL3_SYSTEM_THEME_IMPORTS)
      }
        .distinct()
  }

  enum class Kind {
    /**
     * Call the plain-Compose equivalent instead. A purely structural `CatalogFrame { }` can become
     * `Box { }`; a wrapper whose meaning includes a system-responsive Material 3 scheme should add
     * the [MATERIAL3_SYSTEM_THEME] special rather than collapsing to bare `MaterialTheme { }`.
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

    /**
     * The helper **delegates**: replace the call with the helper's own body, its parameters bound
     * to the call's arguments, and let every other pass run over what that produced.
     *
     * This is the one kind that reads a file other than the preview's — the declaration is looked
     * up in [UsageRules.scaffoldSources] — and it exists because a catalog is allowed to keep its
     * component bodies in one place. `Sticker("button-filled")` says nothing about a button; it
     * says "the shared component set's `button-filled`", and the reader wants what that is.
     *
     * **String-keyed dispatch is part of the kind, not a second one.** A shared component set is
     * normally a single `when (id)` over a few hundred branches, so expanding it verbatim would
     * substitute the entire catalog for the one component asked about. When the body is a lone
     * `when` over a parameter the call bound to a **string literal**, only the matching branch
     * survives. Anything less certain — a computed key, a `when` this cannot read, an argument that
     * is not a literal — is left exactly as the catalog wrote it and reported as residue.
     */
    EXPAND,

    /**
     * A kind this build does not know — a rule kind that has since been retired, or one a newer
     * catalog declares against a newer server. No pass matches it, so the helper is left exactly as
     * the catalog wrote it and reported as residue.
     *
     * Never written in a rules file: it is what [parse] coerces an unrecognised `kind` to, and the
     * reason a catalog's whole `compose-usage.json` no longer dies on one such entry.
     *
     * The failure this exists to prevent is not hypothetical. `ignoreUnknownKeys` covers an unknown
     * *key*; an unknown enum *value* throws, and [parse] catches that and returns null for the
     * entire document — so retiring the `DESTRUCTURE` kind (#3884) meant a catalog pinned to a ref
     * that still declared one lost every other rule in the file too, `Sticker` and
     * `catalogButtonSize` included, and silently fell back to [GENERIC]. A rules file is read at
     * whatever ref the catalog was published from, so its vocabulary is always potentially older
     * than this build's.
     */
    UNKNOWN,
  }

  /**
   * Decodes an unrecognised [Kind] name as [Kind.UNKNOWN] instead of throwing.
   *
   * Deliberately a serializer on this one field rather than `coerceInputValues` on the whole
   * document. That option is global and coerces *any* explicit `null` to its property's default, so
   * it also quietly repaired malformed rules this code cannot honour — an `INLINE` rule with
   * `"members": null` would decode to an empty member map, and [PlaygroundSourceCleaner] would then
   * delete the binding while leaving its member references pointing at nothing. Only the *kind*
   * vocabulary is expected to drift across refs, so only the kind is forgiving; everything else
   * still fails the document and takes the safe [GENERIC] fallback.
   */
  internal object LenientKind : KSerializer<Kind> {
    override val descriptor: SerialDescriptor =
      PrimitiveSerialDescriptor(
        "ee.schimke.composeai.cli.serve.UsageRules.Kind",
        PrimitiveKind.STRING,
      )

    override fun serialize(encoder: Encoder, value: Kind) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): Kind {
      val name = decoder.decodeString()
      return Kind.entries.firstOrNull { it.name == name } ?: Kind.UNKNOWN
    }
  }

  companion object {
    /**
     * A [Scaffold.special] value for an argument-free wrapper that means "stock Material 3,
     * following system night mode". The cleaner preserves its trailing lambda and emits:
     * ```
     * MaterialTheme(
     *   colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
     * ) { … }
     * ```
     */
    const val MATERIAL3_SYSTEM_THEME = "MATERIAL3_SYSTEM_THEME"

    private val MATERIAL3_SYSTEM_THEME_IMPORTS =
      listOf(
        "androidx.compose.foundation.isSystemInDarkTheme",
        "androidx.compose.material3.MaterialTheme",
        "androidx.compose.material3.darkColorScheme",
        "androidx.compose.material3.lightColorScheme",
      )

    /** Every override knob takes `(key, default, index)`; the default is `$1`. */
    private val OVERRIDE_KNOB_PARAMS = listOf("key", "default", "index")

    /**
     * The rules that need no catalog knowledge at all: strip this repo's own preview/catalog
     * annotations, prune the imports that leaves unused. Every catalog gets at least this, so a
     * catalog that has declared nothing still opens in the playground without a stack of
     * annotations naming a machinery the visitor has no way to run.
     */
    val GENERIC =
      UsageRules(
        scaffoldAnnotationPackages = listOf("ee.schimke.composeai.preview"),
        scaffoldPackages = listOf("ee.schimke.composeai.overrides"),
        // The preview-override knobs are **this repo's** API, not a catalog's, so no catalog should
        // have to declare them — and until they were here, every catalog's "plain Compose" leaked
        // `previewOverrideString(...)` into code a developer was invited to copy. Each takes
        // `(key, default, …)`, so the default is what the render on screen was made with and `$1`
        // is its plain reading.
        //
        // Found by the snippet corpus (`scripts/usage-corpus.sh`): two of m3-catalog's ten sampled
        // snippets failed to compile on this alone, and neither was reported as residue — the
        // helpers are not in a scaffold package, so nothing flagged them.
        //
        // `params` carries each helper's own parameter names so a named-argument call
        // (`previewOverrideString(key = "title", default = "Shopping")`) substitutes as the value
        // and not as `default = "Shopping"`. Every knob takes `(key, default, …)`, so `$1` is the
        // default in all of them; only the middle parameters differ.
        scaffolds =
          mapOf(
              "previewOverrideString" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideInt" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideFloat" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideBoolean" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideColor" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideDp" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideFont" to
                listOf("key", "default", "suggestions", "googleFonts", "index"),
              // Two overloads, differing only in the third parameter (`options` / `values`). An
              // argument naming a parameter this list omits is ignored rather than fatal, so one
              // entry covers both.
              "previewOverrideChoice" to listOf("key", "default", "options", "index"),
            )
            .mapValues { (_, params) ->
              Scaffold(kind = Kind.SUBSTITUTE, plain = "\$1", params = params)
            },
      )

    /**
     * Did the **catalog** declare scaffolding, as opposed to inheriting this repo's own rules?
     *
     * The Source panel's note turns on this: "the catalog declared its scaffolding, so what is left
     * is usage code" is a much stronger claim than "this repo stripped its own annotations", and
     * only the catalog can earn it. Asking `scaffolds.isNotEmpty()` used to answer it, and stopped
     * being able to the moment [GENERIC] carried entries of its own — every catalog would then
     * claim a declaration it never made.
     */
    fun UsageRules.declaresCatalogScaffolds(): Boolean = catalogScaffolds().isNotEmpty()

    /**
     * Whether these rules describe [module] — see [UsageRules.modules].
     *
     * An unscoped file (the common case, and every file written before scoping existed) applies
     * everywhere. A scoped file applies only where it says. A location with no module at all is
     * treated as covered: the alternative is silently dropping to [GENERIC] on a catalog published
     * before discovery recorded the field, which would be a regression for the very catalog the
     * rules were written for.
     */
    fun UsageRules.appliesToModule(module: String?): Boolean =
      modules.isEmpty() || module == null || module in modules

    /**
     * The scaffolds a catalog declared itself — the merged map minus the generic ones it inherited.
     *
     * Compared by **entry**, not by key: a catalog that declares only its own reading of
     * `previewOverrideString` has declared something, and a key-difference test would call that
     * catalog undeclared while its rule was busy driving the cleaning.
     */
    fun UsageRules.catalogScaffolds(): Map<String, Scaffold> =
      scaffolds.filter { (name, scaffold) ->
        GENERIC.scaffolds[name] != scaffold
      }

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
        scaffoldAnnotationNames =
          (scaffoldAnnotationNames + GENERIC.scaffoldAnnotationNames).distinct(),
        scaffoldPackages = (scaffoldPackages + GENERIC.scaffoldPackages).distinct(),
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
