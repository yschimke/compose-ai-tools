package ee.schimke.composeai.discovery

import kotlinx.serialization.Serializable

/**
 * One value parameter of a target composable, from its Kotlin metadata. [type] is a short,
 * human-readable rendering (simple class name + `?` when nullable), enough to scaffold a call site
 * and let a developer/agent fill the value — not a fully-qualified, resolvable type reference.
 */
@Serializable
data class TargetParameter(
  val name: String,
  val type: String,
  /**
   * The parameter's **fully-qualified** classifier (`kotlin.String`), or null when not recorded or
   * not a class type.
   *
   * [type] is a deliberately lossy rendering — it prints the simple name so a human can read it —
   * and `com.example.String` and `kotlin.String` render identically as `String`. A generator that
   * picks a placeholder literal off that spelling writes `""` for a domain type and produces source
   * that does not compile, which is the same trap [nullable] exists for one level down.
   */
  val typeFqn: String? = null,
  /** True when the parameter declares a default value (so a call site may legally omit it). */
  val hasDefault: Boolean = false,
  /**
   * True when the parameter is a `@Composable` function-typed slot (a `content = { … }` lambda).
   */
  val composableSlot: Boolean = false,
  /**
   * For a [composableSlot], the **fully-qualified** receiver type of the lambda
   * (`androidx.compose.foundation.layout.RowScope`), or null when it has none.
   *
   * Separate from [type], which renders simple classifier names for readability — a scaffolding
   * hint, not a resolvable reference. A consumer deciding which scoped modifier APIs are legal
   * inside a slot, or generating an import for the scope, needs the qualified name: two libraries
   * can define the same simple `RowScope`, and `RowScope` alone cannot be imported.
   */
  val composableSlotReceiver: String? = null,
  /**
   * Whether the parameter's own type is nullable, read from metadata rather than inferred from
   * [type]'s spelling.
   *
   * The spelling cannot carry it. A rendered type ends in `?` both when the parameter is nullable
   * (`String?`) and when it is a non-null function whose *return* is (`(Int) -> String?`), and the
   * two want opposite treatment from anything generating an argument: `null` type-checks for the
   * first and not for the second. Recorded structurally so no consumer has to guess.
   */
  val nullable: Boolean = false,
  /**
   * Whether the parameter's own type can be constructed with **zero Kotlin arguments** —
   * `TextFieldState()` — so a generator can write a value for a required parameter that has no
   * literal (issue #5067).
   *
   * Resolved at discovery time against the classpath, never re-derived from [type]: the rendered
   * spelling is a simple name with no package and nothing about constructibility, so a consumer
   * reading it could only guess. `TextFieldState` is the case that motivated it — its primary
   * constructor's parameters all carry defaults, which Kotlin emits as the `(String, long, int,
   * DefaultConstructorMarker)` bridge, so `TextFieldState()` compiles from source and
   * `TextField(state = TextFieldState())` does too. The record simply did not carry enough to say
   * so, which is the same lesson [nullable] taught one level down.
   *
   * True implies [typeFqn] is set, because a call site emitting `Type()` also has to import it.
   */
  val noArgConstructible: Boolean = false,
  /**
   * The **fully-qualified callable** of a no-argument factory for this parameter's type —
   * `androidx.compose.foundation.text.input.rememberTextFieldState` for a `TextFieldState` — or
   * null when the classpath offers none.
   *
   * Compose has a naming convention for exactly this: a state type `T` that wants remembering ships
   * a `@Composable fun rememberT(…)` beside it, every parameter defaulted (`rememberScrollState`,
   * `rememberLazyListState`, `rememberCoroutineScope`, `rememberTextFieldState`). A generated call
   * site should prefer it over the raw constructor, because raw state construction in a composable
   * body does not survive recomposition and the factory is what a human would write.
   *
   * Resolved on the classpath at discovery time, never assumed from the name: the record carries
   * the callable it actually found. A convention that is looked up is a fact; a convention that is
   * written down in the generator is the authored table `docs/design/COMPONENT_RECORD.md` keeps
   * deleting. Null when no such function exists, when it is not `@Composable`, when any parameter
   * lacks a default, or when it is gated behind an opt-in marker the call site cannot carry — every
   * one of those being a way `rememberT()` fails to compile.
   */
  val noArgFactory: String? = null,
)
