// RcPlayerElement: factory function and Custom Element for embedding RC documents.

import { RcdPlayer } from './main';
import { CoreDocument } from '../core/CoreDocument';

/** Decode a base64 string to an ArrayBuffer. */
export function base64ToArrayBuffer(base64: string): ArrayBuffer {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes.buffer;
}

export interface RcPlayerOptions {
    src?: string;
    data?: string;          // base64-encoded .rc data
    buffer?: ArrayBuffer;
    width?: number;
    height?: number;
    theme?: 'light' | 'dark' | 'auto';
    background?: string;
    onLoad?: (doc: CoreDocument) => void;
}

export interface RcPlayerHandle {
    loadFromUrl(url: string): Promise<CoreDocument>;
    loadFromBase64(base64: string): Promise<CoreDocument>;
    loadFromArrayBuffer(buffer: ArrayBuffer): Promise<CoreDocument>;
    resize(width: number, height: number): void;
    destroy(): void;
    player: RcdPlayer;
    canvas: HTMLCanvasElement;
}

/**
 * Factory function: creates a canvas inside `container`, instantiates an
 * RcdPlayer, loads from the provided source, and returns a handle.
 */
export function createPlayer(
    container: HTMLElement,
    options: RcPlayerOptions = {}
): RcPlayerHandle {
    const canvas = document.createElement('canvas');
    canvas.width = 256;
    canvas.height = 256;
    if (options.background) {
        canvas.style.background = options.background;
    }
    container.appendChild(canvas);

    const player = new RcdPlayer(canvas);

    if (options.theme) {
        player.setTheme(options.theme);
    }

    if (options.onLoad) {
        player.setOnLoad(options.onLoad);
    }

    const applySize = () => {
        if (options.width != null && options.height != null) {
            player.resize(options.width, options.height);
        }
    };

    // Kick off initial load (fire-and-forget — callers can await handle methods)
    if (options.data) {
        const buf = base64ToArrayBuffer(options.data);
        player.loadFromArrayBuffer(buf).then(applySize);
    } else if (options.buffer) {
        player.loadFromArrayBuffer(options.buffer).then(applySize);
    } else if (options.src) {
        player.loadFromUrl(options.src).then(applySize);
    }

    const handle: RcPlayerHandle = {
        async loadFromUrl(url: string) {
            const doc = await player.loadFromUrl(url);
            applySize();
            return doc;
        },
        async loadFromBase64(base64: string) {
            const buf = base64ToArrayBuffer(base64);
            const doc = await player.loadFromArrayBuffer(buf);
            applySize();
            return doc;
        },
        async loadFromArrayBuffer(buffer: ArrayBuffer) {
            const doc = await player.loadFromArrayBuffer(buffer);
            applySize();
            return doc;
        },
        resize(width: number, height: number) {
            player.resize(width, height);
        },
        destroy() {
            player.stop();
            canvas.remove();
        },
        player,
        canvas,
    };

    return handle;
}

/**
 * <rc-player> Custom Element.
 *
 * Attributes: src, data, width, height, theme, background
 */
export class RcPlayerElement extends HTMLElement {
    static observedAttributes = ['src', 'data', 'width', 'height', 'theme', 'background'];

    private _handle: RcPlayerHandle | null = null;
    private _shadow: ShadowRoot;
    private _container: HTMLDivElement;

    constructor() {
        super();
        this._shadow = this.attachShadow({ mode: 'open' });
        this._shadow.innerHTML = `
            <style>
                :host { display: inline-block; }
                .container { display: inline-block; }
                canvas { display: block; }
            </style>
            <div class="container"></div>
        `;
        this._container = this._shadow.querySelector('.container') as HTMLDivElement;
    }

    connectedCallback() {
        this._init();
    }

    disconnectedCallback() {
        if (this._handle) {
            this._handle.destroy();
            this._handle = null;
        }
    }

    attributeChangedCallback(name: string, _oldVal: string | null, newVal: string | null) {
        if (!this._handle) return;

        if (name === 'src' && newVal) {
            this._handle.loadFromUrl(newVal);
        } else if (name === 'data' && newVal) {
            this._handle.loadFromBase64(newVal);
        } else if (name === 'width' || name === 'height') {
            const w = parseInt(this.getAttribute('width') || '0', 10);
            const h = parseInt(this.getAttribute('height') || '0', 10);
            if (w > 0 && h > 0) {
                this._handle.resize(w, h);
            }
        } else if (name === 'theme' && newVal) {
            this._handle.player.setTheme(newVal as 'light' | 'dark' | 'auto');
        } else if (name === 'background' && newVal) {
            this._handle.canvas.style.background = newVal;
        }
    }

    private _init() {
        if (this._handle) return;

        const opts: RcPlayerOptions = {};
        const src = this.getAttribute('src');
        const data = this.getAttribute('data');
        const w = this.getAttribute('width');
        const h = this.getAttribute('height');
        const theme = this.getAttribute('theme');
        const bg = this.getAttribute('background');

        if (src) opts.src = src;
        if (data) opts.data = data;
        if (w && h) {
            opts.width = parseInt(w, 10);
            opts.height = parseInt(h, 10);
        }
        if (theme) opts.theme = theme as 'light' | 'dark' | 'auto';
        if (bg) opts.background = bg;

        this._handle = createPlayer(this._container, opts);
    }
}
