/**
 * WebGL2-based shader execution engine.
 *
 * Renders a GLSL fragment shader (output from AgslTranspiler) onto an
 * offscreen WebGL2 canvas.  The result can be composited into Canvas2D
 * via `ctx.drawImage(renderer.getCanvas(), ...)`.
 *
 * Usage:
 *   const renderer = new WebGLShaderRenderer();
 *   renderer.render(glsl, width, height, floatUniforms, textures);
 *   canvasCtx.drawImage(renderer.getCanvas(), dx, dy, dw, dh);
 */

const VERTEX_SHADER = `#version 300 es
in vec2 a_position;
void main() {
    gl_Position = vec4(a_position, 0.0, 1.0);
}
`;

// Fullscreen quad: two triangles covering clip space [-1,1]
const QUAD_VERTS = new Float32Array([
    -1, -1,   1, -1,  -1,  1,
    -1,  1,   1, -1,   1,  1,
]);

interface CachedProgram {
    program: WebGLProgram;
    uniformLocations: Map<string, WebGLUniformLocation>;
}

export class WebGLShaderRenderer {
    private canvas: HTMLCanvasElement;
    private gl: WebGL2RenderingContext | null = null;
    private vao: WebGLVertexArrayObject | null = null;
    private programCache = new Map<string, CachedProgram>();

    constructor() {
        this.canvas = document.createElement('canvas');
    }

    /** The offscreen canvas — use as drawImage source after render(). */
    getCanvas(): HTMLCanvasElement {
        return this.canvas;
    }

    /** Returns true if WebGL2 is available. */
    isAvailable(): boolean {
        return this.initGL() !== null;
    }

    /**
     * Render a GLSL fragment shader to the offscreen canvas.
     *
     * @param glsl       - GLSL ES 3.0 source (from AgslTranspiler)
     * @param width      - render target width
     * @param height     - render target height
     * @param floats     - float uniform values: name → Float32Array
     * @param ints       - int uniform values: name → Int32Array
     * @param textures   - texture images: name → source element
     * @param cacheKey   - optional cache key for the compiled program
     *                     (use shader text ID for stable caching)
     */
    render(
        glsl: string,
        width: number,
        height: number,
        floats?: Map<string, Float32Array>,
        ints?: Map<string, Int32Array>,
        textures?: Map<string, TexImageSource>,
        cacheKey?: string,
        docWidth?: number,
        docHeight?: number,
    ): boolean {
        const gl = this.initGL();
        if (!gl) return false;

        // Resize canvas if needed
        if (this.canvas.width !== width || this.canvas.height !== height) {
            this.canvas.width = width;
            this.canvas.height = height;
        }

        // Get or compile program
        const key = cacheKey ?? glsl;
        let cached = this.programCache.get(key);
        if (!cached) {
            const program = this.compileProgram(gl, glsl);
            if (!program) return false;
            cached = { program, uniformLocations: new Map() };
            this.programCache.set(key, cached);
        }

        gl.useProgram(cached.program);
        gl.viewport(0, 0, width, height);

        // Bind u_resolution (actual render size) and u_doc_size (document rect)
        const resLoc = this.getUniformLoc(gl, cached, 'u_resolution');
        if (resLoc) gl.uniform2f(resLoc, width, height);
        const docLoc = this.getUniformLoc(gl, cached, 'u_doc_size');
        if (docLoc) gl.uniform2f(docLoc, docWidth ?? width, docHeight ?? height);

        // Bind float uniforms
        if (floats) {
            for (const [name, values] of floats) {
                const loc = this.getUniformLoc(gl, cached, name);
                if (!loc) continue;
                switch (values.length) {
                    case 1: gl.uniform1f(loc, values[0]); break;
                    case 2: gl.uniform2f(loc, values[0], values[1]); break;
                    case 3: gl.uniform3f(loc, values[0], values[1], values[2]); break;
                    case 4: gl.uniform4f(loc, values[0], values[1], values[2], values[3]); break;
                    default: gl.uniform1fv(loc, values); break;
                }
            }
        }

        // Bind int uniforms
        if (ints) {
            for (const [name, values] of ints) {
                const loc = this.getUniformLoc(gl, cached, name);
                if (!loc) continue;
                switch (values.length) {
                    case 1: gl.uniform1i(loc, values[0]); break;
                    case 2: gl.uniform2i(loc, values[0], values[1]); break;
                    case 3: gl.uniform3i(loc, values[0], values[1], values[2]); break;
                    case 4: gl.uniform4i(loc, values[0], values[1], values[2], values[3]); break;
                    default: gl.uniform1iv(loc, values); break;
                }
            }
        }

        // Bind texture uniforms
        let texUnit = 0;
        if (textures) {
            for (const [name, img] of textures) {
                const loc = this.getUniformLoc(gl, cached, name);
                if (!loc) { texUnit++; continue; }

                const unit = texUnit++;
                gl.activeTexture(gl.TEXTURE0 + unit);

                const tex = gl.createTexture();
                gl.bindTexture(gl.TEXTURE_2D, tex);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
                gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);

                gl.uniform1i(loc, unit);
            }
        }

        // Draw fullscreen quad
        gl.bindVertexArray(this.vao);
        gl.drawArrays(gl.TRIANGLES, 0, 6);

        // Cleanup textures (avoid leaking)
        for (let u = 0; u < texUnit; u++) {
            gl.activeTexture(gl.TEXTURE0 + u);
            gl.bindTexture(gl.TEXTURE_2D, null);
        }

        return true;
    }

    /** Destroy WebGL resources. */
    destroy(): void {
        const gl = this.gl;
        if (!gl) return;
        for (const cached of this.programCache.values()) {
            gl.deleteProgram(cached.program);
        }
        this.programCache.clear();
        if (this.vao) gl.deleteVertexArray(this.vao);
        this.gl = null;
    }

    // ── Internal ──────────────────────────────────────────────────────

    private initGL(): WebGL2RenderingContext | null {
        if (this.gl) return this.gl;
        const gl = this.canvas.getContext('webgl2', {
            alpha: true,
            premultipliedAlpha: false,
            preserveDrawingBuffer: true,
        });
        if (!gl) return null;
        this.gl = gl;

        // Create VAO with fullscreen quad
        this.vao = gl.createVertexArray();
        gl.bindVertexArray(this.vao);
        const buf = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, buf);
        gl.bufferData(gl.ARRAY_BUFFER, QUAD_VERTS, gl.STATIC_DRAW);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

        return gl;
    }

    private compileProgram(gl: WebGL2RenderingContext, fragSource: string): WebGLProgram | null {
        const vs = this.compileShader(gl, gl.VERTEX_SHADER, VERTEX_SHADER);
        const fs = this.compileShader(gl, gl.FRAGMENT_SHADER, fragSource);
        if (!vs || !fs) return null;

        const prog = gl.createProgram()!;
        gl.attachShader(prog, vs);
        gl.attachShader(prog, fs);
        gl.bindAttribLocation(prog, 0, 'a_position');
        gl.linkProgram(prog);

        if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
            console.error('Shader link error:', gl.getProgramInfoLog(prog));
            gl.deleteProgram(prog);
            return null;
        }

        gl.deleteShader(vs);
        gl.deleteShader(fs);
        return prog;
    }

    private compileShader(gl: WebGL2RenderingContext, type: number, source: string): WebGLShader | null {
        const shader = gl.createShader(type)!;
        gl.shaderSource(shader, source);
        gl.compileShader(shader);

        if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
            console.error(`Shader compile error (${type === gl.VERTEX_SHADER ? 'vert' : 'frag'}):`,
                gl.getShaderInfoLog(shader));
            console.error('Source:\n' + source.split('\n').map((l, i) => `${i + 1}: ${l}`).join('\n'));
            gl.deleteShader(shader);
            return null;
        }
        return shader;
    }

    private getUniformLoc(gl: WebGL2RenderingContext, cached: CachedProgram, name: string): WebGLUniformLocation | null {
        let loc = cached.uniformLocations.get(name);
        if (loc === undefined) {
            loc = gl.getUniformLocation(cached.program, name)!;
            cached.uniformLocations.set(name, loc);
        }
        return loc;
    }
}
