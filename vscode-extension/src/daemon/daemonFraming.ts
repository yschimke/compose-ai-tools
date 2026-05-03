/**
 * LSP-style `Content-Length` framing for the daemon's stdio JSON-RPC channel.
 * See PROTOCOL.md § 1.
 *
 * Header bytes are ASCII; body bytes are UTF-8. Length counts UTF-8 bytes of
 * the JSON payload (NOT characters). Multiple messages can arrive in a single
 * chunk and a single message can arrive split across chunks — buffer until a
 * full header + body is in hand.
 */

const HEADER_TERMINATOR = Buffer.from("\r\n\r\n", "ascii");

export interface FrameDecoderEvents {
    onMessage: (json: string) => void;
    onError: (err: Error) => void;
}

export class FrameDecoder {
    private buffer: Buffer = Buffer.alloc(0);
    private expectedBodyLength: number | null = null;

    constructor(private readonly events: FrameDecoderEvents) {}

    push(chunk: Buffer): void {
        this.buffer =
            this.buffer.length === 0
                ? chunk
                : Buffer.concat([this.buffer, chunk]);

        // Loop because a single chunk may contain multiple complete frames.
        // eslint-disable-next-line no-constant-condition
        while (true) {
            if (this.expectedBodyLength === null) {
                const headerEnd = this.buffer.indexOf(HEADER_TERMINATOR);
                if (headerEnd < 0) {
                    return;
                }
                const headerText = this.buffer
                    .subarray(0, headerEnd)
                    .toString("ascii");
                this.buffer = this.buffer.subarray(
                    headerEnd + HEADER_TERMINATOR.length,
                );
                const length = parseContentLength(headerText);
                if (length === null) {
                    this.events.onError(
                        new Error(
                            `Daemon framing: missing or invalid Content-Length header: ${headerText.trim()}`,
                        ),
                    );
                    // Drop the buffer; the channel is unrecoverable from here.
                    this.buffer = Buffer.alloc(0);
                    return;
                }
                this.expectedBodyLength = length;
            }

            if (this.buffer.length < this.expectedBodyLength) {
                return;
            }
            const body = this.buffer
                .subarray(0, this.expectedBodyLength)
                .toString("utf-8");
            this.buffer = this.buffer.subarray(this.expectedBodyLength);
            this.expectedBodyLength = null;
            try {
                this.events.onMessage(body);
            } catch (err) {
                this.events.onError(
                    err instanceof Error ? err : new Error(String(err)),
                );
            }
        }
    }
}

function parseContentLength(headerText: string): number | null {
    for (const line of headerText.split("\r\n")) {
        const colon = line.indexOf(":");
        if (colon < 0) {
            continue;
        }
        const name = line.slice(0, colon).trim().toLowerCase();
        if (name !== "content-length") {
            continue;
        }
        const value = line.slice(colon + 1).trim();
        const n = parseInt(value, 10);
        if (Number.isFinite(n) && n >= 0) {
            return n;
        }
    }
    return null;
}

/** Encodes a JSON object as a single LSP-framed message (Buffer ready for stdin). */
export function encodeFrame(obj: unknown): Buffer {
    const body = Buffer.from(JSON.stringify(obj), "utf-8");
    const header = Buffer.from(
        `Content-Length: ${body.length}\r\n\r\n`,
        "ascii",
    );
    return Buffer.concat([header, body]);
}
