// WebFonts: browser registration of named and document-embedded font families.
//
// A Remote Compose document names its typeface in three ways. The four generic ids
// (`0=DEFAULT, 1=SANS_SERIF, 2=SERIF, 3=MONOSPACE`) are a closed set that `cssFontStackFor` maps to
// the concrete faces Android resolves them to. Anything else — `RemoteFontFamily.Named("Orbitron")`
// — reaches the paint layer as the document's *text id* for the family name, not as a name, because
// `CoreText.updateVariables` falls through to `mType = mFontFamilyId` for a family it doesn't
// recognise. Resolving that id back through the text table is what turns it into a family a browser
// can be asked for. A FontData operation can attach bytes to that same id instead; this module
// registers both sources and gives an embedded face a collision-resistant private CSS family.
//
// Fetching from Google Fonts (rather than vendoring) is the only approach that generalises: a
// document may name *any* family, and the set isn't known until the document is read. The faces
// Android's downloadable-font provider serves at a given name are the same ones Google Fonts serves
// at that name, so this matches the baked raster for the same reason the vendored generics do.
//
// Which families to fetch is stated by the document, not guessed: a family is namespaced
// `"google:Orbitron"` to mean "the Google Fonts family Orbitron". Treating *any* unrecognised family
// as a Google Font would be the wrong default — it turns a typo, or a name that only means something
// on the host ("SF Pro"), into a network request, and it leaves no way to say "this one is local".
// The prefix is a convention over `RemoteFontFamily.Named`, which carries an opaque string; both
// render lanes parse it the same way, so a document means the same thing in the browser as in the
// snapshot renderer.

/** Runtime knobs. Both matter to embedders, so neither is baked in. */
export interface WebFontConfig {
    /**
     * Whether to reach the network at all. Off ⇒ every named family degrades to the fallback
     * generic, which is the correct behaviour under a webview CSP that forbids the font origins
     * (the VS Code webview) and in hermetic CI, where a network fetch is a flake source.
     */
    enabled: boolean;
    /** Base URL of the CSS API. Point it at a mirror or a local fixture server to render offline. */
    baseUrl: string;
}

const DEFAULT_BASE_URL = 'https://fonts.googleapis.com/css2';

let config: WebFontConfig = { enabled: true, baseUrl: DEFAULT_BASE_URL };

export function configureWebFonts(patch: Partial<WebFontConfig>): void {
    config = { ...config, ...patch };
}

export function webFontConfig(): Readonly<WebFontConfig> {
    return config;
}

/**
 * The `ital,wght` matrix to request. Google's CSS API returns *only the faces the family actually
 * ships* — asking Orbitron for all eighteen yields its real 400..900 normal faces and nothing else —
 * so over-asking costs nothing and under-asking silently loses a weight the document uses.
 *
 * It has to be this enumerated form. The API rejects (HTTP 400) a *range* like `wght@100..900` for
 * any family that isn't variable — `Lobster:wght@100..900` and `Pacifico:wght@400..700` both 400 —
 * while the enumeration is accepted for variable and single-weight families alike. A 400 here is
 * indistinguishable at the `<link>` from a network failure, so preferring the tolerant form is what
 * keeps a static family from being reported as a broken one.
 */
const WEIGHTS = [100, 200, 300, 400, 500, 600, 700, 800, 900];

/** The css2 URL for [family]. Exported (and pure) so the request form is covered by tests. */
export function googleFontsUrl(family: string, baseUrl: string = config.baseUrl): string {
    const axis = WEIGHTS.map((w) => `0,${w}`).concat(WEIGHTS.map((w) => `1,${w}`)).join(';');
    // Google's canonical spelling uses `+` for spaces; encodeURIComponent's %20 also resolves, but
    // the `+` form is what every cache in front of the API is keyed on.
    const name = encodeURIComponent(family.trim()).replace(/%20/g, '+');
    // `display=block` keeps the canvas from painting a frame in the fallback face and then
    // reflowing — the swap period is exactly the window a single-shot renderer screenshots in.
    return `${baseUrl}?family=${name}:ital,wght@${axis}&display=block`;
}

/**
 * A font-variation axis the document actually asks for, and the span of values it asks for.
 *
 * The span matters because it decides *what file the API serves*. Asked for an enumerated weight
 * list — the [googleFontsUrl] form — css2 answers with one **pinned static instance per weight**
 * (`font-weight: 100; font-stretch: 100%`). Asked for a range with the axes named, it answers with a
 * genuine variable face (`font-weight: 100 1000; font-stretch: 25% 151%`). Only the second can be
 * varied, so a document carrying axes has to ask the second way or its `wdth` ramp draws three
 * identical lines however correctly the canvas sets the axis.
 */
export interface AxisSpan {
    readonly tag: string;
    readonly min: number;
    readonly max: number;
}

/**
 * The css2 URL for [family] varying over [axes], or null when there is nothing to ask for.
 *
 * Ranges are built from the values the document *uses*, not from the family's declared bounds — the
 * player has no way to know those, and a range outside them is a 400. The document's own values are
 * inside them by construction.
 *
 * A degenerate span (one value) yields null on purpose: css2 rejects `wdth@25..25` with a 400, and a
 * single value needs no variable face anyway — the enumerated [googleFontsUrl] already covers it.
 * Axis tags must be listed alphabetically, which the API enforces, and the values follow in the same
 * order.
 */
export function googleFontsAxisUrl(
    family: string,
    axes: readonly AxisSpan[],
    baseUrl: string = config.baseUrl,
): string | null {
    const varying = axes.filter((a) => a.max > a.min).sort((a, b) => (a.tag < b.tag ? -1 : 1));
    if (varying.length === 0) return null;
    const tags = varying.map((a) => a.tag).join(',');
    const ranges = varying.map((a) => `${trimNumber(a.min)}..${trimNumber(a.max)}`).join(',');
    const name = encodeURIComponent(family.trim()).replace(/%20/g, '+');
    return `${baseUrl}?family=${name}:${tags}@${ranges}&display=block`;
}

/** `25` rather than `25.0` — css2 rejects a trailing `.0` on an axis bound. */
function trimNumber(value: number): string {
    return Number.isInteger(value) ? `${value}` : `${value}`.replace(/0+$/, '');
}

/** The namespace marking a family as one to fetch from Google Fonts. */
export const GOOGLE_PREFIX = 'google:';

/**
 * Split a document's family name into where the face comes from and what it is called.
 *
 * `"google:Space Grotesk"` → `{ source: 'google', name: 'Space Grotesk' }`; anything unprefixed →
 * `{ source: 'local', name: <as written> }`, meaning "whatever the host resolves this to", which is
 * the safe reading for a name we were given no provenance for.
 *
 * Note the returned `name` is always the *bare* family: it is what goes in the CSS stack and what is
 * asked of the API, so a stray prefix can never leak into either.
 */
export function parseFamily(family: string): { source: 'google' | 'local'; name: string } {
    const trimmed = family.trim();
    if (trimmed.toLowerCase().startsWith(GOOGLE_PREFIX)) {
        return { source: 'google', name: trimmed.slice(GOOGLE_PREFIX.length).trim() };
    }
    return { source: 'local', name: trimmed };
}

/**
 * Stylesheet registrations, keyed case-insensitively (CSS family names are ASCII-caseless). One per
 * family: the CSS response is small and declaring every weight costs nothing, because declaring a
 * face does not fetch it.
 */
const stylesheets = new Map<string, Promise<void>>();

/**
 * Face loads, keyed `family|weight|style`. Separate from [stylesheets] because *fetching* is
 * per-variant: a document that draws one regular label should pull Orbitron's regular face, not all
 * six weights the family publishes.
 */
const variants = new Map<string, Promise<void>>();

/** Shared embedded faces with per-player ownership. */
const embeddedFaces = new Map<string, { face?: FontFace; references: number }>();

/** Variants that have finished, so a settled one is never re-announced. See [notify]. */
const done = new Set<string>();

/** Callbacks still waiting on a variant, by variant key. Cleared when it settles. */
const waiting = new Map<string, Set<() => void>>();

function variantKey(
    family: string,
    weight: number,
    italic: boolean,
    axes: readonly { tag: string; value: number }[] = [],
): string {
    const axisPart = axes.map(({ tag, value }) => `${tag}=${value}`).join(',');
    return `${family.toLowerCase()}|${weight}|${italic ? 'i' : 'n'}|${axisPart}`;
}

/**
 * Run every callback waiting on [key], exactly once, and stop tracking it.
 *
 * One-shot is the whole point. Typeface resolution runs *per paint*, so `ensureWebFont` is called
 * again on every frame that draws the family — re-announcing an already-settled variant would
 * schedule a repaint from inside painting, which schedules another, forever. Equally, a caller that
 * arrives while the fetch is in flight has to be recorded rather than dropped: with two players on a
 * page, only the first would otherwise get its repaint, and a *static* document has no later frame
 * to recover on, so the second canvas would sit in the fallback face permanently.
 */
function notify(key: string): void {
    done.add(key);
    const listeners = waiting.get(key);
    waiting.delete(key);
    listeners?.forEach((fn) => fn());
}

/** Families already reported as unavailable, so a 400 is logged once rather than per paint. */
const failed = new Set<string>();

function unquote(family: string): string {
    return family.replace(/^["']|["']$/g, '');
}

/**
 * [family] escaped for use inside a double-quoted CSS string.
 *
 * Both `\` and `"` have to be escaped, and the backslash is not defensive boilerplate: escaping only
 * the quote leaves a family ending in a backslash (`My Font\`) emitting `"My Font\"`, where the
 * backslash escapes the *closing* quote so the string runs on and swallows the rest of the
 * declaration. Family names are document data — arbitrary strings from the text table — not
 * something we author.
 */
export function cssQuoted(family: string): string {
    return family.replace(/[\\"]/g, '\\$&');
}

/**
 * Whether the page already carries a face for [family] — a vendored `@font-face` the host inlined
 * (the parity harness does exactly this for Roboto / Noto Serif / Droid Sans Mono). A local face is
 * both faster and more faithful than the network copy, so it wins and no request is made.
 */
function hasLocalFace(family: string): boolean {
    const want = family.toLowerCase();
    let found = false;
    document.fonts.forEach((face: FontFace) => {
        if (unquote(face.family).toLowerCase() === want) found = true;
    });
    return found;
}

/**
 * Whether the page already carries a face for [family] that can be *varied* — one declaring a
 * `font-weight` or `font-stretch` **range** rather than a single value.
 *
 * The distinction matters because [hasLocalFace] is too coarse for an axis request: the enumerated
 * stylesheet has by then declared a pinned instance per weight, so "a face exists" is true while
 * "the axes can be applied to it" is false, and skipping the range request on that basis would leave
 * a `wdth` ramp drawing identical lines. A range in the declaration is exactly what a variable face
 * advertises (`font-weight: 100 1000`), so it is the right question to ask.
 */
/**
 * Families this module has itself asked the CSS API for a *variable* stylesheet.
 *
 * [hasLocalVariableFace] is the right question only about faces the **host** vendored. Once our own
 * axis request has landed, it answers yes for every later axis request for that family — including
 * one for an axis the declared face pins. A page that renders many documents (the `rc-compare`
 * harness renders a whole catalog into one page) therefore had its first axis win and every other
 * silently skipped: `Roboto Flex` registered over `wght` while painting the weight specimen, then
 * the width specimen's `wdth@25..151` request was dropped as "already variable" and its three lines
 * drew at one width. Document *order* decided it, which is why it reproduced in the catalog run and
 * not for the document alone (compose-ai-tools#4177).
 *
 * So the short-circuit consults this first: a family we asked for is not a local face, and a wider
 * span is worth a request. The URL carries every axis recorded so far ([recordAxes] accumulates),
 * so the second request supersedes the first rather than adding a competing narrow one, and
 * [stylesheets] still memoizes by URL — a repeat of the same span costs nothing.
 */
const ownVariableFamilies = new Set<string>();

function hasLocalVariableFace(family: string): boolean {
    const want = family.toLowerCase();
    let found = false;
    document.fonts.forEach((face: FontFace) => {
        if (unquote(face.family).toLowerCase() !== want) return;
        if (face.weight.includes(' ') || face.stretch.includes(' ')) found = true;
    });
    return found;
}

function loadStylesheet(url: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
        const link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = url;
        link.addEventListener('load', () => resolve());
        // Fires for a 400 (unknown family) as well as a genuine network failure; the two are not
        // distinguishable from here, and both mean "render the fallback".
        link.addEventListener('error', () => reject(new Error(`stylesheet did not load: ${url}`)));
        document.head.appendChild(link);
    });
}

/**
 * The widest span seen so far per family and axis, so a stylesheet can be asked for a range rather
 * than a point.
 *
 * Accumulated because axes arrive *one paint at a time*: the three lines of a `wdth` specimen are
 * three separate text ops carrying 25, then 100, then 151. Each on its own is a degenerate span
 * with no variable face to ask for; together they are the range the document needs. A single-shot
 * renderer awaits [webFontsReady] and repaints, so the frame it keeps is drawn after the full span
 * is known; an interactive one repaints through `onLoaded` as each wider face arrives.
 */
const axisSpans = new Map<string, Map<string, { min: number; max: number }>>();

/** Widen [family]'s recorded spans by [axes], and return them all. */
function recordAxes(family: string, axes: readonly { tag: string; value: number }[]): AxisSpan[] {
    const key = family.toLowerCase();
    const spans = axisSpans.get(key) ?? new Map<string, { min: number; max: number }>();
    for (const { tag, value } of axes) {
        const span = spans.get(tag);
        if (span) {
            span.min = Math.min(span.min, value);
            span.max = Math.max(span.max, value);
        } else {
            spans.set(tag, { min: value, max: value });
        }
    }
    axisSpans.set(key, spans);
    return [...spans].map(([tag, { min, max }]) => ({ tag, min, max }));
}

/**
 * Declare [family]'s faces on the page, once. Resolves having declared nothing when disabled.
 *
 * [url] defaults to the enumerated request; an axis request passes its own, and says so with
 * [variable] so the "already have it locally" check asks whether the local face can be *varied*
 * rather than merely whether one exists — by then this function's own enumerated request has
 * declared several, none of them variable.
 */
function registerStylesheet(
    family: string,
    url: string = googleFontsUrl(family),
    variable = false,
): Promise<void> {
    const key = `${family.toLowerCase()}|${url}`;
    const existing = stylesheets.get(key);
    if (existing) return existing;
    let p: Promise<void>;
    if (!config.enabled) {
        p = Promise.resolve();
    } else if (typeof document === 'undefined' || !document.fonts) {
        // The bundle also runs under node-canvas, where there is no document and no font registry;
        // a named family there simply falls through to the fallback generic.
        p = Promise.resolve();
    } else if (
        variable
            ? !ownVariableFamilies.has(family.toLowerCase()) && hasLocalVariableFace(family)
            : hasLocalFace(family)
    ) {
        // The page already carries a vendored face for this family — faster and more faithful than
        // the network copy, so it wins and no request is made.
        p = Promise.resolve();
    } else {
        if (variable) ownVariableFamilies.add(family.toLowerCase());
        p = loadStylesheet(url);
    }
    stylesheets.set(key, p);
    return p;
}

/**
 * Fetch the face for one (weight, style), and verify it arrived.
 *
 * `@font-face` is lazy and canvas does not drive it: `ctx.font` neither triggers a load nor waits
 * for one, and `document.fonts.ready` resolves while a declared face is still `unloaded` — so
 * without an explicit load the page reports the family, the shorthand names it, and the canvas
 * still paints the fallback.
 *
 * The load is asked for *by font shorthand* rather than by walking `document.fonts` and calling
 * `.load()` on every face carrying this family. Both make the face paintable, but the shorthand
 * routes through the browser's own CSS font matching and so fetches only the face that a request at
 * this weight/style actually resolves to. Loading them all would pull every weight the family
 * publishes — six files for Orbitron — to draw one regular label, and would hold `fontsReady()` open
 * until the unused ones finished, which is exactly the wait a single-shot renderer is blocked on.
 */
async function loadVariant(
    family: string,
    weight: number,
    italic: boolean,
    axes: readonly { tag: string; value: number }[],
): Promise<void> {
    // The enumerated stylesheet always: it is what serves a static family, and it is the fallback
    // when the axis range below is refused (a family that publishes no variable face 400s on any
    // range, which from the `<link>` is indistinguishable from a network failure).
    await registerStylesheet(family);
    if (axes.length > 0) {
        const url = googleFontsAxisUrl(family, recordAxes(family, axes));
        // Failure here is not the caller's problem: the enumerated faces are already declared, so a
        // refused range means the axes go unapplied rather than the family going unpainted.
        if (url) await registerStylesheet(family, url, true).catch(() => {});
    }
    if (typeof document === 'undefined' || !document.fonts) return;
    await document.fonts.load(`${italic ? 'italic ' : ''}${weight} 16px "${cssQuoted(family)}"`);
}

/**
 * Make [family] paintable at [weight]/[italic], and run [onLoaded] once it is.
 *
 * Idempotent per variant: the first call starts the work, later ones join it. Never rejects — a
 * family Google doesn't serve is a document authored against a font we can't get, not a player
 * fault, and the CSS stack already carries a generic fallback for exactly that case. [onLoaded] is
 * how an interactive player repaints text that was first painted in the fallback; see [notify] for
 * why it is recorded per caller and fired exactly once.
 */
export function ensureWebFont(
    family: string,
    weight: number = 400,
    italic: boolean = false,
    onLoaded?: () => void,
    axes: readonly { tag: string; value: number }[] = [],
): Promise<void> {
    // The axis values are part of the variant key, so a second line of a `wdth` ramp is a *new*
    // variant rather than a hit on the first one's promise — which is what gets the wider range
    // requested at all. Without it the family would be marked loaded at the first value seen.
    const key = variantKey(family, weight, italic, axes);
    if (onLoaded && !done.has(key)) {
        const set = waiting.get(key) ?? new Set<() => void>();
        set.add(onLoaded);
        waiting.set(key, set);
    }
    const existing = variants.get(key);
    if (existing) return existing;
    const p = loadVariant(family, weight, italic, axes)
        .catch((e) => {
            const famKey = family.toLowerCase();
            if (!failed.has(famKey)) {
                failed.add(famKey);
                console.warn(`WebFonts: no web font for "${family}", using the fallback stack`, e);
            }
        })
        .then(() => notify(key));
    variants.set(key, p);
    return p;
}

/** Stable, CSS-safe family alias for an embedded face. */
function embeddedFamily(fontId: number, data: Uint8Array): string {
    // Font ids are document-local and commonly start at 42, so the bytes must participate in the
    // alias: two players on one page can otherwise register different faces under the same family.
    let hash = 0x811C9DC5;
    for (const byte of data) {
        hash ^= byte;
        hash = Math.imul(hash, 0x01000193);
    }
    return `__rc_font_${fontId}_${data.length}_${(hash >>> 0).toString(16)}`;
}

function uint16(data: Uint8Array, offset: number): number {
    return (data[offset] << 8) | data[offset + 1];
}

function uint32(data: Uint8Array, offset: number): number {
    return ((data[offset] << 24) | (data[offset + 1] << 16) |
        (data[offset + 2] << 8) | data[offset + 3]) >>> 0;
}

function fixed1616(data: Uint8Array, offset: number): number {
    return (uint32(data, offset) | 0) / 65536;
}

function asciiTag(data: Uint8Array, offset: number): string {
    return String.fromCharCode(data[offset], data[offset + 1], data[offset + 2], data[offset + 3]);
}

/**
 * FontFace descriptors advertised by an embedded OpenType variable font.
 *
 * Browsers only apply `wght`/`wdth` requests when the registered face declares the corresponding
 * range. FontData does not carry separate metadata, so read the standard `fvar` table directly.
 * Invalid, WOFF-only, or non-variable data simply returns an empty descriptor bag.
 */
export function embeddedFontDescriptors(data: Uint8Array): FontFaceDescriptors {
    try {
        let sfnt = 0;
        if (data.length >= 16 && asciiTag(data, 0) === 'ttcf') {
            if (uint32(data, 8) < 1) return {};
            sfnt = uint32(data, 12);
        }
        if (sfnt + 12 > data.length) return {};
        const tables = uint16(data, sfnt + 4);
        let fvar = -1;
        for (let i = 0; i < tables; i++) {
            const record = sfnt + 12 + i * 16;
            if (record + 16 > data.length) return {};
            if (asciiTag(data, record) === 'fvar') {
                fvar = uint32(data, record + 8);
                break;
            }
        }
        if (fvar < 0 || fvar + 16 > data.length) return {};
        const axesOffset = uint16(data, fvar + 4);
        const axisCount = uint16(data, fvar + 8);
        const axisSize = uint16(data, fvar + 10);
        if (axisSize < 20) return {};
        const descriptors: FontFaceDescriptors = {};
        for (let i = 0; i < axisCount; i++) {
            const axis = fvar + axesOffset + i * axisSize;
            if (axis + 20 > data.length) return {};
            const tag = asciiTag(data, axis);
            const min = fixed1616(data, axis + 4);
            const max = fixed1616(data, axis + 12);
            if (!Number.isFinite(min) || !Number.isFinite(max) || max < min) continue;
            if (tag === 'wght') descriptors.weight = `${trimNumber(min)} ${trimNumber(max)}`;
            if (tag === 'wdth') descriptors.stretch = `${trimNumber(min)}% ${trimNumber(max)}%`;
        }
        return descriptors;
    } catch (_) {
        return {};
    }
}

/**
 * Register FontData bytes with the browser and return the family name canvas can use immediately.
 *
 * The load joins [webFontsReady], just like a downloadable named face. Interactive players receive
 * [onLoaded] once and repaint; single-shot renderers await the same promise before keeping a frame.
 */
export function registerEmbeddedFont(
    fontId: number,
    data: Uint8Array,
    onLoaded?: () => void,
): string {
    const family = embeddedFamily(fontId, data);
    const key = `embedded|${family}`;
    if (onLoaded && !done.has(key)) {
        const set = waiting.get(key) ?? new Set<() => void>();
        set.add(onLoaded);
        waiting.set(key, set);
    }
    const retained = embeddedFaces.get(key);
    if (retained) {
        retained.references += 1;
        return family;
    }

    let load: Promise<void>;
    if (typeof document === 'undefined' || !document.fonts || typeof FontFace === 'undefined') {
        // node-canvas has no FontFaceSet. Keep the alias/fallback behaviour deterministic there.
        embeddedFaces.set(key, { references: 1 });
        load = Promise.resolve();
    } else {
        // Copy the exact view: a WireBuffer may be backed by a larger allocation than this font.
        const bytes = data.slice().buffer;
        const face = new FontFace(family, bytes, embeddedFontDescriptors(data));
        embeddedFaces.set(key, { face, references: 1 });
        document.fonts.add(face);
        load = face.load().then(() => undefined).catch((e) => {
            console.warn(`WebFonts: embedded font ${fontId} could not be loaded`, e);
        });
    }
    const promise = load.then(() => { if (embeddedFaces.has(key)) notify(key); });
    variants.set(key, promise);
    return family;
}

/** Release one player's ownership of an embedded face. */
export function releaseEmbeddedFont(
    fontId: number,
    data: Uint8Array,
    onLoaded?: () => void,
): void {
    const key = `embedded|${embeddedFamily(fontId, data)}`;
    const listeners = waiting.get(key);
    if (onLoaded) listeners?.delete(onLoaded);
    if (listeners?.size === 0) waiting.delete(key);
    const retained = embeddedFaces.get(key);
    if (!retained) return;
    retained.references -= 1;
    if (retained.references > 0) return;
    if (retained.face && typeof document !== 'undefined' && document.fonts) {
        document.fonts.delete(retained.face);
    }
    embeddedFaces.delete(key);
    variants.delete(key);
    done.delete(key);
    waiting.delete(key);
}

/**
 * Resolves when every family requested so far has settled.
 *
 * A single-shot renderer (the parity harness, any screenshot path) has no second frame in which a
 * late face could appear, so it must await this between loading the document and painting the frame
 * it keeps. An interactive player doesn't need it — it gets the repaint via `onLoaded`.
 */
export async function webFontsReady(): Promise<void> {
    // Re-read after awaiting: painting a frame can request a variant we hadn't seen when we started.
    let pending = [...variants.values()];
    while (pending.length > 0) {
        await Promise.all(pending);
        const next = [...variants.values()];
        if (next.length === pending.length) break;
        pending = next;
    }
}

/** Drop all registration state. Tests only. */
export function resetWebFonts(): void {
    if (typeof document !== 'undefined' && document.fonts) {
        embeddedFaces.forEach(({ face }) => { if (face) document.fonts.delete(face); });
    }
    embeddedFaces.clear();
    stylesheets.clear();
    axisSpans.clear();
    ownVariableFamilies.clear();
    variants.clear();
    done.clear();
    waiting.clear();
    failed.clear();
    config = { enabled: true, baseUrl: DEFAULT_BASE_URL };
}
