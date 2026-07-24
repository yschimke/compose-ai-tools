// ToneSynthesizer: ports the Java ToneSynthesizer. Produces a WAV (16-bit mono,
// little-endian PCM) Uint8Array from a frequency/duration/waveform spec.

export class ToneSynthesizer {
    static readonly SAMPLE_RATE = 22050;

    /**
     * Synthesize raw 16-bit little-endian mono PCM bytes.
     * waveform: 1=square, 2=sawtooth, 3=triangle, else=sine.
     */
    static synthesizePcm(
        frequency: number,
        durationSec: number,
        waveform: number,
        sampleRate: number
    ): Uint8Array {
        const sampleCount = Math.max(1, Math.floor(sampleRate * durationSec));
        const pcm = new Uint8Array(sampleCount * 2);
        const waveKind = Math.floor(waveform);

        for (let i = 0; i < sampleCount; i++) {
            const t = i / sampleRate;
            const phase = 2 * Math.PI * frequency * t;

            let sample: number;
            switch (waveKind) {
                case 1: // square
                    sample = Math.sin(phase) >= 0 ? 1 : -1;
                    break;
                case 2: // sawtooth
                    sample = 2 * (frequency * t - Math.floor(frequency * t + 0.5));
                    break;
                case 3: // triangle
                    sample = 2 * Math.abs(2 * (frequency * t - Math.floor(frequency * t + 0.5))) - 1;
                    break;
                default: // sine
                    sample = Math.sin(phase);
                    break;
            }

            let envelope: number;
            if (i < 100) {
                envelope = i / 100;
            } else if (i > sampleCount - 100) {
                envelope = (sampleCount - i) / 100;
            } else {
                envelope = 1;
            }

            // Cast to signed 16-bit.
            const s = (Math.round(sample * envelope * 32767) << 16) >> 16;
            pcm[2 * i] = s & 0xff;
            pcm[2 * i + 1] = (s >> 8) & 0xff;
        }

        return pcm;
    }

    /**
     * Wrap raw PCM bytes in a standard 44-byte RIFF/WAVE/fmt/data header
     * (little-endian) followed by the PCM data.
     */
    static buildWav(
        pcm: Uint8Array,
        sampleRate: number = ToneSynthesizer.SAMPLE_RATE,
        bitDepth: number = 16,
        channels: number = 1
    ): Uint8Array {
        const byteRate = (sampleRate * channels * bitDepth) / 8;
        const blockAlign = (channels * bitDepth) / 8;
        const dataSize = pcm.length;

        const out = new Uint8Array(44 + dataSize);
        const view = new DataView(out.buffer);
        const le = true;

        const writeStr = (offset: number, str: string) => {
            for (let i = 0; i < str.length; i++) {
                out[offset + i] = str.charCodeAt(i);
            }
        };

        writeStr(0, 'RIFF');
        view.setInt32(4, 36 + dataSize, le);
        writeStr(8, 'WAVE');
        writeStr(12, 'fmt ');
        view.setInt32(16, 16, le);
        view.setInt16(20, 1, le); // PCM
        view.setInt16(22, channels, le);
        view.setInt32(24, sampleRate, le);
        view.setInt32(28, byteRate, le);
        view.setInt16(32, blockAlign, le);
        view.setInt16(34, bitDepth, le);
        writeStr(36, 'data');
        view.setInt32(40, dataSize, le);

        out.set(pcm, 44);
        return out;
    }

    /** Synthesize a complete WAV file for the given tone. */
    static synthesizeWav(frequency: number, durationSec: number, waveform: number): Uint8Array {
        return ToneSynthesizer.buildWav(
            ToneSynthesizer.synthesizePcm(
                frequency,
                durationSec,
                waveform,
                ToneSynthesizer.SAMPLE_RATE
            )
        );
    }
}
