// `<message-banner>` — replaces the imperative `messageEl` /
// `setMessage` / `ensureNotBlank` plumbing for the small status strip
// under the toolbar.
//
// Three callers share the strip and the owner tag is how they
// coordinate without trampling each other:
//
//  - `extension`: terminal text the extension asserts (build errors,
//    empty-state notices, "Building…"). `applyFilters` and
//    `ensureNotBlank` never overwrite this.
//  - `filter`: "No previews match the current filters" surfaced by
//    `applyFilters` — only present when the filter is narrowing the
//    grid down to nothing. Cleared by the same code on the next
//    filter change.
//  - `fallback`: the seeded "Loading Compose previews…" placeholder
//    and the `ensureNotBlank` safety-net text. Cleared by the first
//    real card or message that arrives.
//
// `<message-banner>` listens for `showMessage` (extension path)
// directly, exposes `setMessage(text, owner)` / `clear(owner)` /
// `getOwner()` / `isVisible()` for the in-webview callers in
// `behavior.ts`, and renders the strip itself.

import { LitElement, html, nothing, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";

export type MessageOwner = "extension" | "filter" | "fallback";

interface ShowMessageMessage {
    command: "showMessage";
    text?: string;
}

type IncomingMessage = ShowMessageMessage | { command: string };

@customElement("message-banner")
export class MessageBanner extends LitElement {
    @state() private text = "";
    @state() private owner: MessageOwner | null = null;

    private readonly onMessage = (
        event: MessageEvent<IncomingMessage>,
    ): void => {
        const msg = event.data;
        if (msg?.command === "showMessage") {
            this.setMessage(
                (msg as ShowMessageMessage).text ?? "",
                "extension",
            );
        }
    };

    // Light DOM so existing `media/preview.css` `.message` rules apply
    // unchanged.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    connectedCallback(): void {
        super.connectedCallback();
        window.addEventListener("message", this.onMessage);
    }

    disconnectedCallback(): void {
        window.removeEventListener("message", this.onMessage);
        super.disconnectedCallback();
    }

    /**
     * Set or clear the status strip text. `owner` defaults to
     * `extension` when text is non-empty (matches the previous
     * imperative default for unowned writes); cleared text drops the
     * owner tag too.
     */
    setMessage(text: string, owner: MessageOwner = "extension"): void {
        if (text) {
            this.text = text;
            this.owner = owner;
        } else {
            this.text = "";
            this.owner = null;
        }
    }

    /** Owner of the currently-shown text, or `null` when hidden. */
    getOwner(): MessageOwner | null {
        return this.owner;
    }

    /** Whether the strip currently shows any text. */
    isVisible(): boolean {
        return this.text.length > 0;
    }

    protected render(): TemplateResult {
        if (!this.text) {
            return html`${nothing}`;
        }
        return html`
            <div class="message" role="status" aria-live="polite">
                ${this.text}
            </div>
        `;
    }
}
