# Proposal: make the stock `TypefaceResolver` reachable so callers can delegate

Companion to `remote-compose-typeface-resolver-gap.md`. Three options, cheapest first. **Option A
alone unblocks the reported case**; B and C are improvements on top, not prerequisites.

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
case where the context is absent. Sufficient on its own, for direct `RemoteComposePlayer` users *and*
for `RemoteDocumentPlayer` callers: leave the nullable `typefaceResolver` argument unset and install
from `init`, at which point the trailing `typefaceResolver?.let(::setTypefaceResolver)` is a no-op and
cannot overwrite what `init` set.

```kotlin
RemoteDocumentPlayer(
    document = document,
    documentWidth = w,
    documentHeight = h,
    // typefaceResolver deliberately omitted — see below.
    init = { player -> player.setTypefaceResolver(MyResolver(player.getTypefaceResolver())) },
)
```

**Cons:** the working call has to *avoid* the declared `typefaceResolver` parameter and use `init`
instead, which is the opposite of what the API's shape suggests. Option C removes that wrinkle.

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

**Cons:** changes an existing interface's nullability contract. Note the break is on the **call**
side, not the implementation side: an existing implementation that returns a non-null `FontInstance`
remains a valid covariant override, and Java implementations are unaffected — but every caller must
now handle `FontInstance?`, which in Kotlin is a compile error until updated. In practice the only
in-tree caller is `AndroidPaintContext`; the exposure is to any downstream code that invokes a
resolver directly.

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

**Cons:** behaviour change for anyone (unknowingly) relying on `init` running first. On its own it
fixes nothing for this report — with no lazy default there is still no stock resolver to wrap, so C
is only worth doing alongside A.

## Suggested combination

**A** is the minimum, and is sufficient by itself. **C** on top makes the fix discoverable, so the
declared `typefaceResolver` parameter and `init` compose instead of the caller having to know that
passing the parameter defeats wrapping. **B** is the better long-term shape if the interface can
still take a source-breaking change to its callers at alpha.

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
