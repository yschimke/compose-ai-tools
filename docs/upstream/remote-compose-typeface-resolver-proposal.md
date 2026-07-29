# Proposal: make the stock `TypefaceResolver` reachable so callers can delegate

Companion to `remote-compose-typeface-resolver-gap.md`. Three options, cheapest first. Any one of
them unblocks the case; they are not mutually exclusive.

## Option A — give `AndroidRemoteContext` a lazy default (smallest change)

Make the getter return the resolver the player would otherwise build, instead of `null`:

```java
public @NonNull TypefaceResolver getTypefaceResolver() {
    if (mTypefaceResolver == null) {
        mTypefaceResolver = new DefaultTypefaceResolver(this);
    }
    return mTypefaceResolver;
}
```

Callers can then wrap:

```kotlin
player.setTypefaceResolver(MyResolver(delegate = player.getTypefaceResolver()))
```

**Pros:** a few lines; no new API surface; `AndroidPaintContext` can keep its own fallback for the
case where the context is absent.

**Cons:** on its own it is not enough for `RemoteDocumentPlayer` callers, because `init` still runs
before `setTypefaceResolver` — see Option C. It works today for direct `RemoteComposePlayer` users.

**Compatibility:** the getter's return type narrows from `@Nullable` to `@NonNull`. Source-compatible
for Kotlin callers that already null-checked; worth confirming against the API-tracking rules.

## Option B — let a resolver decline, and fall back

Give `TypefaceResolver` a way to say "not mine", so a caller can add behaviour without owning the
whole contract:

```java
public interface TypefaceResolver {
    /** Return null to defer to the platform default. */
    @Nullable FontInstance resolve(int fontType, int weight, boolean italic,
                                   @Nullable Typeface fallback, int flags, boolean bestEffort);

    @Nullable FontInstance resolve(String name, int weight, boolean italic,
                                   @Nullable Typeface fallback, int flags, boolean bestEffort);
}
```

`AndroidPaintContext` then tries the installed resolver and, on `null`, uses its own
`DefaultTypefaceResolver`.

**Pros:** the most expressive option and the one that scales to several independent contributors of
font knowledge; a caller never has to reimplement anything it does not care about.

**Cons:** changes an existing interface's nullability contract — a breaking change for anyone already
implementing it.

## Option C — apply `typefaceResolver` before `init`

Independent of A and B, and worth doing regardless: in `RemoteDocumentPlayer`, install the resolver
(and the bitmap loader) *before* handing the player to `init`:

```kotlin
RemoteComposePlayer(it).apply {
    doOnPreDraw { fullyDrawnReporter?.removeReporter() }
    bitmapLoader?.let(::setBitmapLoader)
    typefaceResolver?.let(::setTypefaceResolver)
    init(this)
}
```

Today `init` sees a player whose declared configuration has not been applied yet, and any
configuration `init` performs is silently overwritten a line later. Applying the declared parameters
first makes `init` a genuine customisation hook rather than one whose effect depends on which
parameters the caller also passed.

**Pros:** two moved lines; removes a surprising ordering dependency.

**Cons:** behaviour change for anyone (unknowingly) relying on `init` running first.

## Suggested combination

**A + C** is the smallest set that fixes the reported case: `getTypefaceResolver()` returns something
to wrap, and `init` can wrap it without being overwritten. **B** is the better long-term shape if the
interface can still take a breaking change at alpha.

## What a caller would then write

```kotlin
class NamedFamilyResolver(private val delegate: TypefaceResolver) : TypefaceResolver {

    // Generic typeface ids and embedded fonts are none of our business.
    override fun resolve(fontType: Int, weight: Int, italic: Boolean,
                         fallback: Typeface?, flags: Int, bestEffort: Boolean) =
        delegate.resolve(fontType, weight, italic, fallback, flags, bestEffort)

    override fun resolve(name: String, weight: Int, italic: Boolean,
                         fallback: Typeface?, flags: Int, bestEffort: Boolean): FontInstance {
        hostFontCache.lookup(name, weight, italic)?.let { return SimpleFontInstance(it) }
        return delegate.resolve(name, weight, italic, fallback, flags, bestEffort)
    }
}
```

which is the shape the API already implies — it just isn't constructible today, because `delegate`
cannot be obtained.
