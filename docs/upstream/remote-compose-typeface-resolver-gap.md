# A custom `TypefaceResolver` can only replace the default, never delegate to it

**Component:** `androidx.compose.remote:remote-player-core` / `remote-player-compose`
**Versions checked:** artifacts `1.0.0-alpha15`; source read at `androidx-main`
**Type:** API gap (no workaround available through public API)

## Summary

`RemoteDocumentPlayer` accepts a `typefaceResolver`, and `RemoteComposePlayer` exposes
`get`/`setTypefaceResolver`. But there is no way to install a resolver that handles *some* requests
and forwards the rest to the stock behaviour:

- `AndroidRemoteContext.getTypefaceResolver()` returns `null` until someone calls the setter — there
  is no default instance to read back and delegate to.
- Reimplementing the default requires a `RemoteContext`, and the only accessor
  (`RemoteComposePlayer.getRemoteContext()`) is `private`.

So a caller who wants to add one behaviour — resolving a `RemoteFontFamily.Named` value the host
knows how to fetch — must replace the whole resolver and cannot reproduce the part of the default
that reads document-embedded fonts.

## Why the obvious approaches don't work

### 1. Decorating in the `init` callback

`RemoteDocumentPlayer.kt` builds the player like this:

```kotlin
RemoteComposePlayer(it).apply {
    doOnPreDraw { fullyDrawnReporter?.removeReporter() }
    init(this)
    bitmapLoader?.let(::setBitmapLoader)
    typefaceResolver?.let(::setTypefaceResolver)
}
```

`init(this)` runs **before** `setTypefaceResolver`, so:

```kotlin
init = { player ->
    val current = player.getTypefaceResolver()   // always null here
    if (current != null) player.setTypefaceResolver(MyResolver(current))
}
```

never fires — `getTypefaceResolver()` is null here for the reason in §2 — and had the caller also
passed the `typefaceResolver` parameter, it would have overwritten the result one line later.

The ordering is a wrinkle rather than the root cause: give the getter a lazy default (see the
proposal's Option A) and this shape starts working, provided the caller leaves the `typefaceResolver`
parameter unset. The blocking problems are §2 and §3.

### 2. Reading the current resolver at any point

`RemoteComposePlayer` forwards both accessors to the context:

```java
public void setTypefaceResolver(@NonNull TypefaceResolver typefaceResolver) {
    ((AndroidRemoteContext) mInner.getRemoteContext()).setTypefaceResolver(typefaceResolver);
}

public @Nullable TypefaceResolver getTypefaceResolver() {
    return ((AndroidRemoteContext) mInner.getRemoteContext()).getTypefaceResolver();
}
```

and `AndroidRemoteContext` stores it with no lazy default:

```java
private TypefaceResolver mTypefaceResolver;

public @Nullable TypefaceResolver getTypefaceResolver() {
    return mTypefaceResolver;
}
```

The stock behaviour is constructed inside `AndroidPaintContext` when the context has no resolver, so
it is never observable through the public API. `getTypefaceResolver()` returns `null` for the entire
lifetime of a player nobody has called the setter on — which is exactly the case where a caller would
want to wrap it.

### 3. Constructing `DefaultTypefaceResolver` to delegate to

`DefaultTypefaceResolver`'s constructor takes a `RemoteContext`, and reads it in one place — the
`fontType` overload, to find document-embedded font data:

```java
RemoteContext.FontInfo fi = (RemoteContext.FontInfo) mContext.getObject(fontType);
```

A caller cannot obtain that context: `RemoteComposePlayer.getRemoteContext()` is `private`, and
`RemoteDocumentPlayer` exposes only the `RemoteComposePlayer` (via `init`) and the `CoreDocument`
(which the caller supplies and which has no context accessor).

Passing a freshly-constructed `AndroidRemoteContext` compiles, but it is a *different* context from
the one playing the document, so `getObject(fontType)` misses and every document-embedded font
silently falls back. That is a correctness regression, not a workaround.

## Impact

Any host that can resolve a font the player cannot has to choose between:

- **not resolving it** — named families render in the platform default, silently, and (because a
  Remote Compose document carries geometry measured by the authoring renderer) with the wrong
  metrics as well; or
- **replacing the resolver wholesale** — losing document-embedded `FontData` for every document that
  host plays.

Neither is acceptable for a library that plays arbitrary third-party documents.

## Concrete use case

A snapshot renderer plays `.rc` documents that carry `RemoteFontFamily.Named("…")`. The host already
has the face on disk (it resolves the same families for Compose's own
`Font(GoogleFont(...))` requests through a local cache). It needs to answer *just* the named-family
lookups and leave generic typeface ids, system families, and embedded fonts to the stock resolver.
That is currently impossible.

## Reproduction

```kotlin
RemoteDocumentPlayer(
    document = document,
    documentWidth = w,
    documentHeight = h,
    init = { player ->
        // Prints null, always: nothing has set a resolver, and there is no lazy default.
        println("resolver = ${player.getTypefaceResolver()}")
    },
)
```

Expected: a resolver instance that a caller can wrap.
Actual: `null`, with the stock behaviour living inside `AndroidPaintContext`, unreachable.

See the companion proposal for the suggested fix.
