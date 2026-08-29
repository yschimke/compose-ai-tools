/**
 * Base class for custom elements that enhance server-rendered light DOM.
 *
 * These elements own behaviour, not markup. Keeping their lifecycle independent
 * of a rendering framework avoids mounting a Vue application where there is
 * nothing for Vue to render, while preserving the custom-element contract used
 * by the Kotlin pages.
 */
export class ControllerElement extends HTMLElement {
    connectedCallback(): void {}

    disconnectedCallback(): void {}
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
