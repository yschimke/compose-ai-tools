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
import { type PanelWireframe } from "./semanticsTreeLoader";
import { composePanelTexture } from "./wireframeCompositor";

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

/**
 * A named gradient backdrop, mirroring the offline compositor's `kPresets`
 * (`renderers/xr-composite/src/main.cpp`) so the interactive viewer and the
 * baked still agree. `floor` (straight down) is only present for 3-stop,
 * room-like presets; without it the lower hemisphere mirrors the 2-stop
 * horizon→sky look. `glow` scales the horizon glow band.
 */
interface GradientPreset {
    readonly sky: string;
    readonly horizon: string;
    readonly floor?: string;
    readonly glow: number;
}

const GRADIENT_PRESETS: Record<string, GradientPreset> = {
    // Softly-lit warm room: warm-taupe ceiling, warm wall at the horizon, deep
    // warm-brown floor — the default, matching the compositor.
    "warm-room": {
        sky: "#332e27",
        horizon: "#5a4d40",
        floor: "#1e1a16",
        glow: 0.3,
    },
    // The original cold 2-stop gradient (no floor), kept selectable.
    "studio-dark": { sky: "#05070d", horizon: "#1a1f2b", glow: 0.35 },
};
const DEFAULT_PRESET = "warm-room";

// Radius of the camera-centred gradient skydome. Comfortably inside the
// camera's far plane (100000) and outside its near plane; `depthTest` is off so
// the exact value only needs to avoid clip-space culling.
const SKYDOME_RADIUS = 50000;

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
    /** Camera-centred gradient backdrop; `applyEnvironment` drives its uniforms. */
    private readonly skydome: THREE.Mesh;
    private quadObjects: QuadObject[] = [];
    private focusedId: string | null = null;
    /** Per-panel 2D wireframe overlays, keyed by panel id; empty when no semantics tree is loaded. */
    private wireframes = new Map<string, PanelWireframe>();

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
        this.skydome = createSkydome();
        this.scene.add(this.skydome);
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

    /**
     * Replace the rendered scene. Tears down the previous quads/textures. The optional
     * [wireframes] map (keyed by panel id, from `panelWireframesById`) overlays each matching
     * panel's 2D semantics boxes onto its screenshot face; omit it for a plain textured scene.
     */
    load(
        scene: SpatialScene,
        wireframes: Map<string, PanelWireframe> = new Map(),
    ): void {
        this.clearQuads();
        this.wireframes = wireframes;
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
        this.scene.remove(this.skydome);
        disposeObject(this.skydome);
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
        const wireframe = this.wireframes.get(quad.id);
        this.textureLoader.load(
            url,
            (texture) => {
                if (this.disposed) {
                    texture.dispose();
                    return;
                }
                texture.colorSpace = THREE.SRGBColorSpace;
                // "screenshot + wireframe overlay" face: when this panel carries a 2D semantics
                // tree, composite its boxes over the screenshot. Falls back to the plain texture
                // when there's no overlay or compositing isn't possible (no DOM / sized image).
                let map: THREE.Texture = texture;
                if (wireframe) {
                    const composed = composePanelTexture(
                        texture.image as CanvasImageSource & {
                            width?: number;
                            height?: number;
                        },
                        wireframe,
                    );
                    if (composed) {
                        texture.dispose();
                        map = composed;
                    }
                }
                material.map = map;
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
            // Flat-colour backdrop: hide the gradient dome behind it.
            this.skydome.visible = false;
            this.scene.background = new THREE.Color(env.color);
            return;
        }
        // Gradient backdrop (the default, and the fallback for any non-`color`
        // kind including a not-yet-supported `skybox` texture). Mirror the
        // compositor's precedence: start from a named preset (the scene's
        // `preset`, else the default `warm-room`), then let explicit
        // `sky`/`horizon`/`floor` stops override it. A resolved `floor` turns
        // the gradient into a 3-stop, room-like one.
        const preset =
            (env?.preset && GRADIENT_PRESETS[env.preset]) ||
            GRADIENT_PRESETS[DEFAULT_PRESET];
        const sky = env?.sky ?? preset.sky;
        const horizon = env?.horizon ?? preset.horizon;
        const floor = env?.floor ?? preset.floor;

        const uniforms = (this.skydome.material as THREE.ShaderMaterial)
            .uniforms;
        uniforms.uSky.value.set(sky);
        uniforms.uHorizon.value.set(horizon);
        uniforms.uFloor.value.set(floor ?? horizon);
        uniforms.uHasFloor.value = floor !== undefined ? 1 : 0;
        uniforms.uGlow.value = preset.glow;
        this.skydome.visible = true;
        // The dome covers every direction, but clear to the horizon so any
        // uncovered corner blends in (mirrors the compositor's clear colour).
        this.scene.background = new THREE.Color(horizon);
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
            // Keep the gradient dome centred on the camera so its object-space
            // directions equal world view directions (horizon stays at y = 0).
            this.skydome.position.copy(this.camera.position);
            this.renderer.render(this.scene, this.camera);
        };
        this.frameHandle = requestAnimationFrame(tick);
    }
}

function vecToThree(v: Vec3): THREE.Vector3 {
    return new THREE.Vector3(v.x, v.y, v.z);
}

/**
 * Build the camera-centred gradient backdrop: a back-faced sphere whose shader
 * reproduces the offline compositor's vertical gradient (`buildGradientSkybox`
 * in `main.cpp`). The gradient is computed from the world view direction's `y`
 * — horizon→sky above, horizon→floor below (3-stop) — plus a soft horizon glow
 * band. Colour stops arrive as `THREE.Color` uniforms (linear-light under
 * three's colour management, like the compositor's `hexToLinear`); the standard
 * tonemapping + colour-space chunks encode the linear result to the canvas,
 * matching the compositor's `LinearToneMapper` + sRGB output.
 *
 * `depthTest`/`depthWrite` are off and it draws first (`renderOrder = -1`) so it
 * always sits behind the panels regardless of the dome radius.
 */
function createSkydome(): THREE.Mesh {
    const material = new THREE.ShaderMaterial({
        side: THREE.BackSide,
        depthTest: false,
        depthWrite: false,
        fog: false,
        uniforms: {
            uSky: { value: new THREE.Color(0x000000) },
            uHorizon: { value: new THREE.Color(0x000000) },
            uFloor: { value: new THREE.Color(0x000000) },
            uHasFloor: { value: 0 },
            uGlow: { value: 0 },
        },
        vertexShader: /* glsl */ `
            varying vec3 vDir;
            void main() {
                // Object space == world direction: the dome is only translated
                // (to the camera), never rotated, so this is the view ray.
                vDir = normalize(position);
                gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
            }
        `,
        fragmentShader: /* glsl */ `
            varying vec3 vDir;
            uniform vec3 uSky;
            uniform vec3 uHorizon;
            uniform vec3 uFloor;
            uniform float uHasFloor;
            uniform float uGlow;
            void main() {
                vec3 dir = normalize(vDir);
                vec3 c;
                if (uHasFloor > 0.5 && dir.y < 0.0) {
                    // Lower hemisphere: horizon (wall) -> floor (straight down).
                    float t = smoothstep(0.0, 1.0, -dir.y);
                    c = mix(uHorizon, uFloor, t);
                } else {
                    // Upper hemisphere (and the whole sphere in 2-stop mode).
                    float t = smoothstep(0.0, 1.0, dir.y * 0.5 + 0.5);
                    c = mix(uHorizon, uSky, t);
                }
                // Soft glow band centred on the horizon (dir.y == 0).
                float glowBand = exp(-(dir.y * dir.y) / (2.0 * 0.06 * 0.06));
                c += uHorizon * (glowBand * uGlow);
                gl_FragColor = vec4(c, 1.0);
                #include <tonemapping_fragment>
                #include <colorspace_fragment>
            }
        `,
    });
    const mesh = new THREE.Mesh(
        new THREE.SphereGeometry(SKYDOME_RADIUS, 32, 16),
        material,
    );
    mesh.renderOrder = -1;
    mesh.frustumCulled = false;
    mesh.visible = false; // until the first applyEnvironment
    return mesh;
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
