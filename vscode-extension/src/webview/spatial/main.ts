// Bundled entry for the 3D spatial-layout viewer.
//
// Defines a `<spatial-view>` light-DOM Lit element that owns a
// [SpatialViewer](spatialViewer.ts) (three.js) over its host box. The element
// is the seam the panel's 2D ⇄ 3D toggle mounts: feed it a `SpatialScene`
// ([../shared/spatialScene.ts]) and a `resolveTextureUrl` for CSP-correct
// texture loading, and it renders panels/orbiters as textured quads with
// orbit/pan/zoom and click-to-focus.
//
// three.js is bundled into this entry (its own `media/webview/spatial.js`) so
// the ~560 KB lib only loads when the 3D view is actually requested, rather
// than bloating the main panel bundle. The element dispatches a `panel-focus`
// CustomEvent on click-to-focus so the host can mirror selection into the rest
// of the panel.

import { LitElement, html, css, type TemplateResult } from "lit";
import { customElement, property } from "lit/decorators.js";
import type { SpatialScene } from "../shared/spatialScene";
import { parseSpatialSceneJson } from "./sceneLoader";
import { SpatialViewer } from "./spatialViewer";

@customElement("spatial-view")
export class SpatialView extends LitElement {
    static styles = css`
        :host {
            display: block;
            position: relative;
            width: 100%;
            height: 100%;
            min-height: 240px;
        }
        .spatial-stage {
            position: absolute;
            inset: 0;
            overflow: hidden;
        }
        .spatial-canvas {
            display: block;
        }
        .spatial-empty {
            position: absolute;
            inset: 0;
            display: flex;
            align-items: center;
            justify-content: center;
            color: var(--vscode-descriptionForeground, #888);
            font-size: 12px;
            pointer-events: none;
        }
    `;

    /** The scene to render. Set directly, or via `setSceneJson`. */
    @property({ attribute: false })
    scene: SpatialScene | null = null;

    /** Texture-reference → loadable-URL resolver handed to the viewer. */
    @property({ attribute: false })
    resolveTextureUrl?: (texture: string) => string;

    private viewer?: SpatialViewer;
    private stage?: HTMLDivElement;

    protected render(): TemplateResult {
        return html`
            <div class="spatial-stage"></div>
            ${this.scene
                ? ""
                : html`<div class="spatial-empty">
                      No spatial scene loaded
                  </div>`}
        `;
    }

    protected firstUpdated(): void {
        this.stage =
            this.renderRoot.querySelector<HTMLDivElement>(".spatial-stage") ??
            undefined;
        this.ensureViewer();
        if (this.scene) {
            this.viewer?.load(this.scene);
        }
    }

    protected updated(changed: Map<string, unknown>): void {
        if (changed.has("scene") && this.viewer && this.scene) {
            this.viewer.load(this.scene);
        }
    }

    disconnectedCallback(): void {
        super.disconnectedCallback();
        this.viewer?.dispose();
        this.viewer = undefined;
    }

    /** Parse + load a scene from raw JSON text (throws on contract violation). */
    setSceneJson(text: string): void {
        this.scene = parseSpatialSceneJson(text);
    }

    /**
     * Frame the camera on a panel and highlight it. The seam the host uses to
     * mirror 2D selection into the 3D view (click a preview card → focus its
     * panel here).
     */
    focusPanel(panelId: string): void {
        this.viewer?.focusPanel(panelId);
    }

    private ensureViewer(): void {
        if (this.viewer || !this.stage) return;
        this.viewer = new SpatialViewer(this.stage, {
            // Late-bound: callers often set `resolveTextureUrl` after the
            // element upgrades (so the viewer may already be constructed by
            // then), and the resolver can change between scene loads. Read it
            // off the element at call time rather than capturing it here.
            resolveTextureUrl: (tex) =>
                this.resolveTextureUrl ? this.resolveTextureUrl(tex) : tex,
            onPanelFocus: (panelId) => {
                this.dispatchEvent(
                    new CustomEvent("panel-focus", {
                        detail: { panelId },
                        bubbles: true,
                        composed: true,
                    }),
                );
            },
        });
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "spatial-view": SpatialView;
    }
}
