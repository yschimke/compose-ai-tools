/**
 * Base class for custom elements that enhance server-rendered light DOM.
 *
 * These elements own behaviour, not markup. Keeping their lifecycle independent
 * of a rendering framework avoids mounting a Vue application where there is
 * nothing for Vue to render, while preserving the custom-element contract used
 * by the Kotlin pages.
 */
export class ControllerElement extends HTMLElement {
    private lifecycleCleanups: Array<() => void> = [];

    connectedCallback(): void {}

    disconnectedCallback(): void {
        for (const cleanup of this.lifecycleCleanups.splice(0).reverse())
            cleanup();
    }

    /** Add an event listener whose lifetime is the current element connection. */
    protected listen(
        target: EventTarget,
        type: string,
        listener: EventListenerOrEventListenerObject,
        options?: boolean | AddEventListenerOptions,
    ): void {
        target.addEventListener(type, listener, options);
        this.lifecycleCleanups.push(() =>
            target.removeEventListener(type, listener, options),
        );
    }
}

/** Define a controller element with the same compact class decorator syntax. */
export function customElement(tagName: string) {
    return <T extends CustomElementConstructor>(constructor: T): T => {
        if (!customElements.get(tagName)) {
            customElements.define(tagName, constructor);
        }
        return constructor;
    };
}
