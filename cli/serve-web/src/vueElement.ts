import { render, type VNode } from "vue";
import { ControllerElement } from "./controllerElement.js";

/**
 * Light-DOM custom element whose markup is patched by Vue.
 *
 * The preview server keeps custom elements as its Kotlin/TypeScript boundary.
 * This adapter gives those elements Vue's safe renderer and event handling
 * without adding a client-side application shell or shadow DOM.
 */
export abstract class VueElement extends ControllerElement {
    private mounted = false;
    private hasDetachedSnapshot = false;

    override connectedCallback(): void {
        super.connectedCallback();
        if (this.mounted) return;
        // disconnectedCallback leaves an inert copy behind so detached elements
        // remain inspectable. It is not Vue-owned DOM, so discard it before the
        // new renderer mounts; otherwise a move duplicates the whole control.
        if (this.hasDetachedSnapshot) {
            this.replaceChildren();
            this.hasDetachedSnapshot = false;
        }
        this.mounted = true;
        render(this.renderVue(), this);
    }

    override disconnectedCallback(): void {
        // Vue unmounts event listeners and effects; keep inert clones of the last
        // painted light DOM so moving or inspecting a detached element does not
        // make its UI disappear. A reconnect patches fresh live nodes over them.
        const snapshot = [...this.childNodes].map((node) =>
            node.cloneNode(true),
        );
        this.mounted = false;
        render(null, this);
        this.replaceChildren(...snapshot);
        this.hasDetachedSnapshot = true;
        super.disconnectedCallback();
    }

    /** Schedule a synchronous Vue patch after class-owned state changes. */
    protected requestUpdate(): void {
        if (this.mounted) render(this.renderVue(), this);
    }

    protected abstract renderVue(): VNode | null;
}
