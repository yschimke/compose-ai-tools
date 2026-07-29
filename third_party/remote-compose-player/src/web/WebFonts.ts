// WebFonts: on-demand registration of *named* font families, served from Google Fonts.
//
// A Remote Compose document names its typeface in one of two ways. The four generic ids
// (`0=DEFAULT, 1=SANS_SERIF, 2=SERIF, 3=MONOSPACE`) are a closed set that `cssFontStackFor` maps to
// the concrete faces Android resolves them to. Anything else — `RemoteFontFamily.Named("Orbitron")`
// — reaches the paint layer as the document's *text id* for the family name, not as a name, because
// `CoreText.updateVariables` falls through to `mType = mFontFamilyId` for a family it doesn't
// recognise. Resolving that id back through the text table is what turns it into a family a browser
// can be asked for; this module is what makes the ask resolvable, by registering the face.
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

/** Variants that have finished, so a settled one is never re-announced. See [notify]. */
const done = new Set<string>();

/** Callbacks still waiting on a variant, by variant key. Cleared when it settles. */
const waiting = new Map<string, Set<() => void>>();

function variantKey(family: string, weight: number, italic: boolean): string {
    return `${family.toLowerCase()}|${weight}|${italic ? 'i' : 'n'}`;
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

/** Declare [family]'s faces on the page, once. Resolves having declared nothing when disabled. */
function registerStylesheet(family: string): Promise<void> {
    const key = family.toLowerCase();
    const existing = stylesheets.get(key);
    if (existing) return existing;
    let p: Promise<void>;
    if (!config.enabled) {
        p = Promise.resolve();
    } else if (typeof document === 'undefined' || !document.fonts) {
        // The bundle also runs under node-canvas, where there is no document and no font registry;
        // a named family there simply falls through to the fallback generic.
        p = Promise.resolve();
    } else if (hasLocalFace(family)) {
        // The page already carries a vendored face for this family — faster and more faithful than
        // the network copy, so it wins and no request is made.
        p = Promise.resolve();
    } else {
        p = loadStylesheet(googleFontsUrl(family));
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
async function loadVariant(family: string, weight: number, italic: boolean): Promise<void> {
    await registerStylesheet(family);
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
): Promise<void> {
    const key = variantKey(family, weight, italic);
    if (onLoaded && !done.has(key)) {
        const set = waiting.get(key) ?? new Set<() => void>();
        set.add(onLoaded);
        waiting.set(key, set);
    }
    const existing = variants.get(key);
    if (existing) return existing;
    const p = loadVariant(family, weight, italic)
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
    stylesheets.clear();
    variants.clear();
    done.clear();
    waiting.clear();
    failed.clear();
    config = { enabled: true, baseUrl: DEFAULT_BASE_URL };
}
