// 2D ⇄ 3D toggle for the preview panel. Owns the toggle button, the lazy load
// of the separately-bundled three.js viewer (`media/webview/spatial.js`), and
// the show/hide of the 3D `<spatial-view>` over the 2D card grid.
//
// A self-contained controller in the spirit of the panel's other per-concern
// controllers (FilterController, FocusController, …) so `main.ts` only has to
// construct it and forward the `setSpatialScene` message — no parallel
// module-level state (see docs/AGENTS.md "State seams").
//
// The viewer bundle is loaded only on the first switch to 3D: panels that never
// receive a `SpatialScene` never pay the ~560 KB parse cost, and the toggle
// button stays hidden until a scene arrives so non-XR panels look unchanged.

import type { SpatialScene } from "../shared/spatialScene";
import type { SpatialSemanticsTree } from "../shared/spatialSemanticsTree";

/** The `<spatial-view>` element's public surface this controller drives. */
interface SpatialViewElement extends HTMLElement {
    scene: SpatialScene | null;
    /** Optional companion semantics tree — overlays wireframes on the panel faces. */
    semanticsTree?: SpatialSemanticsTree | null;
    resolveTextureUrl?: (texture: string) => string;
    focusPanel?(panelId: string): void;
}

/** Injectable side effects so the controller is unit-testable without a real
 *  bundle / custom-element registry. */
export interface SpatialToggleEffects {
    /** Already registered? Lets a re-toggle skip the bundle load. */
    isDefined(tagName: string): boolean;
    /** Inject the viewer bundle; resolves once it has run. */
    loadBundle(src: string, nonce: string | null): Promise<void>;
    /** Resolve once the custom element is registered. */
    whenDefined(tagName: string): Promise<unknown>;
    /** Create the viewer element (overridable in tests). */
    createView(): SpatialViewElement;
}

const TAG = "spatial-view";

function defaultEffects(): SpatialToggleEffects {
    return {
        isDefined: (tag) =>
            typeof customElements !== "undefined" &&
            customElements.get(tag) !== undefined,
        loadBundle: (src, nonce) =>
            new Promise<void>((resolve, reject) => {
                const script = document.createElement("script");
                script.src = src;
                if (nonce) script.nonce = nonce;
                script.onload = () => resolve();
                script.onerror = () =>
                    reject(new Error(`failed to load ${src}`));
                document.body.appendChild(script);
            }),
        whenDefined: (tag) => customElements.whenDefined(tag),
        createView: () => document.createElement(TAG) as SpatialViewElement,
    };
}

export interface SpatialToggleDeps {
    /** The button that flips between 2D and 3D. */
    toggleButton: HTMLButtonElement;
    /** Container the `<spatial-view>` is mounted into; hidden in 2D mode. */
    mount: HTMLElement;
    /** The 2D surface (card grid + stage) to hide while 3D is showing. */
    twoDStage: HTMLElement;
    /** Webview URI of the viewer bundle (`data-spatial-src`). */
    bundleSrc: string | null;
    /** CSP nonce for the injected script (`data-csp-nonce`). */
    nonce: string | null;
    /** Notified on click-to-focus inside the 3D view. */
    onPanelFocus?: (panelId: string) => void;
    effects?: Partial<SpatialToggleEffects>;
}

export class SpatialToggleController {
    private readonly effects: SpatialToggleEffects;
    private mode: "2d" | "3d" = "2d";
    private scene: SpatialScene | null = null;
    private semanticsTree: SpatialSemanticsTree | null = null;
    private textureBaseUri = "";
    private view: SpatialViewElement | null = null;
    private bundlePromise: Promise<void> | null = null;
    private viewReady: Promise<void> | null = null;

    constructor(private readonly deps: SpatialToggleDeps) {
        this.effects = { ...defaultEffects(), ...deps.effects };
        deps.toggleButton.addEventListener("click", () => {
            void this.toggle();
        });
        // Hidden until a scene arrives — non-XR panels show no extra chrome.
        deps.toggleButton.hidden = true;
        this.reflectButton();
    }

    /** Current view mode. */
    get currentMode(): "2d" | "3d" {
        return this.mode;
    }

    /** Whether a scene is available to show. */
    get hasScene(): boolean {
        return this.scene !== null;
    }

    /**
     * Install a scene + the base URI its relative texture paths resolve
     * against. Reveals the toggle button and, if already in 3D, repaints. The
     * optional [semanticsTree] companion overlays each panel's 2D wireframe onto
     * its screenshot face (matched to the scene by panel id).
     */
    setScene(
        scene: SpatialScene,
        textureBaseUri: string,
        semanticsTree: SpatialSemanticsTree | null = null,
    ): void {
        this.scene = scene;
        this.semanticsTree = semanticsTree;
        this.textureBaseUri = textureBaseUri;
        this.deps.toggleButton.hidden = false;
        if (this.mode === "3d" && this.view) {
            this.applyScene();
        }
        this.reflectButton();
    }

    /** Flip between 2D and 3D. */
    toggle(): Promise<void> {
        return this.setMode(this.mode === "2d" ? "3d" : "2d");
    }

    /** Switch to an explicit mode. Lazily loads the viewer on first 3D entry. */
    async setMode(mode: "2d" | "3d"): Promise<void> {
        if (mode === "3d") {
            await this.ensureView();
            this.deps.mount.hidden = false;
            this.deps.twoDStage.hidden = true;
            this.applyScene();
        } else {
            this.deps.mount.hidden = true;
            this.deps.twoDStage.hidden = false;
        }
        this.mode = mode;
        this.reflectButton();
    }

    /** Mirror 2D selection into the 3D view (and switch to it). */
    async focusPanel(panelId: string): Promise<void> {
        await this.setMode("3d");
        this.view?.focusPanel?.(panelId);
    }

    private ensureView(): Promise<void> {
        if (this.view) return Promise.resolve();
        // Serialize onto a single promise so a rapid double-toggle (two
        // setMode("3d") calls before the lazy bundle load + whenDefined
        // settle) doesn't run `createView` twice and leave two
        // `<spatial-view>` canvases / render loops in the mount. Clear it on
        // failure so a later retry (e.g. after the bundle src is configured)
        // can start fresh rather than re-throwing a cached rejection.
        this.viewReady ??= this.createView().catch((err) => {
            this.viewReady = null;
            throw err;
        });
        return this.viewReady;
    }

    private async createView(): Promise<void> {
        if (!this.effects.isDefined(TAG)) {
            if (!this.deps.bundleSrc) {
                throw new Error(
                    "spatial-view bundle source is not configured (data-spatial-src)",
                );
            }
            this.bundlePromise ??= this.effects.loadBundle(
                this.deps.bundleSrc,
                this.deps.nonce,
            );
            await this.bundlePromise;
            await this.effects.whenDefined(TAG);
        }
        if (this.view) return;
        const view = this.effects.createView();
        view.resolveTextureUrl = (tex) => this.textureBaseUri + tex;
        if (this.deps.onPanelFocus) {
            view.addEventListener("panel-focus", (e) => {
                const id = (e as CustomEvent<{ panelId: string }>).detail
                    ?.panelId;
                if (id) this.deps.onPanelFocus?.(id);
            });
        }
        this.deps.mount.appendChild(view);
        this.view = view;
    }

    private applyScene(): void {
        if (this.view) {
            this.view.semanticsTree = this.semanticsTree;
            this.view.scene = this.scene;
        }
    }

    private reflectButton(): void {
        const btn = this.deps.toggleButton;
        const in3d = this.mode === "3d";
        btn.setAttribute("aria-pressed", in3d ? "true" : "false");
        btn.title = in3d ? "Switch to 2D preview" : "Switch to 3D spatial view";
        btn.setAttribute(
            "aria-label",
            in3d ? "Switch to 2D preview" : "Switch to 3D spatial view",
        );
    }
}
