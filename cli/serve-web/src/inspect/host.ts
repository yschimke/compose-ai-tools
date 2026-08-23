// Which surface `<cp-inspect-layers>` is drawing over.
//
// The element used to reach straight for the viewer's own ids — `.cp-viewer`, `#cp-img`,
// `#cp-inspect-layer`, `#cp-inspect-legend`, `.cp-inspect` — which is why the derived semantics
// layers (typography, theme, layout projected from the render's own semantics tree) were viewer-only
// for as long as they existed. The focused comparison needs the same layers over its Actual panel,
// and it has none of those ids: its frame is one `<img>` inside a `.cp-compare-shot`, its legend
// sits under the grid, and its toggles are page-level controls beside the authored redline's.
//
// So the wiring is a value now, resolved once at install time. The viewer's tag carries no
// attributes and gets exactly what it always got; a tag that names a host reads its parts from
// there instead. Everything downstream — fetching, drawing, placing — takes the descriptor and has
// no idea which page it is on.

import { baseFrom } from "./layers.js";

/**
 * How a layer is positioned over the frame it describes.
 *
 * `offset` is the viewer: the stage centres a frame inside a box wider than it, so the layer has to
 * sit at the image's own `offsetLeft`/`offsetTop` or every box drifts left by half the slack.
 *
 * `centred` is a panel whose stylesheet already centres the layer over the shot (`.cp-compare-shot`
 * is `display: grid; place-items: center`). There the layer needs its *size* set and nothing else —
 * writing `left`/`top` would fight the `translate(-50%, -50%)` that puts it there.
 */
export type LayerAnchor = "offset" | "centred";

/** The DOM one mounted set of inspection layers works against. */
export interface InspectHost {
    /** Carries `data-inspect` while a layer is on, and names the preview being inspected. */
    root: HTMLElement;
    /** The Compose render every non-spec layer is placed over. */
    frame: HTMLImageElement;
    /** The alternate raster the viewer's spec view puts on the stage. Null off the viewer. */
    specFrame: HTMLImageElement | null;
    /** Absolutely-positioned container the boxes are appended to. */
    layer: HTMLElement;
    /** The readable half — one section per layer, one row per box. */
    legend: HTMLElement;
    /** The checkboxes that decide which layers are on, each carrying `data-cp-inspect`. */
    toggles: HTMLInputElement[];
    /**
     * The attribute holding the URL of the frame currently **decoded**.
     *
     * The viewer swaps its frame as the knobs change and stamps `data-cp-src` once the replacement
     * has decoded, so that attribute — not `src` — is the honest "these are the pixels on screen"
     * signal there. A server-rendered panel never swaps its frame, so its `src` is that signal and
     * reading `data-cp-src` would leave it with no address at all until the fallback kicked in.
     */
    frameSource: string;
    /** Whether this host has the viewer's spec / comparison modes at all. */
    hasSpecModes: boolean;
    /** See [LayerAnchor]. */
    anchor: LayerAnchor;
    /**
     * Whether a click on a box means "report this part of the render".
     *
     * False on the viewer, where a box is a reading aid and a click means nothing — and where
     * adding one would change a shipped page's behaviour for no reason. True on the focused
     * comparison, which is the page a report is filed from: the brief's two ways to choose are
     * clicking an annotated element and dragging a region, and this is the first of them.
     */
    selectable: boolean;
    /** The prefix render URLs hang off, for the address to use before any frame has decoded. */
    base: string;
}

/**
 * The viewer's wiring — the one this element was written for, unchanged.
 *
 * Returns null when any load-bearing part is missing, which is what makes the tag inert on a viewer
 * whose host can produce none of the inspection products.
 */
export function viewerHost(): InspectHost | null {
    const root = document.querySelector<HTMLElement>(".cp-viewer");
    const frame = document.getElementById("cp-img") as HTMLImageElement | null;
    const layer = document.getElementById("cp-inspect-layer");
    const legend = document.getElementById("cp-inspect-legend");
    const toggles = Array.from(
        document.querySelectorAll<HTMLInputElement>(".cp-inspect"),
    );
    if (!root || !frame || !layer || !legend || !toggles.length) return null;
    return {
        root,
        frame,
        specFrame: document.getElementById(
            "cp-spec-img",
        ) as HTMLImageElement | null,
        layer,
        legend,
        toggles,
        frameSource: "data-cp-src",
        hasSpecModes: true,
        anchor: "offset",
        selectable: false,
        base: baseFrom(location.pathname),
    };
}

/**
 * A panel's wiring, named by attributes on the mount tag.
 *
 * Explicit selectors rather than a fixed shape under the host, because the parts genuinely do not
 * nest: on the focused comparison the frame is inside the Actual panel while the legend is a
 * sibling of the whole grid and the toggles sit in the page's control bar. Anything the page does
 * not name is not defaulted to a viewer id — it makes the mount inert, which is the safe direction
 * for a page that simply has no derived layers to show.
 */
export function panelHost(mount: HTMLElement): InspectHost | null {
    const select = <T extends Element>(name: string): T | null => {
        const selector = mount.getAttribute(name);
        return selector ? document.querySelector<T>(selector) : null;
    };
    const root = select<HTMLElement>("data-cp-host");
    const layer = select<HTMLElement>("data-cp-layer");
    const legend = select<HTMLElement>("data-cp-legend");
    const toggleSelector = mount.getAttribute("data-cp-toggles");
    const toggles = toggleSelector
        ? Array.from(
              document.querySelectorAll<HTMLInputElement>(toggleSelector),
          )
        : [];
    if (!root || !layer || !legend || !toggles.length) return null;
    const frame = root.querySelector("img");
    if (!frame) return null;
    return {
        root,
        frame,
        specFrame: null,
        layer,
        legend,
        toggles,
        frameSource: "src",
        hasSpecModes: false,
        anchor: "centred",
        selectable: mount.hasAttribute("data-cp-selectable"),
        base: mount.getAttribute("data-cp-base") ?? baseFrom(location.pathname),
    };
}

/** The host a mounted tag means: the one it names, else the viewer's. */
export function resolveHost(mount: HTMLElement): InspectHost | null {
    return mount.hasAttribute("data-cp-host") ? panelHost(mount) : viewerHost();
}
