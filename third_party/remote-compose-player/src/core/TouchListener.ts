import type { RemoteContext } from './RemoteContext';

export interface TouchListener {
    touchDown(context: RemoteContext, x: number, y: number): void;
    touchDrag(context: RemoteContext, x: number, y: number): void;
    touchUp(context: RemoteContext, x: number, y: number, dx: number, dy: number): void;
    setComponent(component: any): void;
}
