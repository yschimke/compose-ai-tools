// Entry point for the shader test bundle.
// Exposes transpiler + renderer as window.ShaderTest for the test HTML page.

export { transpileAgslToGlsl } from '../../core/shader/AgslTranspiler';
export { WebGLShaderRenderer } from './WebGLShaderRenderer';
