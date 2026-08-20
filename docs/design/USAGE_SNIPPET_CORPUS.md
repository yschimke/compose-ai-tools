# Does the generated usage source actually compile?

The Source panel tells a visitor a snippet is "the plain Compose that produces this render", and the
playground invites them to press Run on it. Nothing was holding that claim to anything: the cleaner's
tests feed it source *this* repository wrote, so they prove the rules match the fixtures and nothing
more.

This is the loop that checks the claim against real catalogs:

```sh
scripts/usage-corpus.sh ~/m3-catalog ~/meshcore-mobile
```

It samples previews from each checkout (5 default + 5 variant by default,
`USAGE_CORPUS_SAMPLES=n` to change), runs the real `PlaygroundSourceCleaner` over them with that
catalog's own rules, and compiles the results. Exit status is 0 when every snippet compiles.

A build that fails *before* type-checking — dependency resolution, a missing toolchain, a daemon
crash — produces no per-file diagnostic, so the script treats a nonzero exit with nothing to
attribute as a corpus failure and prints the log tail. Reading it as "no diagnostics, therefore
everything compiled" is the one bug that would quietly turn the whole loop into a no-op.

## The compile bar is a consumer's classpath, not the catalog's

`:tools:usage-compile-check` compiles the corpus against **Compose and material3 only**. That is
deliberate and it is the whole point: compiling against the catalog's own classpath would let its
internal helpers resolve, and the leakage worth finding is exactly the leakage that resolves there
and nowhere else. The bar is "a developer pasted this into their own Compose app".

The module is empty unless `-PusageCorpus=<dir>` points it at a generated corpus, so it costs a
normal build nothing.

Each snippet is emitted into **its own package**, so the corpus compiles as N independent pastes
rather than as one source set. Sharing a package would answer a different question in both
directions: two previews from the same file that each close over the same helper would collide as
redeclarations (a failure nobody pasting *one* of them would see), and a catalog symbol one snippet
leaked could resolve against a declaration another snippet happened to copy (a pass nobody would
get). It changed neither ratio below, which is worth knowing — the numbers were not an artefact of
the snippets propping each other up.

## What it found

First run, ten samples per catalog:

| Catalog | Compiles | Rules |
|---|---|---|
| m3-catalog | **4 / 10** | 22 declared scaffolds |
| meshcore-mobile | **0 / 10** | none — generic path |

### Residue is not a proxy for "compiles"

The most useful thing the corpus said immediately. `NumberBadge` was reported **clean** — no residue
at all — and did not compile:

```kotlin
Badge { Text(previewOverrideString("label", "3")) }
```

`previewOverrideString` is *this repository's* knob API, not a catalog's. It is not in a scaffold
package and no catalog had declared it, so nothing flagged it, and every catalog's "plain Compose"
was quietly leaking it into code a developer was invited to copy. It is now a `GENERIC` rule
substituting the default argument (`previewOverride*(key, default, …)` → `default`), and declared
rules inherit the generic ones rather than replacing them.

Residue only ever reported *declared* scaffolding that survived a rule. It cannot see a helper nobody
declared, which is precisely the case worth catching. **The compiler is the honest signal.**

Two things had to follow from putting those knobs in `GENERIC`:

- **Named arguments.** `previewOverrideString(key = "title", default = "Shopping")` substituted
  positionally as `default = "Shopping"` — Kotlin that looks right and does not compile, which is
  the worst outcome available for something presented as runnable. A `SUBSTITUTE` rule now declares
  the helper's `params`, so `$1` binds the way Kotlin binds it; a rule that declares none declines a
  named-argument call and reports it as residue rather than guessing.
- **`scaffoldsDeclared` had to stop meaning `scaffolds.isNotEmpty()`.** That flag drives the Source
  panel's stronger claim — *the catalog declared its scaffolding, so what is left is usage code* —
  and only a catalog can earn it. A non-empty `GENERIC` would have had every catalog claiming a
  declaration it never made, so it now asks whether any scaffold is the catalog's own.

### m3-catalog's remaining six failures, by cause

| Cause | Files | Status |
|---|---|---|
| ~~`toggleable` / `editable` destructure a `Pair`~~ | ~~2~~ → 0 | **Fixed**, twice. First by the `DESTRUCTURE` rule kind; then properly, by m3-catalog changing the helpers to return a `MutableState` used through `by`, so `var checked by toggleable(true)` is an ordinary `SUBSTITUTE` to `var checked by remember { mutableStateOf(true) }`. `DESTRUCTURE` was removed once that landed. One of the two compiled immediately; the other is now blocked only on the `stringResource` row below |
| Conditional `stringResource(if (…) Res.string.a else Res.string.b)` | 2 | The inliner only handles the exact single-key form and declines the rest, correctly — but the snippet then keeps an unresolvable `Res` |
| `CatalogFilledStars`, a catalog-owned `ImageVector` | 4 | No rule; wants substituting with a stock `Icons.Filled.Star` or similar |

None is a defect in the cleaner's machinery. Three are missing vocabulary, which is what the corpus
exists to surface.

### meshcore-mobile is a structural mismatch, not a missing rules file

All ten failed, and adding a `compose-usage.json` would not fix them. The two catalogs are built on
different premises:

- **m3-catalog** is annotation-first. Each `@CatalogComponent` is a sticker wrapping *one library
  component*, so there is a call site to reduce to — `Button(onClick = {}) { Text("Filled") }`.
- **meshcore-mobile** is spec-driven: `catalog.spec.json` names plain `@Preview` functions, and those
  previews compose the **app's own screens** with fixture data — `ContactRow` over a hand-built
  `Contact`, `DeviceBody` with a fake `SelfInfo`. There is no library call site underneath. The
  honest reduction of "the chat screen with twelve fake contacts" is that screen with twelve fake
  contacts.

So for a catalog like meshcore the Source panel shows *the preview, sliced and import-pruned* — not
usage code, and the panel already says so when a catalog has declared no rules. That is the correct
outcome, and worth knowing before anyone tries to fix it with rules.

### A third shape: the catalog that *delegates*

This repo's own `:samples:design-catalog-m3` is neither of the two above. Every preview in it is a
one-line wrapper —

```kotlin
@CatalogComponent(id = "Button/Filled", …)
@CatalogModes
@Composable
fun FilledButton() = Sticker("button-filled")
```

— over a shared component set in `:samples:design-catalog-m3-shared`, which is a single `when (id)`
over every component the sheet publishes. That is a deliberate design (one id, one composable, every
lane) and it broke the Source panel completely: the cleaner's closure pass only ever reached
declarations in the preview's **own file**, so the honest reduction of that preview was the wrapper
and nothing else. A visitor opening `Button/Filled` got a snippet with no button in it — [issue
#4169](https://github.com/yschimke/compose-ai-tools/issues/4169).

Two pieces of vocabulary close it, both declared in [`compose-usage.json`](../../compose-usage.json)
at the repo root:

- **`scaffoldSources`** — repo-root-relative Kotlin files the cleaner may read, fetched at the same
  `ref` as the preview's own source. This is what lets it cross a module boundary at all.
- **`EXPAND`** — a rule kind meaning *the helper delegates*: replace the call with the helper's body,
  parameters bound to the call's arguments. When that body is a lone `when` over a parameter the
  call bound to a **string literal**, only the matching branch survives — otherwise expanding the
  shared component set would substitute the whole catalog for the one component asked about.

Chained, `Sticker("button-filled")` → `CatalogSticker { CatalogComponent("button-filled") }` → the
`button-filled` branch, which the ordinary passes then reduce the rest of the way:

```kotlin
@Preview
@Composable
fun FilledButton() {
  val (label, onClick) = "Filled" to {}
  Button(onClick = onClick, enabled = true) { Text(label) }
}
```

Whatever the expanded body still calls that a scaffold source declares (`StatefulCheckbox`,
`CardContentSlot`) is closed over from those files too, capped — unlike the same-file closure, which
is unbounded because a preview file is all about the preview. 30 of the sheet's 36 previews come out
with empty residue; the remainder are the `stringResource` gap below and this repo's own
`LocalSlotMode`, both reported rather than hidden.

[`CatalogUsageRulesTest`](../../cli/src/test/kotlin/ee/schimke/composeai/cli/serve/CatalogUsageRulesTest.kt)
runs the real rules file over the real catalog source on every build, because the two are edited by
different hands and the cleaner's contract is to degrade quietly — a renamed helper or a moved file
puts the wrapper back with nothing to say it regressed.

### Known gap: an override *variant* still shows the author's default

Substituting `previewOverride*(key, default, …)` with `default` is right for the default render and
wrong for a seeded `@OverrideVariant` card: opening the `off` variant of a toggle emits `true`
because that is what the source says, not the `false` the render was seeded with. The values exist —
`PreviewInfo.overrides` carries them — but the seed path resolves a *source location*
(`PlaygroundSeedResolver.Location`), which has no override values in it, so fixing this means
plumbing them from the preview through the location into a key-aware substitution.

This is not a regression: before the knobs were rewritten at all, the variant's snippet said
`previewOverrideBoolean("checked", true)`, which did not reproduce the seeded render *and* did not
compile. It is now honest about the default render and still wrong about the variant, which is
strictly better and still worth fixing.

## The snippet's second job: the API reference links

The imports the cleaner leaves behind are exactly the platform APIs the render uses, which is the one
place anything on this server knows that a Wear catalog's `ImageBackgroundButton` is really
`androidx.wear.compose.material3.Button`. `ApiDocLinks` reads them back off the snippet and the
Source panel lists each as its KDoc page on `developer.android.com` ([issue
#4331](https://github.com/yschimke/compose-ai-tools/issues/4331)).

Two things make this more than a string join. `developer.android.com` publishes a top-level
`@Composable` at `<pkg>/<Name>.composable` and a class at `<pkg>/<Name>`, and the *other* one 404s —
so the resolver infers the kind from how the snippet uses the name, and drops what it cannot place
rather than guessing. And the pages are **not** framable, so this is a list of links rather than the
docs tab the issue asked about; the links sit under the code that names them.

The measurement is the same shape as this document's: run against 244 live snippets from every
catalog on the public host, it produced 220 distinct pages and no 404s. `ApiDocLinksTest` pins the
call-site shapes that got it there — each one a page shape that was wrong before it was added.

## Running it in CI

Not wired to CI yet, deliberately: it needs checkouts of the catalog repositories, and the failure
count is currently a **baseline to drive down** rather than a gate to hold. The shape it wants is a
scheduled job that checks out both catalogs, runs the script, and reports the ratio — turning it into
a gate once m3-catalog reaches 10/10.
