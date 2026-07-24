import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

export class ColorTheme extends Operation {
    static readonly OP_CODE = 196;
    static readonly THEME_LIGHT = -3;
    static readonly THEME_DARK = -2;

    private mId: number;
    private mColorGroupId: number;
    private mLightModeIndex: number;
    private mDarkModeIndex: number;
    private mLightModeFallback: number;
    private mDarkModeFallback: number;
    private mCurrentTheme: number = -1; // Theme.UNSPECIFIED
    private mDarkMode: number;
    private mLightMode: number;

    constructor(id: number, colorGroupId: number, lightModeIndex: number, darkModeIndex: number,
                lightModeFallback: number, darkModeFallback: number) {
        super();
        this.mId = id;
        this.mColorGroupId = colorGroupId;
        this.mLightModeIndex = lightModeIndex;
        this.mDarkModeIndex = darkModeIndex;
        this.mLightModeFallback = lightModeFallback;
        this.mDarkModeFallback = darkModeFallback;
        this.mDarkMode = darkModeFallback;
        this.mLightMode = lightModeFallback;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    setTheme(context: RemoteContext, theme: number): void {
        this.mCurrentTheme = theme;
        if (theme === ColorTheme.THEME_LIGHT) {
            context.loadColor(this.mId, this.mLightMode);
        } else {
            context.loadColor(this.mId, this.mDarkMode);
        }
    }

    apply(context: RemoteContext): void {
        this.setTheme(context, context.getPaintTheme());
    }

    deepToString(indent: string): string {
        return `${indent}ColorTheme(${this.mId}, light=0x${(this.mLightModeFallback >>> 0).toString(16)}, dark=0x${(this.mDarkModeFallback >>> 0).toString(16)})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const colorGroupId = buffer.readInt();
        const lightModeIndex = buffer.readShort();
        const darkModeIndex = buffer.readShort();
        const lightModeFallback = buffer.readInt();
        const darkModeFallback = buffer.readInt();
        operations.push(new ColorTheme(id, colorGroupId, lightModeIndex, darkModeIndex,
            lightModeFallback, darkModeFallback));
    }
}
