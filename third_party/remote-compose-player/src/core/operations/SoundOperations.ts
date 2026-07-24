// SoundOperations: sound data, sound expression, and play-sound operations.
// Parse-only tier: read() must consume the exact bytes; apply() is a no-op for now.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { idFromNan } from './Utils';
import { ToneSynthesizer } from './utilities/ToneSynthesizer';

export class SoundData extends Operation {
    static readonly OP_CODE = 169;
    private mSoundId: number;
    private mData: Uint8Array;

    constructor(soundId: number, data: Uint8Array) {
        super(); this.mSoundId = soundId; this.mData = data;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void { context.loadSound(this.mSoundId, this.mData); }

    deepToString(indent: string): string { return `${indent}SoundData(${this.mSoundId}, ${this.mData.length} bytes)`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const soundId = buffer.readInt();
        const data = buffer.readBuffer();
        operations.push(new SoundData(soundId, data));
    }
}

export class SoundExpression extends Operation {
    static readonly OP_CODE = 206;
    private static readonly MAX_PARAMS = 64;

    private mId: number;
    private mLeftVolume: number;
    private mRightVolume: number;
    private mRate: number;
    private mParams: Float32Array;

    constructor(id: number, leftVolume: number, rightVolume: number, rate: number, params: Float32Array) {
        super();
        this.mId = id; this.mLeftVolume = leftVolume; this.mRightVolume = rightVolume;
        this.mRate = rate; this.mParams = params;
    }

    static readonly TYPE_TONE = 10;

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        if (this.mParams.length === 0) return;
        const typeId = idFromNan(this.mParams[0]);
        if (typeId === SoundExpression.TYPE_TONE && this.mParams.length >= 4) {
            const wav = ToneSynthesizer.synthesizeWav(
                this.mParams[1],
                this.mParams[2],
                this.mParams[3]
            );
            context.loadSound(this.mId, wav);
        }
    }

    deepToString(indent: string): string { return `${indent}SoundExpression(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const leftVolume = buffer.readFloat();
        const rightVolume = buffer.readFloat();
        const rate = buffer.readFloat();
        const len = buffer.readInt();
        if (len > SoundExpression.MAX_PARAMS) {
            throw new Error(`SoundExpression: too many params ${len} > ${SoundExpression.MAX_PARAMS}`);
        }
        const params = new Float32Array(len);
        for (let i = 0; i < len; i++) {
            params[i] = buffer.readFloat();
        }
        operations.push(new SoundExpression(id, leftVolume, rightVolume, rate, params));
    }
}

export class PlaySound extends Operation {
    static readonly OP_CODE = 141;
    private mSoundExpressionId: number;

    constructor(soundExpressionId: number) {
        super(); this.mSoundExpressionId = soundExpressionId;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void { context.playSound(this.mSoundExpressionId); }

    deepToString(indent: string): string { return `${indent}PlaySound(${this.mSoundExpressionId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new PlaySound(buffer.readInt()));
    }
}
