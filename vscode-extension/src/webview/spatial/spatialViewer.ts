// Three.js viewer for a `SpatialScene` ([../shared/spatialScene.ts]). Renders
// each panel (and orbiter affordance) as a flat, unlit textured quad at its
// recovered pose, with an orbit/pan/zoom camera, an optional ground grid +
// axes, billboarded labels, and click-to-focus.
//
// Deliberately NOT a WebXR/immersive surface — VS Code ships stock Electron
// with `checkout_webxr` off (no `navigator.xr`), so this is an inline WebGL
// "magic window" behind an orbit camera, which is the right surface for a
// layout preview anyway. `WebGLRenderer` is used directly; swapping to
// `WebGPURenderer` (with a WebGL2 fallback) would be a one-line change here.
//
// Coordinate frame matches the contract: right-handed, +x right / +y up / +z
// toward the viewer, identity rotation faces +z. That is three.js's default
// frame and `PlaneGeometry`'s default facing, so panel poses map straight
// through. Textures arrive as already-resolved URLs (see `resolveTextureUrl`):
// the contract keeps the raw `texture` reference relative, and the host owns
// path → webview-resource resolution.

import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import type {
    OrbiterAffordance,
    SpatialPanel,
    SpatialScene,
    Vec3,
} from "../shared/spatialScene";
import { renderableQuads } from "./sceneLoader";

/** Optional injection points for the viewer. */
export interface SpatialViewerOptions {
    /**
     * Map a quad's raw `texture` reference (e.g. `"top.png"`) to a URL the
     * browser can load under the active CSP. The host typically returns a
     * `webview.asWebviewUri(...)` string or a `data:` URI. Defaults to identity
     * (the reference is used verbatim as a URL), which is what the dev harness
     * relies on when serving fixtures over http.
     */
    resolveTextureUrl?: (texture: string) => string;
    /** Show the ground grid + world axes. Default `true`. */
    showGrid?: boolean;
    /** Show floating quad labels. Default `true`. */
    showLabels?: boolean;
    /** Fired when a quad is clicked (click-to-focus). */
    onPanelFocus?: (panelId: string) => void;
    /** Override the loader (tests inject a fake to avoid network/Image). */
    textureLoader?: Pick<THREE.TextureLoader, "load">;
}

type Quad = SpatialPanel | OrbiterAffordance;

interface QuadObject {
    quad: Quad;
    mesh: THREE.Mesh;
    label?: THREE.Sprite;
}

const PLACEHOLDER_COLOR = 0x3a3a44;
const PANEL_OUTLINE_COLOR = 0x5a5a6a;
const FOCUS_OUTLINE_COLOR = 0x4a9eff;
const DEFAULT_BACKGROUND = 0x16161a;

export class SpatialViewer {
    private readonly renderer: THREE.WebGLRenderer;
    private readonly scene = new THREE.Scene();
    private readonly camera: THREE.PerspectiveCamera;
    private readonly controls: OrbitControls;
    private readonly raycaster = new THREE.Raycaster();
    private readonly pointer = new THREE.Vector2();
    private readonly textureLoader: Pick<THREE.TextureLoader, "load">;
    private readonly resolveTextureUrl: (texture: string) => string;
    private readonly onPanelFocus?: (panelId: string) => void;
    private readonly showLabels: boolean;

    private readonly quadGroup = new THREE.Group();
    private readonly helperGroup = new THREE.Group();
    private quadObjects: QuadObject[] = [];
    private focusedId: string | null = null;

    private disposed = false;
    private frameHandle: number | null = null;
    private resizeObserver?: ResizeObserver;

    constructor(
        private readonly container: HTMLElement,
        options: SpatialViewerOptions = {},
    ) {
        this.resolveTextureUrl = options.resolveTextureUrl ?? ((t) => t);
        this.onPanelFocus = options.onPanelFocus;
        this.showLabels = options.showLabels ?? true;
        this.textureLoader = options.textureLoader ?? new THREE.TextureLoader();

        const width = Math.max(container.clientWidth, 1);
        const height = Math.max(container.clientHeight, 1);

        this.renderer = new THREE.WebGLRenderer({
            antialias: true,
            alpha: true,
        });
        this.renderer.setPixelRatio(
            typeof window !== "undefined" ? window.devicePixelRatio : 1,
        );
        this.renderer.setSize(width, height, false);
        this.renderer.domElement.classList.add("spatial-canvas");
        container.appendChild(this.renderer.domElement);

        this.scene.background = new THREE.Color(DEFAULT_BACKGROUND);
        this.scene.add(this.quadGroup);
        this.scene.add(this.helperGroup);
        // Flat unlit quads need no lights, but a soft ambient term keeps any
        // future non-`MeshBasicMaterial` additions from rendering pure black.
        this.scene.add(new THREE.AmbientLight(0xffffff, 1));

        this.camera = new THREE.PerspectiveCamera(
            50,
            width / height,
            1,
            100000,
        );

        this.controls = new OrbitControls(
            this.camera,
            this.renderer.domElement,
        );
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.08;
        this.controls.screenSpacePanning = true;

        if (options.showGrid ?? true) {
            this.addHelpers();
        }

        this.renderer.domElement.addEventListener("click", this.handleClick);

        if (typeof ResizeObserver !== "undefined") {
            this.resizeObserver = new ResizeObserver(() => this.handleResize());
            this.resizeObserver.observe(container);
        }

        this.startLoop();
    }

    /** Replace the rendered scene. Tears down the previous quads/textures. */
    load(scene: SpatialScene): void {
        this.clearQuads();
        this.applyEnvironment(scene);

        for (const quad of renderableQuads(scene)) {
            this.addQuad(quad);
        }

        this.applyCamera(scene);
        this.fitHelpersToScene();
    }

    /** Programmatically focus a quad: frame the camera on it and highlight. */
    focusPanel(panelId: string): void {
        const target = this.quadObjects.find((q) => q.quad.id === panelId);
        if (!target) return;
        this.setFocusHighlight(panelId);

        const center = target.mesh.position.clone();
        const { width, height } = target.quad.sizeDp;
        const radius = Math.hypot(width, height) / 2 || 1;
        // Pull back far enough that the quad fills a comfortable fraction of the
        // vertical FOV, then dolly along the quad's facing normal.
        const fov = THREE.MathUtils.degToRad(this.camera.fov);
        const dist = (radius / Math.sin(fov / 2)) * 1.6;
        const normal = new THREE.Vector3(0, 0, 1).applyQuaternion(
            target.mesh.quaternion,
        );
        this.camera.position.copy(center).addScaledVector(normal, dist);
        this.controls.target.copy(center);
        this.controls.update();
    }

    dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        if (this.frameHandle !== null) {
            cancelAnimationFrame(this.frameHandle);
        }
        this.resizeObserver?.disconnect();
        this.renderer.domElement.removeEventListener("click", this.handleClick);
        this.clearQuads();
        this.helperGroup.clear();
        this.controls.dispose();
        this.renderer.dispose();
        this.renderer.domElement.remove();
    }

    /** Currently focused quad id, or `null`. Exposed for tests/wiring. */
    get focused(): string | null {
        return this.focusedId;
    }

    // --- internals ---------------------------------------------------------

    private addQuad(quad: Quad): void {
        const { width, height } = quad.sizeDp;
        const geometry = new THREE.PlaneGeometry(width, height);
        const material = new THREE.MeshBasicMaterial({
            color: PLACEHOLDER_COLOR,
            side: THREE.DoubleSide,
            transparent: true,
        });
        const mesh = new THREE.Mesh(geometry, material);

        const { translation, rotation } = quad.poseInRoot;
        mesh.position.set(translation.x, translation.y, translation.z);
        mesh.quaternion
            .set(rotation.x, rotation.y, rotation.z, rotation.w)
            .normalize();
        mesh.userData.panelId = quad.id;

        // Thin outline so a loading / failed-texture quad still reads as a panel.
        const edges = new THREE.LineSegments(
            new THREE.EdgesGeometry(geometry),
            new THREE.LineBasicMaterial({ color: PANEL_OUTLINE_COLOR }),
        );
        mesh.add(edges);

        const url = this.resolveTextureUrl(quad.texture);
        this.textureLoader.load(
            url,
            (texture) => {
                if (this.disposed) {
                    texture.dispose();
                    return;
                }
                texture.colorSpace = THREE.SRGBColorSpace;
                material.map = texture;
                material.color.set(0xffffff);
                material.needsUpdate = true;
            },
            undefined,
            () => {
                // Leave the placeholder colour in place on load failure.
            },
        );

        this.quadGroup.add(mesh);

        const entry: QuadObject = { quad, mesh };
        if (this.showLabels && quad.label) {
            const label = this.makeLabel(quad.label);
            if (label) {
                label.position.set(0, height / 2 + 30, 0);
                mesh.add(label);
                entry.label = label;
            }
        }
        this.quadObjects.push(entry);
    }

    private makeLabel(text: string): THREE.Sprite | null {
        if (typeof document === "undefined") return null;
        const canvas = document.createElement("canvas");
        const ctx = canvas.getContext("2d");
        if (!ctx) return null;
        const pad = 12;
        const fontPx = 28;
        ctx.font = `${fontPx}px sans-serif`;
        const textWidth = ctx.measureText(text).width;
        canvas.width = Math.ceil(textWidth + pad * 2);
        canvas.height = fontPx + pad * 2;

        ctx.font = `${fontPx}px sans-serif`;
        ctx.fillStyle = "rgba(20, 20, 24, 0.78)";
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.fillStyle = "#f0f0f5";
        ctx.textBaseline = "middle";
        ctx.fillText(text, pad, canvas.height / 2);

        const texture = new THREE.CanvasTexture(canvas);
        texture.colorSpace = THREE.SRGBColorSpace;
        const material = new THREE.SpriteMaterial({
            map: texture,
            depthTest: false,
            transparent: true,
        });
        const sprite = new THREE.Sprite(material);
        const labelHeightDp = 60;
        sprite.scale.set(
            (canvas.width / canvas.height) * labelHeightDp,
            labelHeightDp,
            1,
        );
        return sprite;
    }

    private addHelpers(): void {
        const grid = new THREE.GridHelper(4000, 20, 0x4a4a55, 0x2a2a33);
        (grid.material as THREE.Material).transparent = true;
        (grid.material as THREE.Material).opacity = 0.5;
        this.helperGroup.add(grid);
        this.helperGroup.add(new THREE.AxesHelper(200));
    }

    private fitHelpersToScene(): void {
        // Drop the grid to the lowest quad so it reads as a floor.
        if (this.quadObjects.length === 0) return;
        let minY = Infinity;
        for (const { mesh, quad } of this.quadObjects) {
            minY = Math.min(minY, mesh.position.y - quad.sizeDp.height / 2);
        }
        if (Number.isFinite(minY)) {
            this.helperGroup.position.y = minY;
        }
    }

    private applyEnvironment(scene: SpatialScene): void {
        const env = scene.environment;
        if (env && env.kind === "color" && env.color) {
            this.scene.background = new THREE.Color(env.color);
        } else {
            this.scene.background = new THREE.Color(DEFAULT_BACKGROUND);
        }
        // `skybox` backdrops are a later-phase concern; fall back to the flat
        // background colour rather than failing to render.
    }

    private applyCamera(scene: SpatialScene): void {
        const cam = scene.camera;
        const target = vecToThree(cam.target);
        const yaw = THREE.MathUtils.degToRad(cam.yawDeg);
        const pitch = THREE.MathUtils.degToRad(cam.pitchDeg);
        // Spherical → cartesian offset from target: yaw about +y, pitch up from
        // the horizontal plane, then push out along it by `distance`.
        const offset = new THREE.Vector3(
            Math.cos(pitch) * Math.sin(yaw),
            Math.sin(pitch),
            Math.cos(pitch) * Math.cos(yaw),
        ).multiplyScalar(cam.distance);
        this.camera.position.copy(target).add(offset);
        this.controls.target.copy(target);
        this.controls.update();
    }

    private setFocusHighlight(panelId: string | null): void {
        this.focusedId = panelId;
        for (const { quad, mesh } of this.quadObjects) {
            const edges = mesh.children.find(
                (c) => c instanceof THREE.LineSegments,
            ) as THREE.LineSegments | undefined;
            if (!edges) continue;
            const mat = edges.material as THREE.LineBasicMaterial;
            mat.color.set(
                quad.id === panelId ? FOCUS_OUTLINE_COLOR : PANEL_OUTLINE_COLOR,
            );
        }
    }

    private clearQuads(): void {
        for (const { mesh } of this.quadObjects) {
            this.quadGroup.remove(mesh);
            disposeObject(mesh);
        }
        this.quadObjects = [];
        this.focusedId = null;
    }

    private readonly handleClick = (event: MouseEvent): void => {
        const rect = this.renderer.domElement.getBoundingClientRect();
        this.pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
        this.pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
        this.raycaster.setFromCamera(this.pointer, this.camera);
        const hits = this.raycaster.intersectObjects(
            this.quadGroup.children,
            true,
        );
        for (const hit of hits) {
            const panelId = findPanelId(hit.object);
            if (panelId) {
                this.focusPanel(panelId);
                this.onPanelFocus?.(panelId);
                return;
            }
        }
    };

    private handleResize(): void {
        const width = Math.max(this.container.clientWidth, 1);
        const height = Math.max(this.container.clientHeight, 1);
        this.camera.aspect = width / height;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(width, height, false);
    }

    private startLoop(): void {
        const tick = () => {
            if (this.disposed) return;
            this.frameHandle = requestAnimationFrame(tick);
            this.controls.update();
            this.renderer.render(this.scene, this.camera);
        };
        this.frameHandle = requestAnimationFrame(tick);
    }
}

function vecToThree(v: Vec3): THREE.Vector3 {
    return new THREE.Vector3(v.x, v.y, v.z);
}

function findPanelId(object: THREE.Object3D): string | null {
    let cur: THREE.Object3D | null = object;
    while (cur) {
        const id = cur.userData?.panelId;
        if (typeof id === "string") return id;
        cur = cur.parent;
    }
    return null;
}

function disposeObject(root: THREE.Object3D): void {
    root.traverse((obj) => {
        const mesh = obj as Partial<THREE.Mesh> & Partial<THREE.Sprite>;
        mesh.geometry?.dispose();
        const material = (mesh as THREE.Mesh).material;
        if (Array.isArray(material)) {
            material.forEach(disposeMaterial);
        } else if (material) {
            disposeMaterial(material);
        }
    });
}

function disposeMaterial(material: THREE.Material): void {
    const withMap = material as THREE.Material & { map?: THREE.Texture | null };
    withMap.map?.dispose();
    material.dispose();
}
