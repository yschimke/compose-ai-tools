# Should the usage cleaner parse instead of scan?

`PlaygroundSourceCleaner` rewrites source with masked regex passes. It does that because the Kotlin
frontend is deliberately kept off the CLI's runtime classpath — staged into `lib-bta/` and loaded in
an isolated classloader only for an actual compile ([`cli/build.gradle.kts`](../../cli/build.gradle.kts)).

The snippet corpus made the cost of that decision measurable. Across four review rounds, nearly every
defect it surfaced was **parser-shaped**:

| Defect | What a parse knows |
|---|---|
| `previewOverrideString(key = …, default = …)` bound positionally | argument binding |
| `state.metrics.counted` taken for a package qualifier | qualified expression structure |
| `counted { }` missed — the regex required `(` | a call is a call |
| a qualified call neither rewritten nor reported | the callee, regardless of qualifier |
| `toggleable` destructuring a `Pair` (still open) | destructuring declarations |

Five symptoms, one missing thing: structure. So: measure before rewriting.

## The spike

[`PsiParseSpikeTest`](../../cli/src/test/kotlin/ee/schimke/composeai/cli/serve/PsiParseSpikeTest.kt)
answers three questions.

### 1. Does parse-only PSI work with no analysis?

Yes. `KotlinCoreEnvironment.createForProduction` with an **empty** `CompilerConfiguration` — no
classpath, no source roots, no resolution — is enough to get a `PsiFileFactory` that returns real
`KtFile`s. The expensive half of a frontend is resolution, and none of the rewrites need it.

### 2. What does it cost?

Measured over the 35 snippets the corpus generates:

```
environment setup : ~420 ms   (once per process)
parsed            : 35 files, 41,824 chars, in ~81 ms
per file          : ~2.3 ms
```

Against a seed path that already does a network fetch from GitHub raw, ~2 ms per file is free, and
the ~420 ms setup is one-off and lazy — it need not happen until the first seed is cleaned.

### 3. Does the tree carry what the rewrites need?

Over those same snippets: 49 named functions, 332 call expressions, **334 named arguments**, 1380
qualified expressions. Each of those numbers is something a text pass had to infer and got wrong at
least once.

On a fixture holding every shape the review rounds broke on, the tree separates all of them:

- `previewOverrideString(key = "title", default = "Shopping")` → `default` binds to `"Shopping"` by
  name, in any order, with no `params` list to declare.
- `counted { }` and `counted("label")` are both call expressions.
- `ee.schimke.composeai.overrides.previewOverrideString(…)` and `state.metrics.counted { }` are both
  `KtDotQualifiedExpression`, and each knows its own receiver text — so package vs receiver chain
  stops being a regex guess and becomes a lookup.
- `val (checked, onCheckedChange) = toggleable(…)` is a destructuring declaration, which is what the
  `$known-gaps` entry in `compose-usage.json` needs.

## The frontend never has to reach the CLI classpath

The spike also loads the parser from the **staged `lib-bta/`** through a `URLClassLoader` whose
parent is the platform loader — the same isolation `PlaygroundBtaCompiler` already uses for the
compiler. Load + parse through that route: **~520 ms**, 13 jars, no dependency on the CLI's own
classpath.

So the constraint the text passes exist to respect is not actually in the way. What was in the way
was assuming it was.

The spike's `testImplementation` on `kotlin-compiler-embeddable` is scaffolding for *typed* assertions
only, and should not survive: a real implementation wants one small module compiled `compileOnly`
against the frontend, jarred beside `lib-bta/`, and entered through a single reflective call — rather
than the reflection sprawl this spike uses to prove the route.

## One caveat, which was mine

The isolated-loader test first failed only when `PlaygroundBtaCompilerTest` ran before it, which
looked exactly like a prior in-process compile leaving global frontend state — a serious finding, had
it been true. It was not. `Class.getMethods()` has no specified order, so `methods.first { … }` was
selecting a different `createFileFromText` overload between runs. Selecting by exact signature fixed
it, and it has been stable since.

Worth recording because the false version was the more interesting story, and nothing but checking
told them apart.

## What this does and does not buy

A parse fixes the **machinery**. It does not move the ratio much on its own:

- m3-catalog's 2 destructuring failures and 2 conditional-`stringResource` failures become tractable.
- `CatalogFilledStars` (×4) still needs a substitution rule — no parse invents an icon.
- meshcore-mobile's 0/10 stays structural: its previews compose app screens over fixture data, so
  there is no library call site to reduce to.

The corpus is what makes the rewrite safe to attempt: **3/10 and 0/10 are a baseline**, per snippet,
that a parser-backed cleaner has to match or beat before it lands.
